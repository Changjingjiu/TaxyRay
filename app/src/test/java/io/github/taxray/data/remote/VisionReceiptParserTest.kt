package io.github.taxray.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.ZoneOffset

class VisionReceiptParserTest {
    @Test fun `shopping screenshots yield nine distinct orders and preserve eight actual payments`() {
        val amounts = listOf("2.46", "6.72", "48.93", "8.79", "169.00", "14.30", "1500.48", "46.90")
        val unpaid = receiptArguments(arguments(name = "香皂", amount = "10.74").dropLast(1) + ",\"declared_total\":\"0\"}",
            status = "unpaid", evidence = "实付0 确认收货后自动付款10.74")
        val paid = amounts.mapIndexed { index, amount ->
            receiptArguments(arguments(name = "合成商品${index + 1}", amount = amount).dropLast(1) +
                ",\"declared_total\":\"$amount\"}", evidence = "实付$amount", sources = "[${(index + 1) / 3 + 1}]")
        }
        val batch = VisionReceiptParser.parseArguments(batchArguments(listOf(unpaid) + paid), sourceImageCount = 3)
        assertEquals(9, batch.receipts.size)
        assertEquals(PaymentStatus.UNPAID, batch.receipts.first().paymentStatus)
        assertEquals("10.74", batch.receipts.first().items.single().amount)
        assertEquals("0.00", batch.receipts.first().declaredTotal)
        assertEquals(amounts, batch.receipts.drop(1).map { it.items.single().amount })
        assertEquals(amounts, batch.receipts.drop(1).map { it.declaredTotal })
        assertTrue(batch.receipts.drop(1).all { it.paymentStatus == PaymentStatus.PAID })
        assertTrue(batch.receipts.all { it.discount == null && it.receiptDateTime == null })
    }

    @Test fun `future payment evidence cannot be promoted to paid and uncertain payment stays reviewable`() {
        val claimedPaid = receiptArguments(arguments(amount = "10.74"), evidence = "实付0 确认收货后自动付款10.74")
        val unknown = receiptArguments(arguments(), status = "unknown", evidence = "")
        val batch = VisionReceiptParser.parseArguments(batchArguments(listOf(claimedPaid, unknown)))
        assertEquals(PaymentStatus.UNPAID, batch.receipts[0].paymentStatus)
        assertTrue(batch.receipts[0].warnings.any { it.contains("尚未付款") })
        assertEquals(PaymentStatus.UNKNOWN, batch.receipts[1].paymentStatus)
        assertTrue(batch.receipts[1].warnings.any { it.contains("付款状态不明确") })
        assertEquals(null, batch.receipts[1].paymentEvidence)
    }

    @Test fun `same merchant and same price independent orders are not merged by parser`() {
        val order = receiptArguments(arguments().dropLast(1) + ",\"store_name\":\"合成商店\"}")
        val batch = VisionReceiptParser.parseArguments(batchArguments(listOf(order, order)))
        assertEquals(2, batch.receipts.size)
        assertEquals(listOf("12.30", "12.30"), batch.receipts.map { it.items.single().amount })
        assertTrue(batch.receipts[0].items.single().id != batch.receipts[1].items.single().id)
    }

    @Test fun `each receipt retains its own image sources and cross image overlap warning`() {
        val input = receiptArguments(arguments(), sources = "[1,3]")
            .replace("\"warnings\":[]", "\"warnings\":[\"跨照片重复行请复核\"]")
        val batch = VisionReceiptParser.parseArguments(batchArguments(listOf(input)), sourceImageCount = 3)
        assertEquals(listOf(1, 3), batch.receipts.single().sourceImageIndices)
        assertEquals(listOf("跨照片重复行请复核"), batch.receipts.single().warnings)
    }

