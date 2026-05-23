package com.hank.flow.open.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hank.flow.open.log.OpenFlowLog

/**
 * Debug-only entry point that bypasses the microphone: reads a wav from
 * `assets/` (or an `--es wavAsset <path>` override), runs it through
 * [WhisperEngine] and optionally [PolishEngine], and pushes every stage
 * to OpenFlowLog (Tag.ASR / Tag.LLM) under a `debug_asset_*` prefix.
 *
 * Trigger from host machine:
 *   adb shell am broadcast \
 *       -a com.hank.flow.open.debug.RUN_ASR_FROM_ASSETS \
 *       -n com.hank.flow.open/.debug.DebugAsrReceiver \
 *       --es wavAsset test/jfk.wav \
 *       --es whisperId ggml-tiny-q5_1 \
 *       --es lang auto \
 *       --ez polish false
 *
 * Receiver is registered only in `src/debug/AndroidManifest.xml`, so the
 * release variant never exposes it.
 */
class DebugAsrReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        OpenFlowLog.d(
            OpenFlowLog.Tag.ASR,
            "debug_asset_receiver_delegate",
            mapOf("action" to intent.action),
        )
        runCatching {
            ctx.startService(DebugAssetPipelineRequest.fromIntent(intent).toServiceIntent(ctx))
        }.onFailure {
            OpenFlowLog.e(OpenFlowLog.Tag.ASR, "debug_asset_service_start_failed", it)
            OpenFlowLog.flush()
        }
    }

    companion object {
        const val ACTION = DebugAssetPipelineRequest.ACTION
    }
}
