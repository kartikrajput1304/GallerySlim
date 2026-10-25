package com.galleryslim.cleanup

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.MediaStore
import com.galleryslim.diag.GsLog
import com.galleryslim.scan.MediaItem
import java.text.DateFormat
import java.util.Date

/**
 * Contact sheets: a burst folds into one image of small thumbnails, so every moment survives while
 * only the best shot is kept at full quality. Saved to Pictures/GallerySlim/Contact sheets.
 */
object ContactSheet {
    const val CELL = 480
    const val GAP = 12
    const val FOOTER = 72

    /** Columns for n shots: 2 for up to 4, 3 for up to 9, else 4. Pure. */
    fun columns(n: Int): Int = when { n <= 4 -> 2; n <= 9 -> 3; else -> 4 }

    /** Canvas size for n shots. Pure. */
    fun size(n: Int): Pair<Int, Int> {
        val cols = columns(n); val rows = (n + cols - 1) / cols
        return (cols * CELL + (cols + 1) * GAP) to (rows * CELL + (rows + 1) * GAP + FOOTER)
    }

    /** Blocking. Returns the new image's uri, or null on failure (nothing half-written stays visible). */
    fun make(context: Context, shots: List<MediaItem>, best: MediaItem): Uri? {
        val (w, h) = size(shots.size)
        val cols = columns(shots.size)
        val sheet = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet).apply { drawColor(Color.rgb(24, 24, 28)) }
        val frame = Paint().apply { color = Color.rgb(90, 200, 140); style = Paint.Style.STROKE; strokeWidth = 8f }
        shots.forEachIndexed { i, shot ->
            val x = GAP + (i % cols) * (CELL + GAP); val y = GAP + (i / cols) * (CELL + GAP)
            runCatching {
                val t = context.contentResolver.loadThumbnail(Uri.parse(shot.contentUri), android.util.Size(CELL, CELL), null)
                // Centre-crop into the square cell.
                val side = minOf(t.width, t.height)
                val src = Rect((t.width - side) / 2, (t.height - side) / 2, (t.width + side) / 2, (t.height + side) / 2)
                c.drawBitmap(t, src, Rect(x, y, x + CELL, y + CELL), null)
                t.recycle()
            }
            if (shot.id == best.id) c.drawRect(x + 4f, y + 4f, x + CELL - 4f, y + CELL - 4f, frame)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f }
        val date = if (best.dateTakenMs > 0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(best.dateTakenMs)) else ""
        c.drawText("${shots.size} shots · $date · best shot kept in full", GAP.toFloat() + 8, h - FOOTER / 2f + 12, text)

        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Burst_${best.displayName.substringBeforeLast('.')}_sheet.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GallerySlim/Contact sheets")
            if (best.dateTakenMs > 0) put(MediaStore.Images.Media.DATE_TAKEN, best.dateTakenMs)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { sheet.compress(Bitmap.CompressFormat.JPEG, 88, it) } ?: error("no stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            GsLog.w("contactsheet", "couldn't save contact sheet", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        } finally {
            sheet.recycle()
        }
    }
}
