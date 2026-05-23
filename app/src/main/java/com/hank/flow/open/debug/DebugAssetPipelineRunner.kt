package com.hank.flow.open.debug

import android.content.Context
import com.hank.flow.open.asr.WhisperEngine
import com.hank.flow.open.llm.PolishEngine
import com.hank.flow.open.log.OpenFlowLog
import com.hank.flow.open.model.ModelCatalog
import com.hank.flow.open.model.ModelStore
import com.hank.flow.open.settings.CustomDictionary
import com.hank.flow.open.settings.SettingsStore

object DebugAssetPipelineRunner {

    suspend fun run(context: Context, request: DebugAssetPipelineRequest) {
        OpenFlowLog.d(
            OpenFlowLog.Tag.ASR,
            "debug_asset_received",
            mapOf(
                "wavAsset" to request.wavAsset,
                "whisperId" to request.whisperId,
                "lang" to request.lang,
                "polish" to request.polish,
                "llmId" to if (request.polish) request.llmId else "(disabled)",
                "rawText" to (request.rawText != null),
                "maxTokens" to (request.maxTokens ?: "default"),
            ),
        )

        val store = ModelStore(context)
        val rawText = request.rawText ?: transcribeAsset(context, store, request)
        if (rawText == null) return

        if (!request.polish || rawText.isBlank()) {
            OpenFlowLog.d(
                OpenFlowLog.Tag.ASR,
                "debug_asset_finished",
                mapOf("polished" to false, "outLen" to rawText.length),
            )
            return
        }

        polishText(store, request, rawText)
    }

    private suspend fun transcribeAsset(
        context: Context,
        store: ModelStore,
        request: DebugAssetPipelineRequest,
    ): String? {
        val wav = context.assets.open(request.wavAsset).use { WavReader.read(it) }
        OpenFlowLog.d(
            OpenFlowLog.Tag.ASR,
            "debug_asset_wav_loaded",
            mapOf(
                "wavAsset" to request.wavAsset,
                "pcmShorts" to wav.pcm.size,
                "sampleRate" to wav.sampleRate,
                "channels" to wav.channels,
                "bitsPerSample" to wav.bitsPerSample,
                "durMs" to (wav.pcm.size * 1000L / wav.sampleRate.toLong()),
            ),
        )

        val whisperModel = ModelCatalog.byId(request.whisperId) ?: ModelCatalog.whisperDefault
        val whisperFile = store.pathFor(whisperModel)
        val whisperReady = whisperFile.exists() && whisperFile.length() > MIN_VALID_BYTES
        OpenFlowLog.d(
            OpenFlowLog.Tag.ASR,
            "debug_asset_whisper_ready",
            mapOf(
                "modelId" to whisperModel.id,
                "exists" to whisperFile.exists(),
                "fileLen" to (if (whisperFile.exists()) whisperFile.length() else -1L),
                "expectedLen" to whisperModel.sizeBytes,
                "ready" to whisperReady,
                "path" to whisperFile.absolutePath,
            ),
        )
        if (!whisperReady) {
            OpenFlowLog.d(OpenFlowLog.Tag.ASR, "debug_asset_abort_no_model")
            return null
        }
        val dictionaryEntries = SettingsStore(context).current().customDictionaryEntries
        val dictionaryPrompt = CustomDictionary.toWhisperPrompt(dictionaryEntries)

        val whisper = WhisperEngine(whisperFile.absolutePath)
        return try {
            val t0 = System.currentTimeMillis()
            val result = whisper.transcribe(
                pcm = wav.pcm,
                language = request.lang,
                initialPrompt = dictionaryPrompt,
            )
            val asrMs = System.currentTimeMillis() - t0
            OpenFlowLog.d(
                OpenFlowLog.Tag.ASR,
                "debug_asset_asr_done",
                mapOf(
                    "latencyMs" to asrMs,
                    "loadMs" to result.loadMs,
                    "nativeMs" to result.nativeMs,
                    "textLen" to result.text.length,
                    "dictionaryEntries" to dictionaryEntries.size,
                    "dictionaryPromptLen" to (dictionaryPrompt?.length ?: 0),
                    "text" to result.text.take(80),
                ),
            )
            result.text
        } finally {
            whisper.release()
        }
    }

    private suspend fun polishText(
        store: ModelStore,
        request: DebugAssetPipelineRequest,
        rawText: String,
    ) {
        val llmModel = ModelCatalog.byId(request.llmId) ?: ModelCatalog.llmDefault
        val llmFile = store.pathFor(llmModel)
        val llmReady = llmFile.exists() && llmFile.length() > MIN_VALID_BYTES
        OpenFlowLog.d(
            OpenFlowLog.Tag.LLM,
            "debug_asset_llm_ready",
            mapOf(
                "modelId" to llmModel.id,
                "fileLen" to (if (llmFile.exists()) llmFile.length() else -1L),
                "expectedLen" to llmModel.sizeBytes,
                "ready" to llmReady,
            ),
        )
        if (!llmReady) {
            OpenFlowLog.d(OpenFlowLog.Tag.LLM, "debug_asset_abort_no_llm")
            return
        }

        OpenFlowLog.d(
            OpenFlowLog.Tag.LLM,
            "debug_asset_polish_start",
            mapOf(
                "modelId" to llmModel.id,
                "isQwen3" to llmModel.id.startsWith("qwen3-"),
                "rawTextLen" to rawText.length,
                "maxTokens" to (request.maxTokens ?: "default"),
            ),
        )
        val polish = PolishEngine(
            modelPath = llmFile.absolutePath,
            isQwen3 = llmModel.id.startsWith("qwen3-"),
        )
        try {
            val p0 = System.currentTimeMillis()
            val polished = request.maxTokens?.let {
                polish.polish(rawText, maxNewTokens = it)
            } ?: polish.polish(rawText)
            val polishMs = System.currentTimeMillis() - p0
            OpenFlowLog.d(
                OpenFlowLog.Tag.LLM,
                "debug_asset_polish_done",
                mapOf(
                    "latencyMs" to polishMs,
                    "loadMs" to polished.loadMs,
                    "prefillMs" to polished.prefillMs,
                    "decodeMs" to polished.decodeMs,
                    "firstTokenMs" to polished.firstTokenMs,
                    "maxTokens" to (request.maxTokens ?: "default"),
                    "outLen" to polished.text.length,
                    "text" to polished.text.take(80),
                ),
            )
            OpenFlowLog.d(
                OpenFlowLog.Tag.ASR,
                "debug_asset_finished",
                mapOf("polished" to true, "outLen" to polished.text.length),
            )
        } finally {
            polish.release()
        }
    }

    private const val MIN_VALID_BYTES = 1_000_000L
}
