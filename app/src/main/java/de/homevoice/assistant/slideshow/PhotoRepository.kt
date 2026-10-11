package de.homevoice.assistant.slideshow

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Photos for the slideshow: images uploaded via the web interface (app storage)
 * plus, optionally, a folder picked on the device via the Storage Access Framework.
 */
class PhotoRepository(private val context: Context) {
    val uploadDir: File = File(context.filesDir, "photos").apply { mkdirs() }

    private val _version = MutableStateFlow(0)
    /** Increments whenever uploaded photos change, so the UI rescans. */
    val version: StateFlow<Int> = _version.asStateFlow()

    suspend fun scan(treeUri: String): List<Uri> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Uri>()
        uploadDir.listFiles()?.filter { it.isFile && isImageName(it.name) }?.forEach { result += Uri.fromFile(it) }
        if (treeUri.isNotBlank()) {
            try {
                val tree = Uri.parse(treeUri)
                collect(tree, DocumentsContract.getTreeDocumentId(tree), result, depth = 0)
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
        _version.value++
        return true
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
    }
}
