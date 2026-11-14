package app.yadavar.shamsi.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.yadavar.shamsi.model.KIND_SHARED
import app.yadavar.shamsi.model.Reminder
import app.yadavar.shamsi.model.blankReminder
import app.yadavar.shamsi.shared.Api
import app.yadavar.shamsi.shared.ApiException
import app.yadavar.shamsi.shared.Crypto
import app.yadavar.shamsi.shared.OfflineException
import app.yadavar.shamsi.shared.SharedAlarms
import app.yadavar.shamsi.shared.SharedBackup
import app.yadavar.shamsi.shared.SharedGraph
import app.yadavar.shamsi.shared.SharedItem
import app.yadavar.shamsi.shared.SharedPrefs
import app.yadavar.shamsi.shared.SharedSpace
import app.yadavar.shamsi.shared.SharedStore
import app.yadavar.shamsi.shared.ServerClock
import app.yadavar.shamsi.shared.SyncStatus
import app.yadavar.shamsi.shared.sharedComplete
import kotlinx.coroutines.launch

internal fun errText(e: Throwable): String = when (e) {
    is OfflineException -> "به سرور وصل نشد: اینترنت را بررسی کن یا آدرس سرور اشتباه است."
    is ApiException -> e.message ?: "خطا"
    is kotlinx.coroutines.CancellationException -> ""
    else -> "مشکل غیرمنتظره؛ دوباره امتحان کن."
}

/** The sync button: the arrows turn while syncing (at least about a second, so a quick sync is still visible). */
@Composable
internal fun SyncButton(status: SyncStatus, onSync: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val spinning = pressed || status.kind == SyncStatus.Kind.Syncing
    val turn = androidx.compose.animation.core.rememberInfiniteTransition(label = "sync")
    val angle by turn.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = "angle",
    )
    IconButton(
        onClick = {
            if (pressed) return@IconButton
            pressed = true
            scope.launch {
                val start = System.currentTimeMillis()
                try { onSync() } finally {
                    val left = 1000 - (System.currentTimeMillis() - start)
                    if (left > 0) kotlinx.coroutines.delay(left)
                    pressed = false
                }
            }
        },
    ) {
        Icon(
            Icons.Filled.Sync,
            contentDescription = "همگام‌سازی",
            modifier = if (spinning) Modifier.rotate(-angle) else Modifier,
        )
    }
}

@Composable
internal fun Page(title: String, onBack: () -> Unit, subtitle: String? = null, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت") }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            actions()
        }
        content()
    }
}

@Composable
internal fun StatusLine(status: SyncStatus) {
    val color = when (status.kind) {
        SyncStatus.Kind.Error -> MaterialTheme.colorScheme.error
        SyncStatus.Kind.Offline -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(status.text, style = MaterialTheme.typography.bodySmall, color = color)
}

internal fun dueText(r: Reminder): String {
    val d = if (r.jy != null && r.jm != null && r.jd != null) "${r.jy}/${r.jm}/${r.jd}" else ""
    val t = if (r.hour != null && r.minute != null) String.format("%02d:%02d", r.hour, r.minute) else ""
    return listOf(d, t).filter { it.isNotEmpty() }.joinToString("  ")
}

/** Groups the person can share a new reminder with (empty when not signed in to the shared section yet). */
@Composable
fun rememberShareTargets(): List<Pair<String, String>> {
    val context = LocalContext.current
    remember { SharedGraph.init(context); true }
    val spaces by SharedGraph.store.spaces.collectAsState()
    return if (SharedGraph.prefs.signedIn) spaces.filter { !it.gone }.map { it.id to it.name } else emptyList()
}

/** Saves a new reminder into a shared group (never into the personal list) and syncs it in the background. */
fun saveSharedReminder(context: android.content.Context, reminder: Reminder, spaceId: String) {
    SharedGraph.init(context)
    val appContext = context.applicationContext
    app.yadavar.shamsi.Graph.appScope.launch {
        val now = ServerClock.now()
        val shared = reminder.copy(kind = KIND_SHARED, spaceId = spaceId, alert = false)
        SharedGraph.store.saveItem(SharedItem(shared.id, spaceId, SharedStore.toJson(shared), now, false, true, SharedGraph.prefs.userId))
        SharedGraph.refreshWidgets()
        val name = SharedGraph.store.spaces.value.firstOrNull { it.id == spaceId }?.name ?: "گروه"
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            android.widget.Toast.makeText(appContext, "در گروه «$name» ثبت شد", android.widget.Toast.LENGTH_SHORT).show()
        }
        SharedGraph.syncAll()
    }
}
