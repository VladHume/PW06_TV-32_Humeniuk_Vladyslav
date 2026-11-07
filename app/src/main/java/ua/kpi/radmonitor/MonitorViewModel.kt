package ua.kpi.radmonitor

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
    val type = MutableStateFlow("Усі")
    val sort = MutableStateFlow("Дата")
    val message = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val dark = MutableStateFlow(preferences.getBoolean("dark", false))
    val notifications = MutableStateFlow(preferences.getBoolean("notifications", false))
    val acceleration =
        DeviceSensors(app)
            .acceleration()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
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
    val filtered =
        combine(repository.records, query, type, sort) { r, q, t, s ->
                MonitorLogic.filter(r, q, t, s)
            }
            .catch { message.value = "Помилка читання бази даних" }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
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
        val token = session.value?.token ?: return
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
        if (!value) WorkManager.getInstance(getApplication()).cancelUniqueWork("monitor-reminder")
    }

    fun schedule() {
        if (!notifications.value) {
            message.value = "Спочатку увімкніть сповіщення"
            return
        }
        val request =
            OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(15, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance(getApplication())
            .enqueueUniqueWork("monitor-reminder", ExistingWorkPolicy.REPLACE, request)
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
            withContext(Dispatchers.IO) { FilesService.copyDocument(getApplication(), uri) }
            refreshDocuments()
            message.value = "Копію документа збережено"
        }
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
