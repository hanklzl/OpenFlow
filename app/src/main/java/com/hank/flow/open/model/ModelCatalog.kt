package com.hank.flow.open.model

enum class ModelKind { Whisper, Llm }

data class ModelEntry(
    val id: String,
    val kind: ModelKind,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
    val hfPath: String,
)

/**
 * Built-in catalog of downloadable models. The `hfPath` is appended to the
 * user-selected HuggingFace mirror to produce the final download URL.
 */
object ModelCatalog {

    val whisperDefault = ModelEntry(
        id = "ggml-small-q5_1",
        kind = ModelKind.Whisper,
        displayName = "Whisper small (q5_1)",
        sizeBytes = 190_000_000L,
        sha256 = "ad82bf78eb24fc7d52bc1a01b1c89c8c92cf0c45fadccf2b3f04dca12b96a3a8",
        hfPath = "ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
    )

    val whisperTiny = ModelEntry(
        id = "ggml-tiny-q5_1",
        kind = ModelKind.Whisper,
        displayName = "Whisper tiny (q5_1)",
        sizeBytes = 32_000_000L,
        sha256 = "",
        hfPath = "ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin",
    )

    val llmDefault = ModelEntry(
        id = "qwen2.5-1.5b-instruct-q4_k_m",
        kind = ModelKind.Llm,
        displayName = "Qwen2.5 1.5B Instruct (Q4_K_M) — 默认",
        sizeBytes = 1_100_000_000L,
        sha256 = "",
        hfPath = "Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
    )

    val llmLargerNewer = ModelEntry(
        id = "qwen3.5-2b-instruct-q4_k_m",
        kind = ModelKind.Llm,
        displayName = "Qwen3.5 2B Instruct (Q4_K_M) — 更新更强",
        sizeBytes = 1_400_000_000L,
        sha256 = "",
        hfPath = "Qwen/Qwen3.5-2B-Instruct-GGUF/resolve/main/qwen3.5-2b-instruct-q4_k_m.gguf",
    )

    val llmTinyNewer = ModelEntry(
        id = "qwen3.5-0.8b-instruct-q4_k_m",
        kind = ModelKind.Llm,
        displayName = "Qwen3.5 0.8B Instruct (Q4_K_M) — 轻量",
        sizeBytes = 600_000_000L,
        sha256 = "",
        hfPath = "Qwen/Qwen3.5-0.8B-Instruct-GGUF/resolve/main/qwen3.5-0.8b-instruct-q4_k_m.gguf",
    )

    val all: List<ModelEntry> = listOf(
        whisperDefault,
        whisperTiny,
        llmDefault,
        llmLargerNewer,
        llmTinyNewer,
    )

    fun byId(id: String): ModelEntry? = all.firstOrNull { it.id == id }
}
