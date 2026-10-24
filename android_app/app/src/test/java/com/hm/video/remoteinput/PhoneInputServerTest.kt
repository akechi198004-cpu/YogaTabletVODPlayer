package com.hm.video.remoteinput

import android.app.Application
import android.os.Handler
import android.os.Looper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class PhoneInputServerTest {

    private val servers = mutableListOf<PhoneInputServer>()
    private val received = mutableListOf<String>()
    private val mainThreadCallback = AtomicInteger()
    private val clock = AtomicLong(System.currentTimeMillis())

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
        servers.clear()
    }

    @Test
    fun servesShortPageAndAcceptsChineseSpecialCharactersOnce() {
        val server = start()
        val session = server.start()
        servers.add(server)
        assertTrue(session.url.matches(Regex("http://127\\.0\\.0\\.1:\\d+/[a-z0-9]{6}")))

        val page = get(session.url)
        assertEquals(200, page.first)
        assertTrue(page.second.contains("发送到电视"))
        assertTrue(page.second.contains("action=\"/${session.code}\""))

        val keyword = "三体 & 三体+剧 \"测试\""
        val body = "keyword=" + URLEncoder.encode(keyword, "UTF-8")
        val first = post(session, body)
        assertEquals(200, first.first)
        assertTrue(first.second.contains("已发送到电视"))
        awaitReceived(1)
        assertEquals(listOf(keyword), received)
        assertEquals(1, mainThreadCallback.get())

        val second = try { post(session, body).first } catch (_: Exception) { -1 }
        assertTrue(second == 409 || second == -1)
        idleMain()
        assertEquals(1, received.size)
    }

    @Test
    fun rejectsEmptyOversizeAndForeignOrigin() {
        val server = start()
        val session = server.start()
        servers.add(server)

        assertEquals(400, post(session, "keyword=").first)
        val tooLong = "keyword=" + "a".repeat(200)
        assertTrue(post(session, tooLong).first in setOf(400, 413))
        assertEquals(403, post(session, "keyword=abc", mapOf("Referer" to "http://evil.example/")).first)
        assertTrue(server.allowedSource(mapOf("referer" to session.url)))
        assertTrue(!server.allowedSource(mapOf("origin" to "http://evil.example")))
        assertTrue(!server.allowedSource(mapOf("referer" to "null")))
        idleMain()
        assertTrue(received.isEmpty())
    }

    @Test
    fun expiredAndOldSessionsAreRejected() {
        val server = start(ttl = 50)
        val session = server.start()
        servers.add(server)
        clock.addAndGet(1000)
        val response = get(session.url)
        assertEquals(410, response.first)
        idleMain()
        assertTrue(received.isEmpty())
    }

    @Test
    fun concurrentSubmissionsTriggerExactlyOneKeyword() {
        val server = start()
        val session = server.start()
        servers.add(server)
        val ready = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val results = mutableListOf<Int>()
        repeat(2) {
            pool.submit {
                ready.await()
                val status = try {
                    post(session, "keyword=" + URLEncoder.encode("并发", "UTF-8")).first
                } catch (_: Exception) {
                    -1
                }
                synchronized(results) { results.add(status) }
            }
        }
        ready.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(8, TimeUnit.SECONDS))
        assertEquals(1, results.count { it == 200 })
        assertEquals(1, results.count { it != 200 })
        awaitReceived(1)
        assertEquals(listOf("并发"), received)
    }

    @Test
    fun wrongTokenIsRejected() {
        val server = start()
        val session = server.start()
        servers.add(server)
        assertEquals(404, get("http://127.0.0.1:${session.port}/zzzzzz").first)
        assertEquals(404, get("http://127.0.0.1:${session.port}/").first)
        idleMain()
        assertTrue(received.isEmpty())
    }

    @Test
    fun repeatedOpenCloseCyclesServeFreshSessions() {
        repeat(3) {
            val server = start()
            val session = server.start()
            servers.add(server)
            assertEquals(200, get(session.url).first)
            server.stop()
            val failed = try {
                get(session.url)
                false
            } catch (_: Exception) {
                true
            }
            assertTrue(failed)
        }
    }

    @Test
    fun stoppedSessionStopsAcceptingRequests() {
        val server = start()
        val session = server.start()
        servers.add(server)
        server.stop()
        val failed = try {
            get(session.url)
            false
        } catch (_: Exception) {
            true
        }
        assertTrue(failed)
    }

    @Test
    fun jsonResponsesLetThePageKeepInputAndRetry() {
        val server = start()
        val session = server.start()
        servers.add(server)

        val empty = post(session, "keyword=", json = true)
        assertEquals(400, empty.first)
        assertTrue(empty.second.contains("\"ok\":false"))
        assertTrue(empty.second.contains("\"retryable\":true"))

        val ok = post(session, "keyword=" + URLEncoder.encode("三体", "UTF-8"), json = true)
        assertEquals(200, ok.first)
        assertTrue(ok.second.contains("\"ok\":true"))
        awaitReceived(1)
        assertEquals(listOf("三体"), received)
    }

    @Test
    fun abortsPartiallyReceivedRequestWhenSessionStops() {
        val server = start(ttl = 60_000, requestTimeout = 30_000)
        val session = server.start()
        servers.add(server)

        Socket("127.0.0.1", session.port).use { raw ->
            raw.soTimeout = 3000
            val output = raw.getOutputStream()
            output.write(
                ("POST /${session.code} HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:${session.port}\r\n" +
                    "Content-Type: application/x-www-form-urlencoded\r\n" +
                    "Content-Length: 100\r\n\r\n" +
                    "keyword=partial").toByteArray(Charsets.US_ASCII)
            )
            output.flush()
            Thread.sleep(150)

            server.stop()

            val closed = try {
                raw.getInputStream().read() == -1
            } catch (_: Exception) {
                true
            }
            assertTrue("connection should be closed after stop", closed)
        }
        Thread.sleep(100)
        awaitReceived(0)
        assertTrue(received.isEmpty())
    }

    @Test
    fun silentClientHitsTheRequestDeadline() {
        val server = start(requestTimeout = 300)
        val session = server.start()
        servers.add(server)

        Socket("127.0.0.1", session.port).use { raw ->
            raw.soTimeout = 4000
            val started = System.currentTimeMillis()
            val response = try {
                raw.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (_: Exception) {
                ""
            }
            val elapsed = System.currentTimeMillis() - started
            assertTrue("server should close an idle request", elapsed < 3000)
            assertTrue("expected 408, got: ${response.take(40)}", response.contains("408"))
        }
        awaitReceived(0)
        assertTrue(received.isEmpty())
    }

    private fun start(ttl: Long = 60_000, requestTimeout: Long = 8_000): PhoneInputServer {
        lateinit var server: PhoneInputServer
        server = PhoneInputServer(
            ports = intArrayOf(0),
            sessionTtlMs = ttl,
            requestTimeoutMs = requestTimeout,
            mainHandler = Handler(Looper.getMainLooper()),
            hostOverride = "127.0.0.1",
            allowedHosts = setOf("127.0.0.1"),
            clock = { clock.get() },
            onKeyword = {
                received.add(it)
                if (Looper.myLooper() == Looper.getMainLooper()) mainThreadCallback.incrementAndGet()
            }
        )
        return server
    }

    private fun get(url: String): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun post(
        session: PhoneInputServer.Session,
        body: String,
        extraHeaders: Map<String, String> = emptyMap(),
        json: Boolean = false
    ): Pair<Int, String> {
        val connection = URL(session.url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            connection.setRequestProperty("Origin", "http://127.0.0.1:${session.port}")
            if (json) connection.setRequestProperty("Accept", "application/json")
            extraHeaders.forEach { (key, value) -> connection.setRequestProperty(key, value) }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            assertTrue(connection.getHeaderField("Access-Control-Allow-Origin") == null)
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }

    private fun awaitReceived(count: Int) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (received.size < count && System.nanoTime() < deadline) {
            idleMain()
            Thread.sleep(10)
        }
        idleMain()
    }

    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}
