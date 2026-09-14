package io.github.taxray.ui.components

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** One observer per sheet; a system setting change cancels outstanding stagger delays. */
@Composable
fun rememberReceiptMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun readEnabled(): Boolean = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var enabled by remember(resolver) { mutableStateOf(readEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { enabled = readEnabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}

/** A draw-only top-to-bottom reveal: dimensions and lazy-list measurement stay stable. */
@Composable
fun Modifier.receiptUnfold(motionEnabled: Boolean): Modifier {
    val progress = remember { Animatable(if (motionEnabled) 0f else 1f) }
    LaunchedEffect(motionEnabled) {
        if (!motionEnabled) progress.snapTo(1f)
        else if (progress.value < 1f) progress.animateTo(1f, tween(380, easing = CubicBezierEasing(.05f, .7f, .1f, 1f)))
    }
    return drawWithContent {
        clipRect(bottom = size.height * progress.value) { this@drawWithContent.drawContent() }
    }
}

/** Only the first 12 item positions can stagger, so a 1000-item receipt never queues 25s. */
@Composable
fun Modifier.receiptRowEntrance(index: Int, motionEnabled: Boolean): Modifier {
    if (index !in 0 until 12) return this
    val progress = remember { Animatable(if (motionEnabled) 0f else 1f) }
    LaunchedEffect(motionEnabled) {
        if (!motionEnabled) progress.snapTo(1f)
        else if (progress.value < 1f) {
            delay(index * 25L)
            progress.animateTo(1f, tween(250, easing = CubicBezierEasing(.16f, 1f, .3f, 1f)))
        }
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 4.dp.toPx()
    }
}
