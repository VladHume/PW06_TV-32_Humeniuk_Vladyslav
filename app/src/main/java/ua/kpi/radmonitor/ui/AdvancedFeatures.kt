package ua.kpi.radmonitor.ui
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

/** Two actual SensorManager streams, vector tilt, threshold and category controls. */
@Composable
fun SensorExtras(vm: MonitorViewModel) {
    val a by vm.accelerometer.collectAsStateWithLifecycle()
    val g by vm.gyroscope.collectAsStateWithLifecycle()
    val enabled by vm.notifications.collectAsStateWithLifecycle()
    var showA by rememberSaveable { mutableStateOf(true) }
    var showG by rememberSaveable { mutableStateOf(true) }
    var threshold by rememberSaveable { mutableStateOf("12") }
    var minimum by rememberSaveable { mutableStateOf("0") }
    var descending by rememberSaveable { mutableStateOf(false) }
    var lastAlert by remember { mutableStateOf(0L) }
    var scale by remember { mutableStateOf(1f) }
    var purple by remember { mutableStateOf(false) }
    val c=androidx.compose.ui.platform.LocalContext.current
    val events = remember { callbackFlow {
        val prefs=c.getSharedPreferences("events",0)
        val listener=SharedPreferences.OnSharedPreferenceChangeListener { p,_ -> trySend(p.all.values.map { it.toString() }) }
        prefs.registerOnSharedPreferenceChangeListener(listener);trySend(prefs.all.values.map { it.toString() })
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    } }
    val eventRows by events.collectAsStateWithLifecycle(initialValue=emptyList())
    LaunchedEffect(a, threshold, enabled) {
        val limit=threshold.toDoubleOrNull()
        if(enabled && showA && limit!=null && limit.isFinite() && limit>0 && (a?.magnitude ?: 0.0)>limit && System.currentTimeMillis()-lastAlert>30000) {
            lastAlert=System.currentTimeMillis();vm.schedule("sensor","Прискорення %.2f м/с² вище %.2f".format(a!!.magnitude,limit),0)
        }
    }
    Text("Панель сенсорів",style=MaterialTheme.typography.headlineSmall)
    Row { Checkbox(showA,{showA=it});Text("Акселерометр");Checkbox(showG,{showG=it});Text("Гіроскоп") }
    OutlinedTextField(threshold,{threshold=it},label={Text("Поріг прискорення, м/с²")},supportingText={Text("Подія за ввімкнених сповіщень; пауза між подіями 30 с")})
    OutlinedTextField(minimum,{minimum=it},label={Text("Мінімальне значення у таблиці")})
    TextButton(onClick={descending=!descending}) { Text(if(descending) "Сортування: значення" else "Сортування: тип") }
    val rows=listOfNotNull(if(showA) Triple("Акселерометр",a,"м/с²") else null,if(showG) Triple("Гіроскоп",g,"рад/с") else null)
    (if(descending) rows.sortedByDescending { it.second?.magnitude ?: -1.0 } else rows.sortedBy { it.first }).forEach { (label,v,unit) ->
        if(v==null) Text("$label: датчик недоступний") else if(v.magnitude >= (minimum.toDoubleOrNull() ?: 0.0))
            Text("$label: x=%.2f y=%.2f z=%.2f $unit".format(v.x,v.y,v.z))
    }
    Text("Нахил: зміщення точки за X/Y акселерометра; стрілка — X/Y гіроскопа")
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val center=Offset(size.width/2,size.height/2)
        drawCircle(Color.LightGray,45f,center)
        a?.let { drawCircle(if(purple) Color.Magenta else Color(0xFF14675C),12f,center+Offset((it.x*8*scale).toFloat().coerceIn(-size.width/3,size.width/3),(it.y*8*scale).toFloat().coerceIn(-size.height/3,size.height/3))) }
        g?.let { drawLine(Color.Blue,center,center+Offset((it.x*15*scale).toFloat(),(it.y*15*scale).toFloat()),5f) }
    }
    Slider(scale,{scale=it},valueRange=.5f..3f)
    TextButton(onClick={purple=!purple}){Text("Колір вектора")}
    Row { Button(onClick={vm.schedule("reminder")}) {Text("Подія: нагадування")}; TextButton(onClick={vm.schedule("sensor","Тестова подія сенсора")}) {Text("Подія: сенсор")} }
    eventRows.takeLast(8).forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
}

/** Local PDF page preview and bounded DOCX XML text preview; no external upload. */
@Composable
fun DocumentPreview(file: File, close: () -> Unit) {
    var text by remember(file) { mutableStateOf("Завантаження…") }
    var bitmap by remember(file) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var page by remember(file) { mutableStateOf(0) }
    var count by remember(file) { mutableStateOf(1) }
    LaunchedEffect(file,page) {
        withContext(Dispatchers.IO) {
            try {
                if(file.extension=="pdf") {
                    ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer ->
                        count=renderer.pageCount
                        renderer.openPage(page).use { p ->
                            val image=android.graphics.Bitmap.createBitmap(800,(800.0*p.height/p.width).toInt(),android.graphics.Bitmap.Config.ARGB_8888)
                            image.eraseColor(android.graphics.Color.WHITE);p.render(image,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);bitmap=image
                        }
                    } };text="Сторінка ${page+1} з $count"
                } else {
                    java.util.zip.ZipFile(file).use { zip ->
                        val entry=zip.getEntry("word/document.xml") ?: error("Немає тексту DOCX")
                        val xml=zip.getInputStream(entry).use { input ->
                            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                            while(true) {val n=input.read(buffer);if(n<0)break;require(out.size()+n<=1000000){"Документ завеликий"};out.write(buffer,0,n)};out.toByteArray()
                        }
                        require(xml.size<=1000000) { "Документ завеликий для текстового перегляду" }
                        val parser=android.util.Xml.newPullParser();parser.setInput(xml.inputStream(),"UTF-8")
                        val result=StringBuilder();var event=parser.eventType
                        while(event!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                            if(event==org.xmlpull.v1.XmlPullParser.TEXT) result.append(parser.text)
                            if(event==org.xmlpull.v1.XmlPullParser.END_TAG && parser.name=="p") result.append('\n')
                            event=parser.next()
                        };text=result.toString().take(30000)
                    }
                }
            } catch(e:Exception) { text="Не вдалося відкрити: ${e.message}" }
        }
    }
    AlertDialog(onDismissRequest=close, confirmButton={TextButton(onClick=close){Text("Закрити")}},title={Text(file.name)},text={
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(text)
            bitmap?.let { Image(it.asImageBitmap(),"Сторінка PDF",Modifier.fillMaxWidth()) }
            if(file.extension=="pdf") Row { TextButton(onClick={page=(page-1).coerceAtLeast(0)}){Text("Назад")};TextButton(onClick={page=(page+1).coerceAtMost(count-1)}){Text("Далі")} }
        }
    })
}
