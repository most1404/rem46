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

@Composable
internal fun SpacesPage(onBack: () -> Unit, onOpen: (String) -> Unit, onServer: () -> Unit, onSignedOut: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allSpaces by SharedGraph.store.spaces.collectAsState()
    val spaces = allSpaces.filter { !it.gone }
    val status by SharedGraph.status.collectAsState()
    var createOpen by remember { mutableStateOf(false) }
    var joinOpen by remember { mutableStateOf(false) }
    var signOutOpen by remember { mutableStateOf(false) }
    var restoreOpen by remember { mutableStateOf(false) }
    var notifyOn by remember { mutableStateOf(SharedAlarms.enabled(context)) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        SharedGraph.schedulePeriodic(context)
        SharedGraph.syncAll()
    }

    Page(
        "یادآورهای اشتراکی", onBack,
        actions = {
            SyncButton(status) { SharedGraph.syncAll() }
            if (!SharedGraph.prefs.builtIn) TextButton(onClick = onServer) { Text("سرور") }
            TextButton(onClick = { signOutOpen = true }) { Text("خروج") }
        },
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusLine(status)
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { createOpen = true }) { Text("ساخت گروه") }
                OutlinedButton(onClick = { joinOpen = true }) { Text("پیوستن با کد") }
            }
            TextButton(onClick = { restoreOpen = true }) { Text("گروه‌های حذف‌شده (بازیابی توسط مالک)") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("اعلان یادآورهای اشتراکی روی این گوشی", modifier = Modifier.weight(1f))
                androidx.compose.material3.Switch(
                    checked = notifyOn,
                    onCheckedChange = { notifyOn = it; SharedAlarms.setEnabled(context, it) },
                )
            }
            if (spaces.isEmpty()) {
                Text("هنوز در هیچ گروهی نیستی. یک گروه بساز یا با کد دعوت بپیوند.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(spaces, key = { it.id }) { space ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(space.id) },
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text(space.name, style = MaterialTheme.typography.titleMedium)
                            Text(if (space.role == "owner") "مالک" else "عضو", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    if (createOpen) {
        var name by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) createOpen = false },
            title = { Text("ساخت گروه") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("نام گروه") }, singleLine = true) },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val id = SharedGraph.api.createSpace(name.trim(), SharedGraph.prefs.displayName)
                                val key = Crypto.newGroupKey()
                                SharedGraph.store.upsertSpace(SharedSpace(id, name.trim(), "owner", Crypto.protect(key), 0))
                                message = null
                                createOpen = false
                                onOpen(id)
                            } catch (e: Exception) {
                                message = errText(e)
                                createOpen = false
                            }
                            busy = false
                        }
                    },
                ) { Text(if (busy) "…" else "بساز") }
            },
            dismissButton = { TextButton(onClick = { createOpen = false }, enabled = !busy) { Text("لغو") } },
        )
    }

    // A link "yadavar://join/..." (or the code found in the clipboard) fills the join dialog.
    val pendingInvite by app.yadavar.shamsi.shared.InviteLink.pending.collectAsState()
    LaunchedEffect(pendingInvite) { if (pendingInvite != null) joinOpen = true }
    val clipboardInvite = androidx.compose.ui.platform.LocalClipboardManager.current

    if (joinOpen) {
        var code by remember {
            mutableStateOf(
                app.yadavar.shamsi.shared.InviteLink.pending.value
                    ?: app.yadavar.shamsi.shared.InviteLink.extract(clipboardInvite.getText()?.text)
                    ?: "",
            )
        }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) { joinOpen = false; app.yadavar.shamsi.shared.InviteLink.pending.value = null } },
            title = { Text("پیوستن با کد دعوت") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("پیام دعوت یا لینک را اینجا بچسبان؛ کد از داخل آن پیدا می‌شود.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(code, { code = it }, label = { Text("کد دعوت") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(
                    enabled = code.isNotBlank() && !busy,
                    onClick = {
                        app.yadavar.shamsi.shared.InviteLink.pending.value = null
                        // The whole message, a link or the bare code all work: the code is found inside the text.
                        val parts = (app.yadavar.shamsi.shared.InviteLink.extract(code) ?: code.trim()).split(".")
                        val key = if (parts.size == 3 && parts[0] == "ys1") runCatching { Crypto.unb64(parts[2]) }.getOrNull() else null
                        if (key == null || key.size != 32) {
                            message = "کد دعوت درست نیست؛ باید کامل و با ys1 شروع شود."
                            joinOpen = false
                        } else {
                            busy = true
                            scope.launch {
                                try {
                                    val (id, name) = SharedGraph.api.joinSpace(parts[1], SharedGraph.prefs.displayName)
                                    SharedGraph.store.upsertSpace(SharedSpace(id, name, "member", Crypto.protect(key), 0))
                                    message = null
                                    joinOpen = false
                                    SharedGraph.syncAll()
                                    onOpen(id)
                                } catch (e: Exception) {
                                    message = errText(e)
                                    joinOpen = false
                                }
                                busy = false
                            }
                        }
                    },
                ) { Text(if (busy) "…" else "بپیوند") }
            },
            dismissButton = { TextButton(onClick = { joinOpen = false; app.yadavar.shamsi.shared.InviteLink.pending.value = null }, enabled = !busy) { Text("لغو") } },
        )
    }

    if (restoreOpen) {
        var deleted by remember { mutableStateOf<List<Api.DeletedSpace>?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            try { deleted = SharedGraph.api.deletedSpaces() } catch (e: Exception) { error = errText(e) }
        }
        AlertDialog(
            onDismissRequest = { if (!busy) restoreOpen = false },
            title = { Text("گروه‌های حذف‌شده") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("فقط مالک گروه می‌تواند بازیابی کند. گروه‌های حذف‌شده تا ۳۰ روز نگه داشته می‌شوند.", style = MaterialTheme.typography.bodySmall)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val list = deleted
                    when {
                        list == null && error == null -> Text("در حال دریافت…")
                        list != null && list.isEmpty() -> Text("گروه حذف‌شده‌ای نداری.")
                        list != null -> list.forEach { d ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.name)
                                    Text("حذف: " + d.deletedAt.take(10), style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        busy = true
                                        scope.launch {
                                            try {
                                                SharedGraph.api.restoreSpace(d.id)
                                                SharedGraph.syncAll()
                                                if (SharedGraph.store.spaces.value.none { it.id == d.id && !it.gone }) {
                                                    message = "گروه بازیابی شد ولی کلید آن روی این گوشی نیست؛ با یک دعوت جدید دوباره بپیوند."
                                                } else {
                                                    message = null
                                                }
                                                restoreOpen = false
                                            } catch (e: Exception) {
                                                error = errText(e)
                                            }
                                            busy = false
                                        }
                                    },
                                ) { Text("بازیابی") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { restoreOpen = false }, enabled = !busy) { Text("بستن") } },
        )
    }

    if (signOutOpen) {
        AlertDialog(
            onDismissRequest = { signOutOpen = false },
            title = { Text("خروج از حساب؟") },
            text = { Text("نسخهٔ محلی گروه‌ها و کلیدهای رمز از این گوشی پاک می‌شود. برای دسترسی دوباره باید دوباره وارد شوی و با کد دعوت بپیوندی. یادآورهای شخصی دست نمی‌خورند.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        SharedGraph.store.clearAll()
                        SharedGraph.prefs.signOut()
                        SharedGraph.cancelPeriodic(context)
                        signOutOpen = false
                        onSignedOut()
                    }
                }) { Text("خروج") }
            },
            dismissButton = { TextButton(onClick = { signOutOpen = false }) { Text("لغو") } },
        )
    }
}
