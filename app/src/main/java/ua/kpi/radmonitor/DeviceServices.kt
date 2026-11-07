package ua.kpi.radmonitor

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.hardware.*
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow

/** Телефон не є дозиметром. Акселерометр показує лише рух пристрою. */
class DeviceSensors(private val context: Context) {
    fun acceleration() =
        callbackFlow<Double?> {
            val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val listener =
                object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        val v = e.values
                        val magnitude = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()
                        if (magnitude.isFinite()) trySend(magnitude)
                    }

                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
            if (sensor == null) trySend(null)
            else manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            awaitClose { manager.unregisterListener(listener) }
        }
}

data class Resources(val cpu: Double = 0.0, val memoryMb: Double = 0.0, val battery: Int = -1)

class ResourceSampler(private val c: Context) {
    private var lastTime = android.os.SystemClock.elapsedRealtime()
    private var lastCpu = android.os.Process.getElapsedCpuTime()

    fun read(): Resources {
        val now = android.os.SystemClock.elapsedRealtime()
        val cpu = android.os.Process.getElapsedCpuTime()
        val load = if (now > lastTime) (cpu - lastCpu) * 100.0 / (now - lastTime) else 0.0
        lastTime = now
        lastCpu = cpu
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val battery = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return Resources(
            load.coerceAtLeast(0.0),
            info.totalPss / 1024.0,
            battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
        )
    }
}

class ReminderWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result {
        if (
            !applicationContext
                .getSharedPreferences("settings", 0)
                .getBoolean("notifications", false)
        )
            return Result.success()
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                "monitor",
                "Навчальний моніторинг",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val intent =
            PendingIntent.getActivity(
                applicationContext,
                0,
                Intent(applicationContext, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        try {
            nm.notify(
                4,
                NotificationCompat.Builder(applicationContext, "monitor")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Перевірка навчальних даних")
                    .setContentText("Перегляньте журнал постів і стан сенсора телефону.")
                    .setContentIntent(intent)
                    .setAutoCancel(true)
                    .build(),
            )
        } catch (_: SecurityException) {
            return Result.failure()
        }
        return Result.success()
    }
}

object FilesService {
    /** Зберігаємо копію з контрольованим ім'ям, без шляхів із зовнішнього URI. */
    fun copyDocument(c: Context, uri: Uri): File {
        val type = c.contentResolver.getType(uri) ?: error("Не вдалося визначити MIME")
        require(type in listOf("image/jpeg", "image/png", "application/pdf")) {
            "Оберіть JPEG, PNG або PDF"
        }
        val ext =
            when (type) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                else -> "pdf"
            }
        val dir = File(c.filesDir, "documents").apply { mkdirs() }
        val f = File(dir, "document-${System.currentTimeMillis()}.$ext")
        try {
            c.contentResolver.openInputStream(uri)!!.use { input ->
                f.outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= 10 * 1024 * 1024) { "Ліміт файлу 10 МБ" }
                        out.write(buffer, 0, n)
                    }
                }
            }
        } catch (e: Exception) {
            f.delete()
            throw e
        }
        return f
    }

    fun pdf(c: Context, rows: List<Measurement>): File {
        val pdf = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val chunks = rows.chunked(20).ifEmpty { listOf(emptyList()) }
        chunks.forEachIndexed { index, items ->
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
            val canvas = page.canvas
            paint.color = Color.rgb(20, 80, 70)
            paint.textSize = 22f
            canvas.drawText("Навчальний моніторинг АЕС", 40f, 50f, paint)
            paint.textSize = 11f
            paint.color = Color.BLACK
            canvas.drawText(
                "Варіант 4. Демонстраційні дані. Сторінка ${index+1}/${chunks.size}",
                40f,
                75f,
                paint,
            )
            canvas.drawText("Пост                         мкЗв/год        МВт", 40f, 110f, paint)
            items.forEachIndexed { j, m ->
                val y = 135f + j * 22
                canvas.drawText(m.name.take(24), 40f, y, paint)
                canvas.drawText("%.3f".format(m.doseRate), 280f, y, paint)
                canvas.drawText("%.1f".format(m.powerMw), 390f, y, paint)
            }
            paint.color = Color.rgb(20, 103, 92)
            val max = items.maxOfOrNull { it.doseRate }?.coerceAtLeast(.01) ?: 1.0
            items.forEachIndexed { j, m ->
                val x = 40f + j * 24
                canvas.drawRect(x, 740f - (m.doseRate / max * 110).toFloat(), x + 15, 740f, paint)
            }
            paint.textSize = 10f
            canvas.drawText("Порівняння навчальних показників постів", 40f, 770f, paint)
            pdf.finishPage(page)
        }
        val file = File(c.filesDir, "monitor-report.pdf")
        try {
            file.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
        return file
    }
}
