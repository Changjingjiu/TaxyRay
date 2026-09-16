package io.github.taxray

import io.github.taxray.core.DraftItem
import io.github.taxray.core.TaxCalculator
import io.github.taxray.data.remote.PaymentStatus
import io.github.taxray.data.remote.VisionReceipt
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/** Explicit supplementary scans of one bill. A batch keeps separate bills in separate sessions. */
class ReceiptScanSession(
    rounds: List<VisionReceipt> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
) {
    // Copy collection inputs so another owner cannot change an earlier round while a later one is reviewed.
    val rounds: List<VisionReceipt> = rounds.map {
        it.copy(items = it.items.toList(), warnings = it.warnings.toList(), sourceImageIndices = it.sourceImageIndices.toList())
    }
    val items: List<DraftItem> = this.rounds.flatMap { it.items }

    init {
        require(items.size <= TaxCalculator.MAX_ITEMS) { "每张账单最多支持 1000 项 请先完成当前账单" }
        require(this.rounds.all { it.items.isNotEmpty() }) { "未识别到商品 请重新拍摄" }
        require(this.rounds.map { normalizeStore(it.storeName) }.filter { it.isNotEmpty() }.distinct().size <= 1) {
            "本次识别的商户与当前账单不同 请先完成当前账单 再另开一笔"
        }
        require(this.rounds.mapNotNull { it.receiptDateTime?.let(::receiptMinute) }.distinct().size <= 1) {
            "本次识别的消费时间与当前账单不同 请先完成当前账单 再另开一笔"
        }
    }

    /** A failed append leaves this session intact. Item IDs never act as duplicate-purchase evidence. */
    fun append(result: VisionReceipt): ReceiptScanSession = ReceiptScanSession(
        rounds = rounds + result.copy(items = result.items.map { it.copy(id = UUID.randomUUID().toString()) }),
        startedAt = startedAt,
    )

    /** [selectedItems] is the explicit keep/remove result of duplicate review, in the user's chosen order. */
    fun draft(selectedItems: List<DraftItem> = items): ReceiptDraft {
        require(rounds.isNotEmpty()) { "请先识别一张账单" }
        require(selectedItems.isNotEmpty()) { "请至少保留一个商品" }
        require(selectedItems.size <= TaxCalculator.MAX_ITEMS) { "每张账单最多支持 1000 项" }
        val originalItems = items.associateBy { it.id }
        require(selectedItems.map { it.id }.distinct().size == selectedItems.size &&
            selectedItems.all { originalItems[it.id] == it }) { "待录入商品已变化 请重新确认" }

        val totals = rounds.mapNotNull { it.declaredTotal?.takeIf(String::isNotBlank) }
            .map(TaxCalculator::parseReceiptTotal).distinct()
        val discounts = rounds.mapNotNull { it.discount }.distinctBy { it.amountCents }
        val totalConflict = totals.size > 1
        val discountConflict = discounts.size > 1
        val paymentStatuses = rounds.map { it.paymentStatus }.distinct()
        val paymentStatus = paymentStatuses.singleOrNull() ?: PaymentStatus.UNKNOWN
        val paymentEvidence = rounds.mapNotNull { it.paymentEvidence?.trim()?.takeIf(String::isNotBlank) }
            .distinct().joinToString("；").take(600).takeIf(String::isNotBlank)
        val receiptDate = rounds.firstNotNullOfOrNull { it.receiptDateTime }
        val warnings = buildList {
            rounds.forEachIndexed { index, round ->
                round.warnings.filterNot { it == "账单时间无效 可在入账前修改消费时间" }.forEach { warning ->
                    add("第 ${index + 1} 次识别 $warning")
                }
            }
            if (receiptDate == null) add("未识别到账单时间 已使用本次识别时间 可修改")
            if (totalConflict) add("多次识别的实付不一致 请填写账单最终实付")
            if (discountConflict) add("多次识别的整单优惠不一致 请填写账单最终实付后确认优惠")
            if (paymentStatuses.size > 1) add("多次识别的付款状态不一致 请确认该账单已付款再入账")
        }
        return ReceiptDraft(
            storeName = rounds.firstOrNull { it.storeName.isNotBlank() }?.storeName?.trim().orEmpty(),
            timestamp = receiptDate?.let { LocalDateTime.parse(it).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
                ?: startedAt,
            items = selectedItems.toList(),
            declaredTotal = totals.singleOrNull()?.let(TaxCalculator::formatMoney).orEmpty(),
            fromVision = true,
            warnings = warnings,
            receiptDiscount = discounts.singleOrNull(),
            requireDeclaredTotal = totalConflict || discountConflict,
            paymentStatus = paymentStatus,
            paymentEvidence = paymentEvidence,
        )
    }

    private fun normalizeStore(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filterNot { it.isWhitespace() || Character.isSpaceChar(it) }

    private fun receiptMinute(value: String): LocalDateTime = try {
        LocalDateTime.parse(value).truncatedTo(ChronoUnit.MINUTES)
    } catch (_: Exception) {
        throw IllegalArgumentException("账单时间无效 请重新识别")
    }
}