    @Test fun `source indices payment fields and nested batch schema are strictly validated`() {
        val order = receiptArguments(arguments())
        val invalidOrders = listOf(
            order.replace("\"declared_total\":null,", ""),
            order.replace("\"payment_status\":\"paid\",", ""),
            order.replace("\"payment_status\":\"paid\"", "\"payment_status\":\"maybe\""),
            order.replace("\"payment_evidence\":\"实付12.30\",", ""),
            order.replace("\"payment_evidence\":\"实付12.30\"", "\"payment_evidence\":\"\""),
            order.replace("\"warnings\":[]", "\"warnings\":[{}]"),
            order.replace("\"source_image_indices\":[1],", ""),
        ) + listOf("[]", "[0]", "[6]", "[1,1]", "[1.0]", "[\"1\"]", "[{}]", "[true]").map {
            order.replace("\"source_image_indices\":[1]", "\"source_image_indices\":$it")
        }
        invalidOrders.forEach { value ->
            assertThrows(VisionException::class.java) { VisionReceiptParser.parseArguments(batchArguments(listOf(value))) }
        }
        assertThrows(VisionException::class.java) {
            VisionReceiptParser.parseArguments(batchArguments(listOf(receiptArguments(arguments(), sources = "[2]"))), sourceImageCount = 1)
        }
        for (invalid in listOf("{}", "{\"receipts\":[]}", "{\"receipts\":[],\"warnings\":[],\"extra\":true}")) {
            assertThrows(VisionException::class.java) { VisionReceiptParser.parseArguments(invalid) }
        }
    }

    @Test fun `declared total is required nullable and never inferred from payment evidence`() {
        val explicitUnknown = receiptArguments(arguments(amount = "18.00"), evidence = "先用后付 实付¥18(免运费)")
        val receipt = VisionReceiptParser.parseArguments(batchArguments(listOf(explicitUnknown))).receipts.single()
        assertEquals(null, receipt.declaredTotal)
        assertEquals("18.00", receipt.items.single().amount)
        assertEquals(PaymentStatus.PAID, receipt.paymentStatus)
        assertThrows(VisionException::class.java) {
            VisionReceiptParser.parseArguments(batchArguments(listOf(explicitUnknown.replace("\"declared_total\":null,", ""))))
        }
        for (invalid in listOf("{}", "[]", "true", "\"\"", "\"18.001\"")) {
            assertThrows(VisionException::class.java) {
                VisionReceiptParser.parseArguments(batchArguments(listOf(explicitUnknown.replace("\"declared_total\":null", "\"declared_total\":$invalid"))))
            }
        }
    }

    @Test fun `malformed nested money rejects the batch without silently dropping an order`() {
        val valid = receiptArguments(arguments())
        for (value in listOf("{\"amount\":\"9.99\"}", "[\"9.99\"]", "true", "\"0.001\"")) {
            val malformed = receiptArguments(arguments(amount = value))
            assertThrows(VisionException::class.java) {
                VisionReceiptParser.parseArguments(batchArguments(listOf(valid, malformed)))
            }
        }
    }

    @Test fun `batch receipt warning and total item limits are enforced`() {
        val order = receiptArguments(arguments())
        assertEquals(VisionReceiptParser.MAX_RECEIPTS,
            VisionReceiptParser.parseArguments(batchArguments(List(VisionReceiptParser.MAX_RECEIPTS) { order })).receipts.size)
        assertThrows(VisionException::class.java) {
            VisionReceiptParser.parseArguments(batchArguments(List(VisionReceiptParser.MAX_RECEIPTS + 1) { order }))
        }
        assertThrows(VisionException::class.java) {
            VisionReceiptParser.parseArguments(batchArguments(listOf(order), List(VisionReceiptParser.MAX_WARNINGS + 1) { "需复核" }))
        }
        val item = Json.parseToJsonElement(arguments()).jsonObject.getValue("items").jsonArray.single()
        val largeOrder = receiptArguments(buildJsonObject { put("items", JsonArray(List(501) { item })) }.toString())
        assertThrows(VisionException::class.java) {
            VisionReceiptParser.parseArguments(batchArguments(listOf(largeOrder, largeOrder)))
        }
    }

