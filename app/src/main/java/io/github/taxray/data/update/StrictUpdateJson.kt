package io.github.taxray.data.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive

/** Validates only framing/keys; kotlinx.serialization owns JSON value decoding. */
internal object StrictUpdateJson {
    fun validate(text: String) {
        var index = 0
        fun whitespace() { while (index < text.length && text[index] in " \r\n\t") index++ }
        fun string(): String {
            val start = index++
            var escaped = false
            while (index < text.length) {
                val c = text[index++]
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') {
                    return Json.parseToJsonElement(text.substring(start, index)).jsonPrimitive.content
                }
            }
            error("更新 JSON 字符串未闭合")
        }
        fun value(depth: Int) {
            require(depth <= 16) { "更新 JSON 嵌套过深" }; whitespace()
            require(index < text.length) { "更新 JSON 不完整" }
            when (text[index]) {
                '{' -> {
                    index++; whitespace(); val keys = mutableSetOf<String>()
                    if (index < text.length && text[index] == '}') { index++; return }
                    while (true) {
                        whitespace(); require(index < text.length && text[index] == '"') { "更新 JSON 字段无效" }
                        require(keys.add(string())) { "更新 JSON 存在重复字段" }; whitespace()
                        require(index < text.length && text[index++] == ':') { "更新 JSON 字段缺少冒号" }
                        value(depth + 1); whitespace(); require(index < text.length) { "更新 JSON 对象未闭合" }
                        if (text[index++] == '}') break
                        require(text[index - 1] == ',') { "更新 JSON 对象分隔符无效" }
                    }
                }
                '[' -> {
                    index++; whitespace()
                    if (index < text.length && text[index] == ']') { index++; return }
                    while (true) {
                        value(depth + 1); whitespace(); require(index < text.length) { "更新 JSON 数组未闭合" }
                        if (text[index++] == ']') break
                        require(text[index - 1] == ',') { "更新 JSON 数组分隔符无效" }
                    }
                }
                '"' -> { string() }
                else -> {
                    val start = index
                    while (index < text.length && text[index] !in ",]} \r\n\t") index++
                    require(index > start) { "更新 JSON 值无效" }
                }
            }
        }
        value(0); whitespace(); require(index == text.length) { "更新 JSON 存在多余内容" }
    }
}
