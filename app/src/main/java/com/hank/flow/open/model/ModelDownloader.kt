package com.hank.flow.open.model

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

sealed interface DownloadState {
    data class Progress(val bytesDownloaded: Long, val total: Long) : DownloadState
    data class Done(val file: File) : DownloadState
    data class Failed(val message: String) : DownloadState
}

class ModelDownloader(
    private val store: ModelStore,
    mirrorBase: () -> String,
) {

    private val mirrorBaseProvider = mirrorBase

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun download(entry: ModelEntry): Flow<DownloadState> = flow {
        val target = store.pathFor(entry)
        val partial = File(target.parentFile, target.name + ".part")
        val existingBytes = if (partial.exists()) partial.length() else 0L
        val url = "${mirrorBaseProvider().trimEnd('/')}/${entry.hfPath}"

        val request = Request.Builder().url(url).apply {
            if (existingBytes > 0) {
                header("Range", "bytes=$existingBytes-")
            }
        }.build()

        val response = runCatching { client.newCall(request).execute() }
            .getOrElse {
                emit(DownloadState.Failed("network: ${it.message}"))
                return@flow
            }

        if (!response.isSuccessful && response.code != 206) {
            response.close()
            emit(DownloadState.Failed("http ${response.code}"))
            return@flow
        }

        val body = response.body ?: run {
            response.close()
            emit(DownloadState.Failed("empty body"))
            return@flow
        }

        val totalRemote = body.contentLength().let { if (it <= 0) entry.sizeBytes else it + existingBytes }
        val source = body.source()
        val raf = RandomAccessFile(partial, "rw")
        raf.seek(existingBytes)

        try {
            val buf = ByteArray(64 * 1024)
            var downloaded = existingBytes
            var lastEmit = System.currentTimeMillis()
            while (true) {
                val n = source.inputStream().read(buf)
                if (n == -1) break
                raf.write(buf, 0, n)
                downloaded += n
                val now = System.currentTimeMillis()
                if (now - lastEmit > 200) {
                    emit(DownloadState.Progress(downloaded, totalRemote))
                    lastEmit = now
                }
            }
            emit(DownloadState.Progress(downloaded, totalRemote))
        } catch (t: Throwable) {
            emit(DownloadState.Failed("io: ${t.message}"))
            return@flow
        } finally {
            runCatching { raf.close() }
            response.close()
        }

        if (!partial.renameTo(target)) {
            emit(DownloadState.Failed("rename failed"))
            return@flow
        }
        Log.i(TAG, "Downloaded ${target.name} (${target.length()} bytes)")
        emit(DownloadState.Done(target))
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "ModelDownloader"
    }
}
