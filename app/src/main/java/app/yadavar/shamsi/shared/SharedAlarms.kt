package app.yadavar.shamsi.shared

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.yadavar.shamsi.CHANNEL_ID
import app.yadavar.shamsi.R
import app.yadavar.shamsi.alarm.AlarmScheduler
import app.yadavar.shamsi.model.formatTime
import app.yadavar.shamsi.model.nextTrigger
import app.yadavar.shamsi.model.ringKey
import app.yadavar.shamsi.ui.MainActivity

/**
 * Phone notifications for reminders of shared groups.
 * They are plain notifications (no full-screen alarm); every member's phone posts its own, from its own copy.
 * The person can switch them off for this phone only.
 */
object SharedAlarms {
    const val ACTION_FIRE = "app.yadavar.shamsi.SHARED_FIRE"
    private const val PREFS = "shared_alarms"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("enabled", true)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("enabled", value).apply()
        reschedule(context)
    }

    private fun pending(context: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            "sh:$id".hashCode(),
            Intent(context, SharedAlarmReceiver::class.java).apply {
                action = ACTION_FIRE
                putExtra("id", id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Registers one alarm per active shared reminder and removes the ones that are gone, done or switched off. */
    fun reschedule(context: Context) {
        val app = context.applicationContext
        val manager = app.getSystemService(AlarmManager::class.java) ?: return
        runCatching {
            SharedGraph.init(app)
            val previous = prefs(app).getStringSet("ids", emptySet()) ?: emptySet()
            val spaces = SharedGraph.store.spaces.value.filter { !it.gone }.associate { it.id to it.name }
            val now = System.currentTimeMillis()
            val scheduled = mutableSetOf<String>()
            if (enabled(app) && SharedGraph.prefs.signedIn) {
                for (item in SharedGraph.store.items.value) {
                    if (item.deleted || item.spaceId !in spaces) continue
                    val reminder = SharedStore.fromJson(item.id, item.spaceId, item.json, item.updatedAt) ?: continue
                    val at = nextTrigger(reminder, now, AlarmScheduler.firedKey(app, "sh:${item.id}")) ?: continue
                    val intent = pending(app, item.id)
                    manager.cancel(intent)
                    if (Build.VERSION.SDK_INT >= 31 && !manager.canScheduleExactAlarms()) {
                        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
                    } else {
                        manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
                    }
                    scheduled += item.id
                }
            }
            (previous - scheduled).forEach { manager.cancel(pending(app, it)) }
            prefs(app).edit().putStringSet("ids", scheduled).apply()
        }
    }

    fun post(context: Context, id: String) {
        val app = context.applicationContext
        SharedGraph.init(app)
        if (!enabled(app)) return
        val item = SharedGraph.store.items.value.firstOrNull { it.id == id && !it.deleted } ?: return
        val space = SharedGraph.store.spaces.value.firstOrNull { it.id == item.spaceId && !it.gone } ?: return
        val reminder = SharedStore.fromJson(item.id, item.spaceId, item.json, item.updatedAt) ?: return
        // A stale alarm (finished, changed or already shown) stays silent.
        val key = ringKey(reminder, System.currentTimeMillis()) ?: return
        if (AlarmScheduler.firedKey(app, "sh:$id") == key) return
        AlarmScheduler.markFired(app, "sh:$id", key)
        val open = PendingIntent.getActivity(
            app,
            "shopen:$id".hashCode(),
            Intent(app, MainActivity::class.java).apply {
                data = Uri.parse("yadavar://shared")
                putExtra(MainActivity.EXTRA_OPEN_SHARED, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val time = if (reminder.hour != null && reminder.minute != null) " · ساعت " + formatTime(reminder.hour, reminder.minute) else ""
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(reminder.title.ifBlank { "یادآور" })
            .setContentText("گروه ${space.name}$time")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify("sh:$id".hashCode(), notification) }
    }
}

class SharedAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        SharedAlarms.post(context, id)
        // Registers the next one (a repeating reminder is moved on only when someone finishes it).
        SharedAlarms.reschedule(context)
    }
}
