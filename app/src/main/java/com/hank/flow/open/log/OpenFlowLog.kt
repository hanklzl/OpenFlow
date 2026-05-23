package com.hank.flow.open.log

import android.content.Context
import android.util.Log
import com.dianping.logan.Logan
import com.dianping.logan.LoganConfig
import java.io.File

/**
 * Diagnostic logging facade. Dual-writes every event to:
 *   1) Logan (encrypted on-device file, see app-specific external dir below)
 *   2) android.util.Log (visible via `adb logcat -s OpenFlow/<TAG>`)
 *
 * Initialized once from [com.hank.flow.open.OpenFlowApp.onCreate]. Safe to call
 * before init: pre-init events fall back to logcat only.
 *
 * Log files end up at:
 *   /sdcard/Android/data/<applicationId>/files/logan/logs/
 * （release 包是 com.hank.flow.open，debug 包是 com.hank.flow.open.debug），
 * which is `adb pull`-able without `run-as` (app-specific external storage).
 *
 * AES key/IV are hardcoded debug values; decoded with tools/logan/decode-logan.sh.
 */
object OpenFlowLog {

    enum class Tag { A11Y, OVERLAY, FGS, ASR, LLM, INSERT, AUDIO, APP, MODEL }

    private const val AES_KEY = "0123456789abcdef"
    private const val AES_IV = "abcdef0123456789"
    private const val LEVEL_TRACE = 1
    private const val LEVEL_ERROR = 3

    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        val cachePath = File(
            context.getExternalFilesDir("logan-cache") ?: context.cacheDir,
            "cache",
        ).apply { mkdirs() }.absolutePath
        val logPath = File(
            context.getExternalFilesDir("logan") ?: context.filesDir,
            "logs",
        ).apply { mkdirs() }.absolutePath
        runCatching {
            Logan.init(
                LoganConfig.Builder()
                    .setCachePath(cachePath)
                    .setPath(logPath)
                    .setEncryptKey16(AES_KEY.toByteArray())
                    .setEncryptIV16(AES_IV.toByteArray())
                    .build(),
            )
            initialized = true
        }.onFailure {
            Log.e("OpenFlow/APP", "Logan init failed", it)
        }
        d(Tag.APP, "logan_init", mapOf("path" to logPath, "ok" to initialized))
    }

    fun d(tag: Tag, event: String, fields: Map<String, Any?> = emptyMap()) {
        write(LEVEL_TRACE, tag, event, fields, null)
    }

    fun e(tag: Tag, event: String, t: Throwable? = null, fields: Map<String, Any?> = emptyMap()) {
        write(LEVEL_ERROR, tag, event, fields, t)
    }

    fun flush() {
        runCatching { Logan.f() }
    }

    private fun write(level: Int, tag: Tag, event: String, fields: Map<String, Any?>, t: Throwable?) {
        val payload = if (fields.isEmpty()) "" else fields.entries.joinToString(" ") {
            "${it.key}=${formatVal(it.value)}"
        }
        val thread = Thread.currentThread().name
        val errPart = t?.let { " err=${it.javaClass.simpleName}:${it.message}" }.orEmpty()
        val line = "ts=${System.currentTimeMillis()} thread=$thread tag=$tag event=$event $payload$errPart"
        if (initialized) {
            runCatching { Logan.w(line, level) }
        }
        val logcatTag = "OpenFlow/$tag"
        val logcatMsg = if (payload.isEmpty()) event else "$event $payload"
        if (level == LEVEL_ERROR) {
            Log.e(logcatTag, logcatMsg, t)
        } else {
            Log.d(logcatTag, logcatMsg)
        }
    }

    private fun formatVal(v: Any?): String = when (v) {
        null -> "null"
        is CharSequence -> {
            val s = v.toString()
            if (s.length > 80) "\"${s.take(80)}…\"" else "\"$s\""
        }
        else -> v.toString()
    }
}
