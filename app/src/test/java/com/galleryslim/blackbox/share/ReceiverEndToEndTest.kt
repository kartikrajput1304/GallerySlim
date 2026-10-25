package com.galleryslim.blackbox.share

import com.galleryslim.share.ShareSessions
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * The real GallerySlim Receiver (headless) against the real phone server: chapter 10.5, 10.6 and
 * 10.8 end to end. The Receiver's terminal QR is decoded with ZXing, as the phone's camera would.
 * Needs GS_RECEIVER_BIN (a built receiver binary); skipped otherwise.
 */
class ReceiverEndToEndTest {
    private lateinit var phone: BbPhone
    private lateinit var folder: File
    private lateinit var home: File
    private val procs = mutableListOf<ReceiverProc>()

    @Before fun setUp() {
        assumeTrue("set GS_RECEIVER_BIN to a built receiver", Bb.receiverBin != null)
        phone = BbPhone().start()
        folder = Files.createTempDirectory("bb-photos").toFile()
        home = Files.createTempDirectory("bb-home").toFile()
    }

    @After fun tearDown() {
        procs.forEach { it.kill() }
        if (::phone.isInitialized) phone.stop()
        if (::folder.isInitialized) folder.deleteRecursively()
        if (::home.isInitialized) home.deleteRecursively()
    }

    private fun receiver(vararg extra: String, home: File = this.home): ReceiverProc =
        ReceiverProc(listOf("--headless", "--folder", folder.absolutePath, "--phone", "127.0.0.1:${phone.port}") + extra, home)
            .also { procs += it }

    /** Waits for the n-th code, checks it, and approves it like the owner after the fingerprint. */
    private fun approve(r: ReceiverProc, files: List<ShareSessions.ShareFile>, nth: Int = 1): String {
        val qr = r.scanQr(nth)
        assertNotNull("QR #$nth not readable; output:\n${r.output()}", qr)
        assertTrue("code $qr", qr!!.startsWith("gs2:"))
        assertNotNull("approval of $qr failed", phone.receivers.approve(qr, files))
        return qr
    }

    private fun saved(): Map<String, ByteArray> = folder.walkTopDown()
        .onEnter { it.name != ".gallery-slim" }
        .filter { it.isFile }
        .associate { it.relativeTo(folder).path to it.readBytes() }

    private fun fileLines(r: ReceiverProc) = r.snapshot().filter { it.startsWith("[") }

