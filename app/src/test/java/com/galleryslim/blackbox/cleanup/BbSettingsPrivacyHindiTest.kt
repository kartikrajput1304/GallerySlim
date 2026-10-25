package com.galleryslim.blackbox.cleanup

import com.galleryslim.core.Preset
import com.galleryslim.shrinkflow.AlbumRules
import com.galleryslim.shrinkflow.ExclusionRules
import com.galleryslim.shrinkflow.RoomPlanner
import com.galleryslim.shrinkflow.ShrinkPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** PDF 9.6 Settings (page 71), 9.7 Privacy / How it works (pages 72-73), 9.8 Hindi (pages 73-74), 9.5 Notifications (pages 69-70). */
class BbSettingsPrivacyHindiTest {
    private val savedLocale: Locale = Locale.getDefault()
    @After fun restoreLocale() = Locale.setDefault(savedLocale)

    private val en by lazy { AppSource.strings("values") }
    private val hi by lazy { AppSource.strings("values-hi") }

    // ---------------------------------------------------------------- settings

    @Test fun `ST-01 Keep this much space free offers Off, 5, 10, 15, 20 GB`() {
        assertEquals(listOf(0, 5, 10, 15, 20), RoomPlanner.FLOOR_CHOICES_GB)
    }

    @Test fun `ST-02 Lighter copies of old photos offers Off, 1, 2, 3 years`() {
        assertEquals(listOf(0, 1, 2, 3), AlbumRules.COOL_CHOICES)
    }

    @Test fun `ST-03 Protect new photos for offers 7, 30, 90 days with 30 as default`() {
        assertEquals(listOf(7, 30, 90), ExclusionRules.WINDOW_CHOICES)
        assertEquals(30, ShrinkPrefs(FakeContext()).protectDays)
    }

    @Test fun `ST-04 defaults - Gentle preset, overnight off, lock off, vault off, no motion-clip folder`() {
        val p = ShrinkPrefs(FakeContext())
        assertEquals(Preset.GENTLE, p.preset)
        assertFalse(p.overnightEnabled); assertFalse(p.appLock); assertFalse(p.vaultEnabled)
        assertEquals(null, p.motionExportDirUri)
    }

