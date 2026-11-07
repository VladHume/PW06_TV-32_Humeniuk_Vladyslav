package ua.kpi.radmonitor.viewmodel
import ua.kpi.radmonitor.*
import ua.kpi.radmonitor.domain.*
import ua.kpi.radmonitor.data.*
import ua.kpi.radmonitor.viewmodel.*
import ua.kpi.radmonitor.device.*
import ua.kpi.radmonitor.ui.*


import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.*
import androidx.work.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import retrofit2.HttpException

class MonitorViewModel(app: Application) : AndroidViewModel(app) {
    private val db = MonitorDatabase.create(app)
    val repository = MonitorRepository(db.dao(), app)
    private val preferences = app.getSharedPreferences("settings", 0)
    val session = MutableStateFlow<Session?>(null)
    val query = MutableStateFlow("")
    val statusFilter = MutableStateFlow("Усі")
    val minimumPower = MutableStateFlow("")
    val selectedId = MutableStateFlow<String?>(null)
    val periodHours = MutableStateFlow("Увесь час")
    val statistics = repository.statistics.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun history(id: String) = repository.history(id)
    val type = MutableStateFlow("Усі")
    val sort = MutableStateFlow("Дата")
    val message = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val dark = MutableStateFlow(preferences.getBoolean("dark", false))
    val notifications = MutableStateFlow(preferences.getBoolean("notifications", false))
    private val sensors = DeviceSensors(app)
    val accelerometer = sensors.vectors(android.hardware.Sensor.TYPE_ACCELEROMETER).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val gyroscope = sensors.vectors(android.hardware.Sensor.TYPE_GYROSCOPE).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val acceleration = accelerometer.map { it?.magnitude }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val sensorHistory = flow {
        var history = emptyList<Double>()
        while(true) { accelerometer.value?.let { history = (history + it.magnitude).takeLast(120); emit(history) }; delay(500) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val sampler = ResourceSampler(app)
    val resources =
        flow {
                while (true) {
                    emit(sampler.read())
                    delay(2000)
                }
            }
            .flowOn(Dispatchers.Default)
            .catch { message.value = "Помилка моніторингу ресурсів" }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Resources())
    val all =
        repository.records.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList(),
        )
    private val textFiltered =
        combine(repository.records, query, type, sort) { r, q, t, s ->
                MonitorLogic.filter(r, q, t, s)
            }
            .catch { message.value = "Помилка читання бази даних" }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val filtered = combine(textFiltered, statusFilter, minimumPower, periodHours) { rows, status, power, period ->
        val cutoff = period.toLongOrNull()?.let { System.currentTimeMillis() - it * 3600000 }
        rows.filter { (status == "Усі" || (it.doseRate > MonitorLogic.DEMO_THRESHOLD) == (status == "Поріг перевищено")) && it.powerMw >= (power.toDoubleOrNull() ?: 0.0) && (cutoff == null || it.timestamp >= cutoff) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    // Cold source -> map/filter -> retry -> hot StateFlow; collectors share one producer.
    val forecast = flow { while(true) { emit(kotlin.random.Random.nextDouble(0.08, 0.35)); delay(2000) } }
        .map { (it * 1000).toInt() / 1000.0 }.filter { it >= 0 }
        .retryWhen { _, attempt -> delay(500); attempt < 3 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.12)
    val documents = MutableStateFlow<List<File>>(emptyList())

    init {
        refreshDocuments()
    }

    fun task(block: suspend () -> Unit) {
        viewModelScope.launch {
            busy.value = true
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Monitor", "Operation failed: ${e.javaClass.simpleName}")
                withContext(Dispatchers.IO) { File(getApplication<Application>().filesDir, "errors.log").appendText("${System.currentTimeMillis()} ${e.javaClass.simpleName} ${(e as? HttpException)?.code() ?: ""}\n") }
                if (e is HttpException && e.code() == 401) { session.value = null; repository.clearSession() }
                message.value =
                    when (e) {
                        is HttpException ->
                            when (e.code()) {
                                401 -> "Невірні дані входу або сеанс завершено"
                                403 -> "Доступ заборонено"
                                503 -> "Сервер тимчасово недоступний"
                                else -> "Помилка HTTP ${e.code()}"
                            }
                        else -> e.message ?: "Не вдалося виконати операцію"
                    }
            } finally {
                busy.value = false
            }
        }
    }

    fun login(email: String, password: String) {
        val error = MonitorLogic.validateLogin(email, password)
        if (error != null) {
            message.value = error
            return
        }
        task {
            session.value = repository.login(LoginRequest(email, password))
            message.value = "Вхід виконано"
        }
    }

    fun configureApi(url: String) {
        try {
            repository.configure(url)
            session.value = null
            message.value = "Джерело змінено. Увійдіть повторно."
        } catch (e: Exception) {
            message.value = e.message ?: "Некоректна адреса API"
        }
    }

    fun logout() {
        repository.clearSession()
        session.value = null
    }

    fun register(name: String, email: String, password: String, confirm: String) {
        val error =
            when {
                name.isBlank() -> "Вкажіть ім’я"
                password != confirm -> "Паролі не збігаються"
                else -> MonitorLogic.validateLogin(email, password)
            }
        if (error != null) {
            message.value = error
            return
        }
        task {
            session.value = repository.register(RegistrationRequest(name, email, password))
            message.value = "Навчальний обліковий запис створено до перезапуску процесу"
        }
    }

    fun save(m: Measurement) {
        if (session.value?.role != "operator") {
            message.value = "Лише оператор може змінювати записи"
            return
        }
        task {
            repository.save(m)
            message.value = "Запис збережено"
        }
    }

    fun delete(m: Measurement) {
        if (session.value?.role == "operator")
            task {
                repository.delete(m.id)
                message.value = "Запис видалено"
            }
    }

    fun seed() {
        if (session.value?.role == "operator")
            task {
                repository.import(MonitorLogic.samples())
                message.value = "Додано 3 демонстраційні пости"
            }
    }

    fun sync() {
        val current = session.value ?: return
        if (System.currentTimeMillis() >= current.expiresAt) { logout(); message.value = "Сеанс завершено. Увійдіть повторно."; return }
        val token = current.token
        task {
            var attempts = 0
            while (true) {
                try {
                    repository.sync(token)
                    message.value = "Дані API отримано"
                    break
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    attempts++
                    if (attempts >= 3 || !(e is java.io.IOException || (e is HttpException && e.code() >= 500))) throw e
                    delay(300L * attempts)
                }
            }
        }
    }

    fun setDark(value: Boolean) {
        dark.value = value
        preferences.edit().putBoolean("dark", value).apply()
    }

    fun setNotifications(value: Boolean) {
        notifications.value = value
        preferences.edit().putBoolean("notifications", value).apply()
        if (!value) WorkManager.getInstance(getApplication()).cancelAllWorkByTag("monitor-reminder")
    }

    fun schedule(category: String = "reminder", text: String = "Перегляньте журнал постів і сенсори.", seconds: Long = 15) {
        if (!notifications.value) {
            message.value = "Спочатку увімкніть сповіщення"
            return
        }
        val request =
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(seconds, TimeUnit.SECONDS)
                .setInputData(workDataOf("category" to category, "text" to text, "eventId" to kotlin.random.Random.nextInt()))
                .addTag("monitor-reminder")
                .build()
        WorkManager.getInstance(getApplication())
            .enqueue(request)
        message.value = "Нагадування заплановано: не раніше ніж через 15 с"
    }

    fun export(format: String) {
        task {
            withContext(Dispatchers.IO) {
                File(getApplication<Application>().filesDir, "measurements.$format")
                    .writeText(
                        if (format == "csv") Exchange.csv(all.value) else Exchange.json(all.value)
                    )
            }
            message.value = "Збережено measurements.$format у сховищі застосунку"
        }
    }

    fun importFile(format: String) {
        if (session.value?.role != "operator") return
        task {
            val text =
                withContext(Dispatchers.IO) {
                    File(getApplication<Application>().filesDir, "measurements.$format").readText()
                }
            repository.import(
                if (format == "csv") Exchange.fromCsv(text) else Exchange.fromJson(text)
            )
            message.value = "Імпортовано записи"
        }
    }

    fun pdf() {
        task {
            val file = withContext(Dispatchers.IO) { FilesService.pdf(getApplication(), all.value) }
            message.value = "Створено ${file.name}"
        }
    }

    fun addDocument(uri: Uri) {
        task {
            withContext(Dispatchers.IO) {
                val file = FilesService.copyDocument(getApplication(), uri)
                preferences.edit().putString("document:" + file.name, selectedId.value ?: "Без поста").apply()
            }
            refreshDocuments()
            message.value = "Копію документа збережено"
        }
    }

    fun documentPost(file: File) = preferences.getString("document:" + file.name, "Без поста").orEmpty()
    fun renameDocument(file: File, name: String) {
        task { withContext(Dispatchers.IO) {
            require(name.matches(Regex("[\\p{L}\\p{N} _-]{1,60}"))) { "Назва: 1–60 літер, цифр, пробілів, _ або -" }
            val target = File(file.parentFile, "$name.${file.extension}")
            require(!target.exists()) { "Файл уже існує" }
            check(file.renameTo(target)) { "Не вдалося перейменувати" }
            preferences.edit().putString("document:" + target.name, documentPost(file)).remove("document:" + file.name).apply()
        }; refreshDocuments() }
    }
    fun refreshDocuments() {
        documents.value =
            File(getApplication<Application>().filesDir, "documents").listFiles()?.toList()
                ?: emptyList()
    }

    override fun onCleared() {
        db.close()
        super.onCleared()
    }
}
