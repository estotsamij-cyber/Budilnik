package ee.budilnik

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.net.Uri
import android.os.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.*

object Bus { var tick by mutableIntStateOf(0) }

enum class Mode { WEEKLY, DATES, ONCE }

data class Alarm(
    val id: Int, val hour: Int = 7, val minute: Int = 0, val label: String = "",
    val mode: Mode = Mode.WEEKLY, val days: Set<Int> = emptySet(), val dates: Set<Long> = emptySet(),
    val monthly: Boolean = false, val math: Boolean = false, val enabled: Boolean = true,
    val tone: String = "", // URI мелодии; пусто = мелодия будильника по умолчанию
    val vibrate: Boolean = true,
)

fun Alarm.next(from: LocalDateTime = LocalDateTime.now()): LocalDateTime? {
    val t = LocalTime.of(hour, minute)
    fun at(d: LocalDate): LocalDateTime? = LocalDateTime.of(d, t).takeIf { it.isAfter(from) }
    val today = from.toLocalDate()
    return when (mode) {
        Mode.ONCE -> at(today) ?: at(today.plusDays(1))
        Mode.WEEKLY -> (0L..7L).firstNotNullOfOrNull { i -> today.plusDays(i).takeIf { it.dayOfWeek.value in days }?.let { at(it) } }
        Mode.DATES -> if (monthly) {
            val doms = dates.map { LocalDate.ofEpochDay(it).dayOfMonth }.toSet()
            (0L..400L).firstNotNullOfOrNull { i -> today.plusDays(i).takeIf { it.dayOfMonth in doms }?.let { at(it) } }
        } else dates.sorted().firstNotNullOfOrNull { at(LocalDate.ofEpochDay(it)) }
    }
}

private fun Alarm.toJson() = JSONObject().put("id", id).put("h", hour).put("m", minute).put("l", label)
    .put("mode", mode.name).put("days", JSONArray(days.toList())).put("dates", JSONArray(dates.toList()))
    .put("mo", monthly).put("math", math).put("on", enabled).put("tone", tone).put("vib", vibrate)

private fun JSONObject.toAlarm() = Alarm(
    getInt("id"), getInt("h"), getInt("m"), getString("l"), Mode.valueOf(getString("mode")),
    getJSONArray("days").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() },
    getJSONArray("dates").let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() },
    getBoolean("mo"), getBoolean("math"), getBoolean("on"), optString("tone", ""), optBoolean("vib", true),
)

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("alarms", Context.MODE_PRIVATE)
    fun load(c: Context): List<Alarm> {
        val a = JSONArray(sp(c).getString("list", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it).toAlarm() }
    }
    fun save(c: Context, l: List<Alarm>) { sp(c).edit().putString("list", JSONArray(l.map { it.toJson() }).toString()).apply() }
    fun nextId(c: Context): Int { val n = sp(c).getInt("nid", 1); sp(c).edit().putInt("nid", n + 1).apply(); return n }
}

object Scheduler {
    private fun am(c: Context) = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private fun pi(c: Context, id: Int, label: String, math: Boolean, kind: String, tone: String = "", vib: Boolean = true): PendingIntent =
        PendingIntent.getBroadcast(c, id, Intent(c, AlarmReceiver::class.java).putExtra("id", id).putExtra("label", label)
            .putExtra("math", math).putExtra("kind", kind).putExtra("tone", tone).putExtra("vib", vib), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun schedule(c: Context, id: Int, millis: Long, label: String, math: Boolean, kind: String, tone: String = "", vib: Boolean = true) {
        val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        am(c).setAlarmClock(AlarmManager.AlarmClockInfo(millis, show), pi(c, id, label, math, kind, tone, vib))
    }
    fun cancel(c: Context, id: Int) { am(c).cancel(pi(c, id, "", false, "alarm")) }
    fun set(c: Context, a: Alarm) {
        cancel(c, a.id)
        if (!a.enabled) return
        val t = a.next() ?: return
        schedule(c, a.id, t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), a.label, a.math, "alarm", a.tone, a.vibrate)
    }
    fun resetAll(c: Context) = Store.load(c).forEach { set(c, it) }
}

fun upsert(c: Context, a0: Alarm) {
    val a = if (a0.id == 0) a0.copy(id = Store.nextId(c)) else a0
    Store.save(c, Store.load(c).filter { it.id != a.id } + a)
    Scheduler.set(c, a); Bus.tick++
}

