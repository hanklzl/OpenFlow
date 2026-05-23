package com.hank.flow.open.debug

import java.io.File

/**
 * 纯函数模型搬运器：把 src 文件（通常是 /data/local/tmp/<file>）放到 target
 * （ModelStore.pathFor(entry) 给出的 filesDir/models/<filename>）。
 *
 * 不依赖 Android Context，便于纯 JVM 单测。
 * Receiver 负责把 intent extras 解码成 src / target，再调用 install()。
 */
object DebugModelInstaller {

    sealed class InstallResult {
        data class Done(val targetSize: Long) : InstallResult()
        data class Skip(val targetSize: Long) : InstallResult()
        data class SrcMissing(val srcPath: String) : InstallResult()
        data class CopyFailed(val cause: Throwable) : InstallResult()
    }

    fun install(src: File, target: File, force: Boolean): InstallResult {
        if (!src.exists() || src.length() == 0L) {
            return InstallResult.SrcMissing(src.absolutePath)
        }
        if (!force && target.exists() && target.length() == src.length()) {
            return InstallResult.Skip(target.length())
        }
        return runCatching {
            target.parentFile?.mkdirs()
            src.copyTo(target, overwrite = true)
            src.delete()  // 不阻断结果；删失败时设备 /data/local/tmp 会留临时文件，下次 push 会覆盖
            InstallResult.Done(target.length())
        }.getOrElse { InstallResult.CopyFailed(it) }
    }
}
