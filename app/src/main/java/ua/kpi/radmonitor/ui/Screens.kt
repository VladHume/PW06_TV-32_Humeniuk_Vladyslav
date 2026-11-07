package ua.kpi.radmonitor.ui
import ua.kpi.radmonitor.*
import ua.kpi.radmonitor.R
import ua.kpi.radmonitor.domain.*
import ua.kpi.radmonitor.data.*
import ua.kpi.radmonitor.viewmodel.*
import ua.kpi.radmonitor.device.*
import ua.kpi.radmonitor.ui.*


import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.*
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker


private val Green = Color(0xFF14675C)

/** Compose-компонент MonitorApp: читає стан і передає події у ViewModel або callback. */
@Composable
fun MonitorApp(vm: MonitorViewModel = viewModel()) {
    val dark by vm.dark.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        if (message.isNotBlank()) {
            snack.showSnackbar(message)
            vm.message.value = ""
        }
    }
    MaterialTheme(
        colorScheme =
            if (dark) darkColorScheme(primary = Color(0xFF7CCBB9))
            else lightColorScheme(primary = Green, background = Color(0xFFF4F7F5))
    ) {
        Scaffold(snackbarHost = { SnackbarHost(snack) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painterResource(R.drawable.station),
                        "Емблема навчальної станції",
                        Modifier.size(42.dp),
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("АЕС · Моніторинг", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            "Варіант 4 · Практична ${BuildConfig.PRACTICAL}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (session != null) TextButton(onClick = { vm.logout() }) { Text("Вийти") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (session == null) AuthScreen(vm)
                else {
                    if (BuildConfig.PRACTICAL >= 2)
                        Row(
                            Modifier.horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp)
                        ) {
                            listOf(
                                    "Огляд" to "home",
                                    "Журнал" to "records",
                                    "Мапа" to "map",
                                    "Сенсори" to "sensors",
                                    "Файли" to "files",
                                )
                                .filter { (_, route) ->
                                    when (route) {
                                        "map" -> BuildConfig.PRACTICAL >= 4
                                        "sensors" -> BuildConfig.PRACTICAL >= 5
                                        "files" -> BuildConfig.PRACTICAL >= 6
                                        else -> true
                                    }
                                }
                                .forEach { (title, route) ->
                                    TextButton(
                                        onClick = { nav.navigate(route) { launchSingleTop = true } }
                                    ) {
                                        Text(title)
                                    }
                                }
                        }
                    NavHost(nav, startDestination = "home", modifier = Modifier.weight(1f)) {
                        composable("home") { Dashboard(vm) }
                        composable("records") { RecordsScreen(vm, { nav.navigate("detail/${android.net.Uri.encode(it)}") }, { vm.selectedId.value = it; nav.navigate("map") }) }
                        composable("detail/{id}") { entry -> DetailScreen(vm, entry.arguments?.getString("id").orEmpty()) { vm.selectedId.value = it; nav.navigate("map") } }
                        composable("map") { MapScreen(vm) { vm.selectedId.value = it; nav.navigate("records") { launchSingleTop = true } } }
                        composable("sensors") { SensorsScreen(vm) }
                        composable("files") { FilesScreen(vm) }
                    }
                }
            }
        }
    }
}

