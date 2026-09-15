package io.github.taxray.data.backup

import io.github.taxray.core.DraftItem
import io.github.taxray.core.Receipt
import io.github.taxray.core.TaxCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {
    @Test fun finalDiscountedAmountsAndFreeRowsSurviveBothBackupFormats() {
        val source = listOf(Receipt("discount", "演示账单", 1_700_000_000_000L, TaxCalculator.calculateItems(listOf(
            DraftItem("a", "商品一", "101.80", "13"),
            DraftItem("b", "商品二", "98.20", "9"),
            DraftItem("c", "赠品", "0.00", "13"),
        ))))
        assertEquals(20000L, source.single().totalAmountCents)
        assertEquals(1982L, source.single().totalTaxCents)
        assertEquals(source, BackupCodec.fromJson(BackupCodec.toJson(source)))
        assertEquals(source, BackupCodec.fromCsv(BackupCodec.toCsv(source)))
    }

    private fun sample(id: String = "receipt-1") = Receipt(
        id = id,
        storeName = "测试商店",
        timestamp = 1_700_000_000_123L,
        items = TaxCalculator.calculateItems(listOf(
            DraftItem("item-b", "米", "109.00", "9", "按所选税率估算"),
            DraftItem("item-a", "日用品", "113.00", "13", ""),
            DraftItem("item-c", "免税品", "0.01", "0", "小票明确免税"),
        )),
    )

    @Test fun jsonRoundTripPreservesExactCentsTimestampAndOrder() {
        val source = listOf(sample())
        val encoded = BackupCodec.toJson(source)
        assertEquals(source, BackupCodec.fromJson(encoded))
        assertEquals(source, BackupCodec.fromJson("\uFEFF$encoded"))
        assertTrue(encoded.contains("\"version\": 1"))
        assertFalse(encoded.contains("apiKey"))
        assertFalse(encoded.contains("image"))
    }

    @Test fun jsonRejectsUnsupportedVersionsAndUnknownFields() {
        val encoded = BackupCodec.toJson(listOf(sample()))
        val error = assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.fromJson(encoded.replace("\"version\": 1", "\"version\": 9"))
        }
        assertTrue(error.message!!.contains("版本 9"))
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.fromJson(encoded.replaceFirst("{", "{\"apiKey\":\"must-not-import\","))
        }
    }

    @Test fun jsonRejectsAlteredDerivedTotalsAndTaxSplit() {
        val encoded = BackupCodec.toJson(listOf(sample()))
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.fromJson(encoded.replace("\"totalTaxCents\": 2200", "\"totalTaxCents\": 2201"))
        }
        // Keep row conservation intact; only a real recalculation catches this corruption.
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.fromJson(encoded.replace("\"preTaxCents\": 10000", "\"preTaxCents\": 9999")
                .replace("\"taxCents\": 900", "\"taxCents\": 901")
                .replace("\"taxCents\": 1300", "\"taxCents\": 1301"))
        }
    }

    @Test fun jsonRejectsOversizeAndAdversarialNesting() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.fromJson(" ".repeat(5 * 1024 * 1024 + 1)) }
        // Character count alone is insufficient for UTF-8 input.
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.fromJson("汉".repeat(1_666_667)) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.fromJson("[".repeat(33) + "]".repeat(33)) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.fromJson("{\"version\": 1}") }
    }

    @Test fun jsonRejectsReceiptAndItemCountLimits() {
        val emptyRow = """{"id":"x","storeName":"店","timestamp":1,"totalAmountCents":0,"totalPreTaxCents":0,"totalTaxCents":0,"items":[]}"""
        val tooManyReceipts = """{"format":"taxray-ledger","version":1,"receipts":[${List(10_001) { emptyRow }.joinToString(",")}]}"""
        val receiptError = assertThrows(IllegalArgumentException::class.java) { BackupCodec.fromJson(tooManyReceipts) }
        assertTrue(receiptError.message!!.contains("10000"))
        val itemRows = List(1_001) { index ->
            """{"id":"$index","name":"物品","amountCents":113,"taxRateBps":1300,"preTaxCents":100,"taxCents":13,"categoryReason":""}"""
        }.joinToString(",")
        val tooManyItems = """{"format":"taxray-ledger","version":1,"receipts":[${emptyRow.replace("\"items\":[]", "\"items\":[$itemRows]")}]}"""
        val itemError = assertThrows(IllegalArgumentException::class.java) { BackupCodec.fromJson(tooManyItems) }
        assertTrue(itemError.message!!.contains("1000"))
    }

    @Test fun jsonRejectsConflictingIdsInsideBackup() {
        val first = sample()
        val changed = first.copy(storeName = "另一家商户")
        val conflictingInput = BackupCodec.toJson(listOf(first, changed.copy(id = "second-id")))
            .replace("\"id\": \"second-id\"", "\"id\": \"${first.id}\"")
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.fromJson(conflictingInput)
        }
        val duplicatedInput = BackupCodec.toJson(listOf(first, first.copy(id = "second-id")))
            .replace("\"id\": \"second-id\"", "\"id\": \"${first.id}\"")
        assertEquals(listOf(first, first), BackupCodec.fromJson(duplicatedInput))
    }

    @Test fun csvHandlesQuotesCommasNewlinesAndSpreadsheetFormulasLosslessly() {
        val receipt = sample().copy(
            storeName = "=HYPERLINK(\"https://invalid.example\",\"店\"),\r\n二楼",
            items = TaxCalculator.calculateItems(listOf(
                DraftItem("item-1", "'单引号开头", "0.05", "100", "@SUM(A1:A2)"),
                DraftItem("item-2", "+商品,\"双引号\"\n第二行", "0.01", "13", "备注\r\n换行"),
            )),
        )
        val encoded = BackupCodec.toCsv(listOf(receipt))
        assertTrue(encoded.startsWith("\uFEFFversion,"))
        assertTrue(encoded.contains("\"'=HYPERLINK"))
        assertTrue(encoded.contains("\"''单引号开头\""))
        assertTrue(encoded.contains("\"'@SUM(A1:A2)\""))
        assertEquals(listOf(receipt), BackupCodec.fromCsv(encoded))
        assertEquals(listOf(receipt), BackupCodec.fromCsv(encoded.removePrefix("\uFEFF")))
    }

    @Test fun csvRejectsMalformedOrTamperedRows() {
        val encoded = BackupCodec.toCsv(listOf(sample()))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.fromCsv(encoded + "\"unclosed") }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.fromCsv(encoded.replace("amount_cents", "amount")) }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.fromCsv(encoded.replace("\"2200\"", "\"2201\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.fromCsv(encoded.replace("\"item-a\",\"1\"", "\"item-a\",\"0\""))
        }
    }

    @Test fun emptyLedgerCanBeBackedUpAndRestored() {
        assertEquals(emptyList<Receipt>(), BackupCodec.fromJson(BackupCodec.toJson(emptyList())))
        assertEquals(emptyList<Receipt>(), BackupCodec.fromCsv(BackupCodec.toCsv(emptyList())))
    }

    @Test fun exportsRejectCountsThatImportCannotRestore() {
        val tooMany = List(10_001) { index -> sample("receipt-$index") }
        val jsonError = assertThrows(IllegalArgumentException::class.java) { BackupCodec.toJson(tooMany) }
        val csvError = assertThrows(IllegalArgumentException::class.java) { BackupCodec.toCsv(tooMany) }
        assertTrue(jsonError.message!!.contains("10000"))
        assertTrue(csvError.message!!.contains("10000"))
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.toCsv(listOf(sample(), sample())) }
    }

    @Test fun exportsRejectActualUtf8SizeAboveTheImportLimit() {
        val verboseItems = TaxCalculator.calculateItems(List(1_000) { index ->
            DraftItem("item-$index", "商品".repeat(100), "1", "13", "说明".repeat(250))
        })
        // Each individual row is valid; repeated multibyte text exceeds 5 MB in both formats.
        val largeLedger = List(3) { index -> Receipt("large-$index", "店", 1L, verboseItems) }
        val jsonError = assertThrows(IllegalArgumentException::class.java) { BackupCodec.toJson(largeLedger) }
        val csvError = assertThrows(IllegalArgumentException::class.java) { BackupCodec.toCsv(largeLedger) }
        assertTrue(jsonError.message!!.contains("5 MB"))
        assertTrue(csvError.message!!.contains("5 MB"))
    }
}
