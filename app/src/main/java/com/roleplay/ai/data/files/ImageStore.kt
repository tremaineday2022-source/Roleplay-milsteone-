package com.roleplay.ai.data.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** Copies picked photos into app-private storage and serves them back to the UI and the API. */
class ImageStore(private val context: Context) {

    private val dir: File
        get() = File(context.filesDir, "images").apply { mkdirs() }

    fun file(name: String): File = File(dir, File(name).name)

    fun exists(name: String): Boolean = name.isNotBlank() && file(name).isFile

    /**
     * Copies the image at [uri] into app storage as a JPEG, scaled down so its longest side is
     * at most [maxSide] pixels. Returns the stored file name, or null if it could not be read.
     */
    suspend fun importImage(uri: Uri, maxSide: Int = 1568): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decode(uri, maxSide) ?: return@runCatching null
            val name = "${UUID.randomUUID()}.jpg"
            file(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()
            name
        }.getOrNull()
    }

    fun delete(names: Collection<String>) {
        names.forEach { runCatching { file(it).delete() } }
    }

    /** A `data:` URL for OpenAI-style `image_url` content parts, or null if the file is gone. */
    fun dataUrl(name: String): String? = runCatching {
        val bytes = file(name).takeIf { it.isFile }?.readBytes() ?: return null
        "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }.getOrNull()

    /** Deletes stored images that nothing refers to any more. */
    fun sweep(referenced: Set<String>) {
        dir.listFiles()?.forEach { if (it.name !in referenced) runCatching { it.delete() } }
    }

    private fun decode(uri: Uri, maxSide: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val longest = max(info.size.width, info.size.height)
                if (longest > maxSide) {
                    val scale = maxSide.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1)
                    )
                }
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        val decodedLongest = max(decoded.width, decoded.height)
        if (decodedLongest <= maxSide) return decoded
        val scale = maxSide.toFloat() / decodedLongest
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).roundToInt().coerceAtLeast(1),
            (decoded.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }
}
