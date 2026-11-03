package ee.budilnik

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.media.RingtoneManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.IntentCompat
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val Bg = Color(0xFF0B1424); val Card = Color(0xFF14213A); val Acc = Color(0xFF3DDC97)
val Dim = Color(0xFF8A9BB8); val Txt = Color(0xFFEAF0FA); val OnAcc = Color(0xFF06210F)
val WD = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
val DC = listOf("П", "В", "С", "Ч", "П", "С", "В")

@Composable fun AppTheme(content: @Composable () -> Unit) = MaterialTheme(
    colorScheme = darkColorScheme(primary = Acc, onPrimary = OnAcc, background = Bg, onBackground = Txt, surface = Card,
        onSurface = Txt, surfaceVariant = Card, onSurfaceVariant = Dim, secondaryContainer = Acc, onSecondaryContainer = OnAcc),
    content = content)

fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
@Composable fun sw() = SwitchDefaults.colors(checkedTrackColor = Acc, checkedThumbColor = OnAcc,
    uncheckedTrackColor = Color(0xFF2A3A58), uncheckedThumbColor = Dim, uncheckedBorderColor = Color.Transparent)

@Composable fun App() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf<Alarm?>(null) }
    Box {
        Scaffold(containerColor = Bg, bottomBar = {
            NavigationBar(containerColor = Color(0xFF101C32)) {
                listOf("Будильник" to Icons.Outlined.Alarm, "Секундомер" to Icons.Outlined.Timer, "Таймер" to Icons.Outlined.HourglassEmpty)
                    .forEachIndexed { i, (t, ic) ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(ic, null) }, label = { Text(t) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Acc, selectedTextColor = Acc,
                                indicatorColor = Color.Transparent, unselectedIconColor = Dim, unselectedTextColor = Dim))
                    }
            }
        }) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) { 0 -> AlarmList { edit = it }; 1 -> Stopwatch(); else -> TimerScreen() }
            }
        }
        edit?.let { EditScreen(it) { edit = null } }
    }
}

@Composable fun AlarmList(onEdit: (Alarm) -> Unit) {
    val c = LocalContext.current
    val list = remember(Bus.tick) { Store.load(c).sortedBy { it.hour * 60 + it.minute } }
    val nx = remember(Bus.tick) { list.filter { it.enabled }.mapNotNull { it.next() }.minOrNull() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(20.dp, 24.dp, 20.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column {
                    Text("Следующий звонок", color = Dim, fontSize = 14.sp)
                    Text(nx?.let { cap(it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale("ru"))) + " %02d:%02d".format(it.hour, it.minute) } ?: "Нет",
                        color = Txt, fontSize = 44.sp, fontWeight = FontWeight.SemiBold)
                    nx?.let { val m = Duration.between(LocalDateTime.now(), it).toMinutes(); Text("через ${m / 60} ч ${m % 60} мин", color = Acc, fontSize = 14.sp) }
                }
            }
            item { SetupCard() }
            items(list, key = { it.id }) { a -> AlarmCard(a, { onEdit(a) }) { on -> upsert(c, a.copy(enabled = on)) } }
        }
        FloatingActionButton(onClick = { onEdit(Alarm(0, 7, 0, days = setOf(1, 2, 3, 4, 5))) }, containerColor = Acc, contentColor = OnAcc,
            shape = CircleShape, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) { Icon(Icons.Filled.Add, "Добавить будильник") }
    }
}

@Composable fun SetupCard() {
    val c = LocalContext.current
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { Bus.tick++ }
    val pkg = Uri.parse("package:" + c.packageName)
    val tick = Bus.tick
    val nm = c.getSystemService(NotificationManager::class.java)
    val notif = Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val bat = c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)
    val fsi = Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()
    if (notif && bat && fsi && tick >= 0) return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Чтобы будильник точно звонил, нужны пара разрешений", color = Txt, fontSize = 14.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            when {
                !notif -> perm.launch(Manifest.permission.POST_NOTIFICATIONS)
                !fsi -> c.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
                else -> c.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg))
            }
        }) { Text("Настроить", color = Acc) }
    }
}

