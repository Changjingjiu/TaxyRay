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

    @Test fun croppedMerchantAndOcrTyposWithRepeatedSpecsStillSuggestTheExistingOrder() {
        val old = receipt("old", "", items = listOf(DraftItem(name = longTitle, amount = "48.93")))
        val candidate = receipt("new", "", items = listOf(DraftItem(
            name = longTitle.replace("博士", "博土") + " 400ml*1瓶", amount = "48.93")))
        assertEquals(listOf(old), ReceiptDuplicates.find(candidate, listOf(old), zone))
        assertEquals(listOf(candidate), ReceiptDuplicates.find(old, listOf(candidate), zone))
        assertEquals(1, old.items.size)
        assertEquals(4893L, old.totalAmountCents)
    }

    @Test fun fuzzyOrderHintsKeepAmountDayMerchantAndSpecificationBoundaries() {
        val old = receipt("old", "", items = listOf(DraftItem(name = longTitle, amount = "48.93")))
        fun candidate(name: String = longTitle.replace("博士", "博土"), amount: String = "48.93",
                      store: String = "", date: String = "2026-09-16T06:00:00Z") =
            receipt("new", store, date, listOf(DraftItem(name = name, amount = amount)))
        val different = listOf(candidate(amount = "48.94"), candidate(date = "2026-09-17T06:00:00Z"),
            candidate(longTitle.replace("400ml", "450ml")), candidate(longTitle.replace("400ml", "400g")),
            candidate(longTitle + " 400ml*2瓶"), candidate("家用地板专用清洁剂柠檬清香400ml五合一除菌去污"))
        different.forEach { assertTrue(ReceiptDuplicates.find(it, listOf(old), zone).isEmpty()) }
        val known = old.copy(storeName = "甲商店")
        assertTrue(ReceiptDuplicates.find(candidate(store = "乙商店"), listOf(known), zone).isEmpty())
    }

    @Test fun fuzzyMatchingDoesNotFlattenMultipleLinesOrCompareUnrelatedShortNames() {
        val old = receipt("old", "", items = listOf(DraftItem(name = longTitle, amount = "10")))
        val split = receipt("new", "", items = List(2) { DraftItem(name = longTitle, amount = "5") })
        assertTrue(ReceiptDuplicates.find(split, listOf(old), zone).isEmpty())
        val milk = receipt("milk", "", items = listOf(DraftItem(name = "牛奶", amount = "10")))
        val goat = receipt("goat", "", items = listOf(DraftItem(name = "羊奶", amount = "10")))
        assertTrue(ReceiptDuplicates.find(goat, listOf(milk), zone).isEmpty())
    }

    @Test fun shortOcrDifferencesReuseTheConservativeProductMatcher() {
        val old = receipt("old", "", items = listOf(DraftItem(name = "经典原味酸牛奶", amount = "10")))
        val candidate = receipt("new", "", items = listOf(DraftItem(name = "经典原昧酸牛奶", amount = "10")))
        assertEquals(listOf(old), ReceiptDuplicates.find(candidate, listOf(old), zone))
    }

    private val longTitle = "品牌博士地毯清洁剂400ml多功能便携免水干洗布艺沙发清洁"
}
