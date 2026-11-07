package ua.kpi.radmonitor.domain
import ua.kpi.radmonitor.*
import ua.kpi.radmonitor.domain.*
import ua.kpi.radmonitor.data.*
import ua.kpi.radmonitor.viewmodel.*
import ua.kpi.radmonitor.device.*
import ua.kpi.radmonitor.ui.*


import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.*

/** Навчальний запис. Координати належать вигаданим постам, не реальній АЕС. */
@Entity(tableName = "measurements")
data class Measurement(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "Пост A",
    val type: String = "Периметр",
    val doseRate: Double = 0.12,
    val powerMw: Double = 1000.0,
    val latitude: Double = 50.45,
    val longitude: Double = 30.52,
    val timestamp: Long = System.currentTimeMillis(),
)

object MonitorLogic {
    // Поріг є параметром навчальної моделі, а не нормативом радіаційної безпеки.
    const val DEMO_THRESHOLD = 0.30

    fun dose(rate: Double, hours: Double): Double {
        require(rate.isFinite() && hours.isFinite() && rate >= 0 && hours >= 0)
        return (rate * hours).also { require(it.isFinite()) }
    }

    fun validate(m: Measurement): String? =
        when {
            m.name.isBlank() -> "Вкажіть назву поста"
            m.name.length > 80 -> "Назва: не більше 80 символів"
            m.type !in listOf("Периметр", "Зал", "Контроль") -> "Невідомий тип поста"
            !m.doseRate.isFinite() || m.doseRate < 0 -> "Показник має бути скінченним і невід’ємним"
            !m.powerMw.isFinite() || m.powerMw < 0 -> "Потужність має бути невід’ємною"
            !m.latitude.isFinite() || m.latitude !in -90.0..90.0 -> "Широта: від -90 до 90"
            !m.longitude.isFinite() || m.longitude !in -180.0..180.0 -> "Довгота: від -180 до 180"
            m.timestamp < 0 || m.id.isBlank() -> "Некоректний ідентифікатор або дата"
            else -> null
        }

    fun filter(
        items: List<Measurement>,
        query: String,
        type: String,
        sort: String,
    ): List<Measurement> {
        val result =
            items.filter {
                it.name.contains(query.trim(), ignoreCase = true) &&
                    (type == "Усі" || it.type == type)
            }
        return when (sort) {
            "Назва" -> result.sortedBy { it.name.lowercase() }
            "Тип" -> result.sortedWith(compareBy<Measurement> { it.type }.thenBy { it.name })
            "Показник" -> result.sortedByDescending { it.doseRate }
            "Потужність" -> result.sortedByDescending { it.powerMw }
            else -> result.sortedByDescending { it.timestamp }
        }
    }

    fun validateLogin(email: String, password: String): String? =
        when {
            !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(email) -> "Введіть коректний email"
            password.length < 8 -> "Пароль має містити щонайменше 8 символів"
            else -> null
        }

    fun samples() =
        listOf(
            Measurement("post-a", "Пост A", "Периметр", .12, 980.0, 50.45, 30.52),
            Measurement("post-b", "Пост B", "Зал", .18, 1010.0, 50.46, 30.53),
            Measurement("post-c", "Пост C", "Контроль", .34, 995.0, 50.44, 30.51),
        )
}

/** CSV із quoted fields: назви з комою, лапками та переносом рядка підтримуються. */
object Exchange {
    private val gson = Gson()
    private val header =
        listOf("id", "name", "type", "doseRate", "powerMw", "latitude", "longitude", "timestamp")

    fun json(items: List<Measurement>) = gson.toJson(items)

    fun fromJson(text: String): List<Measurement> {
        val rows: List<Measurement> =
            gson.fromJson(text, object : TypeToken<List<Measurement>>() {}.type)
                ?: error("Порожній JSON")
        return checked(rows)
    }

    private fun checked(rows: List<Measurement>): List<Measurement> {
        require(rows.size <= 10000) { "Не більше 10000 записів" }
        require(rows.map { it.id }.distinct().size == rows.size) { "Повторні ID" }
        rows.forEach {
            require(MonitorLogic.validate(it) == null) {
                MonitorLogic.validate(it) ?: "Некоректний запис"
            }
        }
        return rows
    }

    fun csv(items: List<Measurement>): String =
        (listOf(header) +
                items.map {
                    listOf(
                        it.id,
                        it.name,
                        it.type,
                        it.doseRate.toString(),
                        it.powerMw.toString(),
                        it.latitude.toString(),
                        it.longitude.toString(),
                        it.timestamp.toString(),
                    )
                })
            .joinToString("\r\n") { row ->
                row.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }
            }

    fun fromCsv(text: String): List<Measurement> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> {
                    row.add(field.toString())
                    field.clear()
                }
                (c == '\n' || c == '\r') && !quoted -> {
                    row.add(field.toString())
                    field.clear()
                    rows.add(row)
                    row = mutableListOf()
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> field.append(c)
            }
            i++
        }
        require(!quoted) { "Незакрита лапка CSV" }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        require(rows.firstOrNull() == header) { "Некоректний заголовок CSV" }
        return checked(
            rows.drop(1).map { r ->
                require(r.size == 8) { "Очікується 8 колонок" }
                Measurement(
                    r[0],
                    r[1],
                    r[2],
                    r[3].toDouble(),
                    r[4].toDouble(),
                    r[5].toDouble(),
                    r[6].toDouble(),
                    r[7].toLong(),
                )
            }
        )
    }
}
