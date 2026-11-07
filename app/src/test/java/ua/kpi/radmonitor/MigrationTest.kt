package ua.kpi.radmonitor

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ua.kpi.radmonitor.data.MonitorDatabase

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class MigrationTest {
 @Test fun versionOneDataSurvivesHistoryMigration() = runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>()
  context.deleteDatabase("monitor.db")
  val path=context.getDatabasePath("monitor.db");path.parentFile!!.mkdirs()
  SQLiteDatabase.openOrCreateDatabase(path,null).use { old ->
   old.execSQL("CREATE TABLE measurements (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL, doseRate REAL NOT NULL, powerMw REAL NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, timestamp INTEGER NOT NULL)")
   old.execSQL("INSERT INTO measurements VALUES ('old','Старий пост','Периметр',0.12,980.0,50.45,30.52,1000)")
   old.version=1
  }
  val db=MonitorDatabase.create(context)
  try {
   val before=db.dao().observe().first().single()
   assertEquals("Старий пост",before.name)
   assertTrue(db.dao().history("old").first().isEmpty())
   db.dao().upsert(before.copy(doseRate=.25))
   assertEquals(1,db.dao().history("old").first().size)
   assertEquals(.25,db.dao().observe().first().single().doseRate,0.0)
  } finally {db.close();context.deleteDatabase("monitor.db")}
 }
}
