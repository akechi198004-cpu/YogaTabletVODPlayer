package com.hm.video.data.api

import android.app.Application
import com.google.gson.reflect.TypeToken
import com.hm.video.FixtureHttpServer
import com.hm.video.FixtureResponse
import com.hm.video.data.cache.CachePolicy
import com.hm.video.data.model.PageResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * The page budget must cover the response body, not only the moment the headers arrive, and both
 * the catalog path and the player-service path have to share it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class XingfanPageTimeoutTest {

    @Test
    fun catalogRequestCannotOutliveTheBudgetWhileTheBodyDrips() {
        FixtureHttpServer { FixtureResponse.slowBody(64_000, dripBytes = 8, dripIntervalMs = 100) }.use { server ->
            val client = XingfanClient.pageClientWithCallTimeoutForTest(1)
            val startedAt = System.nanoTime()
            val error = runCatching {
                XingfanClient.fetchPageWithValidators("${server.baseUrl}/dianying/", client = client)
            }.exceptionOrNull()
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertNotNull("A fast header plus a slow body must fail, not read forever", error)
            assertTrue("expected IOException, got $error", error is IOException)
            assertTrue("aborted after ${elapsedMs}ms, budget was 1s", elapsedMs in 700..4_000)
            assertEquals(listOf("/dianying/"), server.requests.toList())
        }
    }

    @Test
    fun playerServiceRequestSharesTheSameWholeCallBudget() {
        var base = ""
        val page = FixtureHttpServer { path ->
            if (path.startsWith("/service")) {
                FixtureResponse.slowBody(64_000, dripBytes = 8, dripIntervalMs = 100)
            } else {
                FixtureResponse.text(
                    """<script>var zanpiancms_player={"url":"id","apiurl":"$base/service?url="};</script>"""
                )
            }
        }
        page.use { server ->
            base = server.baseUrl
            val startedAt = System.nanoTime()
            val error = XingfanClient.withPageClientForTest(XingfanClient.pageClientWithCallTimeoutForTest(1)) {
                runCatching { XingfanRepository.getPlayInfo("${server.baseUrl}/1-1.html", forceRefresh = true) }
                    .exceptionOrNull()
            }
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

            assertNotNull("The player service body is a page and must be bounded too", error)
            assertTrue("expected IOException, got $error", error is IOException)
            assertTrue("aborted after ${elapsedMs}ms, budget was 1s", elapsedMs in 700..5_000)
            assertEquals(
                "page + service must both have been requested",
                listOf("/1-1.html", "/service?url=id"),
                server.requests.toList()
            )
        }
    }

    @Test
    fun aNewerRequestInTheSameScopeCancelsTheOlderSlowCall() {
        FixtureHttpServer { path ->
            if (path == "/slow") FixtureResponse.slowBody(64_000, dripBytes = 8, dripIntervalMs = 100)
            else FixtureResponse.text("<html>fresh</html>")
        }.use { server ->
            val scope = PageCallScope()
            val oldTicket = scope.newRequest()
            val client = XingfanClient.pageClientWithCallTimeoutForTest(30)
            val slowResult = AtomicReference<Result<String>?>(null)
            val slow = Thread {
                slowResult.set(runCatching {
                    XingfanClient.fetchPageWithValidators("${server.baseUrl}/slow", ticket = oldTicket, client = client)
                        .html.orEmpty()
                })
            }
            slow.start()
            val deadline = System.nanoTime() + 3_000_000_000L
            while (server.requests.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals(listOf("/slow"), server.requests.toList())

            val freshTicket = scope.newRequest()
            val fresh = XingfanClient.fetchPageWithValidators("${server.baseUrl}/fresh", ticket = freshTicket, client = client)
            assertEquals("<html>fresh</html>", fresh.html)

            slow.join(3_000)
            assertFalse("the superseded call must stop instead of running its 30s budget", slow.isAlive)
            assertTrue(slowResult.get()?.isFailure == true)
        }
    }

    @Test
    fun cancellationPreventsLateTaskFromRegistering() {
        FixtureHttpServer { FixtureResponse.slowBody(64_000, dripBytes = 8, dripIntervalMs = 100) }.use { server ->
            val scope = PageCallScope()
            val ticket = scope.newRequest()
            scope.cancel()
            val client = XingfanClient.pageClientWithCallTimeoutForTest(30)
            val error = runCatching {
                XingfanClient.fetchPageWithValidators("${server.baseUrl}/slow", ticket = ticket, client = client)
            }.exceptionOrNull()
            assertTrue(error is IOException)
            assertTrue("destroyed scope must reject before opening a socket", server.requests.isEmpty())
        }
    }

    @Test
    fun oldOperationThatStartsLateCannotCancelNewerRequest() {
        FixtureHttpServer { path -> FixtureResponse.text("<html>$path</html>") }.use { server ->
            val scope = PageCallScope()
            val oldTicket = scope.newRequest()
            val currentTicket = scope.newRequest()
            val client = XingfanClient.pageClientWithCallTimeoutForTest(5)

            val staleError = runCatching {
                XingfanClient.fetchPageWithValidators(
                    "${server.baseUrl}/stale", ticket = oldTicket, client = client
                )
            }.exceptionOrNull()
            assertTrue(staleError is IOException)
            assertTrue("late old worker must be refused before it reaches the socket", server.requests.isEmpty())

            val current = XingfanClient.fetchPageWithValidators(
                "${server.baseUrl}/current", ticket = currentTicket, client = client
            )
            assertEquals("<html>/current</html>", current.html)
            assertEquals(listOf("/current"), server.requests.toList())
        }
    }

    @Test
    fun repositoryBackgroundRefreshCannotCancelInterleavedForegroundRequest() {
        FixtureHttpServer { path ->
            if (path == "/foreground") FixtureResponse.slowBody(640, dripBytes = 8, dripIntervalMs = 15)
            else FixtureResponse.text("background")
        }.use { server ->
            val scope = PageCallScope()
            val foregroundTicket = scope.newRequest()
            val client = XingfanClient.pageClientWithCallTimeoutForTest(10)
            val foreground = AtomicReference<Result<String>?>(null)
            val worker = Thread {
                foreground.set(runCatching {
                    XingfanClient.fetchPageWithValidators(
                        "${server.baseUrl}/foreground", ticket = foregroundTicket, client = client
                    ).html.orEmpty()
                })
            }
            worker.start()
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!server.requests.contains("/foreground") && System.nanoTime() < deadline) Thread.sleep(10)

            // Invoke the real cache coordinator's stale-while-revalidate path. Its fetch callback
            // records the ticket it receives and performs an actual local HTTP request.
            val observedTicket = AtomicReference<PageCallScope.Ticket?>(foregroundTicket)
            val backgroundBody = AtomicReference<String?>(null)
            val finished = CountDownLatch(1)
            val type = object : TypeToken<PageResult>() {}.type
            val resultConstructor = XingfanRepository::class.java.declaredClasses
                .first { it.simpleName == "NetworkResult" }.declaredConstructors
                .first { it.parameterCount == 4 }.apply { isAccessible = true }
            val shouldCache: (PageResult) -> Boolean = { true }
            val fetch: (String?, String?, PageCallScope.Ticket?) -> Any = { _, _, refreshTicket ->
                observedTicket.set(refreshTicket)
                try {
                    val page = XingfanClient.fetchPageWithValidators(
                        "${server.baseUrl}/background", ticket = refreshTicket, client = client
                    )
                    backgroundBody.set(page.html)
                    resultConstructor.newInstance(PageResult(emptyList(), 1, 1), page.etag, page.lastModified, false)
                } finally {
                    finished.countDown()
                }
            }
            val scheduleRefresh = XingfanRepository::class.java.declaredMethods
                .first { it.name == "scheduleRefresh" }.apply { isAccessible = true }
            scheduleRefresh.invoke(
                XingfanRepository,
                ConcurrentHashMap<String, Any>(),
                "interleaved-background-${System.nanoTime()}",
                type,
                CachePolicy.SEARCH,
                null,
                null,
                shouldCache,
                fetch
            )

            assertTrue("background refresh did not finish", finished.await(5, TimeUnit.SECONDS))
            assertEquals("background", backgroundBody.get())
            assertEquals("cache refresh must receive no foreground cancellation ticket", null, observedTicket.get())
            worker.join(3_000)

            assertFalse("background refresh must not replace the foreground call", worker.isAlive)
            assertEquals("x".repeat(640), foreground.get()?.getOrThrow())
        }
    }
}
