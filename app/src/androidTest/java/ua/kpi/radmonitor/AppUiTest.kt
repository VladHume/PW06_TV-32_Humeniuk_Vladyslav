package ua.kpi.radmonitor

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** Перевіряє реальні переходи та введення через Compose semantics. */
class AppUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun loginAndCalculate() {
        rule.onNodeWithText("Увійти").performClick()
        rule.waitUntil(10000) {
            rule.onAllNodesWithText("Огляд станції").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Розрахувати").performScrollTo().performClick()
        rule.onNodeWithText("D = 0.960 мкЗв").assertExists()
    }

    @Test
    fun rejectsInvalidCredentials() {
        rule.onNodeWithText("Email").performTextClearance()
        rule.onNodeWithText("Email").performTextInput("bad")
        rule.onNodeWithText("Увійти").performClick()
        rule.onNodeWithText("Введіть коректний email").assertExists()
    }
}
