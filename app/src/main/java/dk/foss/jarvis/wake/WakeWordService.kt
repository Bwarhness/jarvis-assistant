package dk.foss.jarvis.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.rementia.openwakeword.lib.WakeWordEngine
import com.rementia.openwakeword.lib.model.DetectionMode
import com.rementia.openwakeword.lib.model.WakeWordDetection
import com.rementia.openwakeword.lib.model.WakeWordModel
import dk.foss.jarvis.MainActivity
import dk.foss.jarvis.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Always-on "Hey Jarvis" listener. Runs openWakeWord in a microphone foreground
 * service; on detection, brings up conversation mode. Requires RECORD_AUDIO.
 */
class WakeWordService : Service() {

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var engine: WakeWordEngine? = null

    @Volatile private var cooling = false
    @Volatile private var paused = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        ServiceCompat.startForeground(
            this,
            NOTIF_ONGOING,
            ongoingNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        startEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun startEngine() {
        if (engine != null) return
        runCatching {
            val models = listOf(
                WakeWordModel("hey_jarvis", "hey_jarvis_v0.1.onnx", DETECTION_THRESHOLD),
            )
            val e = WakeWordEngine(this, models, DetectionMode.SINGLE_BEST, COOLDOWN_MS, engineScope)
            engine = e
            uiScope.launch { e.detections.collect { onWake(it) } }
            e.start()
            Log.i(TAG, "wake engine started, listening for 'hey jarvis'")
        }.onFailure {
            // Most likely missing model assets or no mic permission — stop gracefully.
            Log.e(TAG, "wake engine failed to start", it)
            stopSelf()
        }
    }

    private fun onWake(detection: WakeWordDetection) {
        Log.i(TAG, "WAKE detected (score=${detection.score}) cooling=$cooling")
        if (cooling) return
        cooling = true

        // Free the mic so conversation mode's recognizer can record, then open the app.
        pauseEngine()

        val launch = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_FROM_ASSIST, true)
        }
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (km?.isKeyguardLocked == true) {
            // Locked: a plain startActivity is suppressed, so use a full-screen-intent
            // notification — the sanctioned way to launch over the keyguard.
            notifyWake(launch)
        } else {
            // Unlocked: launch directly (allowed via SYSTEM_ALERT_WINDOW), no notification noise.
            runCatching { startActivity(launch) }
        }

        uiScope.launch {
            delay(4000)
            cooling = false
        }
    }

    private fun notifyWake(launch: Intent) {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(this, 1, launch, flags)
        val notif = NotificationCompat.Builder(this, CHANNEL_WAKE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Jarvis")
            .setContentText("Listening…")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .build()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_WAKE, notif)
        // The full-screen intent launches the activity; clear the shade entry shortly after.
        uiScope.launch { delay(2000); runCatching { nm.cancel(NOTIF_WAKE) } }
    }

    private fun ongoingNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Jarvis")
            .setContentText("Listening for “Hey Jarvis”")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Wake word", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WAKE, "Wake alerts", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    /** Release the mic (e.g. while a conversation is using the recognizer). */
    private fun pauseEngine() {
        if (paused) return
        paused = true
        runCatching { engine?.stop() }
        Log.i(TAG, "wake paused (mic released)")
    }

    /** Resume always-on listening after a conversation finishes. */
    private fun resumeEngine() {
        if (!paused) return
        paused = false
        runCatching { engine?.start() }
        Log.i(TAG, "wake resumed")
    }

    override fun onDestroy() {
        instance = null
        runCatching { engine?.stop(); engine?.release() }
        engine = null
        runCatching { engineScope.cancel() }
        runCatching { uiScope.cancel() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "JarvisWake"
        private const val CHANNEL_ONGOING = "jarvis_wake_ongoing"
        private const val CHANNEL_WAKE = "jarvis_wake_alert"
        private const val NOTIF_ONGOING = 1001
        private const val NOTIF_WAKE = 1002
        private const val DETECTION_THRESHOLD = 0.5f
        private const val COOLDOWN_MS = 1500L

        @Volatile private var instance: WakeWordService? = null

        /** Pause/resume the always-on listener so the mic is free during a conversation. */
        fun pauseListening() {
            instance?.pauseEngine()
        }

        fun resumeListening() {
            instance?.resumeEngine()
        }

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
