package ua.kpi.radmonitor

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
}
