package app.yadavar.shamsi.shared

import android.content.Context
import android.net.Uri
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export and restore of one group's reminders as a plain JSON file.
 * The file is NOT encrypted (it holds the readable reminders), so the person must keep it somewhere private.
 */
object SharedBackup {
    private const val FORMAT = "yadavar-shared-1"

    fun build(groupName: String, items: List<SharedItem>): String {
        val array = JSONArray()
        items.filter { !it.deleted }.forEach { array.put(JSONObject().put("id", it.id).put("payload", it.json)) }
        return JSONObject()
            .put("app", "yadavar-shamsi")
            .put("format", FORMAT)
            .put("group", groupName)
            .put("savedAt", ServerClock.now())
            .put("reminders", array)
            .toString(2)
    }

    suspend fun writeTo(context: Context, uri: Uri, groupName: String, items: List<SharedItem>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = build(groupName, items).toByteArray(Charsets.UTF_8)
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
        }.getOrDefault(false)
    }

    /** Reads the reminders of a backup file; returns the payloads (null when the file is not a group backup). */
    fun parse(text: String): List<String>? = runCatching {
        val root = JSONObject(text)
        if (root.optString("format") != FORMAT) return@runCatching null
        val array = root.getJSONArray("reminders")
        (0 until array.length()).map { array.getJSONObject(it).getString("payload") }
    }.getOrNull()

    /**
     * Adds the backup's reminders to [spaceId] as new reminders written by this person (new ids, so nothing that
     * other members wrote is ever overwritten). Returns how many were added, or -1 for a file that is not a backup.
     */
    suspend fun restore(context: Context, uri: Uri, spaceId: String): Int = withContext(Dispatchers.IO) {
        val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } }.getOrNull()
            ?: return@withContext -1
        val payloads = parse(text) ?: return@withContext -1
        val now = ServerClock.now()
        var added = 0
        for (payload in payloads) {
            val id = UUID.randomUUID().toString()
            if (SharedStore.fromJson(id, spaceId, payload, now) == null) continue
            SharedGraph.store.saveItem(SharedItem(id, spaceId, payload, now, false, true, SharedGraph.prefs.userId))
            added++
        }
        added
    }
}
