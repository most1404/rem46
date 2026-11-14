package app.yadavar.shamsi.shared

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * "Done" for a reminder of a group. A repeating reminder is not finished: it moves on to its next date and its
 * checklist is cleared (the same rule as for personal reminders); only a one-time reminder goes to "done".
 */
fun sharedComplete(r: app.yadavar.shamsi.model.Reminder, now: Long): app.yadavar.shamsi.model.Reminder {
    if (r.repeat !is app.yadavar.shamsi.model.Repeat.None) {
        val next = app.yadavar.shamsi.model.advanceRepeat(r, java.util.Date(now))
        if (next != null) {
            return r.copy(
                jy = next.jy, jm = next.jm, jd = next.jd,
                anchorDay = r.anchorDay ?: r.jd,
                completed = false, completedAt = null,
                items = r.items.map { it.copy(done = false) },
            )
        }
    }
    return r.copy(completed = true, completedAt = now)
}

/** What the screen shows about synchronisation (Persian text in [text]). */
data class SyncStatus(val kind: Kind, val text: String) {
    enum class Kind { Idle, Syncing, Ok, Offline, Error }
}

/** The shared-groups services: one place that holds the settings, the local store and the sync. */
object SharedGraph {
    private var _prefs: SharedPrefs? = null
    private var _store: SharedStore? = null
    private var _api: Api? = null
    private var appCtx: Context? = null
    private val mutex = Mutex()
    private val _status = MutableStateFlow(SyncStatus(SyncStatus.Kind.Idle, "هنوز همگام‌سازی نشده"))
    val status: StateFlow<SyncStatus> = _status

    fun init(context: Context) {
        if (_prefs != null) return
        val app = context.applicationContext
        appCtx = app
        ServerClock.attach(app)
        _prefs = SharedPrefs(app)
        _store = SharedStore(app)
        _api = Api(_prefs!!)
    }

    val prefs get() = _prefs!!
    val store get() = _store!!
    val api get() = _api!!

    /** When a hidden group was last seen on the server (kept in the small settings file). */
    private fun lastSeen(id: String): Long = prefs.goneSince(id)

    /** Whether this account may change the item: its creator, or the group owner. */
    fun canChange(item: SharedItem): Boolean {
        val role = store.spaces.value.firstOrNull { it.id == item.spaceId }?.role
        return role == "owner" || item.createdBy == null || item.createdBy == prefs.userId
    }

    /** Changes one shared reminder if allowed and queues it for sync. Returns false when missing or not allowed. */
    suspend fun changeItem(id: String, deleted: Boolean = false, transform: (app.yadavar.shamsi.model.Reminder) -> app.yadavar.shamsi.model.Reminder): Boolean {
        val item = store.items.value.firstOrNull { it.id == id && !it.deleted } ?: return false
        if (!canChange(item)) return false
        val old = SharedStore.fromJson(item.id, item.spaceId, item.json, item.updatedAt) ?: return false
        store.saveItem(SharedItem(id, item.spaceId, SharedStore.toJson(transform(old)), ServerClock.now(), deleted, true, item.createdBy ?: prefs.userId))
        refreshWidgets()
        return true
    }

    /** Redraws the home-screen widget (its "shared" tab shows these reminders). */
    fun refreshWidgets() {
        val c = appCtx ?: return
        app.yadavar.shamsi.Graph.appScope.launch {
            runCatching { app.yadavar.shamsi.widget.YadavarWidget().updateAll(c) }
            // Every change of shared reminders (own edit or a sync) also refreshes the phone notifications.
            runCatching { SharedAlarms.reschedule(c) }
        }
    }