    @Test fun `E2E-01 a mixed batch - output, naming rules on disk, dates, verification, exit 0`() {
        File(folder, "2024-01").mkdirs()
        File(folder, "2024-01/IMG_9.jpg").writeText("an older photo already here")
        val now = System.currentTimeMillis()
        val list = listOf(
            phone.file("1", "दिवाली की रात.jpg", Bb.bytes(2_500_000, 1)),
            phone.file("2", "CON.jpg", Bb.bytes(1_000, 2)),
            phone.file("3", "a:b?c*.jpg", Bb.bytes(1_000, 3)),
            phone.file("4", ".hidden.jpg", Bb.bytes(1_000, 4)),
            phone.file("5", "IMG_5", Bb.bytes(1_000, 5)),
            phone.file("6", "x".repeat(300) + ".jpg", Bb.bytes(1_000, 6)),
            phone.file("7", "undated.jpg", Bb.bytes(1_000, 7), taken = 0),
            phone.file("8", "IMG_9.jpg", Bb.bytes(1_000, 8)),
            phone.file("9", "same.jpg", Bb.bytes(1_000, 9)),
            phone.file("10", "same.jpg", Bb.bytes(1_000, 10)),
            phone.file("11", "Case.jpg", Bb.bytes(1_000, 11)),
            phone.file("12", "case.JPG", Bb.bytes(1_000, 12)),
            phone.file("13", "future.jpg", Bb.bytes(1_000, 13), taken = now + 3 * 86_400_000L),
            phone.file("14", "VID_1.mp4", Bb.bytes(1_500_000, 14), mime = "video/mp4"),
        )
        val r = receiver()
        val qr = approve(r, list)
        assertEquals("exit code; output:\n${r.output()}", 0, r.exit())
        val out = r.output()
        // RH-11: terminal output in order.
        val order = listOf(
            "GallerySlim Receiver 0.1.0, saving to ${folder.absolutePath}",
            "Found BB Phone (code BB-42) at 127.0.0.1:${phone.port}",
            "Scan this with GallerySlim on your phone. Check that the phone shows the code BB-42.",
            "Waiting for you to confirm on the phone…",
            "Receiving 14 files (",
            "Done: 14 verified (0 were already here), 0 saved but not confirmed, 0 no longer on the phone, 0 failed.",
        )
        var at = 0
        for (s in order) {
            val i = out.indexOf(s, at)
            assertTrue("missing or out of order: \"$s\"\n$out", i >= 0)
            at = i + s.length
        }
        val lines = fileLines(r)
        assertEquals(14, lines.size)
        assertTrue(lines.joinToString("\n"), lines.all { Regex("^\\[\\d+/14] verified +\\S.*").matches(it) })
        // RW-01 … RW-09 on disk.
        val got = saved()
        val want = listOf(
            "2024-01/दिवाली की रात.jpg", "2024-01/_CON.jpg", "2024-01/a_b_c_.jpg", "2024-01/_.hidden.jpg", "2024-01/IMG_5.jpg",
            "undated/undated.jpg", "2024-01/IMG_9 (2).jpg", "2024-01/same.jpg", "2024-01/same (2).jpg",
            "2024-01/Case.jpg", "2024-01/case.JPG", "2024-01/VID_1.mp4",
        )
        for (w in want) assertTrue("missing $w; have ${got.keys}", got.containsKey(w))
        assertEquals("an older photo already here", File(folder, "2024-01/IMG_9.jpg").readText())
        val long = got.keys.single { it.contains("xxxx") }
        assertTrue("long name ${long.length}", File(long).name.toByteArray().size <= 255 && long.endsWith(".jpg"))
        assertEquals(15, got.size)
        for (f in list) {
            val match = got.entries.filter { it.value.contentEquals(phone.data[f.uri]!!) }
            assertEquals("content of ${f.name}", 1, match.size)
        }
        // RW-02: dates.
        assertEquals(Bb.JAN_2024, File(folder, "2024-01/_CON.jpg").lastModified())
        val fut = folder.walkTopDown().first { it.name == "future.jpg" }
        assertTrue("future date not used as mtime", fut.lastModified() < now + 3_600_000)
        // GS2-19/GS2-20: the phone verified every file, for this computer.
        assertEquals((1..14).map { "$it" }.toSet(), phone.verified.map { it.first }.toSet())
        // GS2-02: computer name, system and folder name reached the phone.
        val computer = phone.verified[0].second
        assertTrue("computer name '$computer'", computer.isNotBlank() && computer != "Your computer")
        // RS-04 / RT-26: the secret never reaches the log or disk.
        val k = qr.substringAfterLast(':')
        val logs = File(home, ".config/GallerySlim Receiver").walkTopDown().filter { it.isFile }.toList()
        assertTrue("no receiver.log", logs.any { it.name == "receiver.log" })
        for (f in logs + folder.walkTopDown().filter { it.isFile && it.path.contains(".gallery-slim") }) {
            val t = f.readText(Charsets.ISO_8859_1)
            assertFalse("${f.name} contains the QR text", t.contains(qr))
            assertFalse("${f.name} contains the secret", t.contains(k))
        }
    }

    @Test fun `E2E-02 files already on the computer are re-checked without downloading`() {
        val list = listOf(phone.file("1", "a.jpg", Bb.bytes(400_000, 1)), phone.file("2", "b.jpg", Bb.bytes(400_000, 2), taken = 0))
        val r1 = receiver()
        approve(r1, list)
        assertEquals(0, r1.exit())
        val opens = phone.opens.get()
        phone.verified.clear()
        val r2 = receiver()
        approve(r2, list)
        assertEquals("output:\n${r2.output()}", 0, r2.exit())
        assertEquals("files streamed again", opens, phone.opens.get())
        assertTrue(r2.output().contains("Done: 2 verified (2 were already here), 0 saved but not confirmed, 0 no longer on the phone, 0 failed."))
        assertTrue(fileLines(r2).all { it.endsWith("— Already on this computer (checked)") })
        assertEquals(setOf("1", "2"), phone.verified.map { it.first }.toSet())
    }