    @Test fun `truncated batch gives actionable local error and cannot produce partial drafts`() {
        val error = assertThrows(VisionException::class.java) {
            VisionReceiptParser.parseResponse(response(arguments(), finish = "length"))
        }
        assertTrue(error.message.orEmpty().contains("输出上限"))
        assertTrue(error.message.orEmpty().contains("减少图片或订单数量"))
    }

    @Test fun `unreadable portions retain readable orders and all unreadable images return review warnings`() {
        val batch = VisionReceiptParser.parseArguments(batchArguments(listOf(receiptArguments(arguments())),
            warnings = listOf("第2张图底部金额被截断 请补拍")))
        assertEquals(1, batch.receipts.size)
        assertEquals(listOf("第2张图底部金额被截断 请补拍"), batch.warnings)
        val empty = VisionReceiptParser.parseArguments(batchArguments(emptyList(), listOf("第1张图无法读取 请重新拍摄")))
        assertTrue(empty.receipts.isEmpty())
        assertTrue(empty.warnings.single().contains("重新拍摄"))
        assertThrows(VisionException::class.java) { VisionReceiptParser.parseArguments(batchArguments(emptyList())) }
        assertThrows(VisionException::class.java) { VisionReceiptParser.parseArguments(arguments()) }
        assertThrows(VisionException::class.java) {
            VisionReceiptParser.parseArguments("""{"input_issue":"multiple_receipts","items":[]}""")
        }
    }

    @Test fun `identical overlapping lines remain separate with distinct review identities`() {
        val item = Json.parseToJsonElement(arguments()).jsonObject.getValue("items").jsonArray.single()
        val receipt = parseReceipt(buildJsonObject {
            put("items", JsonArray(listOf(item, item)))
            put("declared_total", "24.60")
        }.toString())
        assertEquals(2, receipt.items.size)
        assertEquals(listOf("12.30", "12.30"), receipt.items.map { it.amount })
        assertTrue(receipt.items[0].id != receipt.items[1].id)
        assertTrue(receipt.warnings.isEmpty())
        assertEquals("24.60", receipt.declaredTotal)
        assertThrows(VisionException::class.java) {
            parseReceipt("""{"items":[]}""")
        }
        assertThrows(VisionException::class.java) { parseReceipt("""{"items":[]}""") }
    }

    @Test fun `preserves numeric decimal lexeme without Double conversion`() {
        val receipt = parseReceipt(arguments(amount = "99999999.99"))
        assertEquals("99999999.99", receipt.items.single().amount)
        assertEquals("13", receipt.items.single().ratePercent)
    }

    @Test fun `accepts strings and normalizes trailing zeros`() {
        val receipt = parseReceipt(arguments(amount = "\"12.3000\"", category = "agricultural_product"))
        assertEquals("12.30", receipt.items.single().amount)
        assertEquals("9", receipt.items.single().ratePercent)
    }

    @Test fun `only explicit exemption evidence permits zero estimate`() {
        val exempt = parseReceipt(arguments(treatment = "receipt_exempt", evidence = "小票本行标注：免税"))
        assertEquals("0", exempt.items.single().ratePercent)
        assertTrue(exempt.items.single().categoryReason.contains("票面标注免税"))
        for (evidence in listOf("F", "商超自营直采蔬菜", "", "未标注免税", "非免税", "可能免税", "不是免税", "没有免税文字", "免税？")) {
            val draft = parseReceipt(arguments(treatment = "receipt_exempt", evidence = evidence))
            assertEquals(evidence, "13", draft.items.single().ratePercent)
            assertTrue(draft.warnings.any { it.contains("免税") })
        }
    }

    @Test fun `six percent services remain distinct from transport and retail food`() {
        for (category in listOf("catering", "accommodation", "life_service")) {
            assertEquals("6", parseReceipt(arguments(category = category)).items.single().ratePercent)
        }
        assertEquals("9", parseReceipt(arguments(category = "transport")).items.single().ratePercent)
        assertEquals("13", parseReceipt(arguments(category = "processed_food")).items.single().ratePercent)
    }