/** Compose-компонент AuthScreen: читає стан і передає події у ViewModel або callback. */
@Composable
private fun AuthScreen(vm: MonitorViewModel) {
    var registration by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("operator@demo.ua") }
    var password by rememberSaveable { mutableStateOf("Demo12345") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    val busy by vm.busy.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (registration) "Реєстрація" else "Вхід до навчальної системи",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            "Спостереження за вигаданими постами. Демонстраційний API, без підключення до реальної АЕС."
        )
        if (registration)
            OutlinedTextField(
                name,
                { name = it },
                label = { Text("Ім’я") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        OutlinedTextField(
            email,
            { email = it },
            label = { Text("Email") },
            isError = email.isNotEmpty() && !email.contains("@"),
            supportingText = { Text(if (email.isNotEmpty() && !email.contains("@")) "Введіть коректний email" else "Формат: ім’я@домен.ua") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            password,
            { password = it },
            label = { Text("Пароль") },
            supportingText = { Text("Щонайменше 8 символів") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (registration)
            OutlinedTextField(
                confirm,
                { confirm = it },
                label = { Text("Підтвердження пароля") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        Button(
            onClick = {
                if (registration) vm.register(name, email, password, confirm)
                else vm.login(email, password)
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (registration) "Зареєструватися" else "Увійти")
        }
        TextButton(onClick = { registration = !registration }) {
            Text(if (registration) "Повернутися до входу" else "Створити обліковий запис")
        }
        Text(
            "Оператор: operator@demo.ua\nСпостерігач: viewer@demo.ua\nПароль обох: Demo12345",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Compose-компонент Dashboard: читає стан і передає події у ViewModel або callback. */
@Composable
private fun Dashboard(vm: MonitorViewModel) {
    val records by vm.all.collectAsStateWithLifecycle()
    val dark by vm.dark.collectAsStateWithLifecycle()
    var rate by rememberSaveable { mutableStateOf("0.12") }
    var hours by rememberSaveable { mutableStateOf("8") }
    var result by rememberSaveable { mutableStateOf("") }
    var scenario by rememberSaveable { mutableStateOf("Успіх") }
    var apiUrl by rememberSaveable { mutableStateOf("") }
    var simulated by rememberSaveable { mutableStateOf(.12f) }
    val animated by animateFloatAsState(simulated, label = "reading")
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Огляд станції",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Навчальна модель · дані не є вимірюваннями реальної станції",
            style = MaterialTheme.typography.bodySmall,
        )
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Демонстраційний показник", style = MaterialTheme.typography.labelLarge)
                Text(
                    "%.3f мкЗв/год".format(animated),
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text("Потужність: ${980+(simulated*100).roundToInt()} МВт")
                Button(onClick = { simulated = (.08 + Math.random() * .30).toFloat() }) {
                    Text("Оновити показник")
                }
            }
        }
        Text("Розрахунок накопиченої дози", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            rate,
            { rate = it },
            label = { Text("Потужність дози, мкЗв/год") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            hours,
            { hours = it },
            label = { Text("Тривалість, год") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                result =
                    runCatching {
                            "D = %.3f мкЗв"
                                .format(
                                    MonitorLogic.dose(
                                        rate.replace(',', '.').toDouble(),
                                        hours.replace(',', '.').toDouble(),
                                    )
                                )
                        }
                        .getOrElse { "Введіть скінченні невід’ємні числа" }
            }
        ) {
            Text("Розрахувати")
        }
        if (result.isNotBlank()) Text(result, fontWeight = FontWeight.Bold)
        Text("D = P × t. Розрахунок стосується сталого навчального показника.")
        if (BuildConfig.PRACTICAL >= 2) {
            Text("Записів у журналі: ${records.size}")
            Button(onClick = vm::seed) { Text("Додати демонстраційні пости") }
        }
        if (BuildConfig.PRACTICAL >= 3) {
            Text("API і обробка помилок", style = MaterialTheme.typography.titleLarge)
            Choice(
                "Сценарій",
                listOf("Успіх", "Без мережі", "HTTP 503", "Хибний JSON", "Сеанс завершено"),
                scenario,
            ) {
                scenario = it; vm.repository.mock.scenario = it
            }
            Button(onClick = vm::sync) { Text("Синхронізувати API") }
        }
        if (BuildConfig.PRACTICAL >= 3) {
            OutlinedTextField(
                apiUrl,
                { apiUrl = it },
                label = { Text("URL реального API або порожньо для mock") },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { vm.configureApi(apiUrl) }) { Text("Застосувати джерело API") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(dark, vm::setDark)
            Text("Темна тема", Modifier.padding(start = 12.dp))
        }
    }
}

/** Compose-компонент Choice: читає стан і передає події у ViewModel або callback. */
@Composable
private fun Choice(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label: $selected") }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item) },
                    onClick = {
                        onSelect(item)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Compose-компонент RecordsScreen: читає стан і передає події у ViewModel або callback. */
@Composable
private fun RecordsScreen(vm: MonitorViewModel, details: (String) -> Unit, map: (String) -> Unit) {
    val records by vm.filtered.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val type by vm.type.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val selected by vm.selectedId.collectAsStateWithLifecycle()
    val status by vm.statusFilter.collectAsStateWithLifecycle()
    val power by vm.minimumPower.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Measurement?>(null) }
    var showForm by remember { mutableStateOf(false) }
    if (showForm)
        MeasurementForm(editing, { showForm = false }) {
            vm.save(it)
            showForm = false
        }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { Text("Журнал постів", style = MaterialTheme.typography.headlineMedium) }
        if (BuildConfig.PRACTICAL >= 2) {
            item {
                OutlinedTextField(
                    query,
                    { vm.query.value = it },
                    label = { Text("Пошук за назвою") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Choice("Тип", listOf("Усі", "Периметр", "Зал", "Контроль"), type) {
                        vm.type.value = it
                    }
                    Spacer(Modifier.width(8.dp))
                    Choice("Порядок", listOf("Дата", "Назва", "Тип", "Показник", "Потужність"), sort) {
                        vm.sort.value = it
                    }
                }
            }
        }
        item {
            Choice("Стан", listOf("Усі", "Поріг перевищено", "У межах порога"), status) { vm.statusFilter.value = it }
            OutlinedTextField(power, { vm.minimumPower.value = it }, label = { Text("Мінімальна потужність, МВт") }, singleLine = true)
        }
        item {
            if (session?.role == "operator")
                Button(
                    onClick = {
                        editing = null
                        showForm = true
                    }
                ) {
                    Text("Новий запис")
                }
        }
        if (records.isEmpty())
            item {
                Text(
                    "Записів немає. Додайте пост або змініть фільтри.",
                    Modifier.padding(vertical = 20.dp),
                )
            }
        items(records, key = { it.id }) { m ->
            Card(Modifier.fillMaxWidth().clickable { details(m.id) }, colors = CardDefaults.cardColors(containerColor = if (selected == m.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row {
                        Text(m.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(m.type, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        "%.3f мкЗв/год · %.1f МВт".format(m.doseRate, m.powerMw),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                            .format(Date(m.timestamp))
                    )
                    Text("${m.type}: навчальний пост контролю радіаційного фону")
                    TextButton(onClick = { details(m.id) }) { Text("Деталі ${m.name}") }
                    if (BuildConfig.PRACTICAL >= 4) TextButton(onClick = { map(m.id) }) { Text("На мапі ${m.name}") }
                    if (m.doseRate > MonitorLogic.DEMO_THRESHOLD)
                        Text(
                            "Вище навчального порога 0,30",
                            color = MaterialTheme.colorScheme.error,
                        )
                    if (session?.role == "operator")
                        Row {
                            TextButton(
                                onClick = {
                                    editing = m
                                    showForm = true
                                }
                            ) {
                                Text("Редагувати")
                            }
                            TextButton(onClick = { vm.delete(m) }) { Text("Видалити") }
                        }
                }
            }
        }
    }
}

/** Compose-компонент MeasurementForm: читає стан і передає події у ViewModel або callback. */
@Composable
private fun MeasurementForm(
    initial: Measurement?,
    dismiss: () -> Unit,
    save: (Measurement) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: "Периметр") }
    var rate by remember { mutableStateOf(initial?.doseRate?.toString() ?: "0.12") }
    var power by remember { mutableStateOf(initial?.powerMw?.toString() ?: "1000") }
    var lat by remember { mutableStateOf(initial?.latitude?.toString() ?: "50.45") }
    var lon by remember { mutableStateOf(initial?.longitude?.toString() ?: "30.52") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (initial == null) "Новий пост" else "Редагування поста") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("Назва поста") },
                    singleLine = true,
                )
                Choice("Тип", listOf("Периметр", "Зал", "Контроль"), type) { type = it }
                OutlinedTextField(
                    rate,
                    { rate = it },
                    label = { Text("мкЗв/год") },
                    singleLine = true,
                )
                OutlinedTextField(power, { power = it }, label = { Text("МВт") }, singleLine = true)
                OutlinedTextField(lat, { lat = it }, label = { Text("Широта") }, singleLine = true)
                OutlinedTextField(lon, { lon = it }, label = { Text("Довгота") }, singleLine = true)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    try {
                        val m =
                            Measurement(
                                initial?.id ?: java.util.UUID.randomUUID().toString(),
                                name.trim(),
                                type,
                                rate.replace(',', '.').toDouble(),
                                power.replace(',', '.').toDouble(),
                                lat.replace(',', '.').toDouble(),
                                lon.replace(',', '.').toDouble(),
                            )
                        error = MonitorLogic.validate(m) ?: ""
                        if (error.isEmpty()) save(m)
                    } catch (_: NumberFormatException) {
                        error = "Перевірте числові поля"
                    }
                }
            ) {
                Text("Зберегти")
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Скасувати") } },
    )
}

/** Compose-компонент MapScreen: читає стан і передає події у ViewModel або callback. */
@Composable
private fun MapScreen(vm: MonitorViewModel, select: (String) -> Unit) {
    val c = LocalContext.current
    val records by vm.filtered.collectAsStateWithLifecycle()
    val selected by vm.selectedId.collectAsStateWithLifecycle()
    var location by remember { mutableStateOf<Location?>(null) }
    var permission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r
            ->
            permission = r.values.any { it }
            if (!permission)
                vm.message.value = "Доступ до геолокації відхилено. Пости залишаються доступними."
        }
    val map = remember {
        org.osmdroid.config.Configuration.getInstance().userAgentValue = c.packageName
        MapView(c).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(12.0)
            controller.setCenter(GeoPoint(50.45, 30.52))
        }
    }
    DisposableEffect(permission) {
        val manager = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener =
            object : LocationListener {
                override fun onLocationChanged(l: Location) {
                    location = l
                }

                override fun onProviderEnabled(p: String) {}

                override fun onProviderDisabled(p: String) {
                    vm.message.value = "GPS вимкнено"
                }
            }
        if (permission)
            try {
                val provider =
                    if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                        LocationManager.GPS_PROVIDER
                    else LocationManager.NETWORK_PROVIDER
                location = manager.getLastKnownLocation(provider)
                manager.requestLocationUpdates(provider, 3000, 5f, listener)
            } catch (e: Exception) {
                vm.message.value = "Увімкніть геолокацію пристрою"
            }
        onDispose { manager.removeUpdates(listener) }
    }
    DisposableEffect(map) {
        map.onResume()
        onDispose {
            map.onPause()
            map.onDetach()
        }
    }
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Навчальні пости на мапі", style = MaterialTheme.typography.titleLarge)
        Text(
            "Координати постів вигадані. © OpenStreetMap contributors",
            style = MaterialTheme.typography.bodySmall,
        )
        Button(
            onClick = {
                launcher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )
                )
            }
        ) {
            Text("Показати мою позицію")
        }
        Text(
            if (permission)
                location?.let { "GPS: %.5f, %.5f".format(it.latitude, it.longitude) }
                    ?: "Очікування GPS…"
            else "Геолокацію ще не дозволено"
        )
        selected?.let { id -> TextButton(onClick = { select(id) }) { Text("Відкрити вибрану картку") } }
        AndroidView(
            factory = { map },
            modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
            update = { v ->
                v.overlays.clear()
                records.forEach { m ->
                    v.overlays.add(
                        Marker(v).apply {
                            position = GeoPoint(m.latitude, m.longitude)
                            title = m.name
                            snippet = "${m.type}: ${m.doseRate} мкЗв/год · ${m.powerMw} МВт"
                            setOnMarkerClickListener { marker, _ -> vm.selectedId.value = m.id; marker.showInfoWindow(); true }
                            if (m.id == selected) { v.controller.setCenter(position); showInfoWindow() }
                        }
                    )
                }
                location?.let { l ->
                    v.overlays.add(
                        Marker(v).apply {
                            position = GeoPoint(l.latitude, l.longitude)
                            title = "Моя позиція"
                        }
                    )
                }
                v.invalidate()
            },
        )
    }
}

