package com.galleryslim.blackbox.share

import com.galleryslim.scan.MediaItem
import com.galleryslim.scan.MediaKind
import com.galleryslim.share.Gs2
import com.galleryslim.share.ReceiverSessions
import com.galleryslim.share.ShareHub
import com.galleryslim.share.ShareService
import com.galleryslim.share.ShareSessions
import com.galleryslim.share.ShareState
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sessions of the sharing service (8.1 RULES & LIMITS, page 60), the browser way's session rules
 * (8.2, pages 61–62) and the "this run" bookkeeping behind delete-for-good (8.4, pages 63–64).
 */
class PhoneSessionsTest {
    private var clock = 1_000_000_000L
    private val phone = BbPhone(clock = { clock })

    @After fun tearDown() {
        runCatching { phone.stop() }
        ShareHub.receivers = null
        ShareHub.sessions = null
        ShareHub.state.value = ShareState()
    }

    private fun status(id: String, from: String = "127.0.0.1") = phone.http("GET", "/api/status?s=$id", from = from).text

    private fun openPage(from: String = "127.0.0.1"): ShareSessions.Created {
        val before = phone.created.size
        assertEquals(200, phone.http("GET", "/", from = from).status)
        return phone.created[before]
    }

    @Test fun `SND-25 browser sessions expire after 10 min idle, each chunk keeps them alive`() {
        phone.start()
        val c = openPage()
        val f = phone.file("1", "a.jpg", Bb.bytes(100, 1))
        assertTrue(phone.gs1.approve(ShareSessions.qrPayload(c), listOf(f)))
        clock += 9 * 60_000L
        phone.gs1.touch(c.id)                     // a chunk was sent
        clock += 9 * 60_000L
        assertTrue("alive 9 min after the last chunk", status(c.id).contains("approved"))
        clock += 10 * 60_000L + 1_000
        assertTrue("expired after 10 idle minutes", status(c.id).contains("expired"))
        assertNull(phone.gs1.authorizedFile(c.id, c.secret, "1", "127.0.0.1"))
    }

    @Test fun `SND-26 at most 32 unapproved browser sessions`() {
        phone.start()
        val all = (1..33).map { openPage() }
        val waiting = all.count { status(it.id).contains("waiting") }
        assertEquals("unapproved sessions kept", 32, waiting)
        assertTrue("the newest page must work", status(all[32].id).contains("waiting"))
    }

    @Test fun `SND-27 a browser session is pinned to the computer that opened the page`() {
        phone.start()
        val c = openPage("127.0.0.1")
        val f = phone.file("1", "a.jpg", Bb.bytes(100, 1))
        assertTrue(phone.gs1.approve(ShareSessions.qrPayload(c), listOf(f)))
        assertTrue("other computer sees the session", status(c.id, from = "127.0.0.2").contains("expired"))
        assertEquals(403, phone.http("GET", "/api/zip?s=${c.id}&t=${c.secret}", from = "127.0.0.2").status)
        assertEquals(403, phone.http("GET", "/api/file?s=${c.id}&t=${c.secret}&f=1", from = "127.0.0.2").status)
        assertEquals(200, phone.http("GET", "/api/zip?s=${c.id}&t=${c.secret}", from = "127.0.0.1").status)
    }

    @Test fun `SND-28 short code - 2 letters without I and O, a dash, 10-99`() {
        val svc = ShareService()
        val m = ShareService::class.java.getDeclaredMethod("shortCode").apply { isAccessible = true }
        val codes = (1..4000).map { m.invoke(svc) as String }
        val bad = codes.filterNot { Regex("^[A-HJ-NP-Z]{2}-[1-9][0-9]$").matches(it) }
        assertTrue("codes outside the format: ${bad.take(5)}", bad.isEmpty())
        val letters = codes.flatMap { listOf(it[0], it[1]) }.toSet()
        assertEquals("letters used", ('A'..'Z').filter { it != 'I' && it != 'O' }.toSet(), letters)
        val numbers = codes.map { it.substringAfter('-').toInt() }.toSet()
        assertEquals("numbers 10–99 all reachable", (10..99).toSet(), numbers)
        assertTrue("codes are random", codes.toSet().size > 1000)
    }

    @Test fun `SND-07 progress resets on each new approval`() {
        val rs = ReceiverSessions()
        ShareHub.receivers = rs
        ShareHub.setQueue(listOf(item(1), item(2)))
        ShareHub.state.update { it.copy(sentBytes = 500) }
        val s = rs.create("127.0.0.1", "PC", "linux", "Photos")
        val k = ByteArray(32) { 1 }
        assertTrue(ShareHub.approve("gs2:${s.sid}:${Gs2.b64url(k)}"))
        assertEquals(0L, ShareHub.state.value.sentBytes)
        assertEquals(1, ShareHub.state.value.approvedSessions)
    }

    @Test fun `SND-09 only gs1 and gs2 codes are accepted`() {
        val rs = ReceiverSessions()
        ShareHub.receivers = rs
        ShareHub.sessions = ShareSessions()
        ShareHub.setQueue(listOf(item(1)))
        val s = rs.create("127.0.0.1", "PC", "linux", "Photos")
        val k = Gs2.b64url(ByteArray(32) { 2 })
        for (code in listOf("https://evil.example/x", "GS2:${s.sid}:$k", "gs3:${s.sid}:$k", "xgs2:${s.sid}:$k", "", "gs1:", "gs2:")) {
            assertFalse("accepted $code", ShareHub.approve(code))
        }
        assertNull(ShareHub.receiverFor("gs1:abcd:ef01"))
        assertNull(Gs2.parseQr("gs1:${s.sid}:$k"))
        assertNull(ShareSessions.parseQr("gs2:abcd:ef01"))
        assertTrue(ShareHub.approve("gs2:${s.sid}:$k"))
    }