    @Test fun `E2E-03 the phone says the copy does not match - kept, one fresh copy, exit 1`() {
        val f = phone.file("1", "IMG_7.jpg", Bb.bytes(200_000, 1))
        phone.hashHook = { "0".repeat(64) to 200_000L }
        val r = receiver()
        approve(r, listOf(f))
        assertEquals("output:\n${r.output()}", 1, r.exit())
        assertTrue(r.output().contains("Done: 0 verified (0 were already here), 1 saved but not confirmed, 0 no longer on the phone, 0 failed."))
        assertTrue(r.output().contains("Couldn't confirm — kept. The phone says this copy doesn't match, so it won't be offered for freeing space."))
        val got = saved()
        assertEquals(setOf("2024-01/IMG_7.jpg", "2024-01/IMG_7 (2).jpg"), got.keys)
        got.values.forEach { assertArrayEquals(phone.data[f.uri], it) }
        assertTrue(phone.verified.isEmpty())
    }

    @Test fun `E2E-04 a file deleted on the phone during the run is skipped as no longer on the phone, exit 0`() {
        val keep = phone.file("1", "keep.jpg", Bb.bytes(100_000, 1))
        val gone = phone.file("2", "gone.jpg", Bb.bytes(100_000, 2))
        val listed = AtomicInteger()
        // The real phone: a deleted file has no length and cannot be opened.
        phone.realLengthHook = { uri ->
            if (uri == gone.uri && listed.incrementAndGet() > 1) null else phone.data[uri]?.size?.toLong()
        }
        phone.openHook = { uri -> if (uri == gone.uri) null else phone.data[uri]?.inputStream() }
        val r = receiver()
        approve(r, listOf(keep, gone))
        val code = r.exit()
        val line = fileLines(r).firstOrNull { it.contains("gone.jpg") }
        assertTrue("line for the deleted file: $line\n${r.output()}", line != null && line.contains("skipped") && line.endsWith("— No longer on the phone"))
        assertTrue(r.output(), r.output().contains("1 no longer on the phone, 0 failed."))
        assertEquals("files no longer on the phone are allowed; output:\n${r.output()}", 0, code)
    }

    @Test fun `E2E-05 a file that changed on the phone is fetched again and verified`() {
        val f = phone.file("1", "edit.jpg", Bb.bytes(100_000, 1))
        val newer = Bb.bytes(150_000, 2)
        val calls = AtomicInteger()
        phone.realLengthHook = { uri ->
            if (uri == f.uri && calls.incrementAndGet() == 1) 100_000L else phone.data[uri]?.size?.toLong()
        }
        phone.data[f.uri] = newer // changed right after the list was made
        val r = receiver()
        approve(r, listOf(f))
        assertEquals("output:\n${r.output()}", 0, r.exit())
        assertArrayEquals(newer, saved()["2024-01/edit.jpg"])
        assertEquals(listOf("1"), phone.verified.map { it.first })
    }

    @Test fun `E2E-06 not enough free space - Stopped with the PDF message, exit 1`() {
        val f = phone.file("1", "huge.mp4", Bb.bytes(10, 1), mime = "video/mp4")
        phone.realLengthHook = { 900_000_000_000L }
        val r = receiver()
        approve(r, listOf(f))
        assertEquals(1, r.exit())
        val line = r.snapshot().firstOrNull { it.startsWith("Stopped: ") }
        assertTrue("no Stopped line:\n${r.output()}", line != null)
        assertTrue("PDF 10.5: \"not enough free space: need <needed>, free <free>\"; got: $line",
            line!!.startsWith("Stopped: not enough free space: need 900 GB, free "))
        assertEquals(0, phone.opens.get())
    }

    @Test fun `E2E-07 the phone ends the session - new code, saved files kept, finished`() {
        val a = phone.file("1", "a.jpg", Bb.bytes(300_000, 1))
        val b = phone.file("2", "b.jpg", Bb.bytes(300_000, 2))
        val once = AtomicInteger()
        phone.openHook = { uri ->
            if (once.incrementAndGet() == 1) phone.receivers.revokeAll() // the phone stopped sharing / ended this session
            phone.data[uri]?.inputStream()
        }
        val r = receiver()
        approve(r, listOf(a, b))
        assertTrue("no session-ended message:\n${r.output()}",
            r.waitFor("Scan the new code to continue; saved files are kept.", 30_000))
        assertTrue(r.output().contains("The phone ended the session ("))
        approve(r, listOf(a, b), nth = 2)
        assertEquals("output:\n${r.output()}", 0, r.exit())
        assertEquals(setOf("2024-01/a.jpg", "2024-01/b.jpg"), saved().keys)
    }

