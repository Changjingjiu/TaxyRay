package io.github.taxray.data.remote

import io.github.taxray.data.security.ApiSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class ApiEndpointPolicyTest {
    @Test fun `constructs endpoint under the user selected base path`() {
        assertEquals("https://example.test/v1/chat/completions", ApiEndpointPolicy.endpoint(settings()).toString())
        assertEquals("https://example.test/proxy/v1/chat/completions", ApiEndpointPolicy.endpoint(settings("https://example.test/proxy/v1/")).toString())
    }

    @Test fun `automatic suffix accepts complete endpoints without duplicating the path`() {
        for (url in listOf("https://api.deepseek.com/chat/completions", "https://api.deepseek.com/chat/completions/")) {
            assertEquals("https://api.deepseek.com/chat/completions", ApiEndpointPolicy.endpoint(settings(url)).toString())
        }
        assertEquals("https://example.test/proxy/v1/chat/completions",
            ApiEndpointPolicy.endpoint(settings("https://example.test/proxy/v1/chat/completions")).toString())
    }

    @Test fun `explicit mode preserves the path including its trailing slash`() {
        for (url in listOf("https://example.test/invoke", "https://example.test/custom/invoke/", "https://example.test/chat/completions/")) {
            assertEquals(url, ApiEndpointPolicy.endpoint(settings(url).copy(appendChatCompletions = false)).toString())
        }
    }

    @Test fun `address preview needs neither model nor credentials`() {
        assertEquals("https://api.deepseek.com/chat/completions",
            ApiEndpointPolicy.resolveUrl("https://api.deepseek.com", true).toString())
        assertEquals("https://example.test/", ApiEndpointPolicy.resolveUrl("https://example.test/", false).toString())
    }

    @Test fun `refuses insecure credential bearing or ambiguous URLs`() {
        for (base in listOf("http://example.test/v1", "https://user:password@example.test", "https://@example.test",
            "https://example.test/v1?key=secret", "https://example.test/v1?", "https://example.test/v1#fragment",
            "https://example.test/v1#", "not-a-url")) {
            for (append in listOf(true, false)) {
                assertThrows(base, IllegalArgumentException::class.java) {
                    ApiEndpointPolicy.endpoint(settings(base).copy(appendChatCompletions = append))
                }
            }
        }
    }

    @Test fun `requires model and safe nonempty key`() {
        for (config in listOf(settings().copy(modelName = ""), settings().copy(apiKey = ""),
            settings().copy(apiKey = "key\nInjected: yes"), settings().copy(apiKey = "key secret"))) {
            assertThrows(IllegalArgumentException::class.java) { ApiEndpointPolicy.endpoint(config) }
        }
    }

    @Test fun `settings string representation redacts credentials and endpoint`() {
        val printed = settings().toString()
        assertFalse(printed.contains("test-key"))
        assertFalse(printed.contains("example.test"))
    }

    private fun settings(baseUrl: String = "https://example.test/v1") = ApiSettings(baseUrl, "vision-model", "test-key")
}
