package com.alraad.feature.library

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.alraad.feature.library.settings.DiagnosticsEntryCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiagnosticsEntryCardTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `diagnostics entry is a prominent accessible button and invokes its callback once`() {
        var clickCount = 0
        val title = "سجل التشخيص / Diagnostics log"

        composeRule.setContent {
            DiagnosticsEntryCard(onClick = { clickCount++ })
        }

        val card = composeRule.onNodeWithText(title)
        card.assertIsDisplayed()
        card.assertHasClickAction()
        card.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        card.performClick()

        assertEquals(1, clickCount)
        val heightDp = card.fetchSemanticsNode().boundsInRoot.height / composeRule.density
        assertTrue("Expected at least 48dp, was $heightDp dp", heightDp >= 48f)
    }
}