    private fun fmtTime(): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date())

    /** Sends local edits and fetches others' edits for every group. Safe to call often; never throws. */
    suspend fun syncAll(): SyncStatus =
        // Leaving a screen must not abort a sync half-way (that used to show a fake "unknown error").
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { doSync() }

    private suspend fun doSync(): SyncStatus = mutex.withLock {
        if (!prefs.configured || !prefs.signedIn) return@withLock SyncStatus(SyncStatus.Kind.Idle, "وارد نشده‌ای")
        api.schemaOutdated = false
        _status.value = SyncStatus(SyncStatus.Kind.Syncing, "در حال همگام‌سازی…")
        val result = try {
            // Refresh the group list first (new groups appear, removed ones disappear).
            val remote = api.mySpaces()
            // A group the server no longer lists (deleted by the owner, or this person was removed) is hidden, not erased:
            // if the owner restores it, it comes back with its key and items.
            store.spaces.value.filter { l -> remote.none { it.first == l.id } && !l.gone }.forEach { store.markGone(it.id, true); prefs.goneSince(it.id) }
            remote.forEach { (id, name, role) ->
                val old = store.spaces.value.firstOrNull { it.id == id }
                if (old != null) store.upsertSpace(old.copy(name = name, role = role, gone = false)); prefs.clearGone(id)
            }
            store.spaces.value.filter { !it.gone }.forEach { syncSpace(it) }
            // Hidden groups older than 45 days are dropped for good.
            val dropBefore = System.currentTimeMillis() - 45L * 24 * 3600 * 1000
            store.spaces.value.filter { it.gone && lastSeen(it.id) < dropBefore }.forEach { store.removeSpace(it.id) }
            if (api.schemaOutdated) {
                SyncStatus(SyncStatus.Kind.Error, "همگام شد، ولی پایگاه‌داده سرور باید به‌روز شود: schema.sql را دوباره اجرا کن.")
            } else {
                SyncStatus(SyncStatus.Kind.Ok, "همگام شد (${fmtTime()})")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OfflineException) {
            val pending = store.items.value.count { it.dirty }
            SyncStatus(SyncStatus.Kind.Offline, if (pending > 0) "آفلاین هستی؛ $pending تغییر بعداً ارسال می‌شود" else "آفلاین هستی")
        } catch (e: ApiException) {
            SyncStatus(SyncStatus.Kind.Error, e.message ?: "خطا")
        } catch (e: Exception) {
            SyncStatus(SyncStatus.Kind.Error, "مشکل غیرمنتظره در همگام‌سازی؛ کمی بعد دوباره امتحان کن.")
        }
        _status.value = result
        refreshWidgets()
        result
    }

    private suspend fun syncSpace(space: SharedSpace) {
        val key = store.key(space) ?: throw ApiException(0, "", "کلید رمز گروه «${space.name}» روی این گوشی خوانده نشد؛ دوباره با کد دعوت بپیوند.")
        // 1) push local edits. The server accepts an edit only if it is newer than what it has (last edit wins).
        var refetchAll = false
        for (item in store.items.value.filter { it.spaceId == space.id && it.dirty }) {
            val accepted = api.push(space.id, item.id, Crypto.encrypt(key, item.json), item.updatedAt, item.deleted)
            // Either way the local edit is no longer "waiting": if it was rejected, the newer remote version arrives in step 2.
            store.mutate { s, i -> s to i.map { if (it.id == item.id && it.updatedAt == item.updatedAt) it.copy(dirty = false) else it } }
            if (!accepted) refetchAll = true // the server has a newer edit: fetch everything again so it replaces ours
        }
        // 2) pull everything that changed on the server since the last time.
        var rev = if (refetchAll) 0L else (store.spaces.value.firstOrNull { it.id == space.id }?.rev ?: 0L)
        while (true) {
            val batch = api.pull(space.id, rev)
            if (batch.isEmpty()) break
            for (r in batch) {
                val plain = Crypto.decrypt(key, r.payload)
                val local = store.items.value.firstOrNull { it.id == r.id }
                // Keep a newer local edit that has not been sent yet; otherwise the server version wins.
                val keepLocal = local != null && local.dirty && local.updatedAt > r.updatedAt
                if (plain != null && !keepLocal) {
                    store.saveItem(SharedItem(r.id, space.id, plain, r.updatedAt, r.deleted, false, r.createdBy))
                }
                rev = maxOf(rev, r.rev)
            }
            store.upsertSpace(store.spaces.value.first { it.id == space.id }.copy(rev = rev))
            if (batch.size < 500) break
        }
        // Old tombstones (deleted > 60 days ago and already synced) are dropped from the local copy.
        val cutoff = System.currentTimeMillis() - 60L * 24 * 3600 * 1000
        store.mutate { s, i -> s to i.filterNot { it.deleted && !it.dirty && it.updatedAt < cutoff } }
    }

    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<SharedSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork("shared_sync", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancelPeriodic(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork("shared_sync")
    }
}

/** Background sync (about every 15 minutes while the phone has internet; Android may delay it to save battery). */
class SharedSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SharedGraph.init(applicationContext)
        return when (SharedGraph.syncAll().kind) {
            SyncStatus.Kind.Offline -> Result.retry()
            else -> Result.success()
        }
    }
}
