package io.github.taxray.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val PresetTaxRates = listOf("13", "9", "6", "0")

/** The selected custom mode is explicit state and never inferred from a partial number. */
@Composable
fun TaxRateSelector(
    ratePercent: String,
    customSelected: Boolean,
    itemIndex: Int,
    enabled: Boolean,
    motionEnabled: Boolean,
    onPreset: (String) -> Unit,
    onCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < .5f
    val labels = PresetTaxRates + "自定义"
    val customIndex = PresetTaxRates.size
    val containers = if (dark) listOf(Color(0xFF172554), Color(0xFF064E3B), scheme.secondaryContainer, scheme.surfaceVariant, Color(0xFF3B0764))
        else listOf(Color(0xFFEFF6FF), Color(0xFFECFDF5), scheme.secondaryContainer, scheme.surfaceVariant, Color(0xFFFAF5FF))
    val foregrounds = if (dark) listOf(Color(0xFF93C5FD), Color(0xFF6EE7B7), scheme.onSecondaryContainer, scheme.onSurfaceVariant, Color(0xFFD8B4FE))
        else listOf(Color(0xFF1D4ED8), Color(0xFF047857), scheme.onSecondaryContainer, scheme.onSurfaceVariant, Color(0xFF7E22CE))
    val selectedIndex = if (customSelected) customIndex else PresetTaxRates.indexOf(ratePercent).takeIf { it >= 0 } ?: customIndex
    val easing = CubicBezierEasing(.16f, 1f, .3f, 1f)
    val selectedPosition by animateFloatAsState(selectedIndex.toFloat(), if (motionEnabled) tween(250, easing = easing) else snap(), label = "税率高亮位置")
    val selectedColor by animateColorAsState(containers[selectedIndex], if (motionEnabled) tween(250, easing = easing) else snap(), label = "税率高亮颜色")
    val shape = RoundedCornerShape(10.dp)
    val controlHeight = maxOf(48.dp, 34.dp * LocalDensity.current.fontScale)
    BoxWithConstraints(modifier.fillMaxWidth().height(controlHeight).clip(shape)
        .background(scheme.surface).border(1.dp, scheme.outlineVariant, shape)) {
        val segmentWidth = maxOf(maxWidth / labels.size, 48.dp)
        val scrollState = rememberScrollState()
        // Very narrow split-screen windows scroll instead of shrinking touch targets.
        Box(Modifier.fillMaxSize().then(
            if (maxWidth < 48.dp * labels.size) Modifier.horizontalScroll(scrollState) else Modifier
        )) {
            Box(Modifier.width(segmentWidth * labels.size).fillMaxHeight()) {
                Box(Modifier.offset(x = segmentWidth * selectedPosition).width(segmentWidth).fillMaxHeight()
                    .padding(3.dp).background(selectedColor, RoundedCornerShape(7.dp)))
                Row(Modifier.fillMaxSize().selectableGroup()) {
                    labels.forEachIndexed { index, label ->
                        Box(Modifier.weight(1f).fillMaxHeight()
                            .testTag(if (index == customIndex) "rateCustom_$itemIndex" else "rate${label}_$itemIndex")
                            .selectable(selected = selectedIndex == index, enabled = enabled, role = Role.RadioButton,
                                onClick = {
                                    // Re-selecting custom must not erase an already entered rate.
                                    if (index != selectedIndex) { if (index == customIndex) onCustom() else onPreset(label) }
                                }),
                            contentAlignment = Alignment.Center) {
                            Text(if (index == customIndex) label else "$label%", fontSize = 13.sp, lineHeight = 16.sp,
                                fontWeight = if (selectedIndex == index) FontWeight.SemiBold else FontWeight.Normal,
                                color = foregrounds[index].copy(alpha = if (enabled) 1f else .45f), maxLines = 2, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}
