package com.photostamp.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlin.math.ceil
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object Stamper {

    /** User-controlled stamp appearance. sizeScale 1.0 = default size.
     *  corner: 0 bottom-right, 1 bottom-left, 2 top-right, 3 top-left.
     *  backdrop: 0 text shadow only, 1 translucent dark panel. */
    data class StampStyle(
        val fontIndex: Int, val bold: Boolean, val sizeScale: Float,
        val corner: Int = 0, val backdrop: Int = 0,
    )

    val FONT_LABELS = listOf("Monospace", "Sans-serif", "Serif", "Condensed")
    val CORNER_LABELS = listOf("Bottom right", "Bottom left", "Top right", "Top left")
    val BACKDROP_LABELS = listOf("Shadow", "Dark panel")

    private fun typefaceFor(style: StampStyle): Typeface {
        val base = when (style.fontIndex) {
            1 -> Typeface.SANS_SERIF
            2 -> Typeface.SERIF
            3 -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            else -> Typeface.MONOSPACE
        }
        return Typeface.create(base, if (style.bold) Typeface.BOLD else Typeface.NORMAL)
    }

    /**
     * Burns the overlay onto a full-quality copy. All standard EXIF from the
     * source is preserved, then our additions (GPS, UserComment) are written.
     *
     * If [replaceOriginal] and [originalPath] is set, the original file is
     * overwritten in place (JPEG only, needs All-files access). Otherwise a
     * *_stamped.jpg is written into [destRelativePath] (the source's folder),
     * falling back to Pictures/PhotoStamp if that folder can't take it. Returns
     * the resulting Uri, or null on failure.
     *
     * Note: maker notes (manufacturer-proprietary) are not carried by ExifInterface and
     * cannot survive a re-encode; standard EXIF tags are preserved.
     */
    fun stamp(
        ctx: Context,
        src: Uri,
        text: StampText,
        lat: Double?,
        lon: Double?,
        altM: Double?,
        origName: String,
        destRelativePath: String?,
        userComment: String?,
        replaceOriginal: Boolean,
        originalPath: String?,
        mime: String,
        style: StampStyle,
    ): Uri? {
        val resolver = ctx.contentResolver

        // --- read source EXIF (attrs + orientation) before touching anything ---
        val srcAttrs = HashMap<String, String>()
        var orientation = ExifInterface.ORIENTATION_NORMAL
        resolver.openInputStream(src)?.use {
            val ex = ExifInterface(it)
            orientation = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            for (t in COPY_TAGS) ex.getAttribute(t)?.let { v -> srcAttrs[t] = v }
        } ?: return null

        // --- decode upright, draw overlay ---
        val opts = BitmapFactory.Options().apply { inMutable = true }
        val decoded = resolver.openInputStream(src)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        val upright = applyOrientation(decoded, orientation)
        if (upright !== decoded) decoded.recycle()
        val canvasBmp = if (upright.isMutable) upright
            else upright.copy(Bitmap.Config.ARGB_8888, true).also { upright.recycle() }
        drawOverlay(canvasBmp, text, style)

        val jpeg = ByteArrayOutputStream().also {
            canvasBmp.compress(Bitmap.CompressFormat.JPEG, 100, it)
        }.toByteArray()
        canvasBmp.recycle()

        // --- write bytes + EXIF ---
        val canReplace = replaceOriginal && !originalPath.isNullOrBlank() && mime == "image/jpeg" &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager())
        if (replaceOriginal && !canReplace)
            AppLog.log("Can't replace $origName in place; saving a stamped copy instead.")
        if (canReplace) {
            try {
                FileOutputStream(originalPath!!).use { it.write(jpeg) }
                val ex = ExifInterface(originalPath)
                applyExif(ex, srcAttrs, lat, lon, altM, userComment)
                MediaScannerConnection.scanFile(ctx, arrayOf(originalPath), arrayOf("image/jpeg"), null)
                return src
            } catch (e: Exception) {
                AppLog.log("Replace failed (${e.javaClass.simpleName}); saving a copy instead.")
            }
        }
        val outName = origName.substringBeforeLast('.') + "_stamped.jpg"
        val folder = destRelativePath?.takeIf { it.isNotBlank() }
        return writeCopy(ctx, jpeg, outName, folder, srcAttrs, lat, lon, altM, userComment)
            ?: if (folder != null) writeCopy(ctx, jpeg, outName, null, srcAttrs, lat, lon, altM, userComment)
            else null
    }

    private fun writeCopy(
        ctx: Context, jpeg: ByteArray, outName: String, relPath: String?,
        srcAttrs: Map<String, String>, lat: Double?, lon: Double?, altM: Double?, userComment: String?,
    ): Uri? {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, outName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relPath ?: "Pictures/PhotoStamp")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val outUri = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null
        return try {
            resolver.openOutputStream(outUri)?.use { it.write(jpeg) } ?: throw IllegalStateException("no stream")
            try {
                resolver.openFileDescriptor(outUri, "rw")?.use {
                    applyExif(ExifInterface(it.fileDescriptor), srcAttrs, lat, lon, altM, userComment)
                }
            } catch (_: Exception) { }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(outUri, values, null, null)
            }
            outUri
        } catch (e: Exception) {
            runCatching { resolver.delete(outUri, null, null) }
            null
        }
    }

    private fun applyExif(
        ex: ExifInterface, srcAttrs: Map<String, String>,
        lat: Double?, lon: Double?, altM: Double?, userComment: String?,
    ) {
        for ((t, v) in srcAttrs) if (t !in SKIP_ON_WRITE) ex.setAttribute(t, v)
        ex.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        if (lat != null && lon != null) ex.setLatLong(lat, lon)
        if (altM != null && altM.isFinite()) ex.setAltitude(altM)
        userComment?.let { ex.setAttribute(ExifInterface.TAG_USER_COMMENT, it) }
        ex.saveAttributes()
    }

    private fun applyOrientation(bmp: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun textPaint(px: Float, style: StampStyle): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = px
        typeface = typefaceFor(style)
        setShadowLayer(maxOf(1.2f, px * 0.12f), 0f, 0f, Color.argb(200, 0, 0, 0))
    }

    /** Draws the stamp onto [bmp] exactly as it appears on photos (used by the preview). */
    fun drawOverlay(bmp: Bitmap, text: StampText, style: StampStyle) {
        val mainLines = text.lines; val weatherParts = text.weather; val microLine = text.micro
        val w = bmp.width
        val canvas = Canvas(bmp)
        // Size from the short side, so portrait and landscape shots match.
        val ref = minOf(w, bmp.height) * 1.5f
        val mainPx = maxOf(11f, ref / 170f) * style.sizeScale
        val microPx = maxOf(8f, ref / 360f) * style.sizeScale
        val margin = mainPx * 0.9f
        val lineGap = 1.3f

        val mainPaint = textPaint(mainPx, style)
        val microPaint = textPaint(microPx, style)

        data class L(val text: String, val paint: Paint, val px: Float)
        val items = ArrayList<L>()
        for (ln in mainLines) items.add(L(ln, mainPaint, mainPx))

        // Wrap the weather so its block is no wider than the rest of the stamp.
        val otherWidths = ArrayList<Float>()
        mainLines.forEach { otherWidths.add(mainPaint.measureText(it)) }
        microLine?.let { otherWidths.add(microPaint.measureText(it)) }
        val target = otherWidths.maxOrNull() ?: (w * 0.28f)
        for (ln in packWeather(weatherParts, mainPaint, target))
            items.add(L(ln, mainPaint, mainPx))

        microLine?.let { items.add(L(it, microPaint, microPx)) }
        if (items.isEmpty()) return

        val blockHeight = items.fold(0f) { acc, l -> acc + l.px * lineGap }
        val blockWidth = items.maxOf { it.paint.measureText(it.text) }
        val right = style.corner == 0 || style.corner == 2
        val bottom = style.corner == 0 || style.corner == 1
        val pad = mainPx * 0.6f
        val inset = margin + if (style.backdrop == 1) pad else 0f
        var top = if (bottom) bmp.height - inset - blockHeight else inset

        if (style.backdrop == 1) {
            val left = if (right) w - inset - blockWidth else inset
            val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 0, 0, 0) }
            canvas.drawRoundRect(
                RectF(left - pad, top - pad * 0.6f, left + blockWidth + pad, top + blockHeight + pad * 0.4f),
                pad, pad, panel,
            )
        }
        for (l in items) {
            val baseline = top + l.px
            val x = if (right) w - inset - l.paint.measureText(l.text) else inset
            canvas.drawText(l.text, x, baseline, l.paint)
            top += l.px * lineGap
        }
    }

    /**
     * Lay weather fields out so the block is about [target] wide (the width of the
     * rest of the stamp). Short selections stay on one line; longer ones wrap into
     * as many evenly-filled lines as needed to stay within that width.
     */
    private fun packWeather(parts: List<String>, paint: Paint, target: Float): List<String> {
        if (parts.isEmpty()) return emptyList()
        val sep = "   "
        val sepW = paint.measureText(sep)
        val widths = parts.map { paint.measureText(it) }
        val total = widths.sum() + sepW * (parts.size - 1)
        val cap = maxOf(target, widths.maxOrNull() ?: 0f)   // a line must fit its widest field
        if (total <= cap) return listOf(parts.joinToString(sep))

        val lines = ceil(total / cap).toInt().coerceIn(1, parts.size)
        val perLine = (parts.size + lines - 1) / lines
        val out = ArrayList<String>()
        var i = 0
        while (i < parts.size) {
            out.add(parts.subList(i, minOf(i + perLine, parts.size)).joinToString(sep))
            i += perLine
        }
        return out
    }

    private val SKIP_ON_WRITE = setOf(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_PIXEL_X_DIMENSION,
        ExifInterface.TAG_PIXEL_Y_DIMENSION,
        ExifInterface.TAG_IMAGE_WIDTH,
        ExifInterface.TAG_IMAGE_LENGTH,
    )

    /** Standard EXIF tags carried from the original to the stamped output. */
    private val COPY_TAGS = arrayOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_SENSITIVITY_TYPE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_BRIGHTNESS_VALUE,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_MAX_APERTURE_VALUE, ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_LIGHT_SOURCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_COLOR_SPACE, ExifInterface.TAG_WHITE_BALANCE,
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO, ExifInterface.TAG_SCENE_CAPTURE_TYPE, ExifInterface.TAG_CONTRAST,
        ExifInterface.TAG_SATURATION, ExifInterface.TAG_SHARPNESS, ExifInterface.TAG_EXPOSURE_MODE,
        ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SPECIFICATION,
        ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION,
        ExifInterface.TAG_X_RESOLUTION, ExifInterface.TAG_Y_RESOLUTION, ExifInterface.TAG_RESOLUTION_UNIT,
        ExifInterface.TAG_EXIF_VERSION, ExifInterface.TAG_COMPONENTS_CONFIGURATION,
    )
}
