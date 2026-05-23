package com.hank.flow.open.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hank.flow.open.debug.DebugModelInstaller.InstallResult
import com.hank.flow.open.log.OpenFlowLog
import com.hank.flow.open.model.ModelCatalog
import com.hank.flow.open.model.ModelStore
import java.io.File

/**
 * Smoke test 入口：把 adb push 到 /data/local/tmp/<file> 的模型搬到
 * ModelStore.pathFor() 实际位置（filesDir/models/<filename>）。
 *
 *   adb push <local-model> /data/local/tmp/<filename>
 *   adb shell am broadcast \
 *       -a com.hank.flow.open.debug.INSTALL_MODEL_FROM_TMP \
 *       -n com.hank.flow.open/com.hank.flow.open.debug.DebugModelInstallReceiver \
 *       --es srcPath /data/local/tmp/<filename> \
 *       --es modelId ggml-tiny-q5_1 \
 *       --ez force false
 *
 * 受 signature permission `com.hank.flow.open.permission.DEBUG_SMOKE` 保护，
 * 仅同签名应用 + adb shell 可调。
 */
class DebugModelInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val srcPath = intent.getStringExtra("srcPath")
        val modelId = intent.getStringExtra("modelId")
        val force = intent.getBooleanExtra("force", false)

        val entry = modelId?.let { ModelCatalog.byId(it) }
        if (entry == null || srcPath.isNullOrEmpty()) {
            OpenFlowLog.e(
                OpenFlowLog.Tag.MODEL,
                "debug_model_install_bad_args",
                fields = mapOf("modelId" to modelId, "srcPath" to srcPath),
            )
            OpenFlowLog.flush()
            return
        }

        val target = ModelStore(context.applicationContext).pathFor(entry)
        when (val result = DebugModelInstaller.install(File(srcPath), target, force)) {
            is InstallResult.Done -> OpenFlowLog.d(
                OpenFlowLog.Tag.MODEL,
                "debug_model_install_done",
                mapOf("modelId" to modelId, "size" to result.targetSize),
            )
            is InstallResult.Skip -> OpenFlowLog.d(
                OpenFlowLog.Tag.MODEL,
                "debug_model_install_skip",
                mapOf("modelId" to modelId, "size" to result.targetSize),
            )
            is InstallResult.SrcMissing -> OpenFlowLog.e(
                OpenFlowLog.Tag.MODEL,
                "debug_model_install_src_missing",
                fields = mapOf("srcPath" to result.srcPath),
            )
            is InstallResult.CopyFailed -> OpenFlowLog.e(
                OpenFlowLog.Tag.MODEL,
                "debug_model_install_failed",
                t = result.cause,
            )
        }
        OpenFlowLog.flush()
    }

    companion object {
        const val ACTION = "com.hank.flow.open.debug.INSTALL_MODEL_FROM_TMP"
    }
}
