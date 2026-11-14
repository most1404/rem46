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

/** Title and meta text sizes (sp) for the three widget font levels. */
internal fun widgetSizes(font: String): Pair<Int, Int> = when (font) {
    "sm" -> 12 to 10
    "lg" -> 18 to 14
    else -> 15 to 12
}

internal fun emptyText(filter: TimeFilter): String = when (filter) {
    TimeFilter.All -> "یادآوری فعالی نیست"
    TimeFilter.Past -> "یادآوری گذشته‌ای نیست"
    TimeFilter.Today -> "برای امروز کاری نیست"
    TimeFilter.Week -> "برای این هفته کاری نیست"
    TimeFilter.Month -> "برای این ماه کاری نیست"
}

/** Weekday, day + month, time and repeat of one reminder (the weekday comes before the date). */
internal fun widgetMeta(reminder: Reminder): String {
    val parts = mutableListOf<String>()
    val date = dateOf(reminder)
    if (date != null) parts += weekdayName(date) + " " + formatDayMonth(date)
    if (reminder.hour != null && reminder.minute != null) parts += formatTime(reminder.hour, reminder.minute)
    if (reminder.repeat !is Repeat.None) parts += repeatLabel(reminder.repeat)
    return parts.joinToString(" - ")
}

class YadavarWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        SharedGraph.init(context)
        val firstReminders = Graph.repo.all()
        val firstSettings = Graph.settings.current()
        val spaceFlow = WidgetSpace.flow(context)
        val collapsedFlow = WidgetCollapsed.flow(context)
        provideContent {
            val space by spaceFlow.collectAsState()
            val collapsed by collapsedFlow.collectAsState()
            val syncing by WidgetState.syncing.collectAsState()
            // Live state: the widget redraws as soon as the database or settings change.
            val reminders by Graph.repo.reminders.collectAsState(initial = firstReminders)
            val settings by Graph.settings.flow.collectAsState(initial = firstSettings)
            val completing by WidgetState.completing.collectAsState()
            val sharedItems by SharedGraph.store.items.collectAsState()
            val sharedSpaces by SharedGraph.store.spaces.collectAsState()
            val active = sharedSpaces.filter { !it.gone }
            val names = active.associate { it.id to it.name }
            val shown = sharedItems.filter { !it.deleted && it.spaceId in names }
            val tab = space ?: "all"
            val sharedReminders = if (tab == "personal") emptyList() else shown.mapNotNull { SharedStore.fromJson(it.id, it.spaceId, it.json, it.updatedAt) }
                .map { it.copy(expanded = it.id !in (collapsed ?: emptySet())) }
            val editable = shown.filter { SharedGraph.canChange(it) }.map { it.id }.toSet()
            val signedIn = SharedGraph.prefs.signedIn
            val list = when (tab) {
                "shared" -> sharedReminders
                "personal" -> reminders
                else -> reminders + sharedReminders
            }
            WidgetContent(context, list, settings, completing, tab, names, editable, signedIn, syncing)
        }
    }
}

