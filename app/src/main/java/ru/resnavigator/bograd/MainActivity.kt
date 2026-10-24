package ru.resnavigator.bograd

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.*

data class FieldObject(
    val id: String,
    val name: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val description: String = "",
    val access: String = ""
)

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("res_nav", Context.MODE_PRIVATE) }
    private val objects = mutableStateListOf<FieldObject>()
    private var currentLocation by mutableStateOf<Location?>(null)
    private var locationStatus by mutableStateOf("Местоположение ещё не определено")
    private var selectedId by mutableStateOf<String?>(null)
    private var search by mutableStateOf("")
    private var showAdd by mutableStateOf(false)
    private var status by mutableStateOf("Данные хранятся на телефоне")
    private lateinit var lm: LocationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        loadObjects()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppScreen()
                }
            }
        }
    }

    private fun loadObjects() {
        val arr = JSONArray(prefs.getString("objects", "[]"))
        objects.clear()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            objects.add(FieldObject(
                o.optString("id"), o.optString("name"), o.optString("type", "Объект"),
                o.optDouble("lat"), o.optDouble("lon"), o.optString("description"), o.optString("access")
            ))
        }
    }

    private fun saveObjects() {
        val arr = JSONArray()
        objects.forEach { f ->
            arr.put(JSONObject().put("id", f.id).put("name", f.name).put("type", f.type)
                .put("lat", f.lat).put("lon", f.lon).put("description", f.description).put("access", f.access))
        }
        prefs.edit().putString("objects", arr.toString()).apply()
    }

    private fun requestLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationStatus = "Разрешите доступ к местоположению"
            return
        }
        try {
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (last != null) currentLocation = last
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 2f, object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    currentLocation = location
                    locationStatus = "GPS: %.5f, %.5f".format(Locale.US, location.latitude, location.longitude)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, statusCode: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) { locationStatus = "Включите GPS в настройках телефона" }
            })
            locationStatus = currentLocation?.let { "GPS: %.5f, %.5f".format(Locale.US, it.latitude, it.longitude) }
                ?: "Ожидаю GPS-сигнал… Выйдите на открытое место"
        } catch (e: Exception) {
            locationStatus = "Не удалось получить GPS: проверьте разрешения и настройки"
        }
    }

    private fun addObject(obj: FieldObject) {
        objects.removeAll { it.id == obj.id }
        objects.add(0, obj)
        saveObjects()
        selectedId = obj.id
        status = "Объект сохранён"
    }

    private fun exportCsv(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { w ->
                w.write("id,name,type,latitude,longitude,description,access\n")
                objects.forEach { o ->
                    w.write(listOf(o.id,o.name,o.type,o.lat.toString(),o.lon.toString(),o.description,o.access)
                        .joinToString(",") { csv(it) } + "\n")
                }
            }
            status = "Экспортировано объектов: ${objects.size}"
        } catch (e: Exception) { status = "Ошибка экспорта файла" }
    }

    private fun csv(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

    private fun importCsv(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
            val lines = text.lines().filter { it.isNotBlank() }
            val rows = lines.drop(if (lines.firstOrNull()?.contains("latitude", true) == true) 1 else 0)
            var count = 0
            rows.forEach { line ->
                val c = parseCsvLine(line)
                if (c.size >= 5) {
                    val lat = c[3].toDoubleOrNull()
                    val lon = c[4].toDoubleOrNull()
                    if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
                        val id = c[0].ifBlank { "OBJ-${System.currentTimeMillis()}-$count" }
                        objects.removeAll { it.id == id }
                        objects.add(FieldObject(id, c[1], c[2].ifBlank { "Объект" }, lat, lon,
                            c.getOrElse(5){""}, c.getOrElse(6){""}))
                        count++
                    }
                }
            }
            saveObjects()
            status = "Импортировано объектов: $count"
        } catch (e: Exception) { status = "Не удалось прочитать CSV. Проверьте формат файла." }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (ch == '"') {
                if (quoted && i + 1 < line.length && line[i+1] == '"') { cur.append('"'); i++ }
                else quoted = !quoted
            } else if (ch == ',' && !quoted) {
                result.add(cur.toString()); cur.clear()
            } else cur.append(ch)
            i++
        }
        result.add(cur.toString())
        return result
    }

    private fun distanceAndBearing(lat: Double, lon: Double): Pair<String, String>? {
        val here = currentLocation ?: return null
        val dest = Location("object").apply { latitude = lat; longitude = lon }
        val meters = here.distanceTo(dest)
        val bearing = (here.bearingTo(dest) + 360) % 360
        val dist = if (meters < 1000) "${meters.roundToInt()} м" else "%.2f км".format(Locale.US, meters / 1000)
        val dirs = listOf("С", "СВ", "В", "ЮВ", "Ю", "ЮЗ", "З", "СЗ")
        val direction = dirs[((bearing + 22.5) / 45).toInt() % 8]
        return dist to "$direction · ${bearing.roundToInt()}°"
    }

    @Composable
    private fun AppScreen() {
        val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) requestLocation()
            else locationStatus = "Разрешение на GPS не выдано"
        }
        val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            if (uri != null) exportCsv(uri)
        }
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importCsv(uri)
        }
        val filtered = objects.filter {
            val q = search.trim().lowercase()
            q.isBlank() || listOf(it.id,it.name,it.type,it.description,it.access).any { s -> s.lowercase().contains(q) }
        }
        Scaffold(topBar = {
            TopAppBarCompat()
        }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ПОЛЕВОЙ НАВИГАТОР", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(locationStatus, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                                    ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) requestLocation()
                                else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            }, modifier = Modifier.weight(1f)) { Text("Получить GPS") }
                            OutlinedButton(onClick = { showAdd = true }, modifier = Modifier.weight(1f)) { Text("＋ Объект") }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Найти опору, ТП, КТП, адрес…") }, singleLine = true)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportLauncher.launch("bograd_objects.csv") }, modifier = Modifier.weight(1f)) { Text("Экспорт CSV") }
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel")) }, modifier = Modifier.weight(1f)) { Text("Импорт CSV") }
                }
                Text("${filtered.size} объектов · $status", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(6.dp))
                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(if (objects.isEmpty()) "База пока пустая.\nДобавьте объект по GPS или импортируйте CSV." else "Ничего не найдено")
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(filtered, key = { it.id }) { obj ->
                            val selected = selectedId == obj.id
                            Card(Modifier.fillMaxWidth().clickable { selectedId = if (selected) null else obj.id },
                                colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(obj.name.ifBlank { obj.id }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                        Text(obj.type, style = MaterialTheme.typography.labelMedium)
                                    }
                                    Text("ID: ${obj.id} · %.6f, %.6f".format(Locale.US, obj.lat, obj.lon), style = MaterialTheme.typography.bodySmall)
                                    if (obj.description.isNotBlank()) Text(obj.description, style = MaterialTheme.typography.bodyMedium)
                                    if (obj.access.isNotBlank()) Text("Подъезд: ${obj.access}", style = MaterialTheme.typography.bodySmall)
                                    if (selected) {
                                        val nav = distanceAndBearing(obj.lat, obj.lon)
                                        Text(nav?.let { "До объекта: ${it.first} · направление ${it.second}" }
                                            ?: "Нажмите «Получить GPS», чтобы рассчитать расстояние и направление.")
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            OutlinedButton(onClick = {
                                                addObject(obj.copy(id = obj.id, name = obj.name, description = obj.description, access = obj.access))
                                                status = "Карточка выбрана"
                                            }, modifier = Modifier.weight(1f)) { Text("Обновить") }
                                            OutlinedButton(onClick = {
                                                objects.removeAll { it.id == obj.id }; saveObjects()
                                                status = "Объект удалён"
                                            }, modifier = Modifier.weight(1f)) { Text("Удалить") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Text("Офлайн: поиск и база доступны без сети. Карта дорог не встроена.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.padding(vertical = 8.dp))
            }
        }
        if (showAdd) AddObjectDialog(
            onDismiss = { showAdd = false },
            current = currentLocation,
            onSave = { addObject(it); showAdd = false }
        )
    }

    @Composable
    private fun TopAppBarCompat() {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("РЭС Навигатор", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Боградский РЭС · Android · офлайн-база", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }

    @Composable
    private fun AddObjectDialog(onDismiss: () -> Unit, current: Location?, onSave: (FieldObject) -> Unit) {
        var id by remember { mutableStateOf("OBJ-${System.currentTimeMillis().toString().takeLast(6)}") }
        var name by remember { mutableStateOf("") }
        var type by remember { mutableStateOf("Опора") }
        var lat by remember { mutableStateOf(current?.latitude?.toString() ?: "") }
        var lon by remember { mutableStateOf(current?.longitude?.toString() ?: "") }
        var description by remember { mutableStateOf("") }
        var access by remember { mutableStateOf("") }
        var error by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Новый объект") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 540.dp)) {
                    Text("Если GPS ещё не получен, координаты можно ввести вручную.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(id, { id = it }, label = { Text("ID / номер опоры") }, singleLine = true)
                    OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true)
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Тип: $type") }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            listOf("Опора", "ТП", "КТП", "ЛЭП", "Адрес", "Другое").forEach { item ->
                                DropdownMenuItem(text = { Text(item) }, onClick = { type = item; expanded = false })
                            }
                        }
                    }
                    OutlinedTextField(lat, { lat = it }, label = { Text("Широта") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    OutlinedTextField(lon, { lon = it }, label = { Text("Долгота") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    OutlinedTextField(description, { description = it }, label = { Text("Описание") })
                    OutlinedTextField(access, { access = it }, label = { Text("Как подъехать") })
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                Button(onClick = {
                    val la = lat.replace(',', '.').toDoubleOrNull()
                    val lo = lon.replace(',', '.').toDoubleOrNull()
                    if (id.isBlank() || name.isBlank()) error = "Заполните ID и название"
                    else if (la == null || lo == null || la !in -90.0..90.0 || lo !in -180.0..180.0) error = "Проверьте координаты"
                    else onSave(FieldObject(id.trim(), name.trim(), type, la, lo, description.trim(), access.trim()))
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
        )
    }
}
