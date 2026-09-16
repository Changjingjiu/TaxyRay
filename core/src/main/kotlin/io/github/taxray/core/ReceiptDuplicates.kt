package io.github.taxray.core

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** A review hint, never an automatic deletion or a uniqueness constraint. */
object ReceiptDuplicates {
    fun find(candidate: Receipt, existing: List<Receipt>, zone: ZoneId = ZoneId.systemDefault()): List<Receipt> {
        val merchant = normalize(candidate.storeName)
        val lines = signature(candidate)
        val hasNamedItems = candidate.items.any { normalize(it.name).let { name -> name.isNotEmpty() && name != "消费品目" } }
        val day = Instant.ofEpochMilli(candidate.timestamp).atZone(zone).toLocalDate()
        return existing.filter { saved ->
            if (saved.id == candidate.id || saved.totalAmountCents != candidate.totalAmountCents) return@filter false
            val savedMerchant = normalize(saved.storeName)
            val sameMerchant = merchant.isNotEmpty() && merchant == savedMerchant
            val sameDay = Instant.ofEpochMilli(saved.timestamp).atZone(zone).toLocalDate() == day
            // Same merchant/day/paid total catches minor OCR variations. Identical named
            // item multisets also catch a missing receipt date, without dropping repeat rows.
            (sameMerchant && sameDay) ||
                (hasNamedItems && lines == signature(saved) &&
                    (sameMerchant || (sameDay && (merchant.isEmpty() || savedMerchant.isEmpty()))))
        }.sortedByDescending { it.timestamp }
    }

    private fun signature(receipt: Receipt): Map<Pair<String, Long>, Int> = receipt.items
        .groupingBy { normalize(it.name) to it.breakdown.amountCents }.eachCount()

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filterNot { it.isWhitespace() || Character.isSpaceChar(it) }
}
