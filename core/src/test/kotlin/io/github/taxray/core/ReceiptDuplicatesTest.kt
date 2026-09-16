package io.github.taxray.core

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ReceiptDuplicatesTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun receipt(id: String, store: String = "示例商店", date: String = "2026-09-16T06:00:00Z",
                        items: List<DraftItem> = listOf(DraftItem(name = "酸奶", amount = "10.00"))) =
        Receipt(id, store, Instant.parse(date).toEpochMilli(), TaxCalculator.calculateItems(items))

    @Test fun sameMerchantDayAndTotalWarnsDespiteOcrDifference() {
        val old = receipt("old")
        val candidate = receipt("new", items = listOf(DraftItem(name = "发酵乳", amount = "10.00")))
        assertEquals(listOf(old), ReceiptDuplicates.find(candidate, listOf(old), zone))
    }

    @Test fun namedLinesIgnoreOrderFormattingRateAndIdButKeepMultiplicity() {
        val old = receipt("old", "ＡＢＣ 商店", items = listOf(
            DraftItem(name = "酸 奶", amount = "4.00"), DraftItem(name = "面包", amount = "6.00")))
        val candidate = receipt("new", "abc商店", "2026-09-18T06:00:00Z", listOf(
            DraftItem(name = "面包", amount = "6", ratePercent = "9"), DraftItem(name = "酸奶", amount = "4")))
        assertEquals(listOf(old), ReceiptDuplicates.find(candidate, listOf(old), zone))
        val repeated = receipt("repeat", "abc商店", "2026-09-18T06:00:00Z", listOf(
            DraftItem(name = "酸奶", amount = "5"), DraftItem(name = "酸奶", amount = "5")))
        assertTrue(ReceiptDuplicates.find(repeated, listOf(old), zone).isEmpty())
    }

    @Test fun unrelatedMerchantAmountsAndSelfAreNotDuplicates() {
        val candidate = receipt("existing")
        val all = listOf(candidate, receipt("other-store", "另一家店"),
            receipt("other-amount", items = listOf(DraftItem(name = "酸奶", amount = "11"))))
        assertTrue(ReceiptDuplicates.find(candidate, all, zone).isEmpty())
    }

    @Test fun unnamedMerchantsNeedMatchingNamedItemsAndLocalDay() {
        val old = receipt("old", "", "2026-09-15T16:01:00Z")
        assertEquals(listOf(old), ReceiptDuplicates.find(receipt("new", ""), listOf(old), zone))
        assertTrue(ReceiptDuplicates.find(receipt("new", "", "2026-09-17T06:00:00Z"), listOf(old), zone).isEmpty())
        val blank = receipt("blank", "", items = listOf(DraftItem(amount = "10")))
        assertTrue(ReceiptDuplicates.find(blank, listOf(blank.copy(id = "other")), zone).isEmpty())
    }
}
