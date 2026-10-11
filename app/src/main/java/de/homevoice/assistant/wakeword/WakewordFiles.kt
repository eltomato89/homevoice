package de.homevoice.assistant.wakeword

import android.content.Context
import java.io.File
import java.io.InputStream

/** Stores an uploaded openWakeWord model (.onnx) in app storage. */
object WakewordFiles {
    const val EXTENSION = ".onnx"
    private const val MAX_BYTES = 20L * 1024 * 1024

    /** Saves [input] as [name]; returns the absolute path, or null if it is not an .onnx file. */
    fun save(context: Context, name: String, input: InputStream): String? {
        if (!name.endsWith(EXTENSION, ignoreCase = true)) return null
        val dir = File(context.filesDir, "wakeword").apply { mkdirs() }
        // Keep only one custom model.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_"))
        target.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                require(total <= MAX_BYTES) { "Datei zu groß" }
                out.write(buffer, 0, n)
            }
        }
        return target.absolutePath
    }

    /** "hey_computer_v0.1.onnx" → "Hey Computer" */
    fun displayName(path: String): String =
        File(path).nameWithoutExtension
            .replace(Regex("_v\\d+(\\.\\d+)*$"), "")
            .split('_', '-', ' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