    @Test fun `E2E-08 gives up after 3 sessions`() {
        val a = phone.file("1", "a.jpg", Bb.bytes(300_000, 1))
        // Every session ends right after its file list was sent.
        phone.realLengthHook = { uri -> phone.receivers.revokeAll(); phone.data[uri]?.size?.toLong() }
        val r = receiver()
        for (n in 1..3) approve(r, listOf(a), nth = n)
        assertEquals("output:\n${r.output()}", 1, r.exit())
        assertTrue(r.output().contains("Gave up after 3 sessions."))
    }

    @Test fun `E2E-09 Ctrl+C mid-transfer keeps finished files, a new run resumes the partial file`() {
        val small = phone.file("1", "small.jpg", Bb.bytes(50_000, 1))
        val bigBytes = Bb.bytes(24 shl 20, 2)
        val big = phone.file("2", "big.mp4", bigBytes, mime = "video/mp4")
        phone.throttleMs = 60
        val r1 = receiver()
        approve(r1, listOf(small, big))
        val parts = File(folder, ".gallery-slim/parts")
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && (parts.listFiles()?.sumOf { it.length() } ?: 0L) < (3L shl 20)) Thread.sleep(100)
        assertTrue("small file finished first", r1.waitFor("small.jpg", 10_000))
        ProcessBuilder("kill", "-INT", r1.pid().toString()).start().waitFor()
        assertEquals("RH-15: Ctrl+C in headless mode exits 1; output:\n${r1.output()}", 1, r1.exit(20_000))
        assertTrue("RK-05: finished file kept", saved().containsKey("2024-01/small.jpg"))
        val partial = parts.listFiles()?.filter { it.name.endsWith(".part") }.orEmpty()
        assertEquals("one partial file in .gallery-slim/parts/: ${partial.map { it.name }}", 1, partial.size)
        assertTrue(partial[0].length() in 1 until bigBytes.size)
        phone.throttleMs = 0
        phone.skips.clear()
        val r2 = receiver()
        approve(r2, listOf(small, big))
        assertEquals("output:\n${r2.output()}", 0, r2.exit())
        assertTrue("resumed from the partial file (phone skipped ${phone.skips})", phone.skips.any { it > 0 })
        assertArrayEquals(bigBytes, saved()["2024-01/big.mp4"])
    }

    @Test fun `E2E-10 partial files untouched for more than 14 days are removed at the start of a run`() {
        val parts = File(folder, ".gallery-slim/parts").apply { mkdirs() }
        val old = File(parts, "x1-10-0123456789ab.part").apply { writeText("old"); setLastModified(System.currentTimeMillis() - 15 * 86_400_000L) }
        val recent = File(parts, "x2-10-0123456789ab.part").apply { writeText("new"); setLastModified(System.currentTimeMillis() - 13 * 86_400_000L) }
        val f = phone.file("1", "a.jpg", Bb.bytes(1_000, 1))
        val r = receiver()
        approve(r, listOf(f))
        assertEquals(0, r.exit())
        assertFalse("15-day-old partial file still there", old.exists())
        assertTrue("13-day-old partial file removed", recent.exists())
    }

    @Test fun `E2E-11 at most 3 files stream at once`() {
        val list = (1..8).map { phone.file("$it", "p$it.jpg", Bb.bytes(1_500_000, it)) }
        phone.throttleMs = 40
        val r = receiver()
        approve(r, list)
        assertEquals(0, r.exit(120_000))
        assertTrue("max ${phone.maxActive.get()} at once", phone.maxActive.get() in 2..3)
    }

    @Test fun `E2E-12 progress line every 5 s`() {
        val f = phone.file("1", "slow.mp4", Bb.bytes(12 shl 20, 1), mime = "video/mp4")
        phone.throttleMs = 230
        val r = receiver()
        approve(r, listOf(f))
        assertEquals(0, r.exit(120_000))
        val prog = r.snapshot().filter { Regex("^ +\\S.* of \\S.* \\(\\d+%\\)$").matches(it) }
        assertTrue("progress lines: $prog\n${r.output()}", prog.size >= 2)
    }
}
