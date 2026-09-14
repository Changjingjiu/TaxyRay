package io.github.taxray.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.taxray.core.TaxCalculator
import io.github.taxray.ui.theme.AmountStyle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun money(cents: Long): String = "¥${TaxCalculator.formatMoney(cents)}"
fun dateText(timestamp: Long, detail: Boolean = false): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(if (detail) "yyyy.MM.dd HH:mm" else "MM月dd日 · HH:mm"))

@Composable
fun DashedDivider(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(color, Offset.Zero, Offset(size.width, 0f), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun RollingAmount(cents: Long, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "累计增值税估算，${money(cents)}" }) {
        val value = TaxCalculator.formatMoney(cents)
        val fontScale = LocalDensity.current.fontScale
        val size = minOf(36f, (maxWidth.value - 28f) / (value.length * .62f * fontScale)).coerceAtLeast(12f).sp
        // AnimatedContent obeys Android's global animator duration scale, including zero.
        Row(verticalAlignment = Alignment.CenterVertically) {
        Text("¥ ", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        value.forEachIndexed { index, digit ->
            if (digit == '.') Text(".", style = AmountStyle.copy(fontSize = size), color = MaterialTheme.colorScheme.primary)
            else AnimatedContent(targetState = digit, transitionSpec = {
                (slideInVertically(tween(600, easing = CubicBezierEasing(.05f, .7f, .1f, 1f))) { it } + fadeIn(tween(200))) togetherWith
                    (slideOutVertically(tween(600, easing = CubicBezierEasing(.05f, .7f, .1f, 1f))) { -it } + fadeOut(tween(200)))
            }, label = "金额位$index") { Text(it.toString(), style = AmountStyle.copy(fontSize = size), color = MaterialTheme.colorScheme.primary) }
        }
        }
    }
}

@Composable
fun AmountRow(label: String, value: String, emphasis: Boolean = false) {
    if (value.length > 20) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, modifier = Modifier.align(Alignment.End), style = AmountStyle.copy(fontSize = if (emphasis) 19.sp else 14.sp), color = if (emphasis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = AmountStyle.copy(fontSize = if (emphasis) 19.sp else 14.sp), color = if (emphasis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun InformationNote(text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp).padding(top = 1.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
