package ua.kpi.radmonitor.device
import ua.kpi.radmonitor.*
import ua.kpi.radmonitor.domain.*
import ua.kpi.radmonitor.data.*
import ua.kpi.radmonitor.viewmodel.*
import ua.kpi.radmonitor.device.*
import ua.kpi.radmonitor.ui.*


import android.app.*
import android.content.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.work.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.io.File
import kotlin.math.*

/** Vector and monotonic timestamp preserve repeated readings, not just value changes. */
data class VectorReading(val x: Double, val y: Double, val z: Double, val timestamp: Long) {
    val magnitude get() = sqrt(x*x+y*y+z*z)
}

object EventNotifications {
    fun show(context: Context, category: String, id: Int, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(category, if(category=="sensor") "Порогові події сенсорів" else "Нагадування", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val ack = PendingIntent.getBroadcast(context, id, Intent(context, EventReceiver::class.java).putExtra("eventId",id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(id, NotificationCompat.Builder(context,category).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if(category=="sensor") "Поріг сенсора" else "Навчальне нагадування").setContentText(text)
            .setContentIntent(open).addAction(android.R.drawable.ic_menu_view,"Переглянути",open)
            .addAction(android.R.drawable.checkbox_on_background,"Підтвердити",ack).setAutoCancel(true).build())
        context.getSharedPreferences("events",0).edit().putString(id.toString(),"$category · $text · очікує підтвердження").apply()
    }
}
/** Internal receiver updates the same event state that the foreground UI observes. */
class EventReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id=intent.getIntExtra("eventId",0)
        context.getSystemService(NotificationManager::class.java).cancel(id)
        context.getSharedPreferences("events",0).edit().putString(id.toString(),"Подію $id підтверджено").apply()
    }
}

