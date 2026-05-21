package com.hank.flow.open.model

import android.content.Context
import java.io.File

/**
 * Filesystem layout for downloaded models.
 *
 *   filesDir/models/<id>.bin
 *   filesDir/models/<id>.gguf
 *
 * Whisper uses .bin, LLM uses .gguf — but the suffix is just a convention;
 * the catalog's `hfPath` defines the real filename.
 */
class ModelStore(private val context: Context) {

    private val root: File = File(context.filesDir, "models").apply { mkdirs() }

    fun pathFor(entry: ModelEntry): File {
        val filename = entry.hfPath.substringAfterLast('/')
        return File(root, filename)
    }

    fun isReady(entry: ModelEntry): Boolean {
        val file = pathFor(entry)
        return file.exists() && file.length() > MIN_VALID_BYTES
    }

    fun delete(entry: ModelEntry): Boolean = pathFor(entry).delete()

    fun listInstalled(): List<File> = root.listFiles()?.toList().orEmpty()

    companion object {
        private const val MIN_VALID_BYTES = 1_000_000L
    }
}
