package io.github.taxray.data.remote

import io.github.taxray.core.DraftItem
import io.github.taxray.core.TaxCalculator
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Ticket evidence only. Applying any discount still requires an explicit review action. */
data class ReceiptDiscount(val amountCents: Long, val evidence: String)

data class VisionReceipt(
    val storeName: String,
    val items: List<DraftItem>,
    val declaredTotal: String? = null,
    val warnings: List<String> = emptyList(),
    /** Ticket-local ISO date/time; no time zone or device-clock inference. */
    val receiptDateTime: String? = null,
    val discount: ReceiptDiscount? = null,
)

class VisionException(message: String) : IllegalArgumentException(message)

/** Pure parser: the response is untrusted input and this class has no database access. */
object VisionReceiptParser {
    const val FUNCTION_NAME = "parse_receipt_tax_items"
    const val MAX_RESPONSE_BYTES = 2_000_000L
    private const val INVALID_RESPONSE = "模型返回的结构或金额无效，未写入账本。请重新识别或手动录入。"
    private val decimalPattern = Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?")
    private val maxAmount = BigDecimal("99999999.99")
    private val json = Json { isLenient = false; allowSpecialFloatingPointValues = false }

    fun parseResponse(response: String): VisionReceipt = guarded {
        require(response.length <= MAX_RESPONSE_BYTES)
        val root = parseObject(response)
        val choices = root["choices"] as? JsonArray ?: invalid()
        require(choices.size == 1)
        val choice = choices.single() as? JsonObject ?: invalid()
        require(string(choice["finish_reason"], 32) == "tool_calls")
        val message = choice["message"] as? JsonObject ?: invalid()
        require(message["refusal"] == null || message["refusal"] == JsonNull)
        val calls = message["tool_calls"] as? JsonArray ?: invalid()
        require(calls.size == 1)
        val call = calls.single() as? JsonObject ?: invalid()
        require(string(call["type"], 32) == "function")
        val function = call["function"] as? JsonObject ?: invalid()
        require(string(function["name"], 100) == FUNCTION_NAME)
        val arguments = string(function["arguments"], 1_500_000, allowControls = true)
        parseArgumentsInternal(arguments)
    }

    fun parseArguments(arguments: String): VisionReceipt = guarded { parseArgumentsInternal(arguments) }

    private fun parseArgumentsInternal(arguments: String): VisionReceipt {
        require(arguments.length <= 1_500_000)
        val data = parseObject(arguments)
        require(data.keys.all { it in setOf("store_name", "items", "declared_total", "receipt_datetime", "order_discount") })
        val store = data["store_name"]?.let { string(it, 120) }.orEmpty()
        val items = data["items"] as? JsonArray ?: invalid()
        require(items.isNotEmpty() && items.size <= 1_000)
        val warnings = mutableListOf<String>()
        val discount = data["order_discount"]?.takeUnless { it == JsonNull }?.let { value ->
            val fields = value as? JsonObject ?: invalid()
            require(fields.keys == setOf("amount", "evidence"))
            val amount = TaxCalculator.parseReceiptTotal(decimal(fields["amount"]).toPlainString())
            val evidence = string(fields["evidence"], 120)
            require(evidence.isNotBlank())
            ReceiptDiscount(amount, evidence)
        }
        val drafts = items.mapIndexed { index, value ->
            val item = value as? JsonObject ?: invalid()
            require(item.keys.all { it in setOf("name", "amount", "category", "category_evidence", "classification_issue", "tax_treatment", "exemption_evidence") })
            val name = string(item["name"], 200).ifBlank { "消费品目" }
            val amount = money(item["amount"])
            val suggestedCategory = ReceiptCategory.fromWire(string(item["category"], 40))
            val category = ReceiptClassification.guardPrimaryFood(suggestedCategory, name)
            val issue = ClassificationIssue.fromWire(string(item["classification_issue"], 40))
            require(suggestedCategory != ReceiptCategory.UNKNOWN || issue != ClassificationIssue.NONE)
            val categoryEvidence = string(item["category_evidence"], 80)
            val treatment = string(item["tax_treatment"], 32)
            require(treatment in setOf("standard", "receipt_exempt"))
            val evidence = item["exemption_evidence"]?.let { string(it, 200) }.orEmpty()
            val anchoredEvidence = categoryEvidence.isNotBlank() &&
                (name.contains(categoryEvidence) || store.contains(categoryEvidence))
            val hint = when {
                issue != ClassificationIssue.NONE -> issue.hint
                !anchoredEvidence -> "分类依据未对应品名 可补充品名并调整税率"
                treatment == "receipt_exempt" && !explicitExemption(evidence) -> "缺少票面免税文字 可调整税率"
                else -> null
            }
            val percent = when {
                hint != null -> "13"
                treatment == "receipt_exempt" -> "0"
                else -> category.ratePercent
            }
            if (hint != null) warnings += "第 ${index + 1} 项 $hint"
            DraftItem(
                id = UUID.randomUUID().toString(), name = name,
                amount = amount.toPlainString(), ratePercent = percent,
                // Display text is local and concise; model prose never controls the tax estimate.
                categoryReason = listOfNotNull(
                    if (hint != null) "类别待确认" else category.label,
                    if (percent == "0") "票面标注免税" else null,
                    hint,
                ).joinToString(" "),
            )
        }
        val declared = data["declared_total"]?.takeUnless { it == JsonNull }?.let {
            BigDecimal.valueOf(TaxCalculator.parseReceiptTotal(decimal(it).toPlainString()), 2)
        }
        // Amount differences are rendered from the current draft, not frozen in AI warnings.
        // Neither a matching discount nor a small difference changes any item automatically.
        val dateTime = data["receipt_datetime"]?.takeUnless { it == JsonNull }?.let {
            val raw = string(it, 40)
            try {
                validateReceiptDateTime(raw, ZoneId.systemDefault())
            } catch (_: Exception) {
                warnings += "票面时间无效 可在入账前修改消费时间"
                null
            }
        }
        return VisionReceipt(store, drafts, declared?.toPlainString(), warnings, dateTime, discount)
    }

