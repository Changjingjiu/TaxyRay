package io.github.taxray.data.security

/** User supplied credentials. Never serialize this type into a ledger export. */
data class ApiSettings(
    val baseUrl: String = "https://api.deepseek.com",
    val modelName: String = "deepseek-flash",
    val apiKey: String = "",
    val appendChatCompletions: Boolean = true,
) {
    override fun toString(): String = "ApiSettings(credentials=redacted)"
}
