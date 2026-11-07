package ua.kpi.radmonitor
import ua.kpi.radmonitor.*
import ua.kpi.radmonitor.domain.*
import ua.kpi.radmonitor.data.*
import ua.kpi.radmonitor.viewmodel.*
import ua.kpi.radmonitor.device.*
import ua.kpi.radmonitor.ui.*


import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue

/** Checks missing navigation and role boundaries found during the requirements audit. */
class AuditUiTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun login() {
        rule.onNodeWithText("Увійти").performClick()
        rule.waitUntil(10000) { rule.onAllNodesWithText("Огляд станції").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun detailsRouteCarriesIdAndMapReturnsToSelectedCard() {
        assumeTrue(BuildConfig.PRACTICAL>=2)
        login()
        rule.onNodeWithText("Додати демонстраційні пости").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("Журнал",useUnmergedTree=true).performClick()
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Деталі Пост A"))
        rule.onNodeWithText("Деталі Пост A").performScrollTo().performClick()
        rule.onNodeWithText("Деталі поста").assertExists()
        rule.onNodeWithText("Пост A").assertExists()
        if(BuildConfig.PRACTICAL>=4) {
            rule.onNodeWithText("Показати пост на мапі").performScrollTo().performClick()
            rule.onNodeWithText("Відкрити вибрану картку").performClick()
            rule.onNodeWithText("Журнал постів").assertExists()
        }
    }
    @Test fun viewerCannotCreateRecords() {
        assumeTrue(BuildConfig.PRACTICAL>=2)
        rule.onNodeWithText("Email").performTextReplacement("viewer@demo.ua")
        login()
        rule.onNodeWithText("Журнал",useUnmergedTree=true).performClick()
        rule.onNodeWithText("Новий запис").assertDoesNotExist()
    }
}
