package io.github.taxray

import io.github.taxray.core.DraftItem
import io.github.taxray.core.TaxCalculator
import io.github.taxray.data.remote.PaymentStatus
import io.github.taxray.data.remote.ReceiptDiscount
import io.github.taxray.data.remote.VisionReceipt
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReceiptScanSessionTest {
    @Test fun repeatedTicketTotalAndDiscountAreKeptOnceWithoutAllocatingOrDroppingRows() {
        val session = ReceiptScanSession(startedAt = 123L)
            .append(round(amount = "10.04", total = "30", discount = ReceiptDiscount(4, "抹零 0.04")))
            .append(round(amount = "20.00", total = "30.00", discount = ReceiptDiscount(4, "优惠 0.04")))
        val draft = session.draft()
        assertEquals(listOf("10.04", "20.00"), draft.items.map { it.amount })
        assertEquals("30.00", draft.declaredTotal)
        assertEquals(4L, draft.receiptDiscount!!.amountCents)
        assertNull(draft.appliedDiscount)
        assertEquals(3_004L, draft.calculated().sumOf { it.breakdown.amountCents })
        assertTrue(draft.validationMessage()!!.contains("0.04"))
        assertFalse(draft.requireDeclaredTotal)
        assertTrue(draft.fromVision)
    }

    @Test fun totalsAreNeverInferredFromPartialItemSums() {
        val draft = ReceiptScanSession()
            .append(round(amount = "12.00", total = "99.91"))
            .append(round(amount = "20.00", total = null)).draft()
        assertEquals("99.91", draft.declaredTotal)
        assertEquals(listOf("12.00", "20.00"), draft.items.map { it.amount })
        assertNotNull(draft.validationMessage())
        assertNull(draft.appliedDiscount)
    }

    @Test fun conflictingTotalsAndDiscountsPreserveItemsButRequireAnExplicitFinalTotal() {
        val session = ReceiptScanSession()
            .append(round(total = "9.99", discount = ReceiptDiscount(1, "优惠 0.01")))
            .append(round(total = "10.00", discount = ReceiptDiscount(2, "优惠 0.02")))
            .append(round(total = "9.99", discount = ReceiptDiscount(1, "优惠 0.01")))
        val draft = session.draft()
        assertEquals(3, draft.items.size)
        assertEquals("", draft.declaredTotal)
        assertNull(draft.receiptDiscount)
        assertTrue(draft.requireDeclaredTotal)
        assertTrue(draft.warnings.any { it.contains("实付不一致") })
        assertTrue(draft.warnings.any { it.contains("整单优惠不一致") })
        assertNotNull(draft.validationMessage())
        assertNull(draft.copy(declaredTotal = "30.00").validationMessage())
    }

    @Test fun eachConflictingFieldIsClearedIndependently() {
        val discountConflict = ReceiptScanSession()
            .append(round(total = "20.00", discount = ReceiptDiscount(1, "优惠 0.01")))
            .append(round(total = "20", discount = ReceiptDiscount(2, "优惠 0.02"))).draft()
        assertEquals("20.00", discountConflict.declaredTotal)
        assertNull(discountConflict.receiptDiscount)
        assertTrue(discountConflict.requireDeclaredTotal)

        val totalConflict = ReceiptScanSession()
            .append(round(total = "20.00", discount = ReceiptDiscount(1, "优惠 0.01")))
            .append(round(total = "19.99", discount = ReceiptDiscount(1, "优惠 0.01"))).draft()
        assertEquals("", totalConflict.declaredTotal)
        assertEquals(1L, totalConflict.receiptDiscount!!.amountCents)
        assertTrue(totalConflict.requireDeclaredTotal)
    }

    @Test fun missingMetadataUsesEvidenceFromAnotherRoundAndMinuteLevelDatesCanMatch() {
        val date = "2026-09-16T14:02:01"
        val session = ReceiptScanSession(startedAt = 123L)
            .append(round(store = "", date = null))
            .append(round(store = "　Ａ店  ", date = date))
            .append(round(store = "a 店", date = "2026-09-16T14:02:59"))
        assertEquals("Ａ店", session.draft().storeName)
        assertEquals(LocalDateTime.parse(date).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), session.draft().timestamp)
        assertTrue(session.draft().warnings.none { it.contains("账单时间") })
    }

    @Test fun aDifferentMerchantOrPrintedMinuteIsRejectedWithoutChangingEarlierRounds() {
        val session = ReceiptScanSession(startedAt = 123L).append(round(date = "2026-09-16T14:02:00"))
        val before = session.draft()
        val merchantError = assertThrows(IllegalArgumentException::class.java) { session.append(round(store = "另一家店")) }
        assertTrue(merchantError.message!!.contains("另开一笔"))
        val timeError = assertThrows(IllegalArgumentException::class.java) { session.append(round(date = "2026-09-16T14:03:00")) }
        assertTrue(timeError.message!!.contains("另开一笔"))
        assertEquals(1, session.rounds.size)
        assertEquals(before, session.draft())
        assertEquals(123L, session.startedAt)
    }

    @Test fun dateAndItemWarningsAreClearAcrossRoundsWithoutRepeatingTheMissingDateHint() {
        val session = ReceiptScanSession(startedAt = 123L)
            .append(round().copy(warnings = listOf("第 1 项 品名不完整 可补充品名并调整税率", "账单时间无效 可在入账前修改消费时间")))
            .append(round().copy(warnings = listOf("第 1 项 品名不完整 可补充品名并调整税率")))
        val warnings = session.draft().warnings
        assertEquals(3, warnings.size)
        assertTrue(warnings[0].startsWith("第 1 次识别 第 1 项"))
        assertTrue(warnings[1].startsWith("第 2 次识别 第 1 项"))
        assertEquals(1, warnings.count { it.contains("账单时间") })
        assertEquals(123L, session.draft().timestamp)
    }

    @Test fun equalIncomingIdsDoNotCollapseLegitimateItemsAndInputCollectionsAreSnapshotted() {
        val item = DraftItem(id = "reused", name = "纸巾", amount = "10.00")
        val inputItems = mutableListOf(item, item)
        val inputWarnings = mutableListOf("第 1 项 可调整税率")
        val sourceImages = mutableListOf(1)
        val input = round().copy(items = inputItems, warnings = inputWarnings, sourceImageIndices = sourceImages)
        val one = ReceiptScanSession().append(input)
        val two = one.append(input)
        inputItems.clear()
        inputWarnings.clear()
        sourceImages.clear()
        assertEquals(2, one.items.size)
        assertEquals(4, two.items.size)
        assertEquals(4, two.items.map { it.id }.distinct().size)
        assertTrue(two.items.none { it.id == "reused" })
        assertEquals(one.items, two.items.take(2))
        assertEquals(listOf("第 1 项 可调整税率"), one.rounds.single().warnings)
        assertEquals(listOf(1), one.rounds.single().sourceImageIndices)
    }

    @Test fun explicitDuplicateRemovalKeepsSelectedOriginalsAndDoesNotRewriteTicketMoney() {
        val session = ReceiptScanSession().append(round(total = "10.00")).append(round(total = "10"))
        val selected = listOf(session.items.first())
        val draft = session.draft(selected)
        assertEquals(selected, draft.items)
        assertEquals("10.00", draft.declaredTotal)
        assertNull(draft.validationMessage())
        assertEquals(2, session.items.size)
        assertThrows(IllegalArgumentException::class.java) { session.draft(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { session.draft(selected + selected) }
        assertThrows(IllegalArgumentException::class.java) { session.draft(listOf(selected.single().copy(amount = "1.00"))) }
    }

    @Test fun totalItemLimitIsAcrossAllRoundsAndRejectedAppendLeavesExactlyOneThousandRows() {
        val row = DraftItem(name = "商品", amount = "0.01")
        val half = round().copy(items = List(500) { row })
        val session = ReceiptScanSession().append(half).append(half)
        assertEquals(TaxCalculator.MAX_ITEMS, session.items.size)
        assertEquals(1_000, session.items.map { it.id }.distinct().size)
        assertNull(session.draft().validationMessage())
        val error = assertThrows(IllegalArgumentException::class.java) { session.append(round()) }
        assertTrue(error.message!!.contains("1000"))
        assertEquals(2, session.rounds.size)
        assertEquals(1_000, session.items.size)
    }

    @Test fun anEmptySessionOrEmptyNewRoundCannotBeTurnedIntoAnEntry() {
        val session = ReceiptScanSession()
        assertTrue(session.items.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { session.draft() }
        assertThrows(IllegalArgumentException::class.java) { session.append(round().copy(items = emptyList())) }
        assertTrue(session.rounds.isEmpty())
    }

    @Test fun paymentEvidenceIsPreservedAndOnlyMatchingStatusesRemainCertain() {
        PaymentStatus.entries.forEach { status ->
            val session = ReceiptScanSession()
                .append(round().copy(paymentStatus = status, paymentEvidence = " 实付 ¥10.00 "))
                .append(round().copy(paymentStatus = status, paymentEvidence = "实付 ¥10.00"))
            val draft = session.draft()
            assertEquals(status, draft.paymentStatus)
            assertEquals("实付 ¥10.00", draft.paymentEvidence)
            assertFalse(draft.paymentConfirmed)
            assertTrue(draft.warnings.none { it.contains("付款状态不一致") })
        }
    }

    @Test fun mixedPaymentEvidenceNeverSilentlyPromotesAnOrderToPaid() {
        listOf(
            PaymentStatus.PAID to PaymentStatus.UNPAID,
            PaymentStatus.UNPAID to PaymentStatus.PAID,
            PaymentStatus.PAID to PaymentStatus.UNKNOWN,
            PaymentStatus.UNPAID to PaymentStatus.UNKNOWN,
        ).forEach { (first, second) ->
            val session = ReceiptScanSession()
                .append(round().copy(paymentStatus = first, paymentEvidence = "第一张付款凭据"))
                .append(round().copy(paymentStatus = second, paymentEvidence = "第二张付款凭据"))
            val draft = session.draft()
            assertEquals(PaymentStatus.UNKNOWN, draft.paymentStatus)
            assertEquals("第一张付款凭据；第二张付款凭据", draft.paymentEvidence)
            assertTrue(draft.warnings.any { it.contains("付款状态不一致") })
            assertTrue(draft.validationMessage()!!.contains("付款状态不明确"))
            assertFalse(draft.paymentConfirmed)
            assertEquals(2, session.items.size)
        }
    }

    @Test fun paymentEvidenceHasABoundedLengthAndAbsentDatesUseScanTimeWithAWarning() {
        val session = ReceiptScanSession(startedAt = 123L)
            .append(round().copy(paymentEvidence = "甲".repeat(200)))
            .append(round().copy(paymentEvidence = "乙".repeat(200)))
            .append(round().copy(paymentEvidence = "丙".repeat(200)))
        val draft = session.draft()
        assertTrue(draft.paymentEvidence!!.length <= 600)
        assertEquals(123L, draft.timestamp)
        assertEquals(1, draft.warnings.count { it == "未识别到账单时间 已使用本次识别时间 可修改" })
        assertNull(ReceiptScanSession().append(round().copy(paymentEvidence = " ")).draft().paymentEvidence)
    }

    @Test fun separateOrdersAtTheSameMerchantRemainIndependentSessions() {
        val first = ReceiptScanSession().append(round(total = "10.00", date = "2026-09-16T14:02:00"))
        val second = ReceiptScanSession().append(round(amount = "14.30", total = "14.30", date = "2026-09-16T14:03:00"))
        assertEquals(1, first.items.size)
        assertEquals("10.00", first.draft().declaredTotal)
        assertEquals(1, second.items.size)
        assertEquals("14.30", second.draft().declaredTotal)
        assertThrows(IllegalArgumentException::class.java) { first.append(second.rounds.single()) }
    }

    private fun round(
        store: String = "示例商店",
        amount: String = "10.00",
        total: String? = null,
        date: String? = null,
        discount: ReceiptDiscount? = null,
    ) = VisionReceipt(store, listOf(DraftItem(name = "纸巾", amount = amount)), total,
        receiptDateTime = date, discount = discount, paymentStatus = PaymentStatus.PAID)
}
