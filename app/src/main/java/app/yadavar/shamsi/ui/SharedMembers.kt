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
internal fun MembersTab(space: SharedSpace, onLeft: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var members by remember { mutableStateOf<List<Api.Member>>(emptyList()) }
    var invites by remember { mutableStateOf<List<Api.InviteRow>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var inviteOpen by remember { mutableStateOf(false) }
    var newInvite by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) } // "leave" | "delete"
    val isOwner = space.role == "owner"
    var tick by remember { mutableStateOf(0) }

    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) scope.launch {
            val items = SharedGraph.store.items.value.filter { it.spaceId == space.id }
            val ok = SharedBackup.writeTo(context, uri, space.name, items)
            android.widget.Toast.makeText(context, if (ok) "پشتیبان ذخیره شد." else "ذخیرهٔ پشتیبان انجام نشد.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            val added = SharedBackup.restore(context, uri, space.id)
            val text = if (added < 0) "این فایل پشتیبان یک گروه نیست." else "$added یادآور به این گروه اضافه شد."
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
            if (added > 0) { SharedGraph.refreshWidgets(); SharedGraph.syncAll() }
        }
    }

    LaunchedEffect(space.id, tick) {
        try {
            members = SharedGraph.api.members(space.id)
            invites = if (isOwner) SharedGraph.api.invites(space.id) else emptyList()
            message = null
        } catch (e: Exception) {
            message = errText(e)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("اعضا (${members.size})", style = MaterialTheme.typography.titleMedium)
        members.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text((m.name.ifBlank { "بدون نام" }) + if (m.role == "owner") " (مالک)" else "")
                    if (m.joinedAt.isNotEmpty()) Text("عضو از " + m.joinedAt.take(10), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isOwner && m.userId != SharedGraph.prefs.userId) {
                    TextButton(onClick = {
                        scope.launch {
                            try { SharedGraph.api.removeMember(space.id, m.userId); tick++ } catch (e: Exception) { message = errText(e) }
                        }
                    }) { Text("بیرون‌کردن") }
                }
            }
        }
        if (isOwner) {
            Text("دعوت‌ها", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Button(onClick = { inviteOpen = true }) { Text("ساخت لینک/کد دعوت") }
            invites.forEach { inv ->
                val state = when {
                    inv.revoked -> "لغوشده"
                    inv.uses >= inv.maxUses -> "پر شده"
                    else -> "فعال تا ${inv.expiresAt.take(16).replace('T', ' ')} (UTC)"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("…${inv.code.takeLast(4)} — $state", style = MaterialTheme.typography.bodyMedium)
                        Text("استفاده: ${inv.uses} از ${inv.maxUses}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!inv.revoked) {
                        TextButton(onClick = {
                            scope.launch {
                                try { SharedGraph.api.revokeInvite(inv.code); tick++ } catch (e: Exception) { message = errText(e) }
                            }
                        }) { Text("لغو") }
                    }
                }
            }
        }
        Text("پشتیبان‌گیری از این گروه", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text("فایل پشتیبان رمزنگاری‌شده نیست و یادآورها در آن خوانا هستند؛ آن را جای امن نگه دار.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exportLauncher.launch("yadavar-group.json") }) { Text("ذخیرهٔ پشتیبان") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("بازگردانی از فایل") }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { confirm = "leave" }, modifier = Modifier.fillMaxWidth()) { Text("ترک گروه") }
        if (isOwner) {
            OutlinedButton(onClick = { confirm = "delete" }, modifier = Modifier.fillMaxWidth()) { Text("حذف گروه (فقط مالک)") }
        }
    }

    if (inviteOpen) {
        var hours by remember { mutableStateOf(24) }
        var uses by remember { mutableStateOf(1) }
        var busy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!busy) inviteOpen = false },
            title = { Text("دعوت جدید") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("اعتبار:")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(24 to "۱ روز", 72 to "۳ روز", 168 to "۷ روز").forEach { (h, label) ->
                            if (hours == h) Button(onClick = { hours = h }) { Text(label) } else OutlinedButton(onClick = { hours = h }) { Text(label) }
                        }
                    }
                    Text("تعداد دفعات استفاده:")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 5, 20).forEach { n ->
                            if (uses == n) Button(onClick = { uses = n }) { Text("$n") } else OutlinedButton(onClick = { uses = n }) { Text("$n") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val code = SharedGraph.api.createInvite(space.id, hours, uses)
                                val key = SharedGraph.store.key(space)
                                if (key == null) message = "کلید گروه روی این گوشی خوانده نشد." else newInvite = "ys1.$code.${Crypto.b64(key)}"
                                tick++
                            } catch (e: Exception) {
                                message = errText(e)
                            }
                            inviteOpen = false
                            busy = false
                        }
                    },
                ) { Text(if (busy) "…" else "بساز") }
            },
            dismissButton = { TextButton(onClick = { inviteOpen = false }, enabled = !busy) { Text("لغو") } },
        )
    }

    newInvite?.let { text ->
        val shareText = "دعوت به گروه «${space.name}» در یادآور شمسی.\nاگر برنامه نصب است لینک زیر را بزن؛ اگر لمس نشد، کل این پیام را کپی کن و در برنامه: یادآورهای اشتراکی ← پیوستن با کد، بچسبان.\nyadavar://join/$text"
        AlertDialog(
            onDismissRequest = { newInvite = null },
            title = { Text("دعوت آماده است") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text, style = MaterialTheme.typography.bodySmall)
                    Text("هر کس این متن را داشته باشد می‌تواند عضو شود و یادآورها را بخواند. فقط برای افراد مورد اعتماد بفرست؛ هر زمان خواستی از لیست دعوت‌ها «لغو» کن.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText) }
                    context.startActivity(Intent.createChooser(send, "ارسال دعوت"))
                }) { Text("ارسال") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(shareText)) }) { Text("کپی") }
                    TextButton(onClick = { newInvite = null }) { Text("بستن") }
                }
            },
        )
    }

    confirm?.let { kind ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (kind == "leave") "ترک گروه؟" else "حذف گروه؟") },
            text = {
                Text(
                    if (kind == "leave") "یادآورهای این گروه از این گوشی پاک می‌شوند. برای برگشتن به دعوت جدید نیاز داری."
                    else "گروه برای همهٔ اعضا بسته می‌شود. تا ۳۰ روز می‌توانی از «گروه‌های حذف‌شده» آن را بازیابی کنی؛ بعد از آن برای همیشه پاک می‌شود.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            if (kind == "leave") SharedGraph.api.leaveSpace(space.id) else SharedGraph.api.deleteSpace(space.id)
                            if (kind == "leave") SharedGraph.store.removeSpace(space.id)
                            else { SharedGraph.store.markGone(space.id, true); SharedGraph.prefs.goneSince(space.id) }
                            confirm = null
                            onLeft()
                        } catch (e: Exception) {
                            message = errText(e)
                            confirm = null
                        }
                    }
                }) { Text("تأیید") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("لغو") } },
        )
    }
}
