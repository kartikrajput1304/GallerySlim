package com.galleryslim.blackbox.share

import com.galleryslim.share.LanHttpServer
import com.galleryslim.share.QrCodes
import com.galleryslim.share.SharePage
import com.galleryslim.share.ShareSessions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 8.2 "Send to computer via a browser (gs1)" (pages 61–62) and the computer labels of 8.5.
 *
 * Note: on the JVM the phone cannot read real gallery files (Android's content resolver is a
 * stub), so ZIP entries and single files arrive empty here; names, folders, order, headers and
 * access rules are still the real server's. "Fully sent" marking needs a device.
 */
class BrowserSendTest {
    private val phone = BbPhone(
        name = "Pixel 8",
        pageHtml = { c, svg -> SharePage.html(c.id, c.secret, svg, "Pixel 8") },
        qrSvg = { QrCodes.svg(it) },
    ).start()

    @After fun tearDown() = phone.stop()

    private fun page(): Pair<ShareSessions.Created, String> {
        val before = phone.created.size
        val r = phone.http("GET", "/")
        assertEquals(200, r.status)
        return phone.created[before] to r.text
    }

    private fun approved(vararg files: ShareSessions.ShareFile): ShareSessions.Created {
        val (c, _) = page()
        assertTrue(phone.gs1.approve(ShareSessions.qrPayload(c), files.toList()))
        return c
    }

    private fun text(html: String) = html.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ")

    @Test fun `GS1-02 the page - title, heading, phone, QR, steps, waiting`() {
        val (c, html) = page()
        assertTrue(html.contains("<title>GallerySlim · Send to this computer</title>"))
        val t = text(html)
        for (s in listOf(
            "Send to this computer", "From Pixel 8 · only this screen can receive",
            "On the phone, tap Scan the laptop's code", "Point the camera at this square.",
            "Confirm with your fingerprint or PIN.", "Waiting for the phone…",
        )) assertTrue("page lacks \"$s\"", t.contains(s))
        val svg = Regex("<svg.*?</svg>", RegexOption.DOT_MATCHES_ALL).find(html)?.value
        assertEquals("the QR on the page is this page's own code", ShareSessions.qrPayload(c), svg?.let { Bb.scanSvg(it) })
    }

    @Test fun `GS1-05 the page checks every 1,5 s (static)`() {
        val (_, html) = page()
        assertTrue(html.contains("setTimeout(poll,1500)"))
    }

    @Test fun `GS1-06 after approval - Ready to save, n files and size, three choices (static)`() {
        val (_, html) = page()
        for (s in listOf(
            "Ready to save", "' files · '", "Save all as one organized file",
            "One ZIP in your Downloads folder. Unzip it: photos are sorted into <b>GallerySlim / year-month</b> folders.",
            "Save into a folder I choose", "Sorted into year-month folders inside the folder you pick.",
            "Download files one by one instead",
        )) assertTrue("page lacks \"$s\"", html.contains(s))
    }

    @Test fun `GS1-07 the folder choice is shown only with folder access in a secure context (static)`() {
        val (_, html) = page()
        assertTrue(html.contains("""<button class="btn alt hidden" id="pick">"""))
        assertTrue(html.contains("if(window.showDirectoryPicker&&window.isSecureContext){q('pick').classList.remove('hidden')"))
    }

    @Test fun `GS1-08 expired page texts (static)`() {
        val (_, html) = page()
        assertTrue(html.contains("<h1>This link has expired</h1><p>Reload the page on the laptop and scan the new code.</p>"))
        assertTrue(html.contains("if(j.state==='expired')"))
    }

    @Test fun `GS1-16 ZIP button - Preparing, then after a 1,5 s timer Saved as one file and Check your Downloads folder (static)`() {
        val (_, html) = page()
        assertTrue(html.contains("Preparing your file…"))
        assertTrue(html.contains("setTimeout(()=>{q('zip').textContent='Saved as one file ✓';q('rtitle').textContent='Check your Downloads folder'},1500)"))
    }

    @Test fun `GS1-17 one by one - original names, no folders, 2 at a time, end texts (static)`() {
        val (_, html) = page()
        assertTrue(html.contains("a.download=f.name"))
        assertTrue(html.contains("{length:dir?4:2}"))
        for (s in listOf("'✓ saved'", "'failed'", "'All saved ✓'", "' saved'", "'Saved to your Downloads folder.'")) {
            assertTrue("page lacks $s", html.contains(s))
        }
    }

    @Test fun `GS1-18 folder picker - chosen-GallerySlim-yyyy-MM or undated, 4 at a time, (2) on clashes (static)`() {
        val (_, html) = page()
        assertTrue(html.contains("getDirectoryHandle('GallerySlim',{create:true})"))
        assertTrue(html.contains("getDirectoryHandle(f.folder||'undated',{create:true})"))
        assertTrue(html.contains("' ('+(n++)+')'"))
        assertTrue(html.contains("'Saved into the folder you chose, sorted by month.'"))
    }

    @Test fun `GS1-19 browser page uses binary units (static)`() {
        val (_, html) = page()
        assertTrue(html.contains("b>=1<<30?(b/(1<<30)).toFixed(1)+' GB':b>=1<<20?(b/(1<<20)).toFixed(0)+' MB':(b/1024).toFixed(0)+' KB'"))
    }