    @Test fun `uncertain classification overrides a nine percent suggestion`() {
        val receipt = parseReceipt(arguments(category = "agricultural_product", issue = "name_incomplete"))
        assertEquals("13", receipt.items.single().ratePercent)
        assertEquals(listOf("第 1 项 品名不完整 可补充品名并调整税率"), receipt.warnings)
    }

    @Test fun `processed dairy and pastries cannot use primary product category from ingredients`() {
        for ((name, expectedReason) in listOf(
            "原味酸奶" to "加工乳制品", "发酵乳" to "加工乳制品", "调制乳" to "加工乳制品",
            "奶油饼干" to "加工食品", "糕点" to "加工食品", "牛肉馅饼" to "加工食品",
        )) {
            for (category in listOf("fresh_milk", "agricultural_product")) {
                val receipt = parseReceipt(arguments(name = name, category = category))
                assertEquals(name, "13", receipt.items.single().ratePercent)
                assertEquals(expectedReason, receipt.items.single().categoryReason)
                assertTrue(receipt.warnings.isEmpty())
            }
        }
        for (name in listOf("巴氏杀菌乳", "灭菌乳")) {
            assertEquals("9", parseReceipt(arguments(name = name, category = "fresh_milk")).items.single().ratePercent)
        }
        // A cafe selling a served dish is a service transaction, not supermarket retail goods.
        assertEquals("6", parseReceipt(arguments(name = "牛肉馅饼", category = "catering")).items.single().ratePercent)
    }

    @Test fun `clear structured classification does not scan uncertainty words from product text`() {
        val receipt = parseReceipt(arguments(name = "不确定星球原味酸奶", category = "processed_dairy", categoryEvidence = "酸奶"))
        assertEquals("13", receipt.items.single().ratePercent)
        assertEquals("加工乳制品", receipt.items.single().categoryReason)
        assertTrue(receipt.warnings.isEmpty())
    }

    @Test fun `missing or unanchored classification evidence produces a specific action`() {
        for (evidence in listOf("", "消费者身份无法判断 0.09", "鲜奶")) {
            val receipt = parseReceipt(arguments(name = "饮品", category = "fresh_milk", categoryEvidence = evidence))
            assertEquals("13", receipt.items.single().ratePercent)
            assertEquals(listOf("第 1 项 分类依据未对应品名 可补充品名并调整税率"), receipt.warnings)
            assertFalse(receipt.items.single().categoryReason.contains("0.09"))
        }
        val ambiguous = parseReceipt(arguments(category = "unknown", issue = "category_ambiguous", categoryEvidence = ""))
        assertEquals("13", ambiguous.items.single().ratePercent)
        assertTrue(ambiguous.warnings.single().contains("商品类别不明确 可调整税率"))
    }

    @Test fun `uncertain exempt item cannot silently become zero and F does not override a known category`() {
        val uncertain = parseReceipt(arguments(treatment = "receipt_exempt", evidence = "免税", issue = "name_incomplete"))
        assertEquals("13", uncertain.items.single().ratePercent)
        val normal = parseReceipt(arguments(name = "鲜蛋 F", category = "agricultural_product", evidence = "F"))
        assertEquals("9", normal.items.single().ratePercent)
        assertTrue(normal.warnings.isEmpty())
    }

