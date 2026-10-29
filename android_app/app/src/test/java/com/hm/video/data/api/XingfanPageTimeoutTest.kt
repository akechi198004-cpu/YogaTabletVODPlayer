package com.hm.video.data.api

import android.app.Application
import com.hm.video.FixtureHttpServer
import com.hm.video.FixtureResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
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
            val client = XingfanClient.pageClientWithCallTimeoutForTest(30)
            val slowResult = AtomicReference<Result<String>?>(null)
            val slow = Thread {
                slowResult.set(runCatching {
                    XingfanClient.fetchPageWithValidators("${server.baseUrl}/slow", scope = scope, client = client)
                        .html.orEmpty()
                })
            }
            slow.start()
            Thread.sleep(300)

            val fresh = XingfanClient.fetchPageWithValidators("${server.baseUrl}/fresh", scope = scope, client = client)
            assertEquals("<html>fresh</html>", fresh.html)

            slow.join(3_000)
            assertFalse("the superseded call must stop instead of running its 30s budget", slow.isAlive)
            assertTrue(slowResult.get()?.isFailure == true)
        }
    }

    @Test
    fun cancellingTheScopeStopsAnInFlightSlowBody() {
        FixtureHttpServer { FixtureResponse.slowBody(64_000, dripBytes = 8, dripIntervalMs = 100) }.use { server ->
            val scope = PageCallScope()
            val client = XingfanClient.pageClientWithCallTimeoutForTest(30)
            val result = AtomicReference<Result<String>?>(null)
            val worker = Thread {
                result.set(runCatching {
                    XingfanClient.fetchPageWithValidators("${server.baseUrl}/slow", scope = scope, client = client)
                        .html.orEmpty()
                })
            }
            worker.start()
            val requestDeadline = System.nanoTime() + 3_000_000_000L
            while (server.requests.isEmpty() && System.nanoTime() < requestDeadline) {
                Thread.sleep(10)
            }
            Thread.sleep(200) // now inside the slow response body
            val cancelledAt = System.nanoTime()
            scope.cancel()
            worker.join(3_000)
            val elapsedMs = (System.nanoTime() - cancelledAt) / 1_000_000

            assertFalse(worker.isAlive)
            assertTrue(result.get()?.isFailure == true)
            assertTrue("cancel took ${elapsedMs}ms", elapsedMs < 2_000)
        }
    }
}
