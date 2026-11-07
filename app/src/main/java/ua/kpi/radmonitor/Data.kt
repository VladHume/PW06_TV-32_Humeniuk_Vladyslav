package ua.kpi.radmonitor

import android.content.Context
import androidx.room.*
import com.google.gson.Gson
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

@Dao
interface MeasurementDao {
    @Query("SELECT * FROM measurements ORDER BY timestamp DESC")
    fun observe(): Flow<List<Measurement>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: Measurement)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(items: List<Measurement>)

    @Query("DELETE FROM measurements WHERE id=:id") suspend fun delete(id: String)
}

@Database(entities = [Measurement::class], version = 1, exportSchema = false)
abstract class MonitorDatabase : RoomDatabase() {
    abstract fun dao(): MeasurementDao

    companion object {
        fun create(c: Context) =
            Room.databaseBuilder(c, MonitorDatabase::class.java, "monitor.db").build()
    }
}

data class LoginRequest(val email: String, val password: String)

data class RegistrationRequest(val name: String, val email: String, val password: String)

data class Session(val token: String, val role: String)

interface MonitorApi {
    @POST("register") suspend fun register(@Body body: RegistrationRequest): Session

    @POST("login") suspend fun login(@Body body: LoginRequest): Session

    @GET("measurements")
    suspend fun measurements(@Header("Authorization") authorization: String): List<Measurement>
}

/**
 * Відтворюваний mock API: Retrofit виконує JSON/HTTP pipeline через OkHttp. У деморежимі дані не
 * передаються сторонньому серверу.
 */
class DemoApiInterceptor : Interceptor {
    @Volatile var scenario = "Успіх"
    private var issuedToken: String? = null
    private val accounts =
        mutableMapOf("operator@demo.ua" to "Demo12345", "viewer@demo.ua" to "Demo12345")

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var code = 200
        val gson = Gson()
        val body =
            when {
                request.url.encodedPath.endsWith("register") -> {
                    val buffer = okio.Buffer()
                    request.body?.writeTo(buffer)
                    val r = gson.fromJson(buffer.readUtf8(), RegistrationRequest::class.java)
                    if (
                        r.name.isBlank() ||
                            MonitorLogic.validateLogin(r.email, r.password) != null ||
                            accounts.containsKey(r.email)
                    ) {
                        code = 400
                        "{}"
                    } else {
                        accounts[r.email] = r.password
                        issuedToken = java.util.UUID.randomUUID().toString()
                        gson.toJson(Session(issuedToken!!, "viewer"))
                    }
                }
                request.url.encodedPath.endsWith("login") -> {
                    val buffer = okio.Buffer()
                    request.body?.writeTo(buffer)
                    val login = gson.fromJson(buffer.readUtf8(), LoginRequest::class.java)
                    if (accounts[login.email] == login.password) {
                        issuedToken = java.util.UUID.randomUUID().toString()
                        gson.toJson(
                            Session(
                                issuedToken!!,
                                if (login.email == "operator@demo.ua") "operator" else "viewer",
                            )
                        )
                    } else {
                        code = 401
                        "{}"
                    }
                }
                request.header("Authorization") != "Bearer $issuedToken" || issuedToken == null -> {
                    code = 401
                    "{}"
                }
                scenario == "Без мережі" ->
                    throw IOException("Навчальна симуляція відсутності мережі")
                scenario == "HTTP 503" -> {
                    code = 503
                    "{}"
                }
                scenario == "Хибний JSON" -> "{broken"
                else -> gson.toJson(MonitorLogic.samples())
            }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Demo API")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}

class MonitorRepository(private val dao: MeasurementDao, private val context: Context) {
    val records = dao.observe()
    val mock = DemoApiInterceptor()
    var remoteMode = false
        private set

    var api: MonitorApi =
        Retrofit.Builder()
            .baseUrl("https://demo.invalid/")
            .client(OkHttpClient.Builder().addInterceptor(mock).build())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MonitorApi::class.java)

    fun configure(url: String) {
        val useRemote = url.isNotBlank()
        if (useRemote) {
            val uri = java.net.URI(url)
            require(
                uri.scheme == "https" ||
                    (uri.scheme == "http" &&
                        uri.host in listOf("10.0.2.2", "127.0.0.1", "localhost"))
            ) {
                "Потрібен HTTPS або локальний тестовий сервер"
            }
        }
        val client =
            OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        if (!useRemote) client.addInterceptor(mock)
        api =
            Retrofit.Builder()
                .baseUrl(if (useRemote) url.trimEnd('/') + "/" else "https://demo.invalid/")
                .client(client.build())
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(MonitorApi::class.java)
        remoteMode = useRemote
    }

    suspend fun login(body: LoginRequest): Session { checkNetwork(); return api.login(body) }
    suspend fun register(body: RegistrationRequest): Session { checkNetwork(); return api.register(body) }
    private fun checkNetwork() {
        if (!remoteMode) return
        val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
        val capabilities = cm.getNetworkCapabilities(cm.activeNetwork)
        require(
            capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) ==
                true
        ) {
            "Немає підключення до мережі"
        }
    }

    suspend fun save(m: Measurement) {
        require(MonitorLogic.validate(m) == null) { MonitorLogic.validate(m)!! }
        dao.upsert(m)
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun import(rows: List<Measurement>) {
        rows.forEach { require(MonitorLogic.validate(it) == null) }
        dao.upsertAll(rows)
    }

    suspend fun sync(token: String) {
        checkNetwork()
        val rows = api.measurements("Bearer $token")
        require(rows.all { MonitorLogic.validate(it) == null }) { "API повернув некоректні дані" }
        dao.upsertAll(rows)
    }
}
