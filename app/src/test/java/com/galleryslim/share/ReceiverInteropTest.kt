package com.galleryslim.share

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The real Go receiver (headless) against the real Kotlin phone server, end to end on 127.0.0.1.
 * The receiver's terminal QR is "scanned" with ZXing, exactly the code the phone's camera would read.
 * Runs only when GS_RECEIVER_BIN points at a built receiver binary.
 */
class ReceiverInteropTest {
    private val bin = System.getenv("GS_RECEIVER_BIN")

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Turns the half-block terminal rendering back into an image and decodes it. */
    private fun scan(lines: List<String>): String? {
        val ansi = lines.any { it.contains("\u001b[30;107m") }
        val rows = lines.map { it.replace(Regex("\u001b\\[[0-9;]*m"), "") }.filter { l -> l.any { it in "█▀▄" } }
        if (rows.size < 10) return null
        val width = rows.maxOf { it.length }
        val h = rows.size * 2
        val scale = 6
        val px = IntArray(width * scale * h * scale) { 0xFFFFFF }
        rows.forEachIndexed { r, line ->
            line.padEnd(width).forEachIndexed { x, ch ->
                var top = ch == '█' || ch == '▀'
                var bottom = ch == '█' || ch == '▄'
                if (!ansi) { top = !top; bottom = !bottom } // plain mode draws the light modules
                for ((half, dark) in listOf(0 to top, 1 to bottom)) if (dark) {
                    val y0 = (r * 2 + half) * scale
                    for (dy in 0 until scale) for (dx in 0 until scale) px[(y0 + dy) * width * scale + x * scale + dx] = 0
                }
            }
        }
        return runCatching {
            QRCodeReader().decode(
                BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width * scale, h * scale, px))),
                mapOf(DecodeHintType.TRY_HARDER to true),
            ).text
        }.getOrNull()
    }

    @Test
    fun `go receiver pulls, saves by month and verifies every file`() {
        assumeTrue("set GS_RECEIVER_BIN to run", bin != null && File(bin).canExecute())
        val photo = ByteArray(2_500_000) { (it * 13 + 1).toByte() }
        val clip = ByteArray(700_000) { (it * 7 + 3).toByte() }
        val files = mapOf("content://x/1" to photo, "content://x/2" to clip, "content://x/3" to "same name".toByteArray())
        val shareFiles = listOf(
            ShareSessions.ShareFile("1", "IMG_0001.jpg", photo.size.toLong(), "image/jpeg", "content://x/1", 1_700_000_000_000),
            ShareSessions.ShareFile("2", "VID_0002.mp4", clip.size.toLong(), "video/mp4", "content://x/2", 1_710_000_000_000),
            ShareSessions.ShareFile("3", "IMG_0001.jpg", 9, "image/jpeg", "content://x/3", 1_700_000_000_000),
        )
        val receivers = ReceiverSessions()
        val verified = java.util.Collections.synchronizedList(mutableListOf<String>())
        val api = object : LanHttpServer.ReceiverApi {
            override val sessions = receivers
            override val phoneName = "Interop Phone"
            override val shortCode = "IT-11"
            override fun open(uri: String): InputStream? = files[uri]?.let { ByteArrayInputStream(it) }
            override fun realLength(uri: String) = files[uri]?.size?.toLong()
            override fun hash(uri: String, onProgress: () -> Unit) = files[uri]?.let { sha(it) to it.size.toLong() }
            override fun onBytes(bytes: Long) {}
            override fun onVerified(file: ShareSessions.ShareFile, computer: String, sha256: String, bytes: Long) { verified += file.id }
            override fun onStreaming(active: Boolean) {}
        }
        val server = LanHttpServer(
            InetAddress.getByName("127.0.0.1"), ShareSessions(), object : android.content.ContentResolver(null) {},
            { _, _ -> "" }, { "" }, peerAllowed = { true }, receiver = api,
        )
        val port = server.start(18790)
        val folder = Files.createTempDirectory("gs-interop").toFile()
        val home = Files.createTempDirectory("gs-home").toFile()
        try {
            val pb = ProcessBuilder(bin, "--headless", "--folder", folder.absolutePath, "--phone", "127.0.0.1:$port")
                .redirectErrorStream(true)
            pb.environment()["HOME"] = home.absolutePath
            pb.environment()["XDG_CONFIG_HOME"] = File(home, ".config").absolutePath
            val proc = pb.start()
            val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
            val reader = Thread { proc.inputStream.bufferedReader().forEachLine { lines += it } }.apply { start() }
            // Wait for the QR, "scan" it, approve as the owner would after the fingerprint.
            var approved = false
            val deadline = System.currentTimeMillis() + 20_000
            while (!approved && System.currentTimeMillis() < deadline) {
                Thread.sleep(200)
                val payload = scan(synchronized(lines) { lines.toList() })
                if (payload != null) {
                    assertTrue("receiver shows a gs2 code: $payload", payload.startsWith("gs2:"))
                    approved = receivers.approve(payload, shareFiles) != null
                }
            }
            assertTrue("QR scanned and approved; output:\n" + lines.joinToString("\n"), approved)
            assertTrue("receiver finished; output:\n" + lines.joinToString("\n"), proc.waitFor(60, TimeUnit.SECONDS))
            reader.join(2_000)
            assertEquals("exit code; output:\n" + lines.joinToString("\n"), 0, proc.exitValue())
            val saved = folder.walkTopDown().filter { it.isFile && !it.path.contains(".gallery-slim") }.toList()
            assertEquals(saved.joinToString { it.path }, 3, saved.size)
            val byContent = saved.associateBy { sha(it.readBytes()) }
            assertArrayEquals(photo, byContent.getValue(sha(photo)).readBytes())
            assertArrayEquals(clip, byContent.getValue(sha(clip)).readBytes())
            assertTrue("month folders", saved.all { it.parentFile.name.matches(Regex("\\d{4}-\\d{2}")) })
            assertTrue("same name kept apart, nothing overwritten", saved.map { it.name }.toSet().size == 3)
            assertEquals(setOf("1", "2", "3"), verified.toSet())
        } finally {
            server.stop()
            folder.deleteRecursively(); home.deleteRecursively()
        }
    }
}
