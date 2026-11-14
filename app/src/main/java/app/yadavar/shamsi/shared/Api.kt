package app.yadavar.shamsi.shared

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The server cannot be reached (no internet, wrong address, timeout). */
/** Difference between the server clock and this phone's clock, learned from every server answer. */
object ServerClock {
    private const val PREFS = "server_clock"
    @Volatile private var offset = 0L
    @Volatile private var known = false

    fun load(context: android.content.Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        if (p.contains("offset")) { offset = p.getLong("offset", 0L); known = true }
    }

    private var store: android.content.SharedPreferences? = null

    fun attach(context: android.content.Context) {
        store = context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        load(context)
    }

    fun observe(serverDate: Long, sentAt: Long, receivedAt: Long) {
        if (serverDate <= 0L) return
        // The Date header has whole-second resolution: aim for the middle of the second and of the round trip.
        val fresh = serverDate + 500L - (sentAt + receivedAt) / 2
        if (known && kotlin.math.abs(fresh - offset) < 1500L) return
        offset = fresh
        known = true
        store?.edit()?.putLong("offset", fresh)?.apply()
    }

    fun now(): Long = System.currentTimeMillis() + offset
}

class OfflineException(cause: Throwable) : IOException(cause)

/** The server answered with an error. [message] is meant for the person (Persian). */
class ApiException(val code: Int, val raw: String, message: String, val schemaProblem: Boolean = false) : Exception(message)

/** Server address, public (anon) key and login tokens. The anon key is public by design; the data is protected by the RLS rules. */
class SharedPrefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences("shared_prefs", Context.MODE_PRIVATE)

    /** True when the server address and key were built into the app, so people never see the server setup. */
    val builtIn: Boolean
        get() = app.yadavar.shamsi.BuildConfig.SUPABASE_URL.startsWith("https://") && app.yadavar.shamsi.BuildConfig.SUPABASE_ANON_KEY.length > 20

    var url: String
        get() = if (builtIn) app.yadavar.shamsi.BuildConfig.SUPABASE_URL else (p.getString("url", "") ?: "")
        set(v) = p.edit().putString("url", v.trim().trimEnd('/')).apply()
    var anonKey: String
        get() = if (builtIn) app.yadavar.shamsi.BuildConfig.SUPABASE_ANON_KEY else (p.getString("anon", "") ?: "")
        set(v) = p.edit().putString("anon", v.trim()).apply()
    var access: String
        get() = p.getString("access", "") ?: ""
        set(v) = p.edit().putString("access", v).apply()
    var refresh: String
        get() = p.getString("refresh", "") ?: ""
        set(v) = p.edit().putString("refresh", v).apply()
    var userId: String
        get() = p.getString("uid", "") ?: ""
        set(v) = p.edit().putString("uid", v).apply()
    var email: String
        get() = p.getString("email", "") ?: ""
        set(v) = p.edit().putString("email", v).apply()
    var displayName: String
        get() = p.getString("dn", "") ?: ""
        set(v) = p.edit().putString("dn", v).apply()

    /** When a group was first noticed missing from the server (so hidden groups can be dropped after a while). */
    fun goneSince(id: String): Long {
        if (!p.contains("gone_$id")) p.edit().putLong("gone_$id", System.currentTimeMillis()).apply()
        return p.getLong("gone_$id", System.currentTimeMillis())
    }

    fun clearGone(id: String) {
        p.edit().remove("gone_$id").apply()
    }

    val configured get() = url.startsWith("https://") && anonKey.length > 20
    val signedIn get() = refresh.isNotEmpty() && userId.isNotEmpty()

    fun signOut() {
        p.edit().remove("access").remove("refresh").remove("uid").remove("email").apply()
    }

    companion object {
        /** Refuses a "service_role" key: it gives full control of the database and must never be put in an app. */
        fun keyProblem(key: String): String? {
            val parts = key.trim().split(".")
            if (parts.size != 3) return "این شبیه کلید anon نیست (باید سه بخش با نقطه داشته باشد)."
            val role = runCatching {
                val json = String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE), Charsets.UTF_8)
                JSONObject(json).optString("role")
            }.getOrDefault("")
            return when (role) {
                "anon" -> null
                "service_role" -> "این کلید service_role است و نباید در برنامه گذاشته شود! کلید anon public را بگذار."
                else -> "نقش کلید «$role» است؛ کلید anon public لازم است."
            }
        }
    }
}

