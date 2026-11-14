package app.yadavar.shamsi.shared

import app.yadavar.shamsi.model.Repeat
import app.yadavar.shamsi.model.blankReminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedLogicTest {
    private val key = "A".repeat(43)

    @Test
    fun inviteCodeIsFoundInBareCodeLinkAndWholeMessage() {
        val code = "ys1.abc123def456.$key"
        assertEquals(code, InviteLink.extract(code))
        assertEquals(code, InviteLink.extract("yadavar://join/$code"))
        assertEquals(code, InviteLink.extract("دعوت به گروه «خانواده»\nلینک: yadavar://join/$code\nممنون"))
        assertNull(InviteLink.extract("hello"))
        assertNull(InviteLink.extract(null))
    }

    @Test
    fun linkHandsTheCodeOver() {
        InviteLink.pending.value = null
        assertFalse(InviteLink.fromLink("yadavar://join/nothing"))
        assertNull(InviteLink.pending.value)
        assertTrue(InviteLink.fromLink("yadavar://join/ys1.abc.$key"))
        assertEquals("ys1.abc.$key", InviteLink.pending.value)
        InviteLink.pending.value = null
    }

    @Test
    fun reminderSurvivesJsonRoundTrip() {
        val original = blankReminder("personal").copy(
            title = "امتحان عربی", jy = 1405, jm = 8, jd = 22, hour = 9, minute = 30,
            location = "مدرسه", starred = true, anchorDay = 31, repeat = Repeat.of("monthly", 2),
        )
        val back = SharedStore.fromJson(original.id, "space1", SharedStore.toJson(original), 123L)
        assertNotNull(back)
        back!!
        assertEquals(original.title, back.title)
        assertEquals(1405, back.jy); assertEquals(8, back.jm); assertEquals(22, back.jd)
        assertEquals(9, back.hour); assertEquals(30, back.minute)
        assertEquals("مدرسه", back.location)
        assertTrue(back.starred)
        assertEquals(31, back.anchorDay)
        assertEquals(original.repeat.kindName, back.repeat.kindName)
        assertEquals("space1", back.spaceId)
        assertEquals(123L, back.updatedAt)
    }

    @Test
    fun brokenPayloadGivesNullInsteadOfCrash() {
        assertNull(SharedStore.fromJson("id", "s", "not json", 1L))
    }

    @Test
    fun oneTimeReminderGoesToDone() {
        val r = blankReminder("personal").copy(title = "x", jy = 1405, jm = 8, jd = 22)
        val done = sharedComplete(r, 1_000L)
        assertTrue(done.completed)
        assertEquals(1_000L, done.completedAt)
    }

    @Test
    fun serverClockCorrectsAWrongPhoneClock() {
        val phone = System.currentTimeMillis()
        // The server says it is one hour later than the phone.
        ServerClock.observe(phone + 3_600_000L, phone, phone)
        val diff = ServerClock.now() - phone
        assertTrue("offset about one hour, was $diff", diff in 3_599_000L..3_602_000L)
        ServerClock.observe(phone, phone, phone) // back to normal for other tests
    }
}