@Composable
internal fun DateLabel(context: Context, text: String, color: Color, sizeSp: Int) {
    Text(
        text = text,
        maxLines = 1,
        style = TextStyle(
            color = ColorProvider(color),
            fontSize = sizeSp.sp,
            fontWeight = FontWeight.Bold,
        ),
        modifier = GlanceModifier
            .padding(horizontal = 6.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
    )
}

@Composable
internal fun PlusButton(context: Context, palette: WidgetPalette, space: String) {
    Box(
        modifier = GlanceModifier
            .size(36.dp)
            .background(ColorProvider(palette.accent))
            .cornerRadius(18.dp)
            .clickable(
                actionStartActivity(
                    // The current part is preselected in the "register in" row of the form.
                    Intent(context, QuickAddActivity::class.java).apply {
                        data = Uri.parse("yadavar://quickadd/$space")
                        putExtra("space", space)
                    },
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+",
            style = TextStyle(
                color = ColorProvider(if (palette.dark) Color(0xFF1F1B29) else Color.White),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

/** Syncs the shared groups right now (the widget redraws when the sync finishes). */
@Composable
internal fun SyncButton(context: Context, palette: WidgetPalette, syncing: Boolean) {
    Box(
        modifier = GlanceModifier
            .size(36.dp)
            .background(ColorProvider(palette.selected))
            .cornerRadius(18.dp)
            .clickable(actionRunCallback<SyncSharedAction>()),
        contentAlignment = Alignment.Center,
    ) {
        // While syncing, a turning circle replaces the arrows; it stops by itself when the sync is done.
        val views = RemoteViews(context.packageName, R.layout.widget_sync)
        views.setTextColor(R.id.sync_text, palette.accent.toArgb())
        views.setViewVisibility(R.id.sync_text, if (syncing) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.sync_progress, if (syncing) View.VISIBLE else View.GONE)
        AndroidRemoteViews(views, GlanceModifier.size(36.dp))
    }
}

@Composable
internal fun ClockView(context: Context, palette: WidgetPalette, custom: Color?) {
    val views = RemoteViews(context.packageName, if (palette.dark) R.layout.widget_clock_dark else R.layout.widget_clock)
    // The person's own text color also colors the live clock.
    if (custom != null) views.setTextColor(R.id.clock_text, custom.toArgb())
    AndroidRemoteViews(
        views,
        GlanceModifier.width(82.dp).height(36.dp),
    )
}

@Composable
internal fun WidgetContent(
    context: Context,
    reminders: List<Reminder>,
    settings: Settings,
    completing: Set<String>,
    space: String,
    groupNames: Map<String, String>,
    editable: Set<String>,
    signedIn: Boolean,
    syncing: Boolean,
) {
    val now = Date()
    val palette = widgetPaletteWithText(settings.widgetBg, settings.widgetText)
    val customText = app.yadavar.shamsi.ui.chosenTextColor(settings.widgetText)
    val filter = TimeFilter.of(settings.widgetFilter)
    val (titleSp, metaSp) = widgetSizes(settings.widgetFont)
    val visible = filterReminders(reminders, filter, now)
    val rows = buildList {
        visible.forEachIndexed { index, reminder ->
            add(WidgetRow.Main(reminder))
            if (reminder.expanded) reminder.items.forEach { add(WidgetRow.Sub(reminder, it)) }
            val next = visible.getOrNull(index + 1)
            if (next != null && dateOf(next) != dateOf(reminder)) add(WidgetRow.Divider(index))
        }
    }
    val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    val dateText = formatLongDate(toJalali(now))
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(palette.bg))
            .cornerRadius(24.dp)
            .padding(12.dp),
    ) {
        // The two parts of the widget: which one is showing is always visible here.
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf("all" to "همه یادآوری‌ها", "personal" to "شخصی", "shared" to "اشتراکی").forEach { (id, label) ->
                val selected = space == id
                Box(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .padding(vertical = 6.dp)
                        .background(ColorProvider(if (selected) palette.accent else Color.Transparent))
                        .cornerRadius(14.dp)
                        .clickable(actionRunCallback<SetSpaceAction>(actionParametersOf(WidgetKeys.space to id))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        maxLines = 1,
                        style = TextStyle(
                            color = ColorProvider(if (selected) (if (palette.dark) Color(0xFF1F1B29) else Color.White) else palette.muted),
                            fontSize = metaSp.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
        // Always: Jalali date on the right (next to "+"), the live clock on the left.
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Visually (left to right): "+", clock, free space, date. The "+" sits after the clock, on the far left.
            if (rtl) {
                DateLabel(context, dateText, customText ?: palette.accent, titleSp + 1)
                Spacer(modifier = GlanceModifier.defaultWeight())
                ClockView(context, palette, customText)
                if (space != "personal") SyncButton(context, palette, syncing)
                Spacer(modifier = GlanceModifier.width(6.dp))
                PlusButton(context, palette, space)
            } else {
                PlusButton(context, palette, space)
                Spacer(modifier = GlanceModifier.width(6.dp))
                if (space != "personal") SyncButton(context, palette, syncing)
                ClockView(context, palette, customText)
                Spacer(modifier = GlanceModifier.defaultWeight())
                DateLabel(context, dateText, customText ?: palette.accent, titleSp + 1)
            }
        }
        Spacer(modifier = GlanceModifier.height(6.dp))
        Box(
            modifier = GlanceModifier.fillMaxWidth().height(3.dp).background(ColorProvider(palette.accent)),
        ) {}
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TimeFilter.entries.forEachIndexed { index, item ->
                val selected = item == filter
                // Every tab always sets its own background (transparent when inactive),
                // so a color from an earlier state can never stay behind.
                Box(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .padding(vertical = 6.dp)
                        .background(ColorProvider(if (selected) palette.selected else Color.Transparent))
                        .cornerRadius(12.dp)
                        .clickable(actionRunCallback<SetFilterAction>(actionParametersOf(WidgetKeys.filter to item.id))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = item.label,
                        maxLines = 1,
                        style = TextStyle(
                            color = ColorProvider(if (selected) palette.accent else palette.muted),
                            fontSize = (metaSp - 1).sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                }
                if (index < TimeFilter.entries.size - 1) {
                    Box(
                        modifier = GlanceModifier.width(1.dp).height(16.dp).background(ColorProvider(palette.line)),
                    ) {}
                }
            }
        }
        Box(
            modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(ColorProvider(palette.line)),
        ) {}
        Spacer(modifier = GlanceModifier.height(4.dp))
        if (rows.isEmpty()) {
            Text(
                text = if (space == "shared" && !signedIn) "وارد بخش اشتراکی نشده‌ای؛ برنامه را باز کن." else emptyText(filter),
                style = TextStyle(color = ColorProvider(palette.muted), fontSize = metaSp.sp),
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(rows, itemId = { it.key }) { row ->
                    when (row) {
                        is WidgetRow.Main -> {
                            val sh = row.reminder.kind == KIND_SHARED
                            MainRow(
                                context, row.reminder, palette, titleSp, metaSp, row.reminder.id in completing,
                                sh, if (sh) groupNames[row.reminder.spaceId] else null, !sh || row.reminder.id in editable,
                            )
                        }
                        is WidgetRow.Sub -> {
                            val sh = row.reminder.kind == KIND_SHARED
                            SubRow(context, row.reminder, row.item, palette, titleSp, sh, !sh || row.reminder.id in editable)
                        }
                        is WidgetRow.Divider -> DateDivider(palette)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DateDivider(palette: WidgetPalette) {
    Box(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Box(
            modifier = GlanceModifier.fillMaxWidth().height(1.dp).background(ColorProvider(palette.line)),
        ) {}
    }
}

@Composable
internal fun MainRow(
    context: Context,
    reminder: Reminder,
    palette: WidgetPalette,
    titleSp: Int,
    metaSp: Int,
    finishing: Boolean,
    shared: Boolean,
    groupName: String?,
    canEdit: Boolean,
) {
    val baseMeta = widgetMeta(reminder)
    val meta = if (groupName != null) listOf(groupName, baseMeta).filter { it.isNotBlank() }.joinToString(" - ") else baseMeta
    val open = actionStartActivity(if (shared) openSharedIntent(context) else openReminderIntent(context, reminder.id))
    val status = sectionOf(reminder)
    val red = if (palette.dark) Color(0xFFFF7A7A) else Color(0xFFD32F2F)
    val green = if (palette.dark) Color(0xFF5FD38D) else Color(0xFF2E7D32)
    val titleColor = when (status) {
        Section.Overdue -> red
        Section.Today -> green
        else -> palette.text
    }
    val metaColor = when (status) {
        Section.Overdue -> red
        Section.Today -> green
        else -> palette.text
    }
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Only this circle completes the reminder.
        Image(
            provider = ImageProvider(if (finishing) R.drawable.ic_check_circle else R.drawable.ic_circle),
            contentDescription = "تمام شد",
            colorFilter = ColorFilter.tint(ColorProvider(if (finishing) Color(0xFF45B27A) else palette.accent)),
            modifier = GlanceModifier
                .size(30.dp)
                .padding(3.dp)
                // Asks "مطمئنی؟" (or the fingerprint) before the reminder is finished.
                // A shared reminder written by someone else (and not owned by this person) cannot be finished from here.
                .clickable(if (canEdit) actionStartActivity(confirmCompleteIntent(context, reminder.id, shared)) else open),
        )
        // Tapping the text opens this reminder's edit screen.
        Column(
            modifier = GlanceModifier.defaultWeight().padding(start = 8.dp).clickable(open),
        ) {
            Text(
                text = reminder.title.ifBlank { "بدون عنوان" },
                style = if (finishing) {
                    TextStyle(
                        color = ColorProvider(palette.muted),
                        fontSize = titleSp.sp,
                        textDecoration = TextDecoration.LineThrough,
                    )
                } else {
                    TextStyle(color = ColorProvider(titleColor), fontSize = titleSp.sp)
                },
            )
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = TextStyle(color = ColorProvider(metaColor), fontSize = metaSp.sp),
                )
            }
        }
        if (reminder.items.isNotEmpty()) {
            Text(
                text = if (reminder.expanded) "▲" else "▼",
                style = TextStyle(color = ColorProvider(palette.accent), fontSize = titleSp.sp),
                modifier = GlanceModifier
                    .padding(8.dp)
                    .clickable(
                        if (shared) actionRunCallback<ToggleSharedExpandAction>(actionParametersOf(WidgetKeys.id to reminder.id))
                        else actionRunCallback<ToggleExpandAction>(actionParametersOf(WidgetKeys.id to reminder.id)),
                    ),
            )
        }
    }
}

@Composable
internal fun SubRow(context: Context, reminder: Reminder, item: CheckItem, palette: WidgetPalette, titleSp: Int, shared: Boolean, canEdit: Boolean) {
    val style = if (item.done) {
        TextStyle(
            color = ColorProvider(palette.muted),
            fontSize = (titleSp - 1).sp,
            textDecoration = TextDecoration.LineThrough,
        )
    } else {
        TextStyle(color = ColorProvider(palette.text), fontSize = (titleSp - 1).sp)
    }
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(start = 28.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(if (item.done) R.drawable.ic_check_circle else R.drawable.ic_circle),
            contentDescription = "مورد",
            colorFilter = ColorFilter.tint(ColorProvider(if (item.done) palette.muted else palette.accent)),
            modifier = GlanceModifier
                .size(26.dp)
                .padding(3.dp)
                .clickable(
                    if (shared && !canEdit) {
                        actionStartActivity(openSharedIntent(context))
                    } else if (shared) {
                        actionRunCallback<ToggleSharedItemAction>(
                            actionParametersOf(WidgetKeys.id to reminder.id, WidgetKeys.item to item.id),
                        )
                    } else {
                        actionRunCallback<ToggleItemAction>(
                            actionParametersOf(WidgetKeys.id to reminder.id, WidgetKeys.item to item.id),
                        )
                    },
                ),
        )
        Text(
            text = item.title,
            style = style,
            modifier = GlanceModifier
                .defaultWeight()
                .padding(start = 8.dp)
                .clickable(actionStartActivity(if (shared) openSharedIntent(context) else openReminderIntent(context, reminder.id))),
        )
    }
}

class YadavarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = YadavarWidget()
}

class RescheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AlarmScheduler.reschedule(applicationContext)
        YadavarWidget().updateAll(applicationContext)
        ClockDateWidget().updateAll(applicationContext)
        WidgetRefreshReceiver.scheduleNext(applicationContext)
        return Result.success()
    }
}
