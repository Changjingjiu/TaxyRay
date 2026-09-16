package io.github.taxray.ui

import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.taxray.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real activity window without saving credentials or changing the ledger. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun settingsAreRecordableUnlessANonemptyKeyIsRevealed() {
        compose.onNodeWithText("设置").performClick()
        assertScreenProtected(false)
        enterKey("screen-capture-fixture")
        assertScreenProtected(false)

        compose.onNodeWithContentDescription("显示密钥").performClick()
        assertScreenProtected(true)
        compose.onNodeWithContentDescription("隐藏密钥").performClick()
        assertScreenProtected(false)

        compose.onNodeWithContentDescription("显示密钥").performClick()
        enterKey("")
        assertScreenProtected(false)
        enterKey("screen-capture-fixture")
        assertScreenProtected(true)

        compose.onNodeWithText("总览").performClick()
        assertScreenProtected(false)
        compose.onNodeWithText("设置").performClick()
        assertScreenProtected(false)
        compose.onNodeWithContentDescription("显示密钥").assertExists()
    }

    @Test fun recreatingTheSettingsPageRestoresMaskedRecordableState() {
        compose.onNodeWithText("设置").performClick()
        enterKey("screen-capture-fixture")
        compose.onNodeWithContentDescription("显示密钥").performClick()
        assertScreenProtected(true)

        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("账单识别AI").assertExists()
        compose.onNodeWithContentDescription("显示密钥").assertExists()
        assertScreenProtected(false)
    }

    private fun enterKey(value: String) {
        compose.onNodeWithText("API Key").performScrollTo()
            .performSemanticsAction(SemanticsActions.SetText) { it(AnnotatedString(value)) }
    }

    private fun assertScreenProtected(expected: Boolean) {
        compose.runOnIdle {
            val protected = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
            assertEquals("Activity screen capture protection", expected, protected)
        }
    }
}
