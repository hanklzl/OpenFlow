package com.hank.flow.open.history

data class HistoryRecord(
    val id: String,
    val createdAtMs: Long,
    val sampleRate: Int,
    val sampleCount: Int,
    val rawText: String,
    val polishedText: String?,
    val asrModelId: String,
    val llmModelId: String?,
    val asrDurationMs: Long,
    val polishDurationMs: Long?,
) {
    val durationMs: Long get() = sampleCount * 1000L / sampleRate.coerceAtLeast(1)
}