    @Test fun `synthetic ten line receipt retains exact total and concise dairy and pastry reasons`() {
        val lines = listOf(
            Triple("纸巾", "3.50", "general_goods"), Triple("大米", "7.00", "agricultural_product"),
            Triple("原味酸奶", "12.50", "processed_dairy"), Triple("鲜蛋", "5.00", "agricultural_product"),
            Triple("红苹果", "9.91", "agricultural_product"), Triple("食用盐", "10.00", "edible_oil_salt"),
            Triple("苏打饼干", "8.00", "processed_food"), Triple("洗手液", "14.00", "general_goods"),
            Triple("面粉", "10.00", "agricultural_product"), Triple("牛肉馅饼", "20.00", "processed_food"),
        )
        val input = buildJsonObject {
            put("store_name", "合成示例商店")
            put("items", JsonArray(lines.map { (name, amount, category) ->
                Json.parseToJsonElement(arguments(name = name, amount = amount, category = category)).jsonObject.getValue("items").jsonArray.single()
            }))
            put("declared_total", "99.91"); put("receipt_datetime", "2025-11-29T18:42:00")
        }
        val receipt = parseReceipt(input.toString())
        assertEquals(10, receipt.items.size)
        assertEquals("99.91", receipt.declaredTotal)
        assertEquals("2025-11-29T18:42:00", receipt.receiptDateTime)
        assertEquals("加工乳制品", receipt.items[2].categoryReason)
        assertEquals("加工食品", receipt.items[9].categoryReason)
        assertTrue(receipt.warnings.isEmpty())
    }

    @Test fun `ticket local date is retained without filling absent components from device clock`() {
        fun withDate(raw: String) = arguments().dropLast(1) + ",\"receipt_datetime\":$raw}"
        assertEquals("2024-02-29T09:30:00", parseReceipt(withDate("\"2024-02-29T09:30:00\"")).receiptDateTime)
        assertEquals(null, parseReceipt(arguments()).receiptDateTime)
        assertEquals(null, parseReceipt(withDate("null")).receiptDateTime)
        for (invalidDate in listOf("0000-01-01T00:00:00", "1900-01-01T00:00:00", "2025-02-29T09:30:00", "2025-11-29", "11-29T09:30:00", "2025-11-29T24:00:00", "2025-11-29T09:30:00Z", "2025-11-29T09:30:00+08:00")) {
            val receipt = parseReceipt(withDate(JsonPrimitive(invalidDate).toString()))
            assertEquals(null, receipt.receiptDateTime)
            assertEquals(listOf("账单时间无效 可在入账前修改消费时间"), receipt.warnings)
            assertEquals("12.30", receipt.items.single().amount)
        }
    }

