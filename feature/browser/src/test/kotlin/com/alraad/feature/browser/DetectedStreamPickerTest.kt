package com.alraad.feature.browser

import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.alraad.core.common.contracts.StreamFormat
import com.alraad.core.common.contracts.StreamSource
import com.alraad.core.common.contracts.StreamUrl
import com.alraad.feature.browser.ui.DetectedStreamItem
import com.alraad.feature.browser.ui.DetectedStreamPicker
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DetectedStreamPickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val first =
        DetectedStreamItem(
            StreamUrl("https://cdn.example/first.m3u8", StreamFormat.HLS, StreamSource.NETWORK),
            "https://page.example/watch",
            "tab-a",
        )

    @Test
    fun `single link shows count and actions without selector`() {
        composeRule.setContent {
            DetectedStreamPicker(
                items = listOf(first),
                onPlay = {},
                onCopy = {},
                onSave = { _, _ -> },
            )
        }

        composeRule.onNodeWithText("روابط البث المكتشفة: 1").assertExists()
        composeRule.onNodeWithText("تشغيل").assertExists()
        composeRule.onNodeWithText("اختيار رابط البث").assertDoesNotExist()
    }

    @Test
    fun `multiple links expose selector and distinct count`() {
        val second =
            first.copy(
                stream = StreamUrl("https://cdn.example/second.mpd", StreamFormat.DASH, StreamSource.NETWORK),
            )
        composeRule.setContent {
            DetectedStreamPicker(
                items = listOf(first, second, first),
                onPlay = {},
                onCopy = {},
                onSave = { _, _ -> },
            )
        }

        composeRule.onNodeWithText("روابط البث المكتشفة: 2").assertExists()
        composeRule.onNodeWithText("اختيار رابط البث").assertExists()
    }

    @Test
    fun `save action sends selected item and entered title`() {
        var savedItem: DetectedStreamItem? = null
        var savedTitle: String? = null
        composeRule.setContent {
            DetectedStreamPicker(
                items = listOf(first),
                onPlay = {},
                onCopy = {},
                onSave = { item, title ->
                    savedItem = item
                    savedTitle = title
                },
            )
        }

        composeRule.onNodeWithText("حفظ في المكتبة").performClick()
        composeRule.onNodeWithText("اسم البث").assertExists()
        composeRule.onNodeWithText("حفظ").performClick()

        composeRule.runOnIdle {
            assertEquals(first, savedItem)
            assertEquals("cdn.example", savedTitle)
        }
    }
}
