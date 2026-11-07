package ua.kpi.radmonitor
import ua.kpi.radmonitor.*
import ua.kpi.radmonitor.domain.*
import ua.kpi.radmonitor.data.*
import ua.kpi.radmonitor.viewmodel.*
import ua.kpi.radmonitor.device.*
import ua.kpi.radmonitor.ui.*


import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Справжній HTTP-запит на локальний MockWebServer перевіряє Retrofit + Gson. */
class HttpIntegrationTest {
    @Test
    fun parsesHttpAndSendsAuthorization() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(Exchange.json(MonitorLogic.samples()))
            )
            val api =
                Retrofit.Builder()
                    .baseUrl(server.url("/"))
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(MonitorApi::class.java)
            val rows = api.measurements("Bearer integration-token")
            assertEquals(3, rows.size)
            assertEquals(.12, rows.first().doseRate, 0.0)
            val request = server.takeRequest()
            assertEquals("/measurements", request.path)
            assertEquals("Bearer integration-token", request.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }
}
