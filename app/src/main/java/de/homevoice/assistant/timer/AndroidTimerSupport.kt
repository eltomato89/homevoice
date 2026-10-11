package de.homevoice.assistant.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.homevoice.assistant.App
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(id: Int, atMillis: Long) {
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pendingIntent(id))
    }

    override fun cancel(id: Int) {
        alarmManager.cancel(pendingIntent(id))
    }

    private fun pendingIntent(id: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        id,
        Intent(context, TimerReceiver::class.java).putExtra(TimerReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

class PrefsTimerStore(context: Context) : TimerStore {
    private val prefs = context.getSharedPreferences("timers", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(TimerEntry.serializer())

    override fun load(): List<TimerEntry> = try {
        Json.decodeFromString(serializer, prefs.getString("timers", "[]")!!)
    } catch (e: Exception) {
        Log.w("VoiceAssistant", "Could not read timers", e)
        emptyList()
    }

    override fun save(timers: List<TimerEntry>) {
        prefs.edit().putString("timers", Json.encodeToString(serializer, timers)).apply()
    }
}

/** Loops the default alarm sound; stops by itself after [maxDurationMs]. */
class AlarmPlayer(private val context: Context, private val onAutoStop: () -> Unit) : AlarmSound {
    private var player: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { onAutoStop() }

    override fun start() {
        handler.post {
            stopInternal()
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = try {
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    setDataSource(context, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.e("VoiceAssistant", "Alarm sound failed", e)
                null
            }
            handler.postDelayed(autoStop, MAX_DURATION_MS)
        }
    }

    override fun stop() {
        handler.post { stopInternal() }
    }

    private fun stopInternal() {
        handler.removeCallbacks(autoStop)
        player?.run {
            try { stop() } catch (_: IllegalStateException) {}
            release()
        }
        player = null
    }

    private companion object {
        const val MAX_DURATION_MS = 10 * 60 * 1000L
    }
}

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id >= 0) (context.applicationContext as App).container.timerManager.fire(id)
    }

    companion object {
        const val EXTRA_ID = "timer_id"
    }
}

/** Starting the process is enough: [App] restores and re-schedules persisted timers on create. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("VoiceAssistant", "Boot completed, timers restored")
    }
}