    /** Same instant range as ledger validation; the printed year alone is insufficient near UTC boundaries. */
    internal fun validateReceiptDateTime(raw: String, zone: ZoneId): String {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}").matches(raw))
        val local = LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        val instant = local.atZone(zone).toInstant().toEpochMilli()
        require(instant in 0..253402300799999L)
        return raw
    }

    private fun explicitExemption(evidence: String): Boolean =
        evidence.contains("免税") && listOf("不", "非", "未", "无", "没", "可能", "猜测", "推测", "似乎", "大概", "待", "?", "？")
            .none(evidence::contains)

    private fun money(element: JsonElement?): BigDecimal {
        val value = decimal(element)
        require(value >= BigDecimal.ZERO && value <= maxAmount)
        // Do not silently round a model's ambiguous/over-precise money into a ledger amount.
        require(value.stripTrailingZeros().scale() <= 2)
        return value.setScale(2)
    }

    private fun decimal(element: JsonElement?): BigDecimal {
        val primitive = element as? JsonPrimitive ?: invalid()
        require(primitive != JsonNull)
        val raw = primitive.content
        require(raw.length <= 32 && decimalPattern.matches(raw))
        // JsonPrimitive.content preserves the numeric lexeme; a Double is never involved.
        return raw.toBigDecimal()
    }

    private fun string(element: JsonElement?, maxLength: Int, allowControls: Boolean = false): String {
        val primitive = element as? JsonPrimitive ?: invalid()
        require(primitive.isString)
        val value = primitive.content
        require(value.length <= maxLength)
        if (!allowControls) require(value.none { it.code < 32 })
        return value.trim()
    }

    internal fun parseObject(value: String): JsonObject {
        // Bound nesting and reject duplicate object keys before the JSON tree loses that information.
        val objects = mutableListOf<MutableSet<String>?>()
        var quoted = false
        var escaped = false
        var stringStart = 0
        value.forEachIndexed { index, char ->
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') {
                    quoted = false
                    var next = index + 1
                    while (next < value.length && value[next].isWhitespace()) next++
                    if (next < value.length && value[next] == ':') {
                        val keys = objects.lastOrNull() ?: invalid()
                        val key = (json.parseToJsonElement(value.substring(stringStart, index + 1)) as JsonPrimitive).content
                        require(keys.add(key))
                    }
                }
            } else when (char) {
                '"' -> { quoted = true; stringStart = index }
                '{' -> { objects.add(mutableSetOf()); require(objects.size <= 24) }
                '[' -> { objects.add(null); require(objects.size <= 24) }
                '}', ']' -> { require(objects.isNotEmpty()); objects.removeAt(objects.lastIndex) }
            }
        }
        require(objects.isEmpty() && !quoted)
        return json.parseToJsonElement(value) as? JsonObject ?: invalid()
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        invalid()
    }

    private fun invalid(): Nothing = throw VisionException(INVALID_RESPONSE)
}
