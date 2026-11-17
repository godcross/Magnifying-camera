package com.godcross.magnifier

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Size
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Photo(
    val uri: Uri,
    val name: String,
    val dateMillis: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
)

class Shot(val jpeg: ByteArray, val rotationDegrees: Int)

/**
 * Saving and listing photos. Photos go to the phone's own Pictures/MagnifyingCamera folder
 * through MediaStore, so they show up in Samsung Gallery too. Nothing is uploaded anywhere.
 */
object PhotoStore {

    const val FOLDER = "MagnifyingCamera"
    private val RELATIVE_PATH = "${Environment.DIRECTORY_PICTURES}/$FOLDER"

    private fun newName(): String =
        "MAG_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"

    private fun newValues(name: String, pending: Boolean): ContentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            if (pending) put(MediaStore.MediaColumns.IS_PENDING, 1)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), FOLDER)
            dir.mkdirs()
            @Suppress("DEPRECATION")
            put(MediaStore.MediaColumns.DATA, File(dir, name).absolutePath)
        }
    }

    /** Full-quality single shot written straight to MediaStore by CameraX. */
    suspend fun captureToGallery(context: Context, capture: ImageCapture, executor: Executor): Uri? {
        val options = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            newValues(newName(), pending = false),
        ).build()
        return suspendCancellableCoroutine { cont ->
            capture.takePicture(options, executor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    cont.resume(output.savedUri)
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            })
        }
    }

    /** Capture into memory so several shots can be compared before one is saved. */
    suspend fun captureToMemory(capture: ImageCapture, executor: Executor): Shot =
        suspendCancellableCoroutine { cont ->
            capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val buffer = image.planes[0].buffer
                        buffer.rewind()
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        cont.resume(Shot(bytes, image.imageInfo.rotationDegrees))
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    cont.resumeWithException(exception)
                }
            })
        }

    /** Saves the original JPEG bytes untouched (no re-compression), with the right orientation tag. */
    suspend fun saveShot(context: Context, shot: Shot): Uri? = withContext(Dispatchers.IO) {
        val temp = File.createTempFile("shot", ".jpg", context.cacheDir)
        try {
            temp.writeBytes(shot.jpeg)
            try {
                val exif = ExifInterface(temp)
                val orientation = when (((shot.rotationDegrees % 360) + 360) % 360) {
                    90 -> ExifInterface.ORIENTATION_ROTATE_90
                    180 -> ExifInterface.ORIENTATION_ROTATE_180
                    270 -> ExifInterface.ORIENTATION_ROTATE_270
                    else -> ExifInterface.ORIENTATION_NORMAL
                }
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Magnifying Camera")
                exif.saveAttributes()
            } catch (_: Exception) {
                // Orientation tag is a nicety; never lose the photo over it.
            }
            insert(context) { out -> temp.inputStream().use { it.copyTo(out) } }
        } finally {
            temp.delete()
        }
    }

    suspend fun saveBitmap(context: Context, bitmap: Bitmap): Uri? = withContext(Dispatchers.IO) {
        insert(context) { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }
    }

    private fun insert(context: Context, write: (OutputStream) -> Unit): Uri? {
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            newValues(newName(), pending = true),
        ) ?: return null
        try {
            resolver.openOutputStream(uri)?.use(write) ?: throw IllegalStateException("No output stream")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Photos taken by this app, newest first. */
    fun list(context: Context): List<Photo> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        val (selection, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" to arrayOf("$RELATIVE_PATH%")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.MediaColumns.DATA} LIKE ?" to arrayOf("%/${Environment.DIRECTORY_PICTURES}/$FOLDER/%")
        }
        val result = ArrayList<Photo>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args,
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val wCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val hCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    result += Photo(
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        name = c.getString(nameCol) ?: "",
                        dateMillis = c.getLong(dateCol) * 1000L,
                        sizeBytes = c.getLong(sizeCol),
                        width = c.getInt(wCol),
                        height = c.getInt(hCol),
                    )
                }
            }
        } catch (_: SecurityException) {
            // No read access (Android 8/9 without storage permission): show nothing rather than crash.
        }
        return result
    }

    fun loadThumbnail(context: Context, uri: Uri, px: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(px, px), null)
        } else {
            decodeSampled(context, uri, px)
        }
    } catch (_: Exception) {
        null
    }

    /** Decodes an image no larger than [maxDim] on its longest side, upright per its EXIF tag. */
    fun decodeSampled(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / sample > maxDim) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null

        val rotation = try {
            resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        } catch (_: Exception) {
            0
        }
        if (rotation == 0) return bmp
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated != bmp) bmp.recycle()
        return rotated
    }
}
