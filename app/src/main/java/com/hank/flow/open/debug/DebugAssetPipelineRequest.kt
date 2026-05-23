package com.hank.flow.open.debug

import android.content.Context
import android.content.Intent
import com.hank.flow.open.model.ModelCatalog

data class DebugAssetPipelineRequest(
    val wavAsset: String,
    val whisperId: String,
    val lang: String,
    val polish: Boolean,
    val llmId: String,
    val rawText: String?,
    val maxTokens: Int?,
) {
    fun toServiceIntent(context: Context): Intent =
        Intent(context, DebugAssetPipelineService::class.java).apply {
            action = ACTION
            putExtra(EXTRA_WAV_ASSET, wavAsset)
            putExtra(EXTRA_WHISPER_ID, whisperId)
            putExtra(EXTRA_LANG, lang)
            putExtra(EXTRA_POLISH, polish)
            putExtra(EXTRA_LLM_ID, llmId)
            rawText?.let { putExtra(EXTRA_RAW_TEXT, it) }
            maxTokens?.let { putExtra(EXTRA_MAX_TOKENS, it) }
        }

    companion object {
        const val ACTION = "com.hank.flow.open.debug.RUN_ASR_FROM_ASSETS"

        private const val EXTRA_WAV_ASSET = "wavAsset"
        private const val EXTRA_WHISPER_ID = "whisperId"
        private const val EXTRA_LANG = "lang"
        private const val EXTRA_POLISH = "polish"
        private const val EXTRA_LLM_ID = "llmId"
        private const val EXTRA_RAW_TEXT = "rawText"
        private const val EXTRA_MAX_TOKENS = "maxTokens"

        fun fromIntent(intent: Intent?): DebugAssetPipelineRequest =
            fromValues(
                wavAsset = intent?.getStringExtra(EXTRA_WAV_ASSET),
                whisperId = intent?.getStringExtra(EXTRA_WHISPER_ID),
                lang = intent?.getStringExtra(EXTRA_LANG),
                polish = intent?.getBooleanExtra(EXTRA_POLISH, false) ?: false,
                llmId = intent?.getStringExtra(EXTRA_LLM_ID),
                rawText = intent?.getStringExtra(EXTRA_RAW_TEXT),
                maxTokens = if (intent?.hasExtra(EXTRA_MAX_TOKENS) == true) {
                    intent.getIntExtra(EXTRA_MAX_TOKENS, 0)
                } else {
                    null
                },
            )

        fun fromValues(
            wavAsset: String?,
            whisperId: String?,
            lang: String?,
            polish: Boolean,
            llmId: String?,
            rawText: String?,
            maxTokens: Int?,
        ): DebugAssetPipelineRequest =
            DebugAssetPipelineRequest(
                wavAsset = wavAsset ?: "test/jfk.wav",
                whisperId = whisperId ?: ModelCatalog.whisperDefault.id,
                lang = lang ?: "auto",
                polish = polish,
                llmId = llmId ?: ModelCatalog.llmDefault.id,
                rawText = rawText?.takeIf { it.isNotBlank() },
                maxTokens = maxTokens?.takeIf { it > 0 },
            )
    }
}
