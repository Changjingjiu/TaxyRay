package io.github.taxray.core

import org.junit.Assert.*
import org.junit.Test

class ReceiptItemDuplicatesTest {
    private fun item(name: String, amount: String = "9.99") = DraftItem(name = name, amount = amount)

    @Test fun exactCopiesNormalizeWidthCaseAndUnicodeSpacesButRetainOriginalDrafts() {
        val first = item("ＡＢＣ\u00a0原味 酸奶 ５００ｍｌ", "09.90").copy(ratePercent = "待选择")
        val second = item("abc原味\u3000酸奶500ML", "9.9")
        val groups = ReceiptItemDuplicates.find(listOf(first, second))
        assertEquals(listOf(DuplicateItemGroup(listOf(first, second), true)), groups)
        assertSame(first, groups.single().items.first())
        assertEquals("待选择", groups.single().items.first().ratePercent)
    }

    @Test fun aSmallOcrDifferenceInALongNameIsOnlyASimilarityHint() {
        val first = item("沃集鲜茉莉绝弦蛋白威化饼干")
        val second = item("沃集鲜茉莉绝弦蛋白威化饼千")
        assertEquals(listOf(DuplicateItemGroup(listOf(first, second), false)), ReceiptItemDuplicates.find(listOf(first, second)))
    }

    @Test fun shortDifferentNamesAndMostlyDifferentProductsAreNotGrouped() {
        val items = listOf(item("牛奶"), item("羊奶"), item("沃集鲜茉莉蛋白威化饼干"), item("沃集鲜牛肉香菜馅饼"))
        assertTrue(ReceiptItemDuplicates.find(items).isEmpty())
    }

    @Test fun paidAmountsMustBeExactlyEqualToTheCent() {
        assertTrue(ReceiptItemDuplicates.find(listOf(item("经典原味酸牛奶", "9.99"), item("经典原味酸牛奶", "10.00"))).isEmpty())
        val maximum = TaxCalculator.formatMoney(TaxCalculator.MAX_ITEM_AMOUNT_CENTS)
        assertEquals(1, ReceiptItemDuplicates.find(listOf(item("酸奶", maximum), item("酸奶", maximum))).size)
    }

    @Test fun differentNumbersAndUnitsNeverBecomeFuzzyMatches() {
        val items = listOf(
            item("品牌经典原味酸牛奶500ml"), item("品牌经典原味酸牛奶550ml"),
            item("品牌经典原味酸牛奶500g"), item("品牌经典原味酸牛奶50.0ml"),
            item("品牌经典原味酸牛奶500ml2瓶"), item("品牌经典原味酸牛奶500ml3瓶"),
        )
        assertTrue(ReceiptItemDuplicates.find(items).isEmpty())
    }

    @Test fun invalidAmountsAndMeaninglessNamesAreSkipped() {
        val invalid = listOf("", "9.999", "-9.99", "NaN", "1e2", "9999999999999", " ", "0." )
            .flatMap { listOf(item("相同商品", it), item("相同商品", it)) }
        val unnamed = listOf("", "  ", "消 费品目", "？？？", "商品", "未知商品", "12345")
            .flatMap { listOf(item(it), item(it)) }
        assertTrue(ReceiptItemDuplicates.find(invalid + unnamed).isEmpty())
    }

    @Test fun groupsAreDisjointAndKeepInputOrderIncludingRealRepeatPurchases() {
        val items = listOf(item("酸奶"), item("面包"), item("酸奶"), item("面包"), item("酸奶"))
        val groups = ReceiptItemDuplicates.find(items)
        assertEquals(listOf(items[0], items[2], items[4]), groups[0].items)
        assertEquals(listOf(items[1], items[3]), groups[1].items)
        assertEquals(items.size, groups.flatMap { it.items }.map { it.id }.distinct().size)
        assertEquals(5, items.size) // Detection never removes even an exact repeat purchase.
    }

    @Test fun fuzzyMatchesDoNotGrowThroughTransitiveChains() {
        val first = item("经典原味酸牛奶")
        val second = item("经典原昧酸牛奶")
        val third = item("经典原昧酸牛孚")
        assertEquals(listOf(DuplicateItemGroup(listOf(first, second), false)), ReceiptItemDuplicates.find(listOf(first, second, third)))
    }

    @Test fun exactCopiesStayTogetherEvenWhenASeparateFuzzyGroupAlreadyExists() {
        val items = listOf(item("经典原味酸牛奶"), item("经典原昧酸牛奶"), item("经典原昧酸牛孚"), item("经典原昧酸牛孚"))
        val groups = ReceiptItemDuplicates.find(items)
        assertEquals(listOf(items[0], items[1]), groups[0].items)
        assertFalse(groups[0].exact)
        assertEquals(listOf(items[2], items[3]), groups[1].items)
        assertTrue(groups[1].exact)
    }

    @Test fun insertionAndDeletionWithinTheThresholdAreHandled() {
        val first = item("品牌经典原味风味发酵酸牛奶")
        val second = item("品牌经典原味风味发酵乳酸牛奶")
        assertEquals(listOf(DuplicateItemGroup(listOf(first, second), false)), ReceiptItemDuplicates.find(listOf(first, second)))
    }

    @Test fun integerSimilarityThresholdAndTwoEditCapAreRespected() {
        fun matches(first: String, second: String) = ReceiptItemDuplicates.find(listOf(item(first), item(second))).isNotEmpty()
        assertFalse(matches("a".repeat(6), "a".repeat(5) + "b")) // 5 / 6 is below 85%.
        assertTrue(matches("a".repeat(7), "a".repeat(6) + "b"))
        assertFalse(matches("a".repeat(13), "a".repeat(11) + "bb"))
        assertTrue(matches("a".repeat(14), "a".repeat(12) + "bb"))
        assertFalse(matches("a".repeat(100), "a".repeat(97) + "bbb"))
    }

    @Test fun duplicateDetectionDoesNotRequireTheDraftTaxRateToAlreadyBeValid() {
        val first = item("酸奶").copy(ratePercent = "")
        val second = item("酸奶").copy(ratePercent = "13")
        assertEquals(listOf(DuplicateItemGroup(listOf(first, second), true)), ReceiptItemDuplicates.find(listOf(first, second)))
    }

    @Test fun excessiveNamesAreNotTruncatedIntoDuplicates() {
        val prefix = "长".repeat(200)
        assertTrue(ReceiptItemDuplicates.find(listOf(item(prefix + "甲"), item(prefix + "乙"))).isEmpty())
    }

    @Test fun oneThousandIdenticalRowsRemainOneReviewGroup() {
        val items = List(TaxCalculator.MAX_ITEMS) { item("酸奶") }
        assertEquals(listOf(DuplicateItemGroup(items, true)), ReceiptItemDuplicates.find(items))
    }

    @Test fun overTheReceiptLimitIsRejectedAndAnEmptyDraftHasNoMatches() {
        assertTrue(ReceiptItemDuplicates.find(emptyList()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            ReceiptItemDuplicates.find(List(TaxCalculator.MAX_ITEMS + 1) { item("酸奶") })
        }
    }
}
