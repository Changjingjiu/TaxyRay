package io.github.taxray

import io.github.taxray.core.CalculatedItem
import io.github.taxray.core.DiscountAllocation
import io.github.taxray.core.DiscountAllocator
import io.github.taxray.core.DraftItem
import io.github.taxray.core.TaxCalculator
import io.github.taxray.data.remote.ReceiptDiscount

/** Review-only snapshot. Saved ledger rows contain the final paid amounts. */
data class AppliedDiscount(val itemIds: List<String>, val amounts: DiscountAllocation)

data class ReceiptDraft(
    val id: String? = null,
    val storeName: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val items: List<DraftItem> = listOf(DraftItem()),
    val declaredTotal: String = "",
    val fromVision: Boolean = false,
    val warnings: List<String> = emptyList(),
    val receiptDiscount: ReceiptDiscount? = null,
    val appliedDiscount: AppliedDiscount? = null,
    val requireDeclaredTotal: Boolean = false,
) {
    fun allocateDiscount(): ReceiptDraft {
        val original = TaxCalculator.calculateItems(items).map { it.breakdown.amountCents }
        val paid = TaxCalculator.parseReceiptTotal(declaredTotal)
        require(paid < original.sum()) { "实付应低于商品合计 才需要分摊优惠" }
        return copy(appliedDiscount = AppliedDiscount(items.map { it.id }, DiscountAllocator.allocate(original, paid)))
    }

    fun allocationIsCurrent(): Boolean = appliedDiscount?.let { applied ->
        runCatching {
            applied.itemIds == items.map { it.id } &&
                applied.amounts.originalCents == TaxCalculator.calculateItems(items).map { it.breakdown.amountCents } &&
                applied.amounts.paidTotalCents == TaxCalculator.parseReceiptTotal(declaredTotal)
        }.getOrDefault(false)
    } ?: false

    /** Recompute from the original inputs, so repeated taps never compound a discount. */
    fun settledItems(): List<DraftItem> {
        val applied = appliedDiscount ?: return items
        require(allocationIsCurrent()) { "金额已修改 请重新分摊优惠" }
        return items.mapIndexed { index, item -> item.copy(amount = TaxCalculator.formatMoney(applied.amounts.paidCents[index])) }
    }

    fun calculated(): List<CalculatedItem> = TaxCalculator.calculateItems(settledItems())

    fun validationMessage(): String? = runCatching {
        require(storeName.length <= 120) { "商户名称不能超过 120 字" }
        require(!requireDeclaredTotal || declaredTotal.isNotBlank()) { "照片中的金额有冲突 请填写最终实付后入账" }
        val total = calculated().sumOf { it.breakdown.amountCents }
        require(receiptDiscount?.amountCents?.let { it > 0 } != true || declaredTotal.isNotBlank()) {
            "识别到整单优惠 请先填写最终实付"
        }
        if (declaredTotal.isNotBlank()) {
            val expected = TaxCalculator.parseReceiptTotal(declaredTotal)
            require(total == expected) {
                if (total > expected) "还有 ¥${TaxCalculator.formatMoney(total - expected)} 差额 如为优惠或抹零 可按实付分摊"
                else "实付高于商品合计 ¥${TaxCalculator.formatMoney(expected - total)} 请检查漏项或附加费用"
            }
        }
    }.exceptionOrNull()?.message
}
