package ua.kpi.radmonitor
import ua.kpi.radmonitor.*
import ua.kpi.radmonitor.domain.*
import ua.kpi.radmonitor.data.*
import ua.kpi.radmonitor.viewmodel.*
import ua.kpi.radmonitor.device.*
import ua.kpi.radmonitor.ui.*


import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import retrofit2.*
import retrofit2.converter.gson.GsonConverterFactory

class ApiTest {
    private val mock = DemoApiInterceptor()
    private val api =
        Retrofit.Builder()
            .baseUrl("https://demo.invalid/")
            .client(OkHttpClient.Builder().addInterceptor(mock).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MonitorApi::class.java)

    @Test
    fun loginAndProtectedEndpoint() = runBlocking {
        val s = api.login(LoginRequest("operator@demo.ua", "Demo12345"))
        assertEquals("operator", s.role)
        assertEquals(3, api.measurements("Bearer ${s.token}").size)
    }

    @Test
    fun viewerRole() = runBlocking {
        assertEquals("viewer", api.login(LoginRequest("viewer@demo.ua", "Demo12345")).role)
    }

    @Test
    fun deniedWithoutToken() = runBlocking {
        try {
            api.measurements("Bearer bad")
            fail()
        } catch (e: HttpException) {
            assertEquals(401, e.code())
        }
    }

    @Test
    fun wrongPassword() = runBlocking {
        try {
            api.login(LoginRequest("operator@demo.ua", "incorrect"))
            fail()
        } catch (e: HttpException) {
            assertEquals(401, e.code())
        }
    }

    @Test
    fun serviceUnavailable() = runBlocking {
        val s = api.login(LoginRequest("operator@demo.ua", "Demo12345"))
        mock.scenario = "HTTP 503"
        try {
            api.measurements("Bearer ${s.token}")
            fail()
        } catch (e: HttpException) {
            assertEquals(503, e.code())
        }
    }

    @Test
    fun offlineError() = runBlocking {
        val s = api.login(LoginRequest("operator@demo.ua", "Demo12345"))
        mock.scenario = "Без мережі"
        try {
            api.measurements("Bearer ${s.token}")
            fail()
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("мережі"))
        }
    }

    @Test fun expiredMockSessionIsRejected() = runBlocking {
        val session = api.login(LoginRequest("operator@demo.ua", "Demo12345"))
        assertTrue(session.token.startsWith("demo.operator."))
        assertTrue(session.expiresAt > System.currentTimeMillis())
        mock.scenario = "Сеанс завершено"
        try { api.measurements("Bearer ${session.token}"); fail("Expected 401") }
        catch (e: HttpException) { assertEquals(401, e.code()) }
    }
    @Test fun duplicateRegistrationIsRejected() = runBlocking {
        val body=RegistrationRequest("User","new@demo.ua","Demo12345")
        assertEquals("viewer", api.register(body).role)
        try { api.register(body); fail("Expected 400") }
        catch(e: HttpException) { assertEquals(400,e.code()) }
    }
}
