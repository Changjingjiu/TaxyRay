package io.github.taxray.data.update

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** All responses are intercepted before DNS; no real GitHub request or credentials are used. */
class ReleaseUpdateServiceTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun `production transport has no auth cookies retries or implicit redirects and bounded calls`() {
        val client = ReleaseUpdateService.newClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertTrue(client.interceptors.isEmpty())
        assertTrue(client.networkInterceptors.isEmpty())
        assertEquals(CookieJar.NO_COOKIES, client.cookieJar)
        assertEquals(Authenticator.NONE, client.authenticator)
        assertEquals(600_000, client.callTimeoutMillis)
    }
    @Test fun `check and download preserve headers boundaries and stream exact bytes with progress`() = runBlocking {
        val requests = mutableListOf<Request>()
        val service = service { request ->
            requests.add(request)
            when (request.url.toString()) {
                UpdateSource.latestUrl.toString() -> response(request, 200, UpdateFixtures.release().toByteArray())
                UpdateFixtures.metadataUrl -> response(request, 200, UpdateFixtures.manifest().toByteArray())
                UpdateFixtures.apkUrl -> response(request, 302).newBuilder().header("Location", "https://release-assets.githubusercontent.com/test?sig=synthetic").build()
                else -> response(request, 200, UpdateFixtures.bytes)
            }
        }
        val update = service.check()
        val progress = mutableListOf<Long>()
        val directory = files.newFolder()
        val downloaded = service.download(update, directory) { count, total -> assertEquals(5L, total); progress.add(count) }
        assertArrayEquals(UpdateFixtures.bytes, downloaded.readBytes())
        assertEquals(5L, progress.last())
        assertEquals(listOf(downloaded.name), directory.listFiles()!!.map { it.name })
        assertEquals(UpdateSource.API_VERSION, requests.first().header("X-GitHub-Api-Version"))
        assertEquals("application/vnd.github+json", requests.first().header("Accept"))
        requests.forEach { assertNull(it.header("Authorization")); assertNull(it.header("Cookie")); assertNull(it.body) }
        requests.drop(1).forEach { assertNull(it.header("X-GitHub-Api-Version")) }
    }
    @Test fun `hash mismatch truncated response and oversized declared length leave no APK or partial`() {
        val update = UpdateFixtures.update()
        for (content in listOf("wrong".toByteArray(), byteArrayOf(1), ByteArray(6))) {
            val directory = files.newFolder()
            assertThrows(Exception::class.java) { runBlocking { service { response(it, 200, content) }.download(update, directory) { _, _ -> } } }
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }
    @Test fun `untrusted redirect and redirect loops stop before fetching unsafe resources`() {
        var calls = 0
        val unsafe = service { request -> calls++; response(request, 302).newBuilder().header("Location", "https://attacker.test/file").build() }
        assertThrows(Exception::class.java) { runBlocking { unsafe.download(UpdateFixtures.update(), files.newFolder()) { _, _ -> } } }
        assertEquals(1, calls)
        calls = 0
        val loop = service { request -> calls++; response(request, 302).newBuilder().header("Location", UpdateFixtures.apkUrl).build() }
        assertThrows(Exception::class.java) { runBlocking { loop.download(UpdateFixtures.update(), files.newFolder()) { _, _ -> } } }
        assertEquals(4, calls)
    }
    @Test fun `unavailable release rate limits unexpected status and malformed metadata are errors`() {
        for (status in listOf(302, 403, 404, 429, 500)) {
            val error = assertThrows(Exception::class.java) { runBlocking { service { response(it, status, "synthetic-private-body".toByteArray()) }.check() } }
            assertFalse(error.message.orEmpty().contains("synthetic-private-body"))
        }
        for (payload in listOf("{}", "x".repeat(UpdateSource.MAX_RELEASE_BYTES + 1))) {
            assertThrows(Exception::class.java) { runBlocking { service { response(it, 200, payload.toByteArray()) }.check() } }
        }
        assertThrows(Exception::class.java) { runBlocking {
            service { request -> response(request, 200, if (request.url == UpdateSource.latestUrl) UpdateFixtures.release().toByteArray() else "{}".toByteArray()) }.check()
        } }
    }
    @Test fun `socket errors never disclose raw server details`() {
        val error = assertThrows(IOException::class.java) { runBlocking { service { throw IOException("synthetic-secret") }.check() } }
        assertFalse(error.message.orEmpty().contains("synthetic-secret"))
        assertTrue(error.message.orEmpty().contains("GitHub"))
    }
    @Test fun `cancel during body streaming cancels the call and removes partial file`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val released = CountDownLatch(1)
        val canceled = AtomicBoolean(false)
        val directory = files.newFolder()
        val client = ReleaseUpdateService.newClient().newBuilder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { canceled.set(true); released.countDown() }
        }).addInterceptor { chain ->
            val source = object : ForwardingSource(Buffer().write(UpdateFixtures.bytes)) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    entered.complete(Unit)
                    check(released.await(5, TimeUnit.SECONDS))
                    return super.read(sink, byteCount)
                }
            }.buffer()
            response(chain.request(), 200).newBuilder().body(object : ResponseBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = 5L
                override fun source() = source
            }).build()
        }.build()
        val job = launch { ReleaseUpdateService(client).download(UpdateFixtures.update(), directory) { _, _ -> } }
        try {
            withTimeout(5_000) { entered.await() }
            withTimeout(5_000) { job.cancelAndJoin() }
            assertTrue(canceled.get())
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally { released.countDown(); job.cancel() }
    }

    private fun service(block: (Request) -> Response) = ReleaseUpdateService(ReleaseUpdateService.newClient().newBuilder().addInterceptor { block(it.request()) }.build())
    private fun response(request: Request, code: Int, bytes: ByteArray = byteArrayOf()) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
        .body(bytes.toResponseBody("application/octet-stream".toMediaType())).build()
}
