package ua.kpi.radmonitor

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import ua.kpi.radmonitor.ui.DocumentPreview
import ua.kpi.radmonitor.device.*
import ua.kpi.radmonitor.domain.*

/** Exercises real PDF/DOCX rendering and notification actions inside the Android runtime. */
class FeatureUiTest {
 @get:Rule val rule=createAndroidComposeRule<MainActivity>()
 @Test fun docxTextPreview() {
  assumeTrue(BuildConfig.PRACTICAL>=6)
  val f=File(rule.activity.cacheDir,"preview.docx")
  ZipOutputStream(f.outputStream()).use { z -> z.putNextEntry(ZipEntry("word/document.xml"));z.write("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>Тест документа АЕС</w:t></w:r></w:p></w:body></w:document>".toByteArray());z.closeEntry() }
  rule.activity.runOnUiThread {rule.activity.setContent { MaterialTheme {DocumentPreview(f){}} }}
  rule.waitUntil(10000) {rule.onAllNodesWithText("Тест документа АЕС",substring=true).fetchSemanticsNodes().isNotEmpty()}
  rule.onNodeWithText("Тест документа АЕС",substring=true).assertExists()
 }
 @Test fun pdfPreviewChangesPage() {
  assumeTrue(BuildConfig.PRACTICAL>=6)
  val f=FilesService.pdf(rule.activity,(1..25).map {Measurement(id="page-$it",name="Пост $it")})
  rule.activity.runOnUiThread {rule.activity.setContent {MaterialTheme {DocumentPreview(f){}}}}
  rule.waitUntil(10000){rule.onAllNodesWithText("Сторінка 1 з 2").fetchSemanticsNodes().isNotEmpty()}
  rule.onNodeWithText("Далі").performScrollTo().performClick()
  rule.waitUntil(10000){rule.onAllNodesWithText("Сторінка 2 з 2").fetchSemanticsNodes().isNotEmpty()}
 }
 @Test fun notificationActionAcknowledgesOneOfTwoEvents() {
  assumeTrue(BuildConfig.PRACTICAL>=5)
  val c=rule.activity
  if(Build.VERSION.SDK_INT>=33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(c.packageName,Manifest.permission.POST_NOTIFICATIONS)
  val manager=c.getSystemService(NotificationManager::class.java)
  EventNotifications.show(c,"reminder",19001,"Перша подія")
  EventNotifications.show(c,"sensor",19002,"Друга подія")
  rule.waitUntil(10000){manager.activeNotifications.count{it.id in 19001..19002}==2}
  manager.activeNotifications.first{it.id==19001}.notification.actions.last().actionIntent.send()
  rule.waitUntil(10000){c.getSharedPreferences("events",0).getString("19001","")!!.contains("підтверджено")}
  assertTrue(manager.activeNotifications.any{it.id==19002})
  manager.cancel(19002)
 }
}
