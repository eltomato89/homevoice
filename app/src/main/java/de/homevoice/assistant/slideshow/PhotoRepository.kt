package de.homevoice.assistant.slideshow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import de.homevoice.vision.Face
import de.homevoice.vision.FaceDetector
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** A slideshow photo and the faces found in it (normalized coordinates; empty if none or unknown). */
data class Photo(val uri: Uri, val faces: List<Face> = emptyList())

/**
 * Photos for the slideshow: images uploaded via the web interface (app storage)
 * plus, optionally, a folder picked on the device via the Storage Access Framework.
 */
class PhotoRepository(private val context: Context) {
    val uploadDir: File = File(context.filesDir, "photos").apply { mkdirs() }

    private val _version = MutableStateFlow(0)
    /** Increments whenever uploaded photos change, so the UI rescans. */
    val version: StateFlow<Int> = _version.asStateFlow()

    suspend fun scan(treeUri: String): List<Photo> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Photo>()
        uploadDir.listFiles()?.filter { it.isFile && isImageName(it.name) }
            ?.forEach { result += Photo(Uri.fromFile(it), readFaces(it)) }
        if (treeUri.isNotBlank()) {
            try {
                val tree = Uri.parse(treeUri)
                val uris = mutableListOf<Uri>()
                collect(tree, DocumentsContract.getTreeDocumentId(tree), uris, depth = 0)
                uris.forEach { result += Photo(it) }
            } catch (e: Exception) {
                Log.w("VoiceAssistant", "Photo scan failed", e)
            }
        }
        result.shuffled()
    }

    fun uploadedCount(): Int = uploadDir.listFiles()?.count { it.isFile && isImageName(it.name) } ?: 0

    /** Stores an uploaded photo. Returns false for unsupported file names. */
    fun saveUpload(name: String, input: InputStream, maxBytes: Long): Boolean {
        val safe = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!isImageName(safe)) return false
        val target = File(uploadDir, safe)
        val tmp = File(uploadDir, "$safe.part")
        try {
            tmp.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= maxBytes) { "Datei zu groß" }
                    out.write(buffer, 0, n)
                }
            }
            tmp.renameTo(target)
        } finally {
            tmp.delete()
        }
        val stored = optimize(target) ?: target
        detectFaces(stored)
        _version.value++
        return true
    }

    /** Shrinks all uploaded photos that are larger than needed (one-time for older uploads). */
    suspend fun optimizeUploads() = withContext(Dispatchers.IO) {
        var changed = false
        uploadDir.listFiles()?.filter { it.isFile && isImageName(it.name) }?.forEach { file ->
            val stored = optimize(file)?.also { changed = true } ?: file
            if (!facesFile(stored).exists() && detectFaces(stored)) changed = true
        }
        faceDetector?.close()
        faceDetector = null
        if (changed) _version.value++
    }

    // ---- Faces (on-device, nothing leaves the tablet) --------------------------------------------

    private var faceDetector: FaceDetector? = null

    private fun facesFile(photo: File) = File(uploadDir, photo.name + FACES_SUFFIX)

    /** Detects faces once per photo and stores them next to it. Returns true on success. */
    @Synchronized
    private fun detectFaces(file: File): Boolean = try {
        val detector = faceDetector ?: FaceDetector(
            context.assets.open("wakeword/face_rfb640.onnx").use { it.readBytes() }
        ).also { faceDetector = it }
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val longSide = maxOf(info.size.width, info.size.height)
            if (longSide > DETECT_SIDE) {
                val f = DETECT_SIDE.toFloat() / longSide
                decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val started = System.currentTimeMillis()
        val faces = detector.detect(pixels, bitmap.width, bitmap.height)
        bitmap.recycle()
        facesFile(file).writeText(faces.joinToString("\n") { "${it.left} ${it.top} ${it.right} ${it.bottom} ${it.score}" })
        Log.i("VoiceAssistant", "Faces in ${file.name}: ${faces.size} (${System.currentTimeMillis() - started} ms)")
        true
    } catch (e: Exception) {
        Log.w("VoiceAssistant", "Face detection failed for ${file.name}", e)
        false
    }

    private fun readFaces(photo: File): List<Face> = try {
        facesFile(photo).takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
            val v = line.trim().split(' ').mapNotNull { it.toFloatOrNull() }
            if (v.size == 5) Face(v[0], v[1], v[2], v[3], v[4]) else null
        }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Re-encodes a photo as JPEG with at most [MAX_SIDE] px on the long side, with EXIF rotation
     * applied. Phone photos (12–48 MP, HEIC) otherwise take seconds to decode on a small device.
     * Returns the new file when it was replaced, otherwise null.
     */
    private fun optimize(file: File): File? = try {
        val source = ImageDecoder.createSource(file)
        var needed = false
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            val longSide = maxOf(w, h)
            needed = longSide > MAX_SIDE || !file.name.endsWith(".jpg")
            if (longSide > MAX_SIDE) {
                val f = MAX_SIDE.toFloat() / longSide
                decoder.setTargetSize((w * f).toInt(), (h * f).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        if (!needed) {
            bitmap.recycle()
            null
        } else {
            val target = File(uploadDir, file.nameWithoutExtension + ".jpg")
            val tmp = File(uploadDir, target.name + ".part")
            tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            bitmap.recycle()
            if (file != target) file.delete()
            tmp.renameTo(target)
            Log.i("VoiceAssistant", "Photo optimized: ${file.name} → ${target.name} (${target.length() / 1024} KB)")
            target
        }
    } catch (e: Exception) {
        Log.w("VoiceAssistant", "Photo could not be optimized: ${file.name}", e)
        null
    }

    /** Uploaded photos (file names), newest first, with the number of detected faces. */
    fun listUploads(): List<Pair<String, Int>> =
        uploadDir.listFiles()?.filter { it.isFile && isImageName(it.name) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name to readFaces(it).size }
            .orEmpty()

    /** The uploaded photo called [name], or null (also for names that try to leave the folder). */
    private fun upload(name: String): File? {
        val file = File(uploadDir, name).canonicalFile
        return file.takeIf { it.parentFile == uploadDir.canonicalFile && it.isFile && isImageName(it.name) }
    }

    /** Small JPEG preview for the web interface. */
    fun thumbnail(name: String): ByteArray? {
        val file = upload(name) ?: return null
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            val f = THUMB_SIDE.toFloat() / maxOf(info.size.width, info.size.height)
            if (f < 1f) decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        return java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    /** Deletes one uploaded photo and its face data. */
    fun deleteUpload(name: String): Boolean {
        val file = upload(name) ?: return false
        facesFile(file).delete()
        val deleted = file.delete()
        _version.value++
        return deleted
    }

    fun deleteUploads(): Int {
        val files = uploadDir.listFiles()?.filter { it.isFile } ?: return 0
        files.forEach { it.delete() }
        _version.value++
        return files.size
    }

    private fun isImageName(name: String) =
        name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif")

    private fun collect(tree: Uri, documentId: String, out: MutableList<Uri>, depth: Int) {
        if (depth > MAX_DEPTH || out.size >= MAX_PHOTOS) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val mime = cursor.getString(1) ?: continue
                when {
                    mime == DocumentsContract.Document.MIME_TYPE_DIR -> collect(tree, id, out, depth + 1)
                    mime.startsWith("image/") -> out += DocumentsContract.buildDocumentUriUsingTree(tree, id)
                }
                if (out.size >= MAX_PHOTOS) return
            }
        }
    }

    private companion object {
        const val MAX_DEPTH = 8
        const val MAX_PHOTOS = 20_000
        const val MAX_SIDE = 2560
        const val DETECT_SIDE = 1280
        const val THUMB_SIDE = 320
        const val FACES_SUFFIX = ".faces"
    }
}
