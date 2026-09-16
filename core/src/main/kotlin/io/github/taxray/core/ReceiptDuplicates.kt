package io.github.taxray.core

import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** A review hint, never an automatic deletion or a uniqueness constraint. */
object ReceiptDuplicates {
    private val specifications = Regex("\\p{N}+(?:[.,]\\p{N}+)?(?:[a-z]+|千克|公斤|毫升|厘米|毫米|克|斤|升|米|瓶|袋|盒|包|片|件|支|卷|粒|个|套|张)?")
    private val singlePack = Regex("1(?:瓶|袋|盒|包|片|件|支|卷|粒|个|套|张)")

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
                (hasNamedItems && (sameMerchant || (sameDay && (merchant.isEmpty() || savedMerchant.isEmpty()))) &&
                    (lines == signature(saved) || (sameDay && singleItemOcrMatch(candidate, saved))))
        }.sortedByDescending { it.timestamp }
    }

    private fun singleItemOcrMatch(first: Receipt, second: Receipt): Boolean {
        val a = first.items.singleOrNull() ?: return false
        val b = second.items.singleOrNull() ?: return false
        val amount = TaxCalculator.formatMoney(first.totalAmountCents)
        if (ReceiptItemDuplicates.find(listOf(DraftItem(name = a.name, amount = amount),
                DraftItem(name = b.name, amount = amount))).isNotEmpty()) return true

        // Shopping screenshots sometimes repeat a specification below the title. A long
        // title can still be a review candidate despite that suffix and a small OCR typo.
        val left = normalize(a.name)
        val right = normalize(b.name)
        if (left.length !in 16..200 || right.length !in 16..200) return false
        fun specs(name: String) = specifications.findAll(name).map { it.value }
            .filterNot { singlePack.matches(it) }.toSet()
        if (specs(left) != specs(right)) return false
        val leftPairs = left.windowed(2).toSet()
        val rightPairs = right.windowed(2).toSet()
        if (minOf(leftPairs.size, rightPairs.size) < 12) return false
        // Integer Sørensen–Dice score >= 85%. This never deletes or merges a receipt.
        return leftPairs.intersect(rightPairs).size * 200 >= (leftPairs.size + rightPairs.size) * 85
    }

    private fun signature(receipt: Receipt): Map<Pair<String, Long>, Int> = receipt.items
        .groupingBy { normalize(it.name) to it.breakdown.amountCents }.eachCount()

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filterNot { it.isWhitespace() || Character.isSpaceChar(it) }
}
