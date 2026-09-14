package io.github.taxray.ui.components

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import nl.dionsegijn.konfetti.core.models.Shape
import nl.dionsegijn.konfetti.core.models.Size

/**
 * One brief burst per generation event. Add last inside a Box, outside the captured card layer.
 * Keep eventId stable through recomposition/template changes. Removing and reopening the sheet
 * creates a new celebration; activity recreation preserves consumption and does not replay it.
 * This decorative Canvas has no pointer input, click handler, focus target or accessibility text.
 */
@Composable
fun CelebrationOverlay(eventId: Any, modifier: Modifier = Modifier) {
    val resolver = LocalContext.current.contentResolver
    var animationsEnabled by remember {
        mutableStateOf(ValueAnimator.areAnimatorsEnabled() &&
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f)
    }
    var consumed by rememberSaveable(eventId) { mutableStateOf(false) }
    var visible by remember(eventId) { mutableStateOf(false) }

    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                animationsEnabled = ValueAnimator.areAnimatorsEnabled() &&
                    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    // consumed is deliberately not a key: recording the event must not cancel its own timer.
    LaunchedEffect(eventId) {
        if (consumed) return@LaunchedEffect
        consumed = true
        if (!animationsEnabled) return@LaunchedEffect
        visible = true
        delay(2_600)
        visible = false // Konfetti's internal frame loop stops when its Canvas leaves composition.
    }
    LaunchedEffect(animationsEnabled) {
        if (!animationsEnabled) visible = false
    }

    if (visible && animationsEnabled) {
        val parties = remember(eventId) {
            listOf(Party(
                speed = 14f,
                maxSpeed = 28f,
                damping = .9f,
                spread = 360,
                colors = listOf(0x047857, 0x39A88E, 0x6CAFC7, 0xD2AD62),
                size = listOf(Size.SMALL, Size.MEDIUM),
                shapes = listOf(Shape.Square, Shape.Circle),
                timeToLive = 1_500,
                fadeOutEnabled = true,
                position = Position.Relative(.5, .2),
                emitter = Emitter(duration = 80, TimeUnit.MILLISECONDS).max(80),
            ))
        }
        // Konfetti 2.0.5 starts its coroutine with Unit; key ensures a genuinely new event restarts it.
        key(eventId) {
            KonfettiView(modifier = modifier.fillMaxSize().clearAndSetSemantics {
                testTag = "celebrationOverlay"
            }, parties = parties)
        }
    }
}
