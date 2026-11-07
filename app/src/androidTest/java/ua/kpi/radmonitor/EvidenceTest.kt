package ua.kpi.radmonitor
import ua.kpi.radmonitor.*
import ua.kpi.radmonitor.domain.*
import ua.kpi.radmonitor.data.*
import ua.kpi.radmonitor.viewmodel.*
import ua.kpi.radmonitor.device.*
import ua.kpi.radmonitor.ui.*


import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Rule
import org.junit.Test

class EvidenceTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun capture(name: String) {
        // Allow asynchronous map tiles and sensor samples to appear in evidence.
        Thread.sleep(if (name == "04-map") 10000 else 3500)
        rule.waitForIdle()
        val folder =
            File(
                    InstrumentationRegistry.getInstrumentation()
                        .targetContext
                        .getExternalFilesDir(null),
                    "evidence",
                )
                .apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            rule
                .onRoot()
                .captureToImage()
                .asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun captureScreens() {
        capture("01-login")
        rule.onNodeWithText("Увійти").performClick()
        rule.waitUntil(10000) {
            rule.onAllNodesWithText("Огляд станції").fetchSemanticsNodes().isNotEmpty()
        }
        capture("02-dashboard")
        if (BuildConfig.PRACTICAL >= 2) {
            rule.onNodeWithText("Додати демонстраційні пости").performScrollTo().performSemanticsAction(SemanticsActions.OnClick) { it() }
            rule.waitUntil(10000) {
                rule.onAllNodesWithText("Записів у журналі: 3").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText("Журнал", useUnmergedTree = true).performClick()
            capture("03-records")
        }
        if (BuildConfig.PRACTICAL >= 4) {
            rule.onNodeWithText("Мапа", useUnmergedTree = true).performClick()
            capture("04-map")
        }
        if (BuildConfig.PRACTICAL >= 5) {
            rule.onNodeWithText("Сенсори", useUnmergedTree = true).performClick()
            capture("05-sensors")
        }
        if (BuildConfig.PRACTICAL >= 6) {
            rule.onNodeWithText("Файли", useUnmergedTree = true).performScrollTo().performClick()
            capture("06-files")
        }
    }
}
