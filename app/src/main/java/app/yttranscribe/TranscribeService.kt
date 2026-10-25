package app.yttranscribe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Keeps the process alive (foreground + wake lock) while a long transcription runs. */
class TranscribeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            return START_NOT_STICKY
        }
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null) { stopSelf(); return START_NOT_STICKY }

        createChannel()
        ServiceCompat.startForeground(
            this, 1, notification("Starting…", null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (job?.isActive == true) return START_NOT_STICKY

        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yttranscribe:run")
            .apply { acquire(12L * 60 * 60 * 1000) }

        val nm = getSystemService(NotificationManager::class.java)
        job = scope.launch {
            val watcher = launch {
                Transcriber.state
                    .map { s ->
                        when (s) {
                            is TState.Working -> s.stage to s.progress?.let { (it * 100).toInt() }
                            else -> "Finishing" to null
                        }
                    }
                    .distinctUntilChanged()
                    .collect { (text, pct) -> nm.notify(1, notification(text, pct)) }
            }
            try {
                Transcriber.run(applicationContext, url)
            } finally {
                watcher.cancel()
                if (wake?.isHeld == true) wake?.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (wake?.isHeld == true) wake?.release()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Transcription", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String, pct: Int?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, TranscribeService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Transcribing")
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .apply { if (pct != null) setProgress(100, pct, false) else setProgress(0, 0, true) }
            .addAction(0, "Cancel", cancel)
            .build()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val ACTION_CANCEL = "app.yttranscribe.CANCEL"
        private const val CHANNEL = "transcribe"
    }
}

