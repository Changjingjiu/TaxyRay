package io.github.taxray.data.remote

import io.github.taxray.data.security.ApiSettings
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** One destination chosen by the user; redirects are independently disabled on the client. */
object ApiEndpointPolicy {
    fun endpoint(settings: ApiSettings): HttpUrl {
        val endpoint = resolveUrl(settings.baseUrl, settings.appendChatCompletions)
        require(settings.modelName.isNotBlank() && settings.modelName.length <= 200 && settings.modelName.none { it.code < 32 }) {
            "请填写支持图片和工具调用的模型名称。"
        }
        require(settings.apiKey.isNotBlank() && settings.apiKey.length <= 8_192 && settings.apiKey.all { it.code in 33..126 }) {
            "请填写有效 API Key，不能含空白或控制字符。"
        }
        return endpoint
    }

    /** Resolves just the address so Settings can preview it without accessing a key. */
    fun resolveUrl(baseUrl: String, appendChatCompletions: Boolean): HttpUrl {
        val raw = baseUrl.trim()
        require(raw.length <= 2_048 && raw.none { it.code < 33 || it == '\\' }) { "API 地址无效。" }
        require(raw.startsWith("https://", ignoreCase = true)) { "API 地址必须使用 HTTPS。" }
        val base = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("请输入有效的 HTTPS API 地址。")
        require(base.isHttps) { "API 地址必须使用 HTTPS，密钥不会通过明文 HTTP 发送。" }
        require(base.encodedUsername.isEmpty() && base.encodedPassword.isEmpty() &&
            base.query == null && base.fragment == null && !raw.substringAfter("://").substringBefore('/').contains('@')) {
            "API 地址不能包含用户名、密码、查询参数或片段。"
        }
        if (!appendChatCompletions) return base
        val path = base.encodedPath.trimEnd('/')
        val resolvedPath = if (path.endsWith("/chat/completions")) path else "$path/chat/completions"
        return base.newBuilder().encodedPath(resolvedPath).build()
    }
}
