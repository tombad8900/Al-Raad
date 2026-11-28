package com.alraad.feature.browser

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import com.alraad.core.common.contracts.StreamFormat
import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.feature.browser.ui.AddressBar
import com.alraad.feature.browser.ui.DetectedStreamItem
import com.alraad.feature.browser.ui.DetectedStreamPicker
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowserKeyboardDismissTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `submitting address hides software keyboard before navigation`() {
        val keyboard = RecordingKeyboardController()
        var navigatedUrl: String? = null

        composeRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                AddressBar(
                    currentUrl = "https://page.example",
                    isLoading = false,
                    onNavigate = { navigatedUrl = it },
                )
            }
        }

        composeRule.onNode(hasSetTextAction()).performImeAction()

        assertEquals(1, keyboard.hideCalls)
        assertEquals("https://page.example", navigatedUrl)
    }

    @Test
    fun `playing selected stream hides software keyboard before handoff`() {
        val keyboard = RecordingKeyboardController()
        val item =
            DetectedStreamItem(
                stream = StreamUrl("https://cdn.example/video.mp4", StreamFormat.MP4, StreamSource.NETWORK),
                sourcePageUrl = "https://page.example",
                sourceTabId = "tab-1",
            )
        var played: DetectedStreamItem? = null

        composeRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                DetectedStreamPicker(
                    items = listOf(item),
                    onPlay = { played = it },
                    onCopy = {},
                    onSave = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("تشغيل").performClick()

        assertEquals(1, keyboard.hideCalls)
        assertEquals(item, played)
    }
}

private class RecordingKeyboardController : SoftwareKeyboardController {
    var hideCalls = 0

    override fun show() = Unit

    override fun hide() {
        hideCalls++
    }
}
