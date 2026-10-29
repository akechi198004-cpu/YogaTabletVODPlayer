package com.hm.video

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** One canned HTTP response; [dripBytes] > 0 sends headers first and then a slow body. */
data class FixtureResponse(
    val body: ByteArray,
    val contentType: String = "text/html; charset=utf-8",
    val status: String = "200 OK",
    val headers: List<Pair<String, String>> = emptyList(),
    val dripBytes: Int = 0,
    val dripIntervalMs: Long = 0
) {
    companion object {
        fun text(value: String, contentType: String = "text/html; charset=utf-8") =
            FixtureResponse(value.toByteArray(Charsets.UTF_8), contentType)

        /** Headers flush immediately; the body then arrives in small, slow chunks. */
        fun slowBody(
            size: Int,
            dripBytes: Int = 8,
            dripIntervalMs: Long = 100,
            contentType: String = "text/html; charset=utf-8"
        ) = FixtureResponse(
            ByteArray(size) { 'x'.code.toByte() },
            contentType,
            dripBytes = dripBytes,
            dripIntervalMs = dripIntervalMs
        )
    }
}

/**
 * Test-only HTTP fixture that can simulate a server which answers headers instantly and then
 * drips the response body, which is exactly the case a header-only deadline misses.
 * Connections are handled concurrently so a slow response cannot block a later request.
 */
class FixtureHttpServer(private val handler: (String) -> FixtureResponse) : AutoCloseable {
    private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val baseUrl = "http://127.0.0.1:${socket.localPort}"
    val requests = CopyOnWriteArrayList<String>()
    private val workers = CopyOnWriteArrayList<Thread>()

    private val acceptor = Thread({
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            val worker = Thread({ serve(client) }, "hm-fixture-response").apply { isDaemon = true }
            workers.add(worker)
            worker.start()
        }
    }, "hm-fixture-http").apply { isDaemon = true; start() }

    private fun serve(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 2000
                val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = reader.readLine() ?: return
                val path = requestLine.split(' ').getOrNull(1).orEmpty()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                requests.add(path)
                val response = try {
                    handler(path)
                } catch (_: Exception) {
                    FixtureResponse("fixture error".toByteArray(), status = "500 Internal Server Error")
                }
                val out = client.getOutputStream()
                out.write(
                    ("HTTP/1.1 ${response.status}\r\n" +
                        "Content-Type: ${response.contentType}\r\n" +
                        "Content-Length: ${response.body.size}\r\n" +
                        "Connection: close\r\n").toByteArray()
                )
                response.headers.forEach { (name, value) ->
                    out.write("$name: $value\r\n".toByteArray())
                }
                out.write("\r\n".toByteArray())
                out.flush()
                if (response.dripBytes > 0) {
                    var sent = 0
                    while (sent < response.body.size) {
                        val chunk = minOf(response.dripBytes, response.body.size - sent)
                        out.write(response.body, sent, chunk)
                        out.flush()
                        sent += chunk
                        Thread.sleep(response.dripIntervalMs)
                    }
                } else {
                    out.write(response.body)
                    out.flush()
                }
            } catch (_: Exception) {
                // A cancelled client closing mid-body is expected.
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
        workers.forEach { it.interrupt() }
        acceptor.interrupt()
        acceptor.join(1000)
    }
}