    @Test fun `GS1-01 each page load makes its own session and QR code`() {
        phone.start()
        val a = openPage(); val b = openPage()
        assertNotEquals(a.id, b.id)
        assertNotEquals(a.secret, b.secret)
        assertEquals(listOf(ShareSessions.qrPayload(a), ShareSessions.qrPayload(b)), phone.qrPayloads.toList())
        assertTrue(phone.qrPayloads.all { it.startsWith("gs1:") })
    }

    @Test fun `GS1-03 only the right code of a waiting page approves`() {
        phone.start()
        val a = openPage(); val b = openPage()
        val f = phone.file("1", "a.jpg", Bb.bytes(10, 1))
        assertFalse("wrong secret", phone.gs1.approve("gs1:${a.id}:${b.secret}", listOf(f)))
        assertFalse("unknown page", phone.gs1.approve("gs1:abcdef:${a.secret}", listOf(f)))
        assertTrue(status(a.id).contains("waiting"))
        clock += 10 * 60_000L + 1_000
        assertFalse("stale code", phone.gs1.approve(ShareSessions.qrPayload(b), listOf(f)))
        val c = openPage()
        assertTrue(phone.gs1.approve(ShareSessions.qrPayload(c), listOf(f)))
    }

    @Test fun `GS1-04 the page learns approval and the file list`() {
        phone.start()
        val c = openPage()
        assertEquals("""{"state":"waiting"}""", status(c.id))
        val files = listOf(phone.file("1", "IMG_1.jpg", Bb.bytes(1234, 1)), phone.file("2", "old.jpg", Bb.bytes(99, 2), taken = 0))
        assertTrue(phone.gs1.approve(ShareSessions.qrPayload(c), files))
        val s = status(c.id)
        assertTrue(s, s.contains("\"state\":\"approved\""))
        assertTrue(s, s.contains("\"name\":\"IMG_1.jpg\"") && s.contains("\"size\":1234") && s.contains("\"folder\":\"2024-01\""))
        assertTrue(s, s.contains("\"folder\":\"undated\""))
    }

    @Test fun `GS1-08 a waiting page expires after 10 idle minutes`() {
        phone.start()
        val c = openPage()
        clock += 10 * 60_000L + 1_000
        assertEquals("""{"state":"expired"}""", status(c.id))
        assertEquals("""{"state":"expired"}""", status("no-such-session"))
    }

    @Test fun `GS1-15 downloads need the approved session, its secret and the same computer`() {
        phone.start()
        val c = openPage()
        val f = phone.file("1", "a.jpg", Bb.bytes(10, 1))
        assertEquals("before approval", 403, phone.http("GET", "/api/zip?s=${c.id}&t=${c.secret}").status)
        assertEquals("before approval", 403, phone.http("GET", "/api/file?s=${c.id}&t=${c.secret}&f=1").status)
        assertTrue(phone.gs1.approve(ShareSessions.qrPayload(c), listOf(f)))
        val wrong = "0".repeat(c.secret.length)
        assertEquals(403, phone.http("GET", "/api/zip?s=${c.id}&t=$wrong").status)
        assertEquals(403, phone.http("GET", "/api/file?s=${c.id}&t=$wrong&f=1").status)
        assertEquals(403, phone.http("GET", "/api/file?s=${c.id}&t=${c.secret}&f=99").status)
        assertEquals(200, phone.http("GET", "/api/zip?s=${c.id}&t=${c.secret}").status)
    }

    @Test fun `D2-03 earlier verifications are labels only`() {
        ShareHub.setQueue(listOf(item(1), item(2)), fromTrash = true)
        ShareHub.restoreVerified(setOf(1L, 2L), mapOf(1L to "PC", 2L to "PC"))
        val st = ShareHub.state.value
        assertEquals(setOf(1L, 2L), st.previouslyVerified)
        assertTrue("earlier verifications must not count as verified in this run", st.verifiedIds.isEmpty())
        assertTrue(st.deliveredIds.isEmpty())
    }

    @Test fun `D2-04 a new queue starts a new run`() {
        ShareHub.setQueue(listOf(item(1)), fromTrash = true)
        ShareHub.state.update { it.copy(verifiedIds = setOf(1L), deliveredIds = setOf(1L), sentBytes = 10) }
        ShareHub.restoreVerified(setOf(1L), mapOf(1L to "PC"))
        ShareHub.setQueue(listOf(item(1), item(3)), fromTrash = true)
        val st = ShareHub.state.value
        assertTrue(st.verifiedIds.isEmpty())
        assertTrue(st.deliveredIds.isEmpty())
        assertTrue(st.previouslyVerified.isEmpty())
        assertEquals(0L, st.sentBytes)
        assertTrue(st.fromTrash)
    }

    private fun item(id: Long) = MediaItem(id, MediaKind.values().first(), "IMG_$id.jpg", "image/jpeg", 1000L * id, contentUri = "content://bb/$id")
}