fun remove(c: Context, id: Int) {
    Store.save(c, Store.load(c).filter { it.id != id }); Scheduler.cancel(c, id); Bus.tick++
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.getStringExtra("kind") == "alarm") {
            val id = i.getIntExtra("id", 0)
            Store.load(c).firstOrNull { it.id == id }?.let { a ->
                val n = if (a.mode == Mode.ONCE || a.next() == null) a.copy(enabled = false) else a
                Store.save(c, Store.load(c).map { if (it.id == id) n else it })
                Scheduler.set(c, n); Bus.tick++
            }
        }
        ContextCompat.startForegroundService(c, Intent(c, AlarmService::class.java).putExtras(i))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) { Scheduler.resetAll(c) }
}

class AlarmService : Service() {
    private var mp: MediaPlayer? = null
    private var vib: Vibrator? = null
    private val h = Handler(Looper.getMainLooper())
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, sid: Int): Int {
        val kind = i?.getStringExtra("kind").orEmpty()
        val label = if (kind == "timer") "Таймер" else i?.getStringExtra("label").orEmpty()
        val math = i?.getBooleanExtra("math", false) ?: false
        val tone = i?.getStringExtra("tone").orEmpty()
        val shake = i?.getBooleanExtra("vib", true) ?: true
        // Кнопки уведомления на часах: «Выключить» и «Отложить» приходят сюда же
        val cmd = i?.action
        if (cmd == "stop" || cmd == "snooze") {
            if (cmd == "snooze") Scheduler.schedule(this, 100000, System.currentTimeMillis() + 10 * 60_000L, label, math, "snooze", tone, shake)
            stopSelf()
            return START_NOT_STICKY
        }
        h.removeCallbacksAndMessages(null) // второй звонок не должен обрываться таймером первого
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("ring", "Звонок", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val ring = Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("label", label).putExtra("math", math).putExtra("kind", kind).putExtra("tone", tone).putExtra("vib", shake)
        val pi = PendingIntent.getActivity(this, 1, ring, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, "ring").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label.ifBlank { "Будильник" }).setContentText("Нажми, чтобы выключить")
            .setCategory(Notification.CATEGORY_ALARM).setFullScreenIntent(pi, true).setContentIntent(pi).setOngoing(true).build()
        ServiceCompat.startForeground(this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        // Отдельное обычное уведомление для часов Wear OS: постоянное (ongoing) они обычно не пересылают
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("watch", "Часы", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(true); vibrationPattern = longArrayOf(0, 500, 250, 500)
        })
        fun watchAction(a: String, title: String) = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_lock_idle_alarm), title,
            PendingIntent.getService(this, if (a == "stop") 11 else 12,
                Intent(this, AlarmService::class.java).setAction(a).putExtra("label", label).putExtra("math", math)
                    .putExtra("tone", tone).putExtra("vib", shake),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        val wn = Notification.Builder(this, "watch").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label.ifBlank { "Будильник" }).setContentText("Звонит").setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(pi).apply {
                if (!math) addAction(watchAction("stop", "Выключить"))
                if (kind != "timer") addAction(watchAction("snooze", "Отложить"))
            }.build()
        nm.notify(2, wn)
        // Wear OS вибрирует один раз на появление уведомления, поэтому, пока звонит будильник, подаём его заново
        h.postDelayed(object : Runnable {
            override fun run() { nm.notify(2, wn); h.postDelayed(this, 5000) }
        }, 5000)
        try { startActivity(ring) } catch (_: Exception) {}
        play(tone, shake)
        h.postDelayed({ stopSelf() }, 10 * 60_000L)
        return START_NOT_STICKY
    }

    private fun play(tone: String, shake: Boolean) {
        val def = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        // Выбранная мелодия первой, стандартная — запасная: если файл удалён или нет доступа, будильник всё равно зазвонит.
        val uris = listOfNotNull(tone.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }, def)
        mp?.release(); mp = null
        vib?.cancel(); vib = null
        for (u in uris) {
            val p = MediaPlayer()
            try {
                p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                p.setDataSource(this, u); p.isLooping = true; p.setVolume(0.1f, 0.1f); p.prepare(); p.start()
                mp = p
                break
            } catch (e: Exception) {
                p.release()
            }
        }
        var v = 0.1f
        h.post(object : Runnable {
            override fun run() { v = minOf(1f, v + 0.05f); mp?.setVolume(v, v); if (v < 1f) h.postDelayed(this, 1500) }
        })
        if (shake) vib = getSystemService(Vibrator::class.java).also { it.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0)) }
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null); mp?.release(); vib?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).cancel(2)
        super.onDestroy()
    }
}