fun datesText(a: Alarm): String {
    if (a.monthly) return "Каждое " + a.dates.map { LocalDate.ofEpochDay(it).dayOfMonth }.toSortedSet().joinToString(", ") + " число"
    val f = DateTimeFormatter.ofPattern("d MMM", Locale("ru"))
    val l = a.dates.sorted().map { LocalDate.ofEpochDay(it).format(f) }
    return if (l.isEmpty()) "Даты не выбраны" else l.take(4).joinToString(" · ") + if (l.size > 4) " …" else ""
}

@Composable fun AlarmCard(a: Alarm, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).clickable(onClick = onClick).padding(18.dp, 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("%02d:%02d".format(a.hour, a.minute), color = if (a.enabled) Txt else Dim, fontSize = 44.sp, fontWeight = FontWeight.Light)
            if (a.label.isNotBlank()) Text(a.label, color = Dim, fontSize = 14.sp)
            when (a.mode) {
                Mode.WEEKLY -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DC.forEachIndexed { i, d ->
                        val on = (i + 1) in a.days
                        Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) Acc else Color.Transparent), Alignment.Center) {
                            Text(d, color = if (on) OnAcc else Dim, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                Mode.DATES -> Text(datesText(a), color = Acc, fontSize = 14.sp)
                Mode.ONCE -> Text("Один раз", color = Acc, fontSize = 14.sp)
            }
        }
        Switch(a.enabled, onToggle, colors = sw())
    }
}