    private fun zip(c: ShareSessions.Created, ua: String? = null): Pair<Bb.Resp, List<ZipEntry>> {
        val r = phone.http("GET", "/api/zip?s=${c.id}&t=${c.secret}", headers = listOfNotNull(ua?.let { "User-Agent" to it }).toMap())
        assertEquals(200, r.status)
        val body = if (r.headers["transfer-encoding"] == "chunked") Bb.unchunk(r.body) else r.body
        val entries = mutableListOf<ZipEntry>()
        ZipInputStream(ByteArrayInputStream(body)).use { z ->
            while (true) { val e = z.nextEntry ?: break; z.readBytes(); entries += e }
        }
        return r to entries
    }

    @Test fun `GS1-09 ZIP named GallerySlim yyyy-MM-dd HH-mm (phone time)`() {
        val c = approved(phone.file("1", "a.jpg", Bb.bytes(10, 1)))
        val before = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())
        val (r, _) = zip(c)
        val after = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())
        val cd = r.headers["content-disposition"].orEmpty()
        val name = Regex("filename=\"([^\"]+)\"").find(cd)?.groupValues?.get(1)
        assertTrue("ZIP name $name", name == "GallerySlim $before.zip" || name == "GallerySlim $after.zip")
        assertEquals("application/zip", r.headers["content-type"])
    }

    @Test fun `GS1-10 GS1-11 GS1-13 ZIP layout - month folders, undated, duplicates, unsafe characters`() {
        val jul2023 = 1_688_465_000_000L // 2023-07-04
        val c = approved(
            phone.file("1", "IMG_1.jpg", Bb.bytes(10, 1)),
            phone.file("2", "IMG_1.jpg", Bb.bytes(10, 2)),
            phone.file("3", "IMG_1.jpg", Bb.bytes(10, 3), taken = 0),
            phone.file("4", "x.jpg", Bb.bytes(10, 4), taken = jul2023),
            phone.file("5", "a/b\u0001c.jpg", Bb.bytes(10, 5)),
            phone.file("6", "d\\e.jpg", Bb.bytes(10, 6)),
            phone.file("7", "../../etc/passwd", Bb.bytes(10, 7)),
            phone.file("8", "IMG_1.jpg", Bb.bytes(10, 8)),
        )
        val (_, entries) = zip(c)
        val names = entries.map { it.name }
        assertEquals(
            listOf(
                "GallerySlim/2024-01/IMG_1.jpg", "GallerySlim/2024-01/IMG_1 (2).jpg", "GallerySlim/undated/IMG_1.jpg",
                "GallerySlim/2023-07/x.jpg", "GallerySlim/2024-01/a_b_c.jpg", "GallerySlim/2024-01/d_e.jpg",
                "GallerySlim/2024-01/.._.._etc_passwd", "GallerySlim/2024-01/IMG_1 (3).jpg",
            ),
            names,
        )
        assertTrue("an entry escapes GallerySlim/", names.all { it.startsWith("GallerySlim/") && it.count { ch -> ch == '/' } == 2 })
    }

    @Test fun `GS1-12 ZIP entry time is the date taken`() {
        val taken = 1_700_000_000_000L
        val c = approved(phone.file("1", "a.jpg", Bb.bytes(10, 1), taken = taken))
        val (_, entries) = zip(c)
        assertTrue("entry time ${entries[0].time} vs $taken", kotlin.math.abs(entries[0].time - taken) <= 2_000)
    }

    @Test fun `GS1-14 ZIP has no compression - DEFLATE at level 0 (new PDF p61)`() {
        val c = approved(phone.file("1", "a.jpg", Bb.bytes(10, 1)))
        val (_, entries) = zip(c)
        assertEquals("compression method of the entry (8 = deflate)", ZipEntry.DEFLATED, entries[0].method)
    }

    @Test fun `GS1-21 one-by-one download keeps the original name`() {
        val c = approved(phone.file("1", "दिवाली फोटो.jpg", Bb.bytes(10, 1)))
        val r = phone.http("GET", "/api/file?s=${c.id}&t=${c.secret}&f=1")
        assertEquals(200, r.status)
        val cd = r.headers["content-disposition"].orEmpty()
        val utf8 = Regex("filename\\*=UTF-8''(\\S+)").find(cd)?.groupValues?.get(1)
        assertEquals("दिवाली फोटो.jpg", utf8?.let { URLDecoder.decode(it, "UTF-8") })
        assertTrue(cd.startsWith("attachment"))
        assertTrue("an empty or failed single download must not count as fully sent", phone.delivered.isEmpty())
    }

    @Test fun `MOV-04 computer labels for browser sends`() {
        val ip = "192.168.1.19"
        val cases = mapOf(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36" to "Windows PC",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_1) AppleWebKit/605.1.15 Version/17.1 Safari/605.1.15" to "Mac",
            "Mozilla/5.0 (X11; CrOS x86_64 15633.69.0) AppleWebKit/537.36 Chrome/119 Safari/537.36" to "Chromebook",
            "Mozilla/5.0 (Linux; Android 14; SM-S921B) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36" to "Android device",
            "Mozilla/5.0 (X11; Linux x86_64; rv:121.0) Gecko/20100101 Firefox/121.0" to "Linux computer",
            "curl/8.4.0" to "Computer",
            "" to "Computer",
        )
        for ((ua, kind) in cases) assertEquals(ua, "$kind ($ip)", LanHttpServer.computerLabel(ua, ip))
        assertEquals("Computer ($ip)", LanHttpServer.computerLabel(null, ip))
    }
}
