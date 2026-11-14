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

/** The shared-reminders area: server setup → login → groups → one group. */
@Composable
fun SharedHome(onBack: () -> Unit) {
    val context = LocalContext.current
    remember { SharedGraph.init(context); true }
    val prefs = SharedGraph.prefs
    var configured by remember { mutableStateOf(prefs.configured) }
    var signedIn by remember { mutableStateOf(prefs.signedIn) }
    var openSpace by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }

    when {
        !configured || settingsOpen -> ServerSetup(
            onDone = { configured = true; settingsOpen = false },
            onBack = { if (configured && settingsOpen) settingsOpen = false else onBack() },
        )
        !signedIn -> AuthPage(onDone = { signedIn = true }, onBack = onBack, onServer = { settingsOpen = true })
        openSpace != null -> SpacePage(openSpace!!, onBack = { openSpace = null })
        else -> SpacesPage(
            onBack = onBack,
            onOpen = { openSpace = it },
            onServer = { settingsOpen = true },
            onSignedOut = { signedIn = false },
        )
    }
}

@Composable
internal fun ServerSetup(onDone: () -> Unit, onBack: () -> Unit) {
    val prefs = SharedGraph.prefs
    var url by remember { mutableStateOf(prefs.url) }
    var key by remember { mutableStateOf(prefs.anonKey) }
    var error by remember { mutableStateOf<String?>(null) }
    Page("تنظیم سرور", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "بخش اشتراکی به یک پروژهٔ Supabase (رایگان) نیاز دارد که خودت می‌سازی. داده‌ها قبل از ارسال روی گوشی رمز می‌شوند. راهنمای ساخت در فایل supabase/SETUP-FA.md پروژه هست.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(url, { url = it }, label = { Text("Project URL (https://….supabase.co)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(key, { key = it }, label = { Text("کلید anon public") }, modifier = Modifier.fillMaxWidth())
            Text("کلید service_role را هرگز اینجا نگذار؛ برنامه آن را رد می‌کند.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    val u = url.trim().trimEnd('/')
                    val problem = SharedPrefs.keyProblem(key)
                    error = when {
                        !u.startsWith("https://") || !u.contains(".") -> "آدرس باید با https:// شروع شود."
                        problem != null -> problem
                        else -> null
                    }
                    if (error == null) {
                        if (u != prefs.url || key.trim() != prefs.anonKey) prefs.signOut()
                        prefs.url = u
                        prefs.anonKey = key.trim()
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("ذخیره") }
        }
    }
}

@Composable
internal fun AuthPage(onDone: () -> Unit, onBack: () -> Unit, onServer: () -> Unit) {
    val prefs = SharedGraph.prefs
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(prefs.email) }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(prefs.displayName) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    fun run(register: Boolean) {
        if (email.isBlank() || password.length < 6 || name.isBlank()) {
            message = "نام، ایمیل و رمز عبور (حداقل ۶ نویسه) را پر کن."
            return
        }
        busy = true
        message = null
        scope.launch {
            try {
                prefs.displayName = name.trim()
                if (register) SharedGraph.api.signUp(email.trim(), password) else SharedGraph.api.signIn(email.trim(), password)
                SharedGraph.schedulePeriodic(context)
                onDone()
            } catch (e: Exception) {
                message = errText(e)
            }
            busy = false
        }
    }

    Page("ورود به بخش اشتراکی", onBack, actions = { if (!prefs.builtIn) TextButton(onClick = onServer) { Text("سرور") } }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("برای کار در گروه‌ها یک حساب لازم است. یادآورهای شخصی تو هیچ‌وقت به این حساب ارسال نمی‌شوند.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(name, { name = it }, label = { Text("نامی که اعضای گروه می‌بینند") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(email, { email = it }, label = { Text("ایمیل") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(password, { password = it }, label = { Text("رمز عبور") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { run(false) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "صبر کن…" else "ورود") }
            OutlinedButton(onClick = { run(true) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("ثبت‌نام (حساب جدید)") }
        }
    }
}