    @Test fun `ticket date range is checked after timezone conversion including epoch boundaries`() {
        assertEquals("1970-01-01T00:00:00", VisionReceiptParser.validateReceiptDateTime("1970-01-01T00:00:00", ZoneOffset.UTC))
        assertEquals("1969-12-31T12:00:00", VisionReceiptParser.validateReceiptDateTime("1969-12-31T12:00:00", ZoneOffset.ofHours(-12)))
        assertEquals("9999-12-31T23:59:59", VisionReceiptParser.validateReceiptDateTime("9999-12-31T23:59:59", ZoneOffset.UTC))
        assertEquals("2125-11-29T18:42:00", VisionReceiptParser.validateReceiptDateTime("2125-11-29T18:42:00", ZoneOffset.UTC))
        assertThrows(IllegalArgumentException::class.java) {
            VisionReceiptParser.validateReceiptDateTime("1970-01-01T00:00:00", ZoneOffset.ofHours(8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            VisionReceiptParser.validateReceiptDateTime("9999-12-31T23:59:59", ZoneOffset.ofHours(-12))
        }
    }

    @Test fun `unallocated receipt discount is explicit without fabricating negative or changed lines`() {
        val receipt = parseReceipt(arguments().dropLast(1) +
            """, "declared_total":"12.26", "order_discount":{"amount":"0.04","evidence":"优惠 0.04"}}""")
        assertEquals("12.30", receipt.items.single().amount)
        assertEquals("12.26", receipt.declaredTotal)
        assertEquals(ReceiptDiscount(4, "优惠 0.04"), receipt.discount)
        assertTrue(receipt.warnings.isEmpty())
    }

    @Test fun `discount evidence cannot bypass strict money structure or old protocol rejection`() {
        for (discount in listOf(
            """{"amount":"-0.04","evidence":"优惠"}""",
            """{"amount":"0.001","evidence":"优惠"}""",
            """{"amount":"1e2","evidence":"优惠"}""",
            """{"amount":"0.04","evidence":""}""",
            """{"amount":"0.04"}""",
            """{"amount":"0.04","evidence":"优惠","extra":1}""",
            """{"amount":"0.04","amount":"0.05","evidence":"优惠"}""",
            """{"amount":"99999999990.01","evidence":"优惠"}""",
        )) assertThrows(VisionException::class.java) {
            parseReceipt(arguments().dropLast(1) + ",\"order_discount\":$discount}")
        }
        assertThrows(VisionException::class.java) {
            parseReceipt(arguments().dropLast(1) + ",\"amount_issue\":\"order_discount_unallocated\"}")
        }
    }

    @Test fun `zero price gifts and full discounts are retained without made up payments`() {
        val receipt = parseReceipt(arguments(amount = "0").dropLast(1) + ",\"declared_total\":\"0.00\"}")
        assertEquals("0.00", receipt.items.single().amount)
        assertEquals("0.00", receipt.declaredTotal)
        assertEquals(null, receipt.discount)
    }

    @Test fun `already discounted lines are not discounted again even if AI repeats a discount`() {
        val receipt = parseReceipt(arguments().dropLast(1) +
            """, "declared_total":"12.30", "order_discount":{"amount":"1.00","evidence":"会员优惠 1.00"}}""")
        assertEquals("12.30", receipt.items.single().amount)
        val draft = io.github.taxray.ReceiptDraft(items = receipt.items, declaredTotal = receipt.declaredTotal!!,
            receiptDiscount = receipt.discount)
        assertEquals(null, draft.validationMessage())
        assertEquals(1230L, draft.calculated().single().breakdown.amountCents)
        assertThrows(IllegalArgumentException::class.java) { draft.allocateDiscount() }
    }

    @Test fun `negative missing ambiguous excessive and nonfinite amounts fail closed`() {
        for (amount in listOf("-1", "0.001", "\"1,234.56\"", "\"¥12.30\"", "\"1e2\"", "1e2", "100000000", "null", "true", "\"NaN\"", "[]", "{}")) {
            assertThrows("amount=$amount", VisionException::class.java) {
                parseReceipt(arguments(amount = amount))
            }
        }
        assertThrows(VisionException::class.java) {
            parseReceipt("""{"items":[{"name":"纸巾","tax_rate":0.13}]}""")
        }
    }

    @Test fun `unknown categories issues treatments and old rate protocol are rejected`() {
        for (value in listOf(
            arguments(category = "0.06"), arguments(issue = "maybe"), arguments(treatment = "zero_rate"),
            arguments(category = "unknown"), arguments().replace("\"category\":", "\"tax_rate\":0.13,\"category\":"),
            arguments().replace("\"category\":", "\"category_reason\":\"并非不确定\",\"category\":"),
        )) {
            assertThrows(VisionException::class.java) { parseReceipt(value) }
        }
    }

    @Test fun `missing required item fields and unknown fields are rejected`() {
        val item = Json.parseToJsonElement(arguments()).jsonObject.getValue("items").jsonArray.single().jsonObject
        for (key in listOf("name", "amount", "category", "category_evidence", "classification_issue", "tax_treatment")) {
            val missing = buildJsonObject { put("items", JsonArray(listOf(kotlinx.serialization.json.JsonObject(item - key)))) }
            assertThrows("missing $key", VisionException::class.java) { parseReceipt(missing.toString()) }
        }
        assertThrows(VisionException::class.java) {
            parseReceipt(arguments().replace("\"category\":", "\"paid\":false,\"category\":"))
        }
    }

    @Test fun `declared total mismatch stays unallocated and is validated by the live draft`() {
        val receipt = parseReceipt(arguments().dropLast(1) + ",\"declared_total\":\"9.00\"}")
        assertEquals("12.30", receipt.items.single().amount)
        assertEquals("9.00", receipt.declaredTotal)
        assertEquals(null, receipt.discount)
        val draft = io.github.taxray.ReceiptDraft(items = receipt.items, declaredTotal = receipt.declaredTotal!!)
        assertTrue(draft.validationMessage()!!.contains("3.30"))
        assertEquals(null, draft.copy(declaredTotal = "12.30").validationMessage())
    }

    @Test fun `exact declared total does not produce mismatch warning`() {
        val receipt = parseReceipt(arguments().dropLast(1) + ",\"declared_total\":12.3}")
        assertFalse(receipt.warnings.any { it.contains("不一致") })
    }

    @Test fun `declared receipt total may exceed the individual item ceiling`() {
        val item = Json.parseToJsonElement(arguments(amount = "\"99999999.99\"")).jsonObject.getValue("items").jsonArray.single()
        val receipt = parseReceipt(buildJsonObject {
            put("items", JsonArray(listOf(item, item))); put("declared_total", "199999999.98")
        }.toString())
        assertEquals("199999999.98", receipt.declaredTotal)
        assertFalse(receipt.warnings.any { it.contains("不一致") })
        assertThrows(VisionException::class.java) {
            parseReceipt(arguments().dropLast(1) + ",\"declared_total\":\"99999999990.01\"}")
        }
    }

    @Test fun `complete single expected tool call accepted`() {
        val result = VisionReceiptParser.parseResponse(response(arguments()))
        assertEquals("纸巾", result.receipts.single().items.single().name)
    }

    @Test fun `wrong function additional calls truncated response and refusal rejected`() {
        for (response in listOf(
            response(arguments(), name = "record_receipt_items"),
            response(arguments(), name = "parse_receipt_tax_items"),
            response(arguments(), calls = 2),
            response(arguments(), finish = "length"),
            response(arguments(), finish = "stop"),
            response(arguments(), choices = 2),
            """{"choices":[{"finish_reason":"tool_calls","message":{"refusal":"No"}}]}""",
            """{"choices":[{"message":{"content":"{\\\"items\\\":[]}"}}]}""",
            response(arguments()).dropLast(1),
        )) assertThrows(VisionException::class.java) { VisionReceiptParser.parseResponse(response) }
    }

    @Test fun `item limit and depth limit are enforced`() {
        val item = Json.parseToJsonElement(arguments()).jsonObject.getValue("items").jsonArray.single()
        fun many(count: Int) = buildJsonObject { put("items", JsonArray(List(count) { item })) }.toString()
        assertEquals(1_000, parseReceipt(many(1_000)).items.size)
        assertThrows(VisionException::class.java) { parseReceipt(many(1_001)) }
        assertThrows(VisionException::class.java) { parseReceipt("[".repeat(100) + "]".repeat(100)) }
        assertThrows(VisionException::class.java) { parseReceipt("""{"items":[]}""") }
    }

    @Test fun `duplicate including escaped object keys are rejected before last value wins`() {
        for (value in listOf(
            arguments().replace("\"amount\":", "\"amount\":-1,\"amount\":"),
            arguments().replace("\"category\":", "\"categ\\u006fry\":\"unknown\",\"category\":"),
        )) assertThrows(VisionException::class.java) { parseReceipt(value) }
    }

    @Test fun `malformed body is never disclosed in exception`() {
        val exception = assertThrows(VisionException::class.java) {
            VisionReceiptParser.parseResponse("secret-token-and-sensitive-receipt")
        }
        assertFalse(exception.message.orEmpty().contains("secret-token"))
        assertEquals(null, exception.cause)
    }

    @Test fun `request uses one forced canonical tool and JPEG data URI`() {
        val body = VisionAgentService.recognitionBody("vision-model", listOf(byteArrayOf(1, 2)))
        assertEquals(1, body.getValue("tools").jsonArray.size)
        assertEquals(VisionReceiptParser.FUNCTION_NAME, body.getValue("tool_choice").jsonObject
            .getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
        val messages = body.getValue("messages").jsonArray
        val url = messages[1].jsonObject.getValue("content").jsonArray.single {
            it.jsonObject.getValue("type").jsonPrimitive.content == "image_url"
        }.jsonObject
            .getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
        assertEquals("data:image/jpeg;base64,AQI=", url)
        assertFalse(body.containsKey("api_key"))
        val schema = body.getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject.getValue("parameters").jsonObject
        val batchProperties = schema.getValue("properties").jsonObject
        assertEquals(setOf("receipts", "warnings"), batchProperties.keys)
        val receiptSchema = batchProperties.getValue("receipts").jsonObject.getValue("items").jsonObject
        val properties = receiptSchema.getValue("properties").jsonObject
        assertTrue(properties.containsKey("receipt_datetime"))
        assertFalse(properties.containsKey("input_issue"))
        assertEquals(setOf("paid", "unpaid", "unknown"),
            properties.getValue("payment_status").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertTrue(receiptSchema.getValue("required").jsonArray.map { it.jsonPrimitive.content }
            .containsAll(listOf("declared_total", "payment_status", "payment_evidence", "source_image_indices", "warnings")))
        assertEquals(setOf("string", "null"), properties.getValue("declared_total").jsonObject
            .getValue("type").jsonArray.map { it.jsonPrimitive.content }.toSet())
        assertFalse(properties.containsKey("amount_issue"))
        val discount = properties.getValue("order_discount").jsonObject
        assertEquals(setOf("amount", "evidence"), discount.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet())
        val item = properties.getValue("items").jsonObject.getValue("items").jsonObject
        val itemProperties = item.getValue("properties").jsonObject
        assertFalse(itemProperties.containsKey("tax_rate"))
        assertFalse(itemProperties.containsKey("category_reason"))
        assertTrue(itemProperties.getValue("category").jsonObject.getValue("enum").jsonArray.any { it.jsonPrimitive.content == "life_service" })
        assertTrue(item.getValue("required").jsonArray.any { it.jsonPrimitive.content == "classification_issue" })
    }

    private fun arguments(amount: String = "12.30", category: String = "general_goods", evidence: String = "",
        treatment: String = "standard", issue: String = "none", name: String = "纸巾", categoryEvidence: String = name): String =
        """{"items":[{"name":${JsonPrimitive(name)},"amount":$amount,"category":${JsonPrimitive(category)},"category_evidence":${JsonPrimitive(categoryEvidence)},"classification_issue":${JsonPrimitive(issue)},"tax_treatment":${JsonPrimitive(treatment)},"exemption_evidence":${JsonPrimitive(evidence)}}]}"""

    // A test fixture assembles the current batch protocol without parsing away duplicate keys.
    private fun receiptArguments(receipt: String, status: String = "paid", evidence: String = "实付12.30", sources: String = "[1]"): String {
        val total = if ("\"declared_total\"" in receipt) "" else "\"declared_total\":null,"
        return """{$total"payment_status":${JsonPrimitive(status)},"payment_evidence":${JsonPrimitive(evidence)},"source_image_indices":$sources,"warnings":[],${receipt.drop(1)}"""
    }

    private fun batchArguments(receipts: List<String>, warnings: List<String> = emptyList()): String =
        """{"receipts":[${receipts.joinToString(",")}],"warnings":${JsonArray(warnings.map(::JsonPrimitive))}}"""

    private fun parseReceipt(receipt: String): VisionReceipt =
        VisionReceiptParser.parseArguments(batchArguments(listOf(receiptArguments(receipt)))).receipts.single()

    private fun response(arguments: String, name: String = VisionReceiptParser.FUNCTION_NAME,
        calls: Int = 1, choices: Int = 1, finish: String = "tool_calls"): String = buildJsonObject {
        put("choices", buildJsonArray {
            repeat(choices) { add(buildJsonObject {
                put("finish_reason", finish)
                put("message", buildJsonObject {
                    put("tool_calls", buildJsonArray {
                        repeat(calls) { add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject { put("name", name); put("arguments", batchArguments(listOf(receiptArguments(arguments)))) })
                        }) }
                    })
                })
            }) }
        })
    }.toString()
}
