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

/** Opens the app straight on the edit screen of this reminder. */
internal fun openReminderIntent(context: Context, id: String): Intent =
    Intent(context, MainActivity::class.java).apply {
        // A distinct data URI keeps every row's PendingIntent separate.
        data = Uri.parse("yadavar://reminder/$id")
        putExtra(MainActivity.EXTRA_OPEN_ID, id)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

/** Opens the app on the shared-reminders section. */
internal fun openSharedIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        data = Uri.parse("yadavar://shared")
        putExtra(MainActivity.EXTRA_OPEN_SHARED, true)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

/** Opens the confirmation (message or fingerprint) that must be passed before a reminder is finished from the widget. */
internal fun confirmCompleteIntent(context: Context, id: String, shared: Boolean = false): Intent =
    Intent(context, ConfirmCompleteActivity::class.java).apply {
        data = Uri.parse((if (shared) "yadavar://complete-shared/" else "yadavar://complete/") + id)
        putExtra("id", id)
        putExtra("shared", shared)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

/** The completion of a reminder from the widget: a green check first, then the row disappears. */
suspend fun completeFromWidget(context: Context, id: String) {
    WidgetState.completing.update { it + id }
    YadavarWidget().updateAll(context)
    delay(650)
    Actions.complete(id)
    WidgetState.completing.update { it - id }
    YadavarWidget().updateAll(context)
}

/** A reminder of a shared group finished from the widget (same green check first, then the row disappears). */
suspend fun completeSharedFromWidget(context: Context, id: String) {
    SharedGraph.init(context)
    WidgetState.completing.update { it + id }
    YadavarWidget().updateAll(context)
    delay(650)
    val done = SharedGraph.changeItem(id) { app.yadavar.shamsi.shared.sharedComplete(it, app.yadavar.shamsi.shared.ServerClock.now()) }
    WidgetState.completing.update { it - id }
    YadavarWidget().updateAll(context)
    if (done) SharedGraph.syncAll()
}

class SetFilterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val value = parameters[WidgetKeys.filter] ?: return
        Graph.settings.setWidgetFilter(value)
        YadavarWidget().updateAll(context)
    }
}

class SyncSharedAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        SharedGraph.init(context)
        WidgetState.syncing.value = true
        YadavarWidget().update(context, glanceId)
        val start = System.currentTimeMillis()
        try {
            SharedGraph.syncAll()
        } finally {
            // The turning circle stays visible for at least a second so a quick sync is still noticed.
            val left = 1200 - (System.currentTimeMillis() - start)
            if (left > 0) delay(left)
            WidgetState.syncing.value = false
            YadavarWidget().update(context, glanceId)
        }
    }
}

class ToggleSharedExpandAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[WidgetKeys.id] ?: return
        WidgetCollapsed.toggle(context, id)
        YadavarWidget().update(context, glanceId)
    }
}

class SetSpaceAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val value = parameters[WidgetKeys.space] ?: return
        // The widget collects this flow, so only the touched widget needs to redraw and it does so immediately.
        WidgetSpace.set(context, value)
        YadavarWidget().update(context, glanceId)
    }
}

class ToggleSharedItemAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[WidgetKeys.id] ?: return
        val item = parameters[WidgetKeys.item] ?: return
        SharedGraph.init(context)
        val ok = SharedGraph.changeItem(id) { r -> r.copy(items = r.items.map { if (it.id == item) it.copy(done = !it.done) else it }) }
        YadavarWidget().updateAll(context)
        if (ok) SharedGraph.syncAll()
    }
}

class CompleteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[WidgetKeys.id] ?: return
        completeFromWidget(context, id)
    }
}

class ToggleItemAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[WidgetKeys.id] ?: return
        val item = parameters[WidgetKeys.item] ?: return
        Actions.toggleItem(id, item)
        YadavarWidget().updateAll(context)
    }
}

class ToggleExpandAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[WidgetKeys.id] ?: return
        Actions.toggleExpanded(id)
        YadavarWidget().updateAll(context)
    }
}
