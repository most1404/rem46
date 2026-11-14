package app.yadavar.shamsi.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.yadavar.shamsi.Graph
import app.yadavar.shamsi.R
import app.yadavar.shamsi.alarm.AlarmScheduler
import app.yadavar.shamsi.data.Actions
import app.yadavar.shamsi.jalali.toJalali
import app.yadavar.shamsi.model.CheckItem
import app.yadavar.shamsi.model.KIND_SHARED
import app.yadavar.shamsi.model.Reminder
import app.yadavar.shamsi.model.Repeat
import app.yadavar.shamsi.model.Section
import app.yadavar.shamsi.model.Settings
import app.yadavar.shamsi.model.TimeFilter
import app.yadavar.shamsi.model.dateOf
import app.yadavar.shamsi.model.filterReminders
import app.yadavar.shamsi.model.formatDayMonth
import app.yadavar.shamsi.model.formatLongDate
import app.yadavar.shamsi.model.formatTime
import app.yadavar.shamsi.model.repeatLabel
import app.yadavar.shamsi.model.sectionOf
import app.yadavar.shamsi.model.weekdayName
import app.yadavar.shamsi.shared.SharedGraph
import app.yadavar.shamsi.shared.SharedStore
import app.yadavar.shamsi.ui.MainActivity
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Reminders whose circle was just tapped: the widget shows a green check for a moment before they disappear. */
object WidgetState {
    val completing = MutableStateFlow<Set<String>>(emptySet())
    /** True while the sync button of the widget is working: a spinner replaces the arrows. */
    val syncing = MutableStateFlow(false)
}

/** Reminders of shared groups whose sub-items are hidden in the widget (all are shown by default, like in the app). */
object WidgetCollapsed {
    private fun prefs(context: Context) = context.getSharedPreferences("widget_space", Context.MODE_PRIVATE)
    private val flow = MutableStateFlow<Set<String>?>(null)

    fun flow(context: Context): MutableStateFlow<Set<String>?> {
        if (flow.value == null) flow.value = prefs(context).getStringSet("collapsed", emptySet())?.toSet() ?: emptySet()
        return flow
    }

    fun toggle(context: Context, id: String) {
        val now = flow(context).value ?: emptySet()
        val next = if (id in now) now - id else now + id
        flow.value = next
        prefs(context).edit().putStringSet("collapsed", next).apply()
    }
}

object WidgetKeys {
    val id = ActionParameters.Key<String>("id")
    val item = ActionParameters.Key<String>("item")
    val filter = ActionParameters.Key<String>("filter")
    val space = ActionParameters.Key<String>("space")
}

/**
 * Which widget tab is showing: "all", "personal" or "shared".
 * Held in a flow that the widget collects, so a tap redraws at once without rebuilding every widget.
 */
object WidgetSpace {
    private fun prefs(context: Context) = context.getSharedPreferences("widget_space", Context.MODE_PRIVATE)
    private val flow = MutableStateFlow<String?>(null)

    fun flow(context: Context): MutableStateFlow<String?> {
        if (flow.value == null) flow.value = get(context)
        return flow
    }

    fun get(context: Context): String = prefs(context).getString("space", "all")?.takeIf { it in setOf("all", "personal", "shared") } ?: "all"

    fun set(context: Context, value: String) {
        flow(context).value = value
        prefs(context).edit().putString("space", value).apply()
    }
}

sealed interface WidgetRow {
    val key: Long

    data class Main(val reminder: Reminder) : WidgetRow {
        override val key: Long get() = reminder.id.hashCode().toLong()
    }

    data class Sub(val reminder: Reminder, val item: CheckItem) : WidgetRow {
        override val key: Long get() = (reminder.id + item.id).hashCode().toLong()
    }

    /** Thin line drawn after the last reminder of one date, before the next date starts. */
    data class Divider(val index: Int) : WidgetRow {
        override val key: Long get() = 1_000_000_000L + index
    }
}
