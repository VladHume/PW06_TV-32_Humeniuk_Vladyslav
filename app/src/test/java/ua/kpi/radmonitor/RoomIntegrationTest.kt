package ua.kpi.radmonitor

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomIntegrationTest {
    @Test
    fun insertUpdateDeleteAndFlow() = runBlocking {
        val db =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext<Context>(),
                    MonitorDatabase::class.java,
                )
                .allowMainThreadQueries()
                .build()
        try {
            val dao = db.dao()
            val m = Measurement(id = "test", doseRate = .1)
            dao.upsert(m)
            assertEquals(1, dao.observe().first().size)
            dao.upsert(m.copy(doseRate = .2))
            assertEquals(.2, dao.observe().first().single().doseRate, 0.0)
            dao.delete("test")
            assertTrue(dao.observe().first().isEmpty())
        } finally {
            db.close()
        }
    }
}