/** Інтерактивний Canvas: масштаб і вибір точки спільні для лінії та стовпців. */
@Composable
private fun DataChart(values: List<Double>, bars: Boolean, color: Color = Green) {
    var zoom by remember { mutableStateOf(1f) }
    var selected by remember { mutableStateOf(-1) }
    val visible = values.takeLast((values.size / zoom).roundToInt().coerceAtLeast(2))
    val maximum = (visible.maxOrNull() ?: 1.0).coerceAtLeast(.001)
    Column {
        Canvas(
            Modifier.fillMaxWidth()
                .height(180.dp)
                .semantics { contentDescription = "Графік показників" }
                .pointerInput(visible) {
                    detectTapGestures { p ->
                        selected =
                            ((p.x / size.width) * (visible.size - 1))
                                .roundToInt()
                                .coerceIn(0, (visible.size - 1).coerceAtLeast(0))
                    }
                }
        ) {
            drawLine(
                Color.Gray,
                Offset(0f, size.height - 8),
                Offset(size.width, size.height - 8),
                2f,
            )
            if (visible.isNotEmpty()) {
                val path = Path()
                visible.forEachIndexed { i, value ->
                    val x =
                        if (visible.size == 1) size.width / 2
                        else i * size.width / (visible.size - 1)
                    val y = (size.height - 12) * (1 - value / maximum).toFloat() + 4
                    if (bars)
                        drawLine(
                            color,
                            Offset(x, size.height - 8),
                            Offset(x, y),
                            (size.width / visible.size * .5f).coerceAtMost(30f),
                        )
                    else {
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    if (visible.size == 1) drawCircle(color, 5f, Offset(x, y))
                    if (i == selected) drawCircle(Color(0xFFCB6B32), 7f, Offset(x, y))
                }
                if (!bars) drawPath(path, color, style = Stroke(4f, cap = StrokeCap.Round))
            }
        }
        if (selected in visible.indices) Text("Точка ${selected+1}: %.3f".format(visible[selected]))
        else
            Text(
                "Натисніть на графік, щоб переглянути значення",
                style = MaterialTheme.typography.bodySmall,
            )
        Text("Масштаб: %.1f×".format(zoom))
        Slider(
            zoom,
            {
                zoom = it
                selected = -1
            },
            valueRange = 1f..4f,
        )
    }
}

/** Compose-компонент Gauge: читає стан і передає події у ViewModel або callback. */
@Composable
private fun Gauge(value: Double?, color: Color = Green) {
    val animated by
        animateFloatAsState(((value ?: 0.0) / 20).toFloat().coerceIn(0f, 1f), label = "sensor")
    Canvas(
        Modifier.fillMaxWidth().height(120.dp).semantics {
            contentDescription = "Індикатор прискорення"
        }
    ) {
        drawArc(Color.LightGray, 180f, 180f, false, style = Stroke(20f, cap = StrokeCap.Round))
        drawArc(color, 180f, 180f * animated, false, style = Stroke(20f, cap = StrokeCap.Round))
    }
}

/** Compose-компонент SensorsScreen: читає стан і передає події у ViewModel або callback. */
@Composable
private fun SensorsScreen(vm: MonitorViewModel) {
    val acceleration by vm.acceleration.collectAsStateWithLifecycle()
    val records by vm.all.collectAsStateWithLifecycle()
    val enabled by vm.notifications.collectAsStateWithLifecycle()
    val history by vm.sensorHistory.collectAsStateWithLifecycle()
    var bars by remember { mutableStateOf(false) }
    var alternate by remember { mutableStateOf(false) }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vm.setNotifications(granted)
            if (!granted) vm.message.value = "Сповіщення заборонено в системі"
        }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Сенсори й події", style = MaterialTheme.typography.headlineMedium)
        Text("Акселерометр вимірює прискорення телефону, а не радіацію.")
        Text(
            acceleration?.let { "%.3f м/с²".format(it) } ?: "Датчик недоступний або ще немає даних",
            style = MaterialTheme.typography.headlineSmall,
        )
        Gauge(acceleration, if (alternate) Color(0xFF5978B5) else Green)
        TextButton(onClick = { alternate = !alternate }) { Text("Змінити колір індикатора") }
        Text("Прискорення в реальному часі", style = MaterialTheme.typography.titleLarge)
        DataChart(history, false)
        SensorExtras(vm)
        Text("Навчальна енергія за 1 год: МВт·год", style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(bars, { bars = it })
            Text(if (bars) "Стовпці" else "Лінія")
        }
        DataChart(records.map { it.powerMw }, bars)
        DataChart(records.map { it.powerMw }, !bars)
        if(records.isNotEmpty()) Text("Середнє: %.1f; пікове: %.1f МВт·год".format(records.map { it.powerMw }.average(), records.maxOf { it.powerMw }))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                enabled,
                { value ->
                    if (value && Build.VERSION.SDK_INT >= 33)
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else vm.setNotifications(value)
                },
            )
            Text("Локальні сповіщення")
        }
        Button(onClick = { vm.schedule() }) { Text("Нагадати через 15 секунд") }
        Text(
            "WorkManager виконає нагадування не раніше заданого часу; ОС може відкласти його.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Compose-компонент FilesScreen: читає стан і передає події у ViewModel або callback. */
@Composable
private fun FilesScreen(vm: MonitorViewModel) {
    val c = LocalContext.current
    val files by vm.documents.collectAsStateWithLifecycle()
    val records by vm.filtered.collectAsStateWithLifecycle()
    val allRecords by vm.all.collectAsStateWithLifecycle()
    val selectedPost by vm.selectedId.collectAsStateWithLifecycle()
    val period by vm.periodHours.collectAsStateWithLifecycle()
    var fileType by rememberSaveable { mutableStateOf("Усі") }
    var fileSort by rememberSaveable { mutableStateOf("Дата") }
    var preview by remember { mutableStateOf<File?>(null) }
    var renaming by remember { mutableStateOf<File?>(null) }
    var newName by remember { mutableStateOf("") }
    preview?.let { DocumentPreview(it) { preview = null } }
    renaming?.let { file -> AlertDialog(onDismissRequest = { renaming=null }, title = { Text("Перейменування") }, text = { OutlinedTextField(newName,{newName=it},label={Text("Назва без розширення")}) }, confirmButton={TextButton(onClick={vm.renameDocument(file,newName);renaming=null}){Text("Зберегти назву")}},dismissButton={TextButton(onClick={renaming=null}){Text("Скасувати")}}) }
    val resources by vm.resources.collectAsStateWithLifecycle()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(vm::addDocument)
        }
    var exportName by remember { mutableStateOf("monitor-report.pdf") }
    val saver =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            if (uri != null)
                vm.task {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        c.contentResolver.openOutputStream(uri)!!.use { out ->
                            File(c.filesDir, exportName).inputStream().use { it.copyTo(out) }
                        }
                    }
                    vm.message.value = "Файл експортовано"
                }
        }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Файли та ресурси", style = MaterialTheme.typography.headlineMedium)
        for (format in listOf("csv", "json")) {
            Text(format.uppercase(), style = MaterialTheme.typography.titleLarge)
            Row {
                Button(onClick = { vm.export(format) }) { Text("Записати") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.importFile(format) }) { Text("Прочитати") }
            }
            TextButton(
                onClick = {
                    exportName = "measurements.$format"
                    if (File(c.filesDir, exportName).exists()) saver.launch(exportName)
                    else vm.message.value = "Спочатку запишіть файл"
                }
            ) {
                Text("Експортувати $format")
            }
        }
        Choice("Період, год",listOf("Увесь час","1","24","168"),period) {vm.periodHours.value=it}
        Text("Дані з журналу після читання CSV/JSON; фільтри спільні з журналом")
        DataChart(records.map {it.doseRate},false)
        DataChart(records.map {it.doseRate},true)
        Button(onClick = vm::pdf) { Text("Створити PDF-звіт") }
        TextButton(
            onClick = {
                exportName = "monitor-report.pdf"
                if (File(c.filesDir, exportName).exists()) saver.launch(exportName)
                else vm.message.value = "Спочатку створіть PDF"
            }
        ) {
            Text("Експортувати PDF")
        }
        Choice("Пост для вкладень",listOf("Без поста") + allRecords.map {it.id}, selectedPost ?: "Без поста") {vm.selectedId.value=if(it=="Без поста") null else it}
        Choice("Тип файлу",listOf("Усі","jpg","png","pdf","docx"),fileType){fileType=it}
        Choice("Сортування файлів",listOf("Дата","Тип"),fileSort){fileSort=it}
        Button(onClick = { picker.launch(arrayOf("image/jpeg", "image/png", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) }) {
            Text("Додати фото або документ")
        }
        val displayedFiles=files.filter { (fileType=="Усі" || it.extension==fileType) && (selectedPost==null || vm.documentPost(it)==selectedPost) }
        (if(fileSort=="Тип") displayedFiles.sortedBy {it.extension} else displayedFiles.sortedByDescending {it.lastModified()}).forEach { file ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(file.name)
                    Text("Пост: ${vm.documentPost(file)} · ${Date(file.lastModified())}",style=MaterialTheme.typography.bodySmall)
                    TextButton(onClick={renaming=file;newName=file.nameWithoutExtension}){Text("Перейменувати")}
                    if(file.extension in listOf("pdf","docx")) TextButton(onClick={preview=file}){Text("Переглянути документ")}
                    if (file.extension in listOf("png", "jpg"))
                        coil.compose.AsyncImage(
                            model = file,
                            contentDescription = "Прикріплене фото",
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                        )
                    else Text("${file.extension.uppercase()} · ${file.length()/1024} КБ")
                }
            }
        }
        Text("Ресурси пристрою", style = MaterialTheme.typography.titleLarge)
        Text("CPU процесу: %.1f %% одного ядра".format(resources.cpu))
        LinearProgressIndicator(
            progress = { (resources.cpu / 100).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Пам’ять процесу (PSS): %.1f МБ".format(resources.memoryMb))
        Text("Батарея: ${if(resources.battery in 0..100)"${resources.battery}%" else "недоступна"}")
        Gauge(if (resources.battery in 0..100) resources.battery / 5.0 else null)
    }
}

/** Route argument selects the entity; Room Flow keeps details and history current. */
@Composable
private fun DetailScreen(vm: MonitorViewModel, id: String, map: (String) -> Unit) {
    val rows by vm.all.collectAsStateWithLifecycle()
    val history by remember(id) { vm.history(id) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val m = rows.find { it.id == id }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Деталі поста", style = MaterialTheme.typography.headlineMedium)
        if (m == null) Text("Пост видалено або він ще завантажується") else {
            Text(m.name); Text("Тип: ${m.type}"); Text("Показник: ${m.doseRate} мкЗв/год; потужність: ${m.powerMw} МВт")
            Text("Координати: ${m.latitude}, ${m.longitude}")
            if (BuildConfig.PRACTICAL >= 4) Button(onClick = { map(id) }) { Text("Показати пост на мапі") }
            Text("Журнал змін (Room, зв’язок один-до-багатьох)")
            history.take(20).forEach { Text("${it.action} · ${Date(it.changedAt)}") }
        }
    }
}
