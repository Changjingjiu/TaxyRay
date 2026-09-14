package io.github.taxray.ui

import android.animation.ValueAnimator
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.taxray.ui.components.CelebrationOverlay
import java.io.FileInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory Compose scene. No ledger, real activity state or production preferences are touched. */
@RunWith(AndroidJUnit4::class)
class CelebrationOverlayTest {
    @get:Rule val compose = createComposeRule()
    private var originalScale: String? = null

    @Before fun enableAndRememberSystemAnimations() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        originalScale = Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        setAnimationScale("1")
        compose.waitUntil(5_000) { ValueAnimator.areAnimatorsEnabled() }
        compose.mainClock.autoAdvance = false
    }

    @After fun restoreSystemAnimations() {
        setAnimationScale(originalScale)
    }

    @Test fun tapsPassThroughWhileCanvasExistsAndSameEventDoesNotReplay() {
        var clicks by mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme(colorScheme = if (clicks % 2 == 0) lightColorScheme() else darkColorScheme()) {
                Box(Modifier.size(240.dp)) {
                    Button(
                        onClick = { clicks++ },
                        modifier = Modifier.align(Alignment.Center).testTag("underlyingAction"),
                    ) { Text("切换模板 $clicks") }
                    CelebrationOverlay("one-generation", Modifier.matchParentSize())
                }
            }
        }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("celebrationOverlay").assertExists()
        // Real pointer dispatch, not performClick's semantics action, while the overlay is alive.
        compose.onNodeWithTag("underlyingAction").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("celebrationOverlay").assertExists()

        compose.mainClock.advanceTimeBy(2_700)
        compose.onNodeWithTag("celebrationOverlay").assertDoesNotExist()
        // Changing the surrounding UI with the same generation ID must not create a new burst.
        compose.onNodeWithTag("underlyingAction").performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, clicks) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("celebrationOverlay").assertDoesNotExist()
    }

    private fun setAnimationScale(value: String?) {
        val command = if (value == null) "settings delete global animator_duration_scale"
            else "settings put global animator_duration_scale ${value.toFloat()}"
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
