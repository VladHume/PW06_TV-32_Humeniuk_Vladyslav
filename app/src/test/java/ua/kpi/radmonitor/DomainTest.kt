package ua.kpi.radmonitor
import ua.kpi.radmonitor.*
import ua.kpi.radmonitor.domain.*
import ua.kpi.radmonitor.data.*
import ua.kpi.radmonitor.viewmodel.*
import ua.kpi.radmonitor.device.*
import ua.kpi.radmonitor.ui.*


import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    @Test
    fun doseCalculation() {
        assertEquals(.96, MonitorLogic.dose(.12, 8.0), 1e-10)
    }

    @Test
    fun zeroDuration() {
        assertEquals(0.0, MonitorLogic.dose(.3, 0.0), 0.0)
    }

    @Test
    fun rejectInvalidDose() {
        for (v in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { MonitorLogic.dose(v, 2.0) }
        }
    }

    @Test
    fun rejectOverflow() {
        assertThrows(IllegalArgumentException::class.java) {
            MonitorLogic.dose(Double.MAX_VALUE, 2.0)
        }
    }

    @Test
    fun coordinateLimits() {
        assertNull(MonitorLogic.validate(Measurement(latitude = -90.0, longitude = 180.0)))
        assertNotNull(MonitorLogic.validate(Measurement(latitude = 91.0)))
        assertNotNull(MonitorLogic.validate(Measurement(longitude = Double.NaN)))
    }

    @Test
    fun rejectNegativeReading() {
        assertNotNull(MonitorLogic.validate(Measurement(doseRate = -.01)))
    }

    @Test
    fun rejectEmptyName() {
        assertNotNull(MonitorLogic.validate(Measurement(name = "  ")))
    }

    @Test
    fun filterAndSort() {
        val result = MonitorLogic.filter(MonitorLogic.samples(), "пост", "Усі", "Показник")
        assertEquals("post-c", result.first().id)
        assertEquals(1, MonitorLogic.filter(result, " B ", "Зал", "Дата").size)
        assertTrue(MonitorLogic.filter(result, "zz", "Усі", "Дата").isEmpty())
    }

    @Test
    fun validateCredentials() {
        assertNotNull(MonitorLogic.validateLogin("notemail", "12345678"))
        assertNotNull(MonitorLogic.validateLogin("a@b.ua", "123"))
        assertNull(MonitorLogic.validateLogin("operator@demo.ua", "Demo12345"))
    }

    @Test
    fun jsonRoundTrip() {
        val rows = MonitorLogic.samples()
        assertEquals(rows, Exchange.fromJson(Exchange.json(rows)))
    }

    @Test
    fun csvQuotedFieldsRoundTrip() {
        val rows = listOf(Measurement(name = "Пост, \"А\"\nКиїв"))
        assertEquals(rows, Exchange.fromCsv(Exchange.csv(rows)))
    }

    @Test
    fun rejectMalformedCsv() {
        assertThrows(IllegalArgumentException::class.java) { Exchange.fromCsv("bad,header") }
        assertThrows(IllegalArgumentException::class.java) { Exchange.fromCsv("\"open") }
    }

    @Test
    fun rejectDuplicateIds() {
        val row = Measurement()
        assertThrows(IllegalArgumentException::class.java) {
            Exchange.fromJson(Exchange.json(listOf(row, row)))
        }
    }

    @Test
    fun rejectInvalidImportedMeasurement() {
        assertThrows(IllegalArgumentException::class.java) {
            Exchange.fromJson(Exchange.json(listOf(Measurement(doseRate = -1.0))))
        }
    }
}