@Composable fun Seg(items: List<String>, sel: Int, on: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { i, t ->
            Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp)).background(if (i == sel) Acc else Color.Transparent).clickable { on(i) }, Alignment.Center) {
                Text(t, color = if (i == sel) OnAcc else Dim, fontSize = 15.sp, fontWeight = if (i == sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun TimeDialog(h: Int, m: Int, onOk: (Int, Int) -> Unit, onCancel: () -> Unit) {
    val st = rememberTimePickerState(h, m, true)
    AlertDialog(onDismissRequest = onCancel, containerColor = Card,
        confirmButton = { TextButton({ onOk(st.hour, st.minute) }) { Text("ОК", color = Acc) } },
        dismissButton = { TextButton(onCancel) { Text("Отмена", color = Dim) } },
        text = { Box(Modifier.fillMaxWidth(), Alignment.Center) { TimePicker(st) } })
}

@Composable fun EditScreen(init: Alarm, onClose: () -> Unit) {
    val c = LocalContext.current
    var a by remember { mutableStateOf(init) }
    var tp by remember { mutableStateOf(false) }
    var ym by remember { mutableStateOf(YearMonth.now()) }
    BackHandler(onBack = onClose)
    val canSave = a.next() != null // без дней/дат (или только с прошедшими датами) будильник никогда не зазвонит
    val audioPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val u = r.data?.let { IntentCompat.getParcelableExtra(it, RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java) }
            val def = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            a = a.copy(tone = if (u == null || u == def) "" else u.toString())
            // Звуки с карты памяти читаются только с разрешением на аудио; системные — без него
            if (u != null && u.toString().startsWith("content://media/external"))
                audioPerm.launch(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    val toneName = remember(a.tone) {
        if (a.tone.isBlank()) "По умолчанию"
        else try { RingtoneManager.getRingtone(c, Uri.parse(a.tone))?.getTitle(c) ?: "Своя" } catch (e: Exception) { "Своя" }
    }
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClose) { Text("Отмена", color = Dim, fontSize = 16.sp) }
            TextButton({ upsert(c, a.copy(enabled = true)); onClose() }, enabled = canSave) {
                Text("Сохранить", color = if (canSave) Acc else Dim, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text("%02d:%02d".format(a.hour, a.minute), color = Txt, fontSize = 84.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable { tp = true })
        Seg(listOf("Дни недели", "Даты", "Один раз"), a.mode.ordinal) { a = a.copy(mode = Mode.values()[it]) }
        when (a.mode) {
            Mode.WEEKLY -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    WD.forEachIndexed { i, d ->
                        val on = (i + 1) in a.days
                        Box(Modifier.size(44.dp).clip(CircleShape).background(if (on) Acc else Card)
                            .clickable { a = a.copy(days = if (on) a.days - (i + 1) else a.days + (i + 1)) }, Alignment.Center) {
                            Text(d, color = if (on) OnAcc else Txt, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                Text(if (a.days.isEmpty()) "Выбери дни" else "Звонит по " + a.days.sorted().joinToString(", ") { WD[it - 1].lowercase() },
                    color = Acc, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            Mode.DATES -> {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(10.dp, 8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton({ ym = ym.minusMonths(1) }) { Text("‹", color = Txt, fontSize = 22.sp) }
                        Text(cap(ym.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale("ru"))) + " " + ym.year, color = Txt, fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                        TextButton({ ym = ym.plusMonths(1) }) { Text("›", color = Txt, fontSize = 22.sp) }
                    }
                    Row { WD.forEach { Text(it, color = Dim, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) } }
                    val off = ym.atDay(1).dayOfWeek.value - 1
                    val len = ym.lengthOfMonth()
                    for (r in 0 until (off + len + 6) / 7) Row(Modifier.fillMaxWidth()) {
                        for (col in 0..6) {
                            val d = r * 7 + col - off + 1
                            Box(Modifier.weight(1f).height(44.dp), Alignment.Center) {
                                if (d in 1..len) {
                                    val ep = ym.atDay(d).toEpochDay(); val on = ep in a.dates
                                    Box(Modifier.size(40.dp).clip(CircleShape).background(if (on) Acc else Color.Transparent)
                                        .clickable { a = a.copy(dates = if (on) a.dates - ep else a.dates + ep) }, Alignment.Center) {
                                        Text("$d", color = if (on) OnAcc else Txt, fontSize = 16.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                    }
                }
                Text(datesText(a), color = Acc, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(horizontal = 18.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Повторять каждый месяц", color = Txt, modifier = Modifier.weight(1f))
                    Switch(a.monthly, { a = a.copy(monthly = it) }, colors = sw())
                }
            }
            Mode.ONCE -> Text(a.next()?.let { if (it.toLocalDate() == LocalDate.now()) "Сработает сегодня" else "Сработает завтра" } ?: "",
                color = Acc, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(horizontal = 18.dp, vertical = 6.dp)) {
            TextField(a.label, { a = a.copy(label = it) }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Acc, unfocusedIndicatorColor = Dim))
            Row(Modifier.fillMaxWidth().height(56.dp).clickable {
                pick.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Мелодия")
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        if (a.tone.isBlank()) RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) else Uri.parse(a.tone))
                })
            }, verticalAlignment = Alignment.CenterVertically) {
                Text("Мелодия", color = Txt, modifier = Modifier.weight(1f))
                Text(toneName, color = Dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            }
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Вибрация", color = Txt, modifier = Modifier.weight(1f))
                Switch(a.vibrate, { a = a.copy(vibrate = it) }, colors = sw())
            }
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Выключать, решив пример", color = Txt, modifier = Modifier.weight(1f))
                Switch(a.math, { a = a.copy(math = it) }, colors = sw())
            }
        }
        if (init.id != 0) TextButton({ remove(c, init.id); onClose() }) { Text("Удалить", color = Color(0xFFFF6B6B)) }
        Spacer(Modifier.height(24.dp))
    }
    if (tp) TimeDialog(a.hour, a.minute, { h, m -> a = a.copy(hour = h, minute = m); tp = false }) { tp = false }
}

@Composable fun Round(t: String, bg: Color, fg: Color, on: () -> Unit) {
    Box(Modifier.size(88.dp).clip(CircleShape).background(bg).clickable(onClick = on), Alignment.Center) {
        Text(t, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

// Каждая цифра в ячейке одинаковой ширины, иначе при смене цифр строка дёргается влево-вправо
@Composable fun Mono(t: String, size: TextUnit, modifier: Modifier = Modifier) {
    val w = with(LocalDensity.current) { (size.toPx() * 0.6f).toDp() }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        t.forEach { ch ->
            if (ch.isDigit()) Box(Modifier.width(w), Alignment.Center) {
                Text("$ch", color = Txt, fontSize = size, fontWeight = FontWeight.Light, maxLines = 1, softWrap = false,
                    modifier = Modifier.wrapContentWidth(unbounded = true))
            } else Text("$ch", color = Txt, fontSize = size, fontWeight = FontWeight.Light, maxLines = 1, softWrap = false)
        }
    }
}

fun fmt(ms: Long) = "%02d:%02d.%02d".format(ms / 60000, ms / 1000 % 60, ms / 10 % 100)

object SW {
    var run by mutableStateOf(false); var base by mutableLongStateOf(0L); var t0 by mutableLongStateOf(0L)
    val laps = mutableStateListOf<Long>()
}

@Composable fun Stopwatch() {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(SW.run) { while (SW.run) { now = SystemClock.elapsedRealtime(); delay(30) } }
    val e = SW.base + if (SW.run) now - SW.t0 else 0L
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(64.dp))
        Mono(fmt(e), 64.sp)
        Spacer(Modifier.height(28.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Round(if (SW.run) "Круг" else "Сброс", Card, Txt) { if (SW.run) SW.laps.add(e) else { SW.base = 0L; SW.laps.clear() } }
            Round(if (SW.run) "Стоп" else "Старт", Acc, OnAcc) {
                if (SW.run) { SW.base = e; SW.run = false } else { SW.t0 = SystemClock.elapsedRealtime(); now = SW.t0; SW.run = true }
            }
        }
        Spacer(Modifier.height(24.dp))
        LazyColumn {
            items(SW.laps.indices.reversed().toList()) { i ->
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Круг ${i + 1}", color = Dim, fontSize = 17.sp)
                    Text(fmt(SW.laps[i] - (if (i > 0) SW.laps[i - 1] else 0L)), color = Txt, fontSize = 17.sp)
                }
            }
        }
    }
}

object TM {
    var total by mutableLongStateOf(600_000L); var left by mutableLongStateOf(600_000L)
    var end by mutableLongStateOf(0L); var run by mutableStateOf(false)
}

@Composable fun NumField(label: String, v: String, mod: Modifier, on: (String) -> Unit) =
    TextField(v, { on(it.filter(Char::isDigit).take(2)) }, label = { Text(label) }, singleLine = true, modifier = mod,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Acc, unfocusedIndicatorColor = Dim))

@Composable fun TimerDialog(init: Long, onOk: (Long) -> Unit, onCancel: () -> Unit) {
    val s0 = init / 1000
    var h by remember { mutableStateOf((s0 / 3600).toString()) }
    var m by remember { mutableStateOf((s0 / 60 % 60).toString()) }
    var s by remember { mutableStateOf((s0 % 60).toString()) }
    val total = (h.toLongOrNull() ?: 0L) * 3600 + (m.toLongOrNull() ?: 0L) * 60 + (s.toLongOrNull() ?: 0L)
    AlertDialog(onDismissRequest = onCancel, containerColor = Card,
        title = { Text("Время таймера", color = Txt) },
        confirmButton = { TextButton({ onOk(total * 1000) }, enabled = total > 0) { Text("ОК", color = if (total > 0) Acc else Dim) } },
        dismissButton = { TextButton(onCancel) { Text("Отмена", color = Dim) } },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Часы", h, Modifier.weight(1f)) { h = it }
                NumField("Мин", m, Modifier.weight(1f)) { m = it }
                NumField("Сек", s, Modifier.weight(1f)) { s = it }
            }
        })
}

@Composable fun TimerScreen() {
    val c = LocalContext.current
    var dlg by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(TM.run) {
        while (TM.run) { now = SystemClock.elapsedRealtime(); if (TM.end - now <= 0) { TM.run = false; TM.left = TM.total }; delay(100) }
    }
    val rem = if (TM.run) maxOf(0L, TM.end - now) else TM.left
    val s = (rem + 999) / 1000
    if (dlg) TimerDialog(TM.total, { ms -> TM.total = ms; TM.left = ms; dlg = false }) { dlg = false }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(64.dp))
        Box(Modifier.size(260.dp), Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val st = Stroke(10.dp.toPx(), cap = StrokeCap.Round); val m = 5.dp.toPx()
                val sz = Size(size.width - 2 * m, size.height - 2 * m)
                drawArc(Card, 0f, 360f, false, topLeft = Offset(m, m), size = sz, style = st)
                drawArc(Acc, -90f, 360f * rem / TM.total, false, topLeft = Offset(m, m), size = sz, style = st)
            }
            Mono(if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60),
                if (s >= 3600) 44.sp else 60.sp, Modifier.clickable(enabled = !TM.run) { dlg = true })
        }
        Spacer(Modifier.height(12.dp))
        Text("Нажми на время, чтобы задать своё", color = Dim, fontSize = 13.sp)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 5, 10, 15).forEach { m ->
                val sel = TM.total == m * 60_000L
                Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(22.dp)).background(if (sel) Acc else Card)
                    .clickable(enabled = !TM.run) { TM.total = m * 60_000L; TM.left = TM.total }, Alignment.Center) {
                    Text("$m мин", color = if (sel) OnAcc else Txt, fontSize = 14.sp, maxLines = 1, softWrap = false)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Round("Сброс", Card, Txt) { TM.run = false; TM.left = TM.total; Scheduler.cancel(c, 100001) }
            Round(if (TM.run) "Пауза" else "Старт", Acc, OnAcc) {
                if (TM.run) { TM.left = maxOf(0L, TM.end - SystemClock.elapsedRealtime()); TM.run = false; Scheduler.cancel(c, 100001) }
                else if (TM.left > 0) {
                    TM.end = SystemClock.elapsedRealtime() + TM.left; now = SystemClock.elapsedRealtime(); TM.run = true
                    Scheduler.schedule(c, 100001, System.currentTimeMillis() + TM.left, "", false, "timer")
                }
            }
        }
    }
}

@Composable fun RingScreen(label: String, math: Boolean, snoozable: Boolean, onStop: () -> Unit, onSnooze: () -> Unit) {
    val t = remember { LocalTime.now() }
    val a = remember { (12..29).random() }
    val b = remember { (3..9).random() }
    var ans by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("%02d:%02d".format(t.hour, t.minute), color = Txt, fontSize = 88.sp, fontWeight = FontWeight.Light)
            if (label.isNotBlank()) Text(label, color = Dim, fontSize = 20.sp)
        }
        if (math) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("$a × $b = ?", color = Txt, fontSize = 32.sp)
            TextField(ans, { ans = it.filter(Char::isDigit) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                colors = TextFieldDefaults.colors(focusedContainerColor = Card, unfocusedContainerColor = Card))
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { if (!math || ans == (a * b).toString()) onStop() }, modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Acc, contentColor = OnAcc)) {
                Text(if (math) "Проверить" else "Выключить", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            if (snoozable) TextButton(onSnooze, Modifier.fillMaxWidth()) { Text("Отложить на 10 минут", color = Dim) }
        }
    }
}
