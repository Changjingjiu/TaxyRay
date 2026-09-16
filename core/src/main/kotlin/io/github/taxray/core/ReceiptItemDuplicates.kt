package io.github.taxray.core

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A review suggestion containing the original draft rows, never an instruction to delete them. */
data class DuplicateItemGroup(val items: List<DraftItem>, val exact: Boolean)

/** Finds possible overlapping receipt rows without erasing legitimate repeat purchases. */
object ReceiptItemDuplicates {
    private const val MAX_NAME_LENGTH = 200
    private val unnamed = setOf("消费品目", "消费项目", "商品", "未知", "未知商品", "未识别商品", "未命名", "未命名商品")
    private val specifications = Regex(
        "\\p{N}+(?:[.,]\\p{N}+)?(?:[a-z]+|千克|公斤|毫升|厘米|毫米|克|斤|升|米|瓶|袋|盒|包|片|件|支|卷|粒)?",
    )

    fun find(items: List<DraftItem>): List<DuplicateItemGroup> {
        require(items.size <= TaxCalculator.MAX_ITEMS) { "每张账单最多支持 1000 项" }
        val candidates = items.mapIndexedNotNull { index, item ->
            // Use the same input bounds as receipt recognition; do not truncate a name into a match.
            if (item.name.length > MAX_NAME_LENGTH) return@mapIndexedNotNull null
            val name = Normalizer.normalize(item.name, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT).filterNot { it.isWhitespace() || Character.isSpaceChar(it) }
            if (name.length > MAX_NAME_LENGTH || name in unnamed || name.none { it.isLetter() }) return@mapIndexedNotNull null
            // Duplicate hints concern the paid amount, independent of an editable tax-rate draft.
            val cents = runCatching { TaxCalculator.calculate(item.amount, "0").amountCents }.getOrNull()
                ?: return@mapIndexedNotNull null
            Candidate(index, item, name, cents, specifications.findAll(name).map { it.value }.toList())
        }

        // Keep exact copies together first, so fuzzy grouping cannot split identical rows.
        val exactGroups = candidates.groupBy { it.cents to it.name }.values
        val groupsByAmount = linkedMapOf<Long, MutableList<MutableList<List<Candidate>>>>()
        for (exactGroup in exactGroups) {
            val candidate = exactGroup.first()
            val groups = groupsByAmount.getOrPut(candidate.cents) { mutableListOf() }
            // Complete-link matching prevents a chain of small OCR changes from joining unrelated rows.
            val target = groups.firstOrNull { group ->
                group.all { sameProductHint(it.first(), candidate) }
            }
            if (target == null) groups += mutableListOf(exactGroup) else target += exactGroup
        }
        return groupsByAmount.values.flatten()
            .map { it.flatten().sortedBy(Candidate::index) }
            .filter { it.size > 1 }
            .sortedBy { it.first().index }
            .map { group -> DuplicateItemGroup(group.map(Candidate::item), group.all { it.name == group.first().name }) }
    }

    private fun sameProductHint(first: Candidate, second: Candidate): Boolean {
        if (first.specifications != second.specifications) return false
        if (first.name == second.name) return true
        val length = max(first.name.length, second.name.length)
        // Integer-only threshold: at least 85% agreement, capped at two character edits.
        val maxEdits = min(2, length * 15 / 100)
        if (maxEdits == 0 || min(first.name.length, second.name.length) < 6) return false
        return withinEditDistance(first.name, second.name, maxEdits)
    }

    private fun withinEditDistance(first: String, second: String, limit: Int): Boolean {
        if (abs(first.length - second.length) > limit) return false
        // OCR variations usually share most of the name; trimming also bounds the DP work.
        var start = 0
        while (start < min(first.length, second.length) && first[start] == second[start]) start++
        var firstEnd = first.length
        var secondEnd = second.length
        while (firstEnd > start && secondEnd > start && first[firstEnd - 1] == second[secondEnd - 1]) {
            firstEnd--
            secondEnd--
        }
        val firstLength = firstEnd - start
        val secondLength = secondEnd - start
        if (min(firstLength, secondLength) == 0) return max(firstLength, secondLength) <= limit

        val outside = limit + 1
        var previous = IntArray(secondLength + 1) { if (it <= limit) it else outside }
        var current = IntArray(secondLength + 1)
        for (row in 1..firstLength) {
            current.fill(outside)
            current[0] = if (row <= limit) row else outside
            var rowMinimum = current[0]
            for (column in max(1, row - limit)..min(secondLength, row + limit)) {
                val replace = previous[column - 1] + if (first[start + row - 1] == second[start + column - 1]) 0 else 1
                current[column] = min(replace, min(previous[column] + 1, current[column - 1] + 1))
                rowMinimum = min(rowMinimum, current[column])
            }
            if (rowMinimum > limit) return false
            val swap = previous
            previous = current
            current = swap
        }
        return previous[secondLength] <= limit
    }

    private data class Candidate(
        val index: Int,
        val item: DraftItem,
        val name: String,
        val cents: Long,
        val specifications: List<String>,
    )
}
