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
internal fun SpacePage(spaceId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val spaces by SharedGraph.store.spaces.collectAsState()
    val allItems by SharedGraph.store.items.collectAsState()
    val status by SharedGraph.status.collectAsState()
    val space = spaces.firstOrNull { it.id == spaceId && !it.gone }
    var tab by rememberSaveable { mutableStateOf(0) }
    var filter by rememberSaveable { mutableStateOf("all") }
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    var editing by remember { mutableStateOf<Reminder?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    if (space == null) {
        // The group disappeared (left, deleted or removed by the owner).
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val myId = SharedGraph.prefs.userId
    val isOwner = space.role == "owner"
    val groupItems = allItems.filter { it.spaceId == space.id && !it.deleted }

    // Each person changes only what they wrote; the owner may change everything (the server enforces the same rule).
    fun canChange(id: String): Boolean {
        val item = groupItems.firstOrNull { it.id == id } ?: return true
        return isOwner || item.createdBy == null || item.createdBy == myId
    }

    fun denied() {
        android.widget.Toast.makeText(context, "فقط سازندهٔ این یادآور یا مالک گروه می‌تواند آن را تغییر دهد.", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun write(r: Reminder, deleted: Boolean) {
        scope.launch {
            val creator = SharedGraph.store.items.value.firstOrNull { it.id == r.id }?.createdBy ?: myId
            SharedGraph.store.saveItem(SharedItem(r.id, space.id, SharedStore.toJson(r), ServerClock.now(), deleted, true, creator))
            SharedGraph.refreshWidgets()
            SharedGraph.syncAll()
        }
    }

    editing?.let { current ->
        EditorScreen(
            initial = current,
            onClose = { editing = null },
            onSave = { saved -> write(saved.copy(kind = KIND_SHARED, spaceId = space.id), false); editing = null },
            onDelete = if (editingIsNew) null else ({ write(current, true); editing = null }),
        )
        return
    }

    val reminders = groupItems
        .mapNotNull { SharedStore.fromJson(it.id, it.spaceId, it.json, it.updatedAt) }
        .map { it.copy(expanded = it.id !in collapsed) }

    Page(
        "یادآوری‌های اشتراکی", onBack, subtitle = "(${space.name})",
        actions = {
            if (isOwner) IconButton(onClick = { renameText = space.name; renameOpen = true }) { Icon(Icons.Filled.Edit, contentDescription = "ویرایش نام گروه") }
            SyncButton(status) { SharedGraph.syncAll() }
        },
    ) {
        if (renameOpen) {
            AlertDialog(
                onDismissRequest = { renameOpen = false },
                title = { Text("ویرایش نام گروه") },
                text = {
                    OutlinedTextField(renameText, { renameText = it.take(80) }, label = { Text("نام گروه") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                },
                confirmButton = {
                    TextButton(
                        enabled = renameText.isNotBlank(),
                        onClick = {
                            val newName = renameText.trim()
                            renameOpen = false
                            scope.launch {
                                try {
                                    SharedGraph.api.renameSpace(space.id, newName)
                                    SharedGraph.store.upsertSpace(space.copy(name = newName))
                                    SharedGraph.refreshWidgets()
                                    SharedGraph.syncAll()
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(context, errText(e), android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                    ) { Text("ذخیره") }
                },
                dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("انصراف") } },
            )
        }
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("یادآورها") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("اعضا و دعوت") })
                }
                Box(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) { StatusLine(status) }
                if (tab == 0) {
                    Box(Modifier.weight(1f)) {
                        // The same list as the personal reminders: sections, filters, search, ticks, done list.
                        HomeTab(
                            padding = PaddingValues(0.dp),
                            reminders = reminders,
                            filter = filter,
                            onFilter = { filter = it },
                            onOpen = { r -> if (canChange(r.id)) { editingIsNew = false; editing = r } else denied() },
                            onComplete = { r ->
                                if (!canChange(r.id)) denied()
                                else if (r.completed) write(r.copy(completed = false, completedAt = null), false)
                                else write(sharedComplete(r, ServerClock.now()), false)
                            },
                            onExpand = { r -> collapsed = if (r.id in collapsed) collapsed - r.id else collapsed + r.id },
                            onToggleItem = { r, itemId ->
                                if (canChange(r.id)) write(r.copy(items = r.items.map { if (it.id == itemId) it.copy(done = !it.done) else it }), false) else denied()
                            },
                            onRestore = { r ->
                                if (canChange(r.id)) { write(r.copy(completed = false, completedAt = null), false); filter = "all" } else denied()
                            },
                            onDelete = { r -> if (canChange(r.id)) write(r, true) else denied() },
                            onEditDone = { r ->
                                if (canChange(r.id)) {
                                    val reopened = r.copy(completed = false, completedAt = null)
                                    write(reopened, false)
                                    filter = "all"
                                    editingIsNew = false
                                    editing = reopened
                                } else denied()
                            },
                            spaceLabel = "یادآوری‌های اشتراکی (${space.name})",
                            sharedSpace = true,
                        )
                    }
                } else {
                    MembersTab(space, onLeft = onBack)
                }
            }
            if (tab == 0) {
                FloatingActionButton(
                    onClick = { editingIsNew = true; editing = blankReminder("personal").copy(kind = KIND_SHARED, spaceId = space.id, alert = false) },
                    modifier = Modifier.align(Alignment.BottomStart).padding(20.dp),
                ) { Icon(Icons.Filled.Add, contentDescription = "افزودن") }
            }
        }
    }
}
