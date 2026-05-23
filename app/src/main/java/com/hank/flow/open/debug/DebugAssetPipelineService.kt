package com.hank.flow.open.debug

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.hank.flow.open.log.OpenFlowLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DebugAssetPipelineService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = DebugAssetPipelineRequest.fromIntent(intent)
        OpenFlowLog.d(
            OpenFlowLog.Tag.ASR,
            "debug_asset_service_started",
            mapOf("startId" to startId, "polish" to request.polish, "rawText" to (request.rawText != null)),
        )
        scope.launch {
            try {
                DebugAssetPipelineRunner.run(applicationContext, request)
            } catch (t: Throwable) {
                OpenFlowLog.e(OpenFlowLog.Tag.ASR, "debug_asset_pipeline_failed", t)
            } finally {
                OpenFlowLog.flush()
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
