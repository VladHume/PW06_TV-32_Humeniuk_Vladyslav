package ua.kpi.radmonitor.data
import ua.kpi.radmonitor.*
import ua.kpi.radmonitor.domain.*
import ua.kpi.radmonitor.data.*
import ua.kpi.radmonitor.viewmodel.*
import ua.kpi.radmonitor.device.*
import ua.kpi.radmonitor.ui.*


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

    @Upsert suspend fun write(item: Measurement)
    @Insert suspend fun audit(event: ChangeEvent)
    @Transaction suspend fun upsert(item: Measurement) {
        write(item)
        audit(ChangeEvent(measurementId = item.id, action = "Збереження"))
    }

    @Transaction suspend fun upsertAll(items: List<Measurement>) { items.forEach { upsert(it) } }
    @Query("SELECT * FROM changes WHERE measurementId=:id ORDER BY changedAt DESC")
    fun history(id: String): Flow<List<ChangeEvent>>
    @Query("SELECT type, COUNT(*) AS count, AVG(doseRate) AS average, MAX(doseRate) AS peak FROM measurements GROUP BY type")
    fun statistics(): Flow<List<TypeStatistics>>

    @Query("DELETE FROM measurements WHERE id=:id") suspend fun delete(id: String)
}

@Entity(tableName = "changes", foreignKeys = [ForeignKey(entity = Measurement::class, parentColumns = ["id"], childColumns = ["measurementId"], onDelete = ForeignKey.CASCADE)], indices = [Index("measurementId")])
data class ChangeEvent(@PrimaryKey(autoGenerate = true) val eventId: Long = 0, val measurementId: String, val action: String, val changedAt: Long = System.currentTimeMillis())
data class TypeStatistics(val type: String, val count: Int, val average: Double, val peak: Double)

@Database(entities = [Measurement::class, ChangeEvent::class], version = 2, exportSchema = false)
abstract class MonitorDatabase : RoomDatabase() {
    abstract fun dao(): MeasurementDao

    companion object {
        fun create(c: Context) =
            Room.databaseBuilder(c, MonitorDatabase::class.java, "monitor.db").addMigrations(object : androidx.room.migration.Migration(1,2) {
                override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE IF NOT EXISTS changes (eventId INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, measurementId TEXT NOT NULL, action TEXT NOT NULL, changedAt INTEGER NOT NULL, FOREIGN KEY(measurementId) REFERENCES measurements(id) ON DELETE CASCADE)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS index_changes_measurementId ON changes(measurementId)")
                }
            }).build()
    }
}

data class LoginRequest(val email: String, val password: String)

data class RegistrationRequest(val name: String, val email: String, val password: String)

data class Session(val token: String, val role: String, val expiresAt: Long = System.currentTimeMillis() + 3600000)

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
class DemoApiInterceptor(context: Context? = null) : Interceptor {
    private val preferences = context?.getSharedPreferences("demo-api", 0)
    private var expiresAt = preferences?.getLong("expiresAt", 0) ?: 0
    private fun issue(role: String): Session {
        expiresAt = System.currentTimeMillis() + 3600000
        issuedToken = "demo.$role.$expiresAt.${java.util.UUID.randomUUID()}"
        preferences?.edit()?.putString("token", issuedToken)?.putLong("expiresAt", expiresAt)?.apply()
        return Session(issuedToken!!, role, expiresAt)
    }
    @Volatile var scenario = "Успіх"
    private var issuedToken: String? = preferences?.getString("token", null)
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
                        gson.toJson(issue("viewer"))
                    }
                }
                request.url.encodedPath.endsWith("login") -> {
                    val buffer = okio.Buffer()
                    request.body?.writeTo(buffer)
                    val login = gson.fromJson(buffer.readUtf8(), LoginRequest::class.java)
                    if (accounts[login.email] == login.password) {
                        gson.toJson(issue(if (login.email == "operator@demo.ua") "operator" else "viewer"))
                    } else {
                        code = 401
                        "{}"
                    }
                }
                request.header("Authorization") != "Bearer $issuedToken" || issuedToken == null || System.currentTimeMillis() >= expiresAt || scenario == "Сеанс завершено" -> {
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
    val mock = DemoApiInterceptor(context)
    private val authPreferences = context.getSharedPreferences("auth", 0)
    private var authToken: String? = authPreferences.getString("token", null)
    private val authInterceptor = Interceptor { chain ->
        val request = chain.request()
        val authenticated = if (request.header("Authorization") == null && authToken != null)
            request.newBuilder().header("Authorization", "Bearer $authToken").build() else request
        chain.proceed(authenticated)
    }
    fun rememberSession(session: Session) {
        authToken = session.token
        authPreferences.edit().putString("token", session.token).putLong("expiresAt", session.expiresAt).apply()
    }
    fun clearSession() { authToken = null; authPreferences.edit().clear().apply() }
    fun history(id: String) = dao.history(id)
    val statistics = dao.statistics()
    var remoteMode = false
        private set

    var api: MonitorApi =
        Retrofit.Builder()
            .baseUrl("https://demo.invalid/")
            .client(OkHttpClient.Builder().addInterceptor(authInterceptor).addInterceptor(mock).build())
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
            OkHttpClient.Builder().addInterceptor(authInterceptor)
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

    suspend fun login(body: LoginRequest): Session { checkNetwork(); return api.login(body).also(::rememberSession) }
    suspend fun register(body: RegistrationRequest): Session { checkNetwork(); return api.register(body).also(::rememberSession) }
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
