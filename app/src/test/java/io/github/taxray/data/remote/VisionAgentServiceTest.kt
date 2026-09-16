package io.github.taxray.data.remote

import io.github.taxray.data.security.ApiSettings
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake application interceptors stop all calls before DNS; no external API is contacted. */
class VisionAgentServiceTest {
    private val settings = ApiSettings("https://example.test/v1", "vision-model", "synthetic-test-key")

    @Test fun `production client refuses redirects retries and unbounded calls`() {
        val client = VisionAgentService.createDefaultClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertTrue(client.callTimeoutMillis in 1..90_000)
    }

    @Test fun `connection test sends only lightweight text and scopes credential to header`() = runBlocking {
        val client = VisionAgentService.createDefaultClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("https://example.test/v1/chat/completions", request.url.toString())
            assertEquals("Bearer synthetic-test-key", request.header("Authorization"))
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            assertTrue(body.contains("Reply OK."))
            assertFalse(body.contains("synthetic-test-key"))
            assertFalse(body.contains("image_url"))
            assertFalse(body.contains("thinking"))
            assertEquals(64, Json.parseToJsonElement(body).jsonObject.getValue("max_tokens").jsonPrimitive.int)
            response(request, 200, """{"choices":[{"finish_reason":"stop","message":{"content":"OK"}}]}""")
        }.build()
        val message = VisionAgentService(client).testConnection(settings)
        assertTrue(message.contains("文本连接成功"))
        assertTrue(message.contains("仍需实际小票验证"))
    }

    @Test fun `official DeepSeek connection disables default thinking and preserves an explicit endpoint`() = runBlocking {
        val deepSeek = settings.copy(baseUrl = "https://api.deepseek.com/chat/completions", modelName = "deepseek-flash", appendChatCompletions = false)
        val client = VisionAgentService.createDefaultClient().newBuilder().addInterceptor { chain ->
            assertEquals(deepSeek.baseUrl, chain.request().url.toString())
            val body = requestJson(chain.request())
            assertEquals("disabled", body.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(64, body.getValue("max_tokens").jsonPrimitive.int)
            assertEquals("false", body.getValue("stream").jsonPrimitive.content)
            response(chain.request(), 200, """{"choices":[{"finish_reason":"stop","message":{"content":"OK.","reasoning_content":""}}]}""")
        }.build()
        assertTrue(VisionAgentService(client).testConnection(deepSeek).contains("连接成功"))
    }

    @Test fun `DeepSeek-specific parameters are not sent to other hosts even for that model name`() = runBlocking {
        val config = settings.copy(baseUrl = "https://api.deepseek.com.example.test/invoke", modelName = "deepseek-flash", appendChatCompletions = false)
        val client = VisionAgentService.createDefaultClient().newBuilder().addInterceptor { chain ->
            assertEquals(config.baseUrl, chain.request().url.toString())
            assertFalse(requestJson(chain.request()).containsKey("thinking"))
            response(chain.request(), 200, """{"choices":[{"finish_reason":"stop","message":{"content":"OK"}}]}""")
        }.build()
        assertTrue(VisionAgentService(client).testConnection(config).contains("连接成功"))
    }

    @Test fun `DeepSeek receipt request combines non-thinking mode with forced canonical tool`() = runBlocking {
        val client = VisionAgentService.createDefaultClient().newBuilder().addInterceptor { chain ->
            val body = requestJson(chain.request())
            assertEquals("disabled", body.getValue("thinking").jsonObject.getValue("type").jsonPrimitive.content)
            assertEquals(8_192, body.getValue("max_tokens").jsonPrimitive.int)
            assertEquals(VisionReceiptParser.FUNCTION_NAME,
                body.getValue("tool_choice").jsonObject.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
            response(chain.request(), 200, toolResponse())
        }.build()
        val result = VisionAgentService(client).parseReceipt(
            settings.copy(baseUrl = "https://api.deepseek.com", modelName = "deepseek-flash"),
            listOf(byteArrayOf(1, 2)),
        )
        assertEquals("113.00", result.items.single().amount)
    }

    @Test fun `all photos are sent once in selection order in a single tool request`() = runBlocking {
        val photos = listOf(byteArrayOf(3, 5, 8), byteArrayOf(2, 4), byteArrayOf(9))
        var calls = 0
        val client = VisionAgentService.createDefaultClient().newBuilder().addInterceptor { chain ->
            calls++
            val body = requestJson(chain.request())
            val messages = body.getValue("messages").jsonArray
            val content = messages[1].jsonObject.getValue("content").jsonArray
            val sentPhotos = content.filter { it.jsonObject.getValue("type").jsonPrimitive.content == "image_url" }
                .map { it.jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content }
            assertEquals(photos.map { "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(it)}" }, sentPhotos)
            assertEquals(listOf("第 1 张 / 共 3 张", "第 2 张 / 共 3 张", "第 3 张 / 共 3 张"),
                content.filter { it.jsonObject.getValue("type").jsonPrimitive.content == "text" }
                    .drop(1).map { it.jsonObject.getValue("text").jsonPrimitive.content })
            assertEquals(1, body.getValue("tools").jsonArray.size)
            val prompt = messages.first().jsonObject.getValue("content").jsonPrimitive.content
            assertTrue(prompt.contains("不自行去重 不删除同名同价行"))
            assertTrue(prompt.contains("跨照片的重复行留给用户处理"))
            assertTrue(prompt.contains("multiple_receipts"))
            assertFalse(body.toString().contains(settings.apiKey))
            response(chain.request(), 200, toolResponse())
        }.build()
        assertEquals("113.00", VisionAgentService(client).parseReceipt(settings, photos).items.single().amount)
        assertEquals(1, calls)
    }

    @Test fun `empty excessive and oversized batches fail before networking`() {
        var calls = 0
        val client = VisionAgentService.createDefaultClient().newBuilder().addInterceptor { chain ->
            calls++
            response(chain.request(), 200, toolResponse())
        }.build()
        val badBatches = listOf(
            emptyList(), listOf(byteArrayOf()),
            List(ReceiptImageBatch.MAX_IMAGES + 1) { byteArrayOf(1) },
            listOf(ByteArray(ReceiptImageBatch.MAX_IMAGE_BYTES + 1)),
            List(ReceiptImageBatch.MAX_IMAGES) { ByteArray(ReceiptImageBatch.MAX_IMAGE_BYTES + 1) },
        )
        badBatches.forEach { images ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { VisionAgentService(client).parseReceipt(settings, images) }
            }
        }
        assertEquals(0, calls)
    }

    @Test fun `five photos at exact byte limits remain accepted`() {
        val photos = List(ReceiptImageBatch.MAX_IMAGES) { ByteArray(ReceiptImageBatch.MAX_IMAGE_BYTES) }
        assertEquals(ReceiptImageBatch.MAX_TOTAL_BYTES.toLong(), photos.sumOf { it.size.toLong() })
        ReceiptImageBatch.validate(photos)
    }

    @Test fun `http errors suppress sensitive body and do not follow location`() {
        for (status in listOf(302, 400, 401, 402, 403, 404, 413, 429, 500)) {
            val calls = java.util.concurrent.atomic.AtomicInteger(0)
            val client = VisionAgentService.createDefaultClient().newBuilder().addInterceptor { chain ->
                calls.incrementAndGet()
                response(chain.request(), status, "secret-provider-body-and-user-data").newBuilder()
                    .header("Location", "https://untrusted.test/steal").build()
            }.build()
            val error = assertThrows(VisionException::class.java) {
                runBlocking { VisionAgentService(client).testConnection(settings) }
            }
            assertFalse(error.message.orEmpty().contains("secret-provider-body"))
            assertTrue(error.message.orEmpty().contains("HTTP $status"))
            if (status == 402) assertTrue(error.message.orEmpty().contains("余额不足"))
            if (status == 400) assertTrue(error.message.orEmpty().contains("参数不兼容"))
            // Coroutine stack-trace recovery can attach a copy of our already-sanitized exception.
            // Assert the security property across the whole chain, not the runtime's cause shape.
            generateSequence<Throwable>(error) { it.cause }.take(10).forEach { cause ->
                assertFalse(cause.message.orEmpty().contains("secret-provider-body"))
                assertFalse(cause.message.orEmpty().contains("synthetic-test-key"))
            }
            assertEquals(1, calls.get())
        }
    }

    @Test fun `oversized and invalid connection responses are rejected`() {
        for (payload in listOf("x".repeat(2_000_001), "{}", """{"choices":[]}""")) {
            val client = VisionAgentService.createDefaultClient().newBuilder().addInterceptor { chain ->
                response(chain.request(), 200, payload)
            }.build()
            assertThrows(VisionException::class.java) {
                runBlocking { VisionAgentService(client).testConnection(settings) }
            }
        }
    }

    @Test fun `reasoning-only truncated and empty responses explain that the server responded`() {
        val cases = listOf(
            """{"choices":[{"finish_reason":"length","message":{"content":"","reasoning_content":"synthetic-sensitive-reasoning"}}]}""" to "输出上限",
            """{"choices":[{"finish_reason":"stop","message":{"content":"","reasoning_content":"synthetic-sensitive-reasoning"}}]}""" to "空回复",
            """{"choices":[{"finish_reason":"insufficient_system_resource","message":{"content":"partial"}}]}""" to "中断",
            """{"choices":[]}""" to "格式无效",
        )
        for ((payload, expected) in cases) {
            val client = VisionAgentService.createDefaultClient().newBuilder().addInterceptor { chain ->
                response(chain.request(), 200, payload)
            }.build()
            val error = assertThrows(VisionException::class.java) {
                runBlocking { VisionAgentService(client).testConnection(settings) }
            }
            assertTrue(error.message.orEmpty().contains("HTTP 200"))
            assertTrue(error.message.orEmpty().contains(expected))
            assertFalse(error.message.orEmpty().contains("synthetic-sensitive"))
        }
    }

    @Test fun `timeout exception details never escape`() {
        val client = VisionAgentService.createDefaultClient().newBuilder().addInterceptor {
            throw SocketTimeoutException("synthetic-secret-server-detail")
        }.build()
        val error = assertThrows(VisionException::class.java) {
            runBlocking { VisionAgentService(client).testConnection(settings) }
        }
        assertTrue(error.message.orEmpty().contains("超时"))
        assertFalse(error.message.orEmpty().contains("synthetic-secret"))
    }

    @Test fun `coroutine cancellation cancels underlying call`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val client = VisionAgentService.createDefaultClient().newBuilder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.set(true) }
            })
            .addInterceptor { chain ->
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                response(chain.request(), 200, """{"choices":[{"finish_reason":"stop","message":{"content":"OK"}}]}""")
            }.build()
        val job = launch { VisionAgentService(client).testConnection(settings) }
        try {
            withTimeout(5_000) { entered.await() }
            job.cancelAndJoin()
            assertTrue(cancelled.get())
        } finally {
            release.countDown()
        }
    }

    private fun response(request: okhttp3.Request, status: Int, body: String): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic")
        .body(body.toResponseBody("application/json".toMediaType())).build()

    private fun requestJson(request: okhttp3.Request) =
        Json.parseToJsonElement(Buffer().also { request.body!!.writeTo(it) }.readUtf8()).jsonObject

    private fun toolResponse() = buildJsonObject {
        put("choices", buildJsonArray { add(buildJsonObject {
            put("finish_reason", "tool_calls")
            put("message", buildJsonObject { put("tool_calls", buildJsonArray { add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", VisionReceiptParser.FUNCTION_NAME)
                    put("arguments", """{"items":[{"name":"合成测试商品","amount":"113.00","category":"general_goods","category_evidence":"测试商品","classification_issue":"none","tax_treatment":"standard"}]}""")
                })
            }) }) })
        }) })
    }.toString()
}
