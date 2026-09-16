package io.github.taxray.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.taxray.core.ContributionSummary
import io.github.taxray.core.Receipt
import io.github.taxray.core.TaxCalculator
import io.github.taxray.ui.theme.AmountStyle

enum class CardTemplate { PAPER, FOREST }

private val PaperColors = lightColorScheme(
    surface = Color(0xFFFAF9F5), onSurface = Color(0xFF172D27),
    onSurfaceVariant = Color(0xFF53645B), primary = Color(0xFF047857),
    outlineVariant = Color(0xFFCED9D1),
)
private val ForestColors = darkColorScheme(
    surface = Color(0xFF103E30), onSurface = Color(0xFFF7F6ED),
    onSurfaceVariant = Color(0xFFBBCFC2), primary = Color(0xFFA9E5BD),
    outlineVariant = Color(0xFF4A7160),
)
private data class CompositionGroup(val label: String, val taxCents: Long)

/** Static, opaque rendering: safe to capture without waiting for any animation. */
@Composable
fun TaxContributionCard(
    receipt: Receipt,
    modifier: Modifier = Modifier,
    template: CardTemplate = CardTemplate.PAPER,
) {
    val summary = remember(receipt) { ContributionSummary.fromReceipts(listOf(receipt)) }
    ContributionCard(
        summary = summary,
        subject = receipt.storeName.ifBlank { "日常消费" },
        period = "${dateText(receipt.timestamp, detail = true)} · ${receipt.items.size} 项",
        cumulative = false,
        modifier = modifier,
        template = template,
    )
}

/** Shares the complete saved ledger, independent of the ledger screen's search or date filters. */
@Composable
fun CumulativeTaxContributionCard(
    receipts: List<Receipt>,
    modifier: Modifier = Modifier,
    template: CardTemplate = CardTemplate.PAPER,
) {
    val summary = remember(receipts) { ContributionSummary.fromReceipts(receipts) }
    val firstDay = summary.firstTimestamp?.let { dateText(it, detail = true).substringBefore(' ') }
    val lastDay = summary.lastTimestamp?.let { dateText(it, detail = true).substringBefore(' ') }
    val period = when {
        firstDay == null -> "暂无账单"
        firstDay == lastDay -> "$firstDay · ${summary.itemCount} 项"
        else -> "$firstDay — $lastDay\n${summary.itemCount} 项消费"
    }
    ContributionCard(summary, "全部 ${summary.receiptCount} 笔账单", period, true, modifier, template)
}

@Composable
private fun ContributionCard(
    summary: ContributionSummary,
    subject: String,
    period: String,
    cumulative: Boolean,
    modifier: Modifier,
    template: CardTemplate,
) {
    MaterialTheme(colorScheme = if (template == CardTemplate.PAPER) PaperColors else ForestColors) {
        val colors = MaterialTheme.colorScheme
        val groups = remember(summary) {
            val all = summary.rateGroups
            if (all.size <= 5) all.map { CompositionGroup("${TaxCalculator.formatRate(it.rateBps)}%", it.taxCents) }
            else all.take(4).map { CompositionGroup("${TaxCalculator.formatRate(it.rateBps)}%", it.taxCents) } +
                CompositionGroup("其他 ${all.size - 4} 档", all.drop(4).fold(0L) { sum, group -> Math.addExact(sum, group.taxCents) })
        }
        val palette = if (template == CardTemplate.PAPER) listOf(
            Color(0xFF08785A), Color(0xFF438AAC), Color(0xFF8473A7), Color(0xFFB57B3A), Color(0xFF84918B),
        ) else listOf(
            Color(0xFFA9E5BD), Color(0xFF95C7E5), Color(0xFFD1B9E8), Color(0xFFEACA8C), Color(0xFF8EAEA0),
        )
        Column(
            modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Text("纳税人公共贡献记录", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.height(18.dp))
            DashedDivider()
            Spacer(Modifier.height(16.dp))
            Text(subject, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(period, fontSize = 11.sp, lineHeight = 17.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Text(if (cumulative) "累计增值税估算" else "本次增值税估算", fontSize = 12.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            val taxValue = money(summary.totalTaxCents)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val size = minOf(35f, maxWidth.value / (taxValue.length * .62f * LocalDensity.current.fontScale)).coerceAtLeast(12f).sp
                Text(taxValue, style = AmountStyle.copy(fontSize = size), color = colors.primary, maxLines = 1)
            }
            Spacer(Modifier.height(16.dp))
            AmountRow(if (cumulative) "累计消费实付" else "消费实付", money(summary.totalAmountCents))
            Spacer(Modifier.height(6.dp))
            AmountRow("估算税前金额", money(summary.totalPreTaxCents))
            Spacer(Modifier.height(6.dp))
            AmountRow("估算税额 / 实付", "${summary.effectiveRatePercent}%")
            Spacer(Modifier.height(18.dp))
            DashedDivider()
            Spacer(Modifier.height(14.dp))
            Text("估算税额构成", fontSize = 12.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            val totalTax = summary.totalTaxCents
            Canvas(Modifier.fillMaxWidth().height(7.dp).semantics {
                contentDescription = if (totalTax == 0L) "估算税额为零" else groups.joinToString { "${it.label} ${money(it.taxCents)}" }
            }) {
                drawRect(colors.outlineVariant)
                // Floating-point ratios are used for pixels only, never stored amounts.
                if (totalTax > 0) {
                    var preceding = 0L
                    groups.forEachIndexed { index, group ->
                        val start = (preceding.toDouble() / totalTax * size.width).toFloat()
                        preceding += group.taxCents
                        val end = (preceding.toDouble() / totalTax * size.width).toFloat()
                        if (end > start) drawRect(palette[index], Offset(start, 0f), Size(end - start, size.height))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            groups.forEachIndexed { index, group ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(palette[index], CircleShape))
                    Spacer(Modifier.width(7.dp))
                    Text(group.label, Modifier.weight(1f), fontSize = 11.sp, color = colors.onSurfaceVariant)
                    Text(money(group.taxCents), style = AmountStyle.copy(fontSize = 11.sp), color = colors.onSurface)
                }
            }
            if (totalTax == 0L) Text("估算税额为零 无税额占比分布", Modifier.padding(top = 3.dp), fontSize = 10.sp, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            DashedDivider()
            Spacer(Modifier.height(12.dp))
            Text("每一笔理性消费都在支撑社会前行", fontSize = 12.sp, color = colors.onSurface)
            Spacer(Modifier.height(5.dp))
            Text("依据录入金额与所选税率计算 仅供个人参考\n非发票 非完税证明 不代表商户实际缴税",
                fontSize = 10.sp, lineHeight = 15.sp, color = colors.onSurfaceVariant)
        }
    }
}
