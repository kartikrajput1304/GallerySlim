package com.galleryslim.blackbox.share

import com.galleryslim.share.ShareSessions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real Receiver in page mode against the real phone server, driven through its local page's
 * status and actions: 10.3 "The Receiver page: phase by phase" (pages 77–78). The phone is given
 * with --phone so the Receiver never searches the local network. Needs GS_RECEIVER_BIN.
 */
class ReceiverPageEndToEndTest {
    private lateinit var phone: BbPhone
    private lateinit var folder: File
    private lateinit var home: File
    private var r: ReceiverProc? = null
    private var pagePort = 0
    private var token = ""

    @Before fun setUp() {
        assumeTrue("set GS_RECEIVER_BIN to a built receiver", Bb.receiverBin != null)
        phone = BbPhone().start()
        folder = Files.createTempDirectory("bb-page-photos").toFile()
        home = Files.createTempDirectory("bb-page-home").toFile()
    }

    @After fun tearDown() {
        r?.kill()
        if (::phone.isInitialized) phone.stop()
        if (::folder.isInitialized) folder.deleteRecursively()
        if (::home.isInitialized) home.deleteRecursively()
    }

    private fun startPage() {
        val p = ReceiverProc(
            listOf("--no-browser", "--folder", folder.absolutePath, "--phone", "127.0.0.1:${phone.port}"), home,
            mapOf("DISPLAY" to ":987"),
        )
        r = p
        assertTrue("page did not start:\n${p.output()}", p.waitFor("Close it or press Ctrl+C to stop.", 15_000))
        val (port, tok) = p.pageUrl()!!
        pagePort = port; token = tok
    }

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { out.append(c); i++; continue }
            when (val e = s[i + 1]) {
                'u' -> { out.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
                'n' -> { out.append('\n'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                else -> { out.append(e); i += 2 }
            }
        }
        return out.toString()
    }

    private class Status(val json: String, private val un: (String) -> String) {
        fun str(key: String) = Regex("\"$key\":\"((?:[^\"\\\\]++|\\\\.)*+)\"").find(json)?.groupValues?.get(1)?.let(un) ?: ""
        fun count(key: String) = Regex("\"counts\":\\{[^}]*\"$key\":(\\d+)").find(json)?.groupValues?.get(1)?.toInt() ?: -1
        val phase get() = str("phase")
        override fun toString() = json.replace(Regex("\"qrSvg\":\"[^\"]{40,}\""), "\"qrSvg\":\"…\"")
    }

    private fun status() = Status(Bb.http(pagePort, "GET", "/$token/status.json").text, ::unescape)

    private fun waitFor(ms: Long = 30_000, what: String, cond: (Status) -> Boolean): Status {
        val end = System.currentTimeMillis() + ms
        var st = status()
        while (System.currentTimeMillis() < end) {
            st = status()
            if (cond(st)) return st
            Thread.sleep(150)
        }
        throw AssertionError("page never reached: $what; last status $st\noutput:\n${r?.output()}")
    }

    private fun action(name: String, body: String = "{}") = Bb.http(
        pagePort, "POST", "/$token/$name",
        mapOf("Content-Type" to "application/json", "Origin" to "http://127.0.0.1:$pagePort"), body.toByteArray(),
    ).text

    private fun qrOf(st: Status): String {
        val qr = Bb.scanSvg(st.str("qrSvg"))
        assertNotNull("QR on the page not readable", qr)
        assertTrue(qr!!.startsWith("gs2:"))
        return qr
    }

    @Test fun `RP-02 RP-05 RP-07 RP-09 RD-11 one phone - pairing, receiving, done`() {
        val files = listOf(
            phone.file("1", "a.jpg", Bb.bytes(6 shl 20, 1)),
            phone.file("2", "b.jpg", Bb.bytes(200_000, 2)),
            phone.file("3", "c.jpg", Bb.bytes(200_000, 3)),
        )
        phone.throttleMs = 120
        startPage()
        val pairing = waitFor(what = "pairing with a code") { it.phase == "pairing" && it.str("qrSvg").isNotEmpty() }
        assertEquals("BB Phone", pairing.str("phoneName"))
        assertEquals("BB-42", pairing.str("code"))
        assertTrue(folder.absolutePath == pairing.str("folder"))
        val qr = qrOf(pairing)
        val pending = phone.receivers.pending(qr)!!
        assertEquals("folder name sent to the phone", folder.name, pending.folder)
        assertEquals("linux", pending.os)
        assertNotNull(phone.receivers.approve(qr, files))
        waitFor(what = "receiving") { it.phase == "receiving" }
        val other = Files.createTempDirectory("bb-other").toFile()
        val answer = action("set-folder", """{"path":"${other.absolutePath}"}""")
        assertTrue("changing the folder while receiving → $answer", answer.contains("Please wait until receiving has finished"))
        other.deleteRecursively()
        val done = waitFor(60_000, "done") { it.phase == "done" }
        assertEquals(3, done.count("verified"))
        assertEquals(0, done.count("failed"))
        assertTrue(done.json.contains("\"filesDone\":3") && done.json.contains("\"filesTotal\":3"))
        assertEquals(setOf("1", "2", "3"), phone.verified.map { it.first }.toSet())
    }

    @Test fun `RP-12 Receive more retries only the missing files in the same session`() {
        val a = phone.file("1", "a.jpg", Bb.bytes(100_000, 1))
        val b = phone.file("2", "b.jpg", Bb.bytes(100_000, 2))
        val c = phone.file("3", "c.jpg", Bb.bytes(100_000, 3))
        val calls = AtomicInteger()
        // c.jpg keeps changing length on the phone, so it fails.
        phone.realLengthHook = { uri ->
            val n = phone.data[uri]!!.size.toLong()
            if (uri == c.uri && calls.incrementAndGet() % 2 == 0) n + 1 else n
        }
        startPage()
        val pairing = waitFor(what = "pairing") { it.phase == "pairing" && it.str("qrSvg").isNotEmpty() }
        val qr = qrOf(pairing)
        assertNotNull(phone.receivers.approve(qr, listOf(a, b, c)))
        val done = waitFor(90_000, "done with a failure") { it.phase == "done" }
        assertEquals("$done", 2, done.count("verified"))
        assertEquals("$done", 1, done.count("failed"))
        phone.realLengthHook = null
        val opensBefore = phone.opens.get()
        action("retry")
        val again = waitFor(60_000, "done after Receive more") { it.phase == "done" && it.count("verified") == 3 }
        assertEquals("only the missing file was fetched again", 1, phone.opens.get() - opensBefore)
        assertEquals("Received and checked earlier appears for a.jpg and b.jpg; status $again",
            2, Regex("Received and checked earlier").findAll(again.json).count())
        assertEquals("no new code was needed", qr, qr)
    }

    @Test fun `RP-13 Try again after the code expired makes a new code for the same phone`() {
        startPage()
        val first = waitFor(what = "pairing") { it.phase == "pairing" && it.str("qrSvg").isNotEmpty() }
        val qr1 = qrOf(first)
        phone.receivers.revokeAll() // the code was not confirmed in time on the phone
        val err = waitFor(what = "error") { it.phase == "error" }
        assertEquals("The code expired before it was confirmed on the phone. Press Try again for a new code.", err.str("error"))
        action("retry")
        val second = waitFor(what = "new code") { it.phase == "pairing" && it.str("qrSvg").isNotEmpty() && qrOf(it) != qr1 }
        assertEquals("BB Phone", second.str("phoneName"))
        val f = phone.file("1", "a.jpg", Bb.bytes(1000, 1))
        assertNotNull(phone.receivers.approve(qrOf(second), listOf(f)))
        waitFor(what = "done") { it.phase == "done" && it.count("verified") == 1 }
    }

    @Test fun `RP-20 the phone ends the session - back to pairing with a new code`() {
        val a = phone.file("1", "a.jpg", Bb.bytes(300_000, 1))
        val b = phone.file("2", "b.jpg", Bb.bytes(300_000, 2))
        val once = AtomicInteger()
        phone.openHook = { uri ->
            if (once.incrementAndGet() == 1) phone.receivers.revokeAll()
            phone.data[uri]?.inputStream()
        }
        startPage()
        val first = waitFor(what = "pairing") { it.phase == "pairing" && it.str("qrSvg").isNotEmpty() }
        val qr1 = qrOf(first)
        assertNotNull(phone.receivers.approve(qr1, listOf(a, b)))
        val again = waitFor(what = "pairing again") { it.phase == "pairing" && it.str("qrSvg").isNotEmpty() && qrOf(it) != qr1 }
        assertNotEquals(qr1, qrOf(again))
        assertNotNull(phone.receivers.approve(qrOf(again), listOf(a, b)))
        waitFor(60_000, "done") { it.phase == "done" && it.count("verified") == 2 }
    }

    @Test fun `RP-14 phone unreachable while pairing`() {
        startPage()
        waitFor(what = "pairing") { it.phase == "pairing" && it.str("qrSvg").isNotEmpty() }
        phone.stop()
        val err = waitFor(90_000, "error") { it.phase == "error" }
        assertEquals("Couldn't reach the phone. Check that it still shows \"Send to computer\" and is on the same Wi-Fi.", err.str("error"))
    }

    @Suppress("unused") private fun unused(f: ShareSessions.ShareFile) = f
}
