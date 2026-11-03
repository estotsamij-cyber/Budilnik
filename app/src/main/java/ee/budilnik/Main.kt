package ee.budilnik

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val tr = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.dark(tr), SystemBarStyle.dark(tr))
        Scheduler.resetAll(this) // подстраховка: после обновления приложения системные будильники сбрасываются
        setContent { AppTheme { App() } }
    }
    override fun onResume() { super.onResume(); Bus.tick++ }
}

class RingActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val tr = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(SystemBarStyle.dark(tr), SystemBarStyle.dark(tr))
        val label = intent.getStringExtra("label").orEmpty()
        val math = intent.getBooleanExtra("math", false)
        val timer = intent.getStringExtra("kind") == "timer"
        val tone = intent.getStringExtra("tone").orEmpty()
        val shake = intent.getBooleanExtra("vib", true)
        setContent {
            AppTheme {
                RingScreen(label, math, !timer,
                    onStop = { stopService(Intent(this, AlarmService::class.java)); finish() },
                    onSnooze = {
                        stopService(Intent(this, AlarmService::class.java))
                        Scheduler.schedule(this, 100000, System.currentTimeMillis() + 10 * 60_000L, label, math, "snooze", tone, shake)
                        finish()
                    })
            }
        }
    }
}
