package app.yadavar.shamsi.shared

import kotlinx.coroutines.flow.MutableStateFlow

/** Finding an invite code inside any text, and handing over a code that arrived through a link. */
object InviteLink {
    private val pattern = Regex("ys1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")

    /** A code received through a link; the groups page picks it up (after login if needed) and opens the join dialog. */
    val pending = MutableStateFlow<String?>(null)

    /** The invite code (ys1.<code>.<key>) found in [text], or null. Works for a pasted link, a whole shared message or the bare code. */
    fun extract(text: String?): String? = text?.let { pattern.find(it)?.value }

    /** Remembers the code of a "yadavar://join/..." link. */
    fun fromLink(link: String?): Boolean {
        val code = extract(link) ?: return false
        pending.value = code
        return true
    }
}