    @Test fun `ST-05 Settings sections and options read as in the PDF`() {
        val want = mapOf(
            "settings_title" to "Settings", "section_quality" to "Quality", "section_automatic" to "Automatic",
            "section_safety" to "Safety", "section_other" to "Other", "settings_preset" to "How much to shrink",
            "preset_gentle" to "Gentle", "preset_balanced" to "Balanced", "preset_aggressive" to "Aggressive", "preset_light" to "Light copy",
            "settings_overnight" to "Shrink overnight", "settings_floor" to "Keep this much space free",
            "settings_cool" to "Lighter copies of old photos", "settings_rules" to "Your rules",
            "settings_rules_caption" to "Five questions that decide what gets suggested for clearing",
            "settings_protect" to "Protect new photos for", "settings_lock" to "Lock with fingerprint or PIN",
            "settings_lock_caption" to "Asks for your fingerprint or PIN before showing the originals that are ready to clear, and the vault",
            "settings_vault" to "Safety vault",
            "settings_vault_caption" to "Keeps an exact copy of every original in a folder you choose, before anything is shrunk",
            "settings_vault_space_warning" to "A vault on this phone's own storage uses as much space as the originals. Pick an SD card, USB drive or a cloud-synced folder.",
            "settings_open_vault" to "Open vault", "settings_open_vault_caption" to "See and restore the copies kept in the vault",
            "settings_open_vault_unset" to "Choose the vault folder", "settings_motion_folder" to "Motion-clip export folder",
            "settings_privacy" to "Privacy", "settings_privacy_caption" to "What GallerySlim does and doesn't do with your photos",
        )
        val wrong = want.filter { (k, v) -> en[k] != v }.map { (k, v) -> "$k: want '$v', got '${en[k]}'" }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test fun `ST-06 Settings has no language, theme, version or Share log entry`() {
        val sheet = AppSource.file("java/com/galleryslim/ui/SettingsSheet.kt")
        val found = listOf("Language", "Theme", "Version", "Share log", "Dark mode").filter { "\"$it" in sheet }
        assertTrue("unexpected Settings entries: $found", found.isEmpty())
    }

    // ---------------------------------------------------------------- privacy & how it works

    @Test fun `PR-01 the Privacy page has the six PDF sections with their exact text`() {
        val missing = AppSource.missing(
            "Everything happens on your phone",
            "Photos and videos are shrunk, checked and sorted on this phone. They are never uploaded to us or anyone else.",
            "No account, no ads, no tracking",
            "GallerySlim has no sign-in, shows no ads and contains no analytics or tracking code.",
            "Internet is used only for Send to computer",
            "The app uses the network only to send photos to a computer on your own Wi-Fi. Sharing runs while its notification is showing (tap Stop sharing to end it), sends only to the computer whose code you scanned and confirmed with your fingerprint or PIN (or, on a phone without a screen lock, with a confirmation message), and stops by itself 30 minutes after the last transfer or when Wi-Fi drops. Nothing goes over the internet.",
            "Nothing is deleted without you",
            "Originals move to Android's trash only after you confirm, and stay restorable there for 30 days. Two things delete for good, each only after you confirm in the app and again in Android's own dialog: \"Free this space now\" (originals that already have a checked smaller copy) and \"Send the trash to computer\" (files the GallerySlim Receiver has checked byte for byte on your computer).",
            "Text reading stays on the phone",
            "Clean up reads text in photos to spot receipts and ID documents. This uses an on-device model; no image or text leaves the phone. ID documents are never suggested for deletion.",
            "Your diagnostic log stays here",
            "The app keeps a small log of what it did, on the phone. It is shared only if you tap Share diagnostic log yourself. App data is excluded from Google backups and phone-to-phone transfers.",
        )
        assertTrue("missing or changed Privacy texts:\n${missing.joinToString("\n")}", missing.isEmpty())
    }

    @Test fun `PR-02 How it works has the three steps, five questions and the left-alone answer`() {
        val missing = AppSource.missing(
            "GallerySlim makes a smaller copy", "The original waits in Ready to clear", "You clear the originals",
            "Common questions", "Will my photos look worse?", "Can I undo?", "Does anything get uploaded?",
            "What about Google Photos?", "Which photos are left alone?",
            "Favourites, photos from the last 30 days, HDR photos, ID documents and albums you protect. You can still pick them by hand.",
        )
        assertTrue("missing or changed How-it-works texts:\n${missing.joinToString("\n")}", missing.isEmpty())
    }

    @Test fun `ST-08 app data is excluded from Google backups and phone-to-phone transfers`() {
        val app = AppSource.xmlAttrs("AndroidManifest.xml", "application").single()
        assertEquals("false", app["android:allowBackup"])
        val rules = app["android:dataExtractionRules"]
        assertTrue("no data extraction rules", rules != null)
        val file = "res/xml/" + rules!!.removePrefix("@xml/") + ".xml"
        for (section in listOf("cloud-backup", "device-transfer")) {
            val sec = AppSource.xmlAttrs(file, section)
            assertEquals("$section present", 1, sec.size)
            assertTrue("$section must not include anything", AppSource.xmlAttrs(file, "include").isEmpty())
        }
        val excluded = AppSource.xmlAttrs(file, "exclude").map { it["domain"] to it["path"] }
        for (d in listOf("root", "file", "database", "sharedpref")) {
            assertEquals("domain $d excluded in both sections", 2, excluded.count { it == (d to ".") })
        }
    }

    // ---------------------------------------------------------------- notifications

    @Test fun `NT-01 notification channels and texts in this area are shipped`() {
        val missing = AppSource.missing(
            "Weekly tidy-up", "Your weekly tidy-up is ready", "Your rules picked what to clear. Take a look, then confirm once.",
            "Monthly report", "Room for about ", " more photos. Tap to see and share.",
            "Low space", "Running low: ", "Make room now, before the camera says it's full?",
            "Android's daily time limit for background work was reached. What finished is in Ready to clear; the rest continues next time.",
        )
        assertTrue("missing texts: $missing", missing.isEmpty())
    }

    // ---------------------------------------------------------------- Hindi

    @Test fun `HI-01 the Hindi file has the 44 resources and covers every English string`() {
        assertEquals(44, hi.size)
        assertEquals(en.keys, hi.keys)
    }

    @Test fun `HI-02 the shipped Hindi texts match the PDF table`() {
        val want = mapOf(
            "settings_title" to "सेटिंग्स", "settings_preset" to "कितना छोटा करें",
            "preset_gentle" to "हल्का", "preset_balanced" to "संतुलित", "preset_aggressive" to "ज़्यादा", "preset_light" to "हल्की कॉपी",
            "section_quality" to "क्वालिटी", "section_automatic" to "अपने आप", "section_safety" to "सुरक्षा", "section_other" to "अन्य",
            "settings_overnight" to "रात में छोटा करें", "settings_floor" to "इतनी जगह हमेशा खाली रखें",
            "settings_cool" to "पुरानी फ़ोटो की हल्की कॉपी", "settings_rules" to "आपके नियम",
            "settings_protect" to "नई फ़ोटो को सुरक्षित रखें", "settings_days" to "%1\$d दिन",
            "settings_lock" to "फ़िंगरप्रिंट या PIN से लॉक करें", "settings_vault" to "सेफ़्टी वॉल्ट", "settings_open_vault" to "वॉल्ट खोलें",
            "settings_motion_folder" to "मोशन-क्लिप एक्सपोर्ट फ़ोल्डर", "settings_not_set" to "सेट नहीं है", "settings_privacy" to "प्राइवेसी",
            "settings_off" to "बंद", "settings_gb" to "%1\$d GB", "settings_years" to "%1\$d साल से पुरानी",
        )
        val wrong = want.filter { (k, v) -> hi[k] != v }.map { (k, v) -> "$k: want '$v', got '${hi[k]}'" }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test fun `HI-03 GallerySlim is not translated`() {
        assertEquals("GallerySlim", hi["app_name"])
    }

    @Test fun `HI-04 every Hindi text keeps the English placeholders`() {
        val ph = Regex("%\\d+\\$[a-z]")
        val wrong = en.filter { (k, v) -> ph.findAll(v).map { it.value }.toList() != ph.findAll(hi[k].orEmpty()).map { it.value }.toList() }.keys
        assertTrue("placeholder mismatch: $wrong", wrong.isEmpty())
    }

    @Test fun `HI-05 only English and Hindi resources exist, and Share small is labelled in English`() {
        val folders = File(AppSource.mainDir, "res").listFiles()!!.filter { File(it, "strings.xml").isFile }.map { it.name }.toSet()
        assertEquals(setOf("values", "values-hi"), folders)
        val share = AppSource.xmlAttrs("AndroidManifest.xml", "activity").single { it["android:name"] == ".sharesmall.ShareSmallActivity" }
        assertEquals("Share small", share["android:label"])
    }

    @Test fun `HI-06 app sizes use Western digits and a dot decimal on a Hindi phone`() {
        Locale.setDefault(Locale.forLanguageTag("hi-IN"))
        assertEquals("4.2 GB", com.galleryslim.ui.formatBytes((4.2 * (1L shl 30)).toLong()))
        assertEquals("850 MB", com.galleryslim.ui.formatBytes(850L * (1L shl 20)))
    }
}
