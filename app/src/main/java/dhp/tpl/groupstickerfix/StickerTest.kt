package dhp.tpl.groupstickerfix

import android.content.ContentResolver
import android.content.ClipDescription
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import de.robv.android.xposed.XposedBridge

/**
 * "Does this image look like a sticker?" – a cut-out object on a transparent background.
 * Used for every input route (keyboard, clipboard, drag & drop) so behaviour is the same everywhere.
 * Photos / screenshots (no alpha, or no transparent area) are never stickers.
 */
object StickerTest {
    private const val MAX_SIDE = 256          // analyse a downsampled copy
    private const val MIN_TRANSPARENT = 0.12  // >= 12% of pixels (almost) fully transparent
    private const val MIN_OPAQUE = 0.04       // >= 4% of pixels (almost) opaque = there is an object

    /** Clip metadata that marks an item as a sticker/cut-out (Samsung gallery object capture, keyboards). */
    fun tagged(desc: ClipDescription?): Boolean {
        desc ?: return false
        val parts = ArrayList<String>()
        desc.label?.toString()?.let(parts::add)
        for (i in 0 until desc.mimeTypeCount) parts.add(desc.getMimeType(i))
        try { desc.extras?.keySet()?.let(parts::addAll) } catch (_: Throwable) {}
        return parts.any { it.contains("sticker", true) || it.contains("object_capture", true) }
    }

    fun looksLikeSticker(cr: ContentResolver, uri: Uri): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val type = bounds.outMimeType.orEmpty()
        if (bounds.outWidth <= 0 || (type != "image/png" && type != "image/webp")) {
            false
        } else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            if (bmp == null || !bmp.hasAlpha()) {
                false
            } else {
                val w = bmp.width; val h = bmp.height
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                var transparent = 0; var opaque = 0
                for (p in px) {
                    val a = p ushr 24
                    if (a <= 16) transparent++ else if (a >= 200) opaque++
                }
                bmp.recycle()
                val tf = transparent.toDouble() / px.size
                val of = opaque.toDouble() / px.size
                XposedBridge.log("${Resolver.TAG}: sticker test ${bounds.outWidth}x${bounds.outHeight} transparent=${"%.2f".format(tf)} opaque=${"%.2f".format(of)}")
                tf >= MIN_TRANSPARENT && of >= MIN_OPAQUE
            }
        }
    } catch (t: Throwable) {
        XposedBridge.log("${Resolver.TAG}: sticker test failed: $t")
        false
    }
}