/** Minimal Supabase client (Auth + PostgREST + RPC) over plain HTTPS. */
class Api(private val prefs: SharedPrefs) {
    private class Resp(val code: Int, val body: String)

    private suspend fun call(method: String, path: String, body: String?, bearer: String?, extra: Map<String, String> = emptyMap()): Resp =
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(prefs.url + path).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 15_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("apikey", prefs.anonKey)
                conn.setRequestProperty("Authorization", "Bearer " + (bearer ?: prefs.anonKey))
                conn.setRequestProperty("Content-Type", "application/json")
                extra.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (body != null) {
                    conn.doOutput = true
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val sentAt = System.currentTimeMillis()
                val code = conn.responseCode
                // The server's clock (Date header) is used to stamp edits, so a wrong phone clock cannot win or lose edits.
                ServerClock.observe(conn.getHeaderFieldDate("Date", 0L), sentAt, System.currentTimeMillis())
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                conn.disconnect()
                Resp(code, text)
            } catch (e: IOException) {
                throw OfflineException(e)
            }
        }

    /** True when the last pull had to skip the created_by column because the server's tables are not updated yet. */
    @Volatile var schemaOutdated = false

    private val schemaCodes = setOf("42703", "42P01", "42883", "PGRST202", "PGRST204", "PGRST205")

    private fun fail(r: Resp): Nothing {
        val j = runCatching { JSONObject(r.body) }.getOrNull()
        val msg = j?.let { o -> listOf("msg", "message", "error_description", "error").map { o.optString(it) }.firstOrNull { it.isNotEmpty() } } ?: ""
        val pg = j?.optString("code").orEmpty()
        val schema = pg in schemaCodes || r.code == 404
        // The raw server text is kept only inside the exception for debugging; the person always gets a plain Persian sentence.
        throw ApiException(r.code, r.raw(), explain(r.code, msg, pg, schema), schema)
    }

    private fun Resp.raw() = body.take(300)

    private fun explain(code: Int, m: String, pg: String, schema: Boolean): String = when {
        schema -> "ساختار پایگاه‌داده سرور به‌روز نیست. فایل supabase/schema.sql را دوباره در SQL Editor اجرا کن."
        m.contains("invite_invalid") -> "کد دعوت نامعتبر، منقضی، لغوشده یا پر شده است."
        m.contains("forbidden") -> "اجازهٔ این کار را نداری."
        m.contains("owner_must_transfer_or_delete") -> "تو مالک گروه هستی و هنوز عضو دیگری هست؛ اول گروه را حذف کن یا اعضا را بیرون کن."
        m.contains("Invalid login") -> "ایمیل یا رمز عبور اشتباه است."
        m.contains("already registered") -> "این ایمیل قبلاً ثبت‌نام کرده؛ «ورود» را بزن."
        m.contains("Email not confirmed") -> "ایمیل هنوز تأیید نشده؛ لینک تأیید در صندوق ایمیل است (یا تأیید ایمیل را در Supabase خاموش کن)."
        m.contains("Password should be") -> "رمز عبور کوتاه است (حداقل ۶ نویسه)."
        m.contains("bad_name") -> "نام گروه باید بین ۱ تا ۸۰ نویسه باشد."
        m.contains("too_large") -> "متن یادآوری بیش از حد بلند است."
        pg == "42501" -> "اجازهٔ این کار را نداری."
        pg == "23505" -> "این مورد قبلاً ثبت شده است."
        pg == "23503" -> "گروه یا مورد مرتبط پیدا نشد (شاید حذف شده باشد)."
        code == 401 -> "نشست ورود منقضی شده؛ دوباره وارد شو."
        code == 429 -> "درخواست‌ها زیاد بود؛ کمی بعد دوباره امتحان کن."
        code >= 500 -> "سرور موقتاً در دسترس نیست؛ بعداً دوباره امتحان کن."
        else -> "درخواست انجام نشد (کد $code). اگر تکرار شد برنامه و پایگاه‌داده سرور را به‌روز کن."
    }

    // ─── Auth ───
    private fun saveSession(j: JSONObject) {
        prefs.access = j.getString("access_token")
        prefs.refresh = j.getString("refresh_token")
        prefs.userId = j.getJSONObject("user").getString("id")
    }

    suspend fun signUp(email: String, password: String) {
        val b = JSONObject().put("email", email).put("password", password).toString()
        val r = call("POST", "/auth/v1/signup", b, null)
        if (r.code !in 200..299) fail(r)
        val j = JSONObject(r.body)
        if (!j.has("access_token")) throw ApiException(200, "", "ثبت‌نام انجام شد؛ ایمیل تأیید فرستاده شد. بعد از تأیید، «ورود» را بزن.")
        saveSession(j)
        prefs.email = email
    }

    suspend fun signIn(email: String, password: String) {
        val b = JSONObject().put("email", email).put("password", password).toString()
        val r = call("POST", "/auth/v1/token?grant_type=password", b, null)
        if (r.code !in 200..299) fail(r)
        saveSession(JSONObject(r.body))
        prefs.email = email
    }

    private suspend fun refreshSession(): Boolean {
        if (prefs.refresh.isEmpty()) return false
        val b = JSONObject().put("refresh_token", prefs.refresh).toString()
        val r = call("POST", "/auth/v1/token?grant_type=refresh_token", b, null)
        if (r.code !in 200..299) {
            if (r.code in 400..401) prefs.signOut()
            return false
        }
        saveSession(JSONObject(r.body))
        return true
    }

    /** Calls with the login token; on "expired" refreshes it once and tries again. */
    private suspend fun authed(method: String, path: String, body: String?, extra: Map<String, String> = emptyMap()): String {
        var r = call(method, path, body, prefs.access, extra)
        if (r.code == 401 && refreshSession()) r = call(method, path, body, prefs.access, extra)
        if (r.code !in 200..299) fail(r)
        return r.body
    }

    // ─── Groups ───
    suspend fun createSpace(name: String, display: String): String {
        val b = JSONObject().put("p_name", name).put("p_display", display).toString()
        return authed("POST", "/rest/v1/rpc/create_space", b).trim().trim('"')
    }

    suspend fun createInvite(space: String, hours: Int, maxUses: Int): String {
        val b = JSONObject().put("p_space", space).put("p_hours", hours).put("p_max_uses", maxUses).toString()
        return authed("POST", "/rest/v1/rpc/create_invite", b).trim().trim('"')
    }

    suspend fun revokeInvite(code: String) {
        authed("POST", "/rest/v1/rpc/revoke_invite", JSONObject().put("p_code", code).toString())
    }

    /** Returns (spaceId, name). */
    suspend fun joinSpace(code: String, display: String): Pair<String, String> {
        val b = JSONObject().put("p_code", code).put("p_display", display).toString()
        val arr = JSONArray(authed("POST", "/rest/v1/rpc/join_space", b))
        val o = arr.getJSONObject(0)
        return o.getString("id") to o.getString("name")
    }

    suspend fun leaveSpace(space: String) {
        authed("POST", "/rest/v1/rpc/leave_space", JSONObject().put("p_space", space).toString())
    }

    suspend fun renameSpace(space: String, name: String) {
        authed("POST", "/rest/v1/rpc/rename_space", JSONObject().put("p_space", space).put("p_name", name).toString())
    }

    suspend fun deleteSpace(space: String) {
        authed("POST", "/rest/v1/rpc/delete_space", JSONObject().put("p_space", space).toString())
    }

    suspend fun restoreSpace(space: String) {
        authed("POST", "/rest/v1/rpc/restore_space", JSONObject().put("p_space", space).toString())
    }

    suspend fun deletedSpaces(): List<DeletedSpace> {
        val arr = JSONArray(authed("POST", "/rest/v1/rpc/list_deleted_spaces", "{}"))
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> DeletedSpace(o.getString("id"), o.getString("name"), o.optString("deleted_at")) } }
    }

    suspend fun removeMember(space: String, user: String) {
        authed("POST", "/rest/v1/rpc/remove_member", JSONObject().put("p_space", space).put("p_user", user).toString())
    }

    class Member(val userId: String, val role: String, val name: String, val joinedAt: String)
    class DeletedSpace(val id: String, val name: String, val deletedAt: String)
    class InviteRow(val code: String, val expiresAt: String, val uses: Int, val maxUses: Int, val revoked: Boolean)

    suspend fun members(space: String): List<Member> {
        val arr = JSONArray(authed("GET", "/rest/v1/space_members?space_id=eq.${enc(space)}&select=user_id,role,display_name,joined_at&order=joined_at.asc", null))
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Member(o.getString("user_id"), o.getString("role"), o.optString("display_name"), o.optString("joined_at")) } }
    }

    suspend fun invites(space: String): List<InviteRow> {
        val arr = JSONArray(authed("GET", "/rest/v1/invites?space_id=eq.${enc(space)}&select=code,expires_at,uses,max_uses,revoked&order=created_at.desc", null))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            InviteRow(o.getString("code"), o.getString("expires_at"), o.getInt("uses"), o.getInt("max_uses"), o.getBoolean("revoked"))
        }
    }

    /** Spaces this user belongs to: (id, name, role). */
    suspend fun mySpaces(): List<Triple<String, String, String>> {
        val spaces = JSONArray(authed("GET", "/rest/v1/spaces?select=id,name", null))
        val mem = JSONArray(authed("GET", "/rest/v1/space_members?user_id=eq.${enc(prefs.userId)}&select=space_id,role", null))
        val roles = (0 until mem.length()).associate { mem.getJSONObject(it).let { o -> o.getString("space_id") to o.getString("role") } }
        return (0 until spaces.length()).map {
            val o = spaces.getJSONObject(it)
            Triple(o.getString("id"), o.getString("name"), roles[o.getString("id")] ?: "member")
        }
    }

    // ─── Items ───
    class RemoteItem(val id: String, val payload: String, val updatedAt: Long, val deleted: Boolean, val rev: Long, val createdBy: String?)

    suspend fun pull(space: String, afterRev: Long): List<RemoteItem> {
        val base = "/rest/v1/shared_items?space_id=eq.${enc(space)}&rev=gt.$afterRev&order=rev.asc&limit=500&select=id,payload,updated_at,deleted,rev"
        val body = try {
            authed("GET", "$base,created_by", null)
        } catch (e: ApiException) {
            if (!e.schemaProblem) throw e
            // The server has not been updated with the newest schema.sql: read without the creator column so sync still works.
            schemaOutdated = true
            authed("GET", base, null)
        }
        val arr = JSONArray(body)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            RemoteItem(o.getString("id"), o.getString("payload"), o.getLong("updated_at"), o.getBoolean("deleted"), o.getLong("rev"), if (!o.has("created_by") || o.isNull("created_by")) null else o.getString("created_by"))
        }
    }

    /** True when the server accepted the change (false = the server already had a newer edit). */
    suspend fun push(space: String, id: String, payload: String, updatedAt: Long, deleted: Boolean): Boolean {
        val b = JSONObject().put("p_id", id).put("p_space", space).put("p_payload", payload)
            .put("p_updated_at", updatedAt).put("p_deleted", deleted).toString()
        return authed("POST", "/rest/v1/rpc/upsert_item", b).trim() == "true"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
