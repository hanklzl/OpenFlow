package com.hank.flow.open.llm

/**
 * Qwen ChatML template for transcript polishing.
 * Keeps the system role focused on fidelity: do not add content, do not
 * change semantics — only clean disfluencies and improve readability.
 */
object PolishPrompt {

    private val SYSTEM = """
        你是一个语音转录润色助手。
        - 任务：将下面的口语转录文本润色为通顺、规范的书面表达。
        - 必须保留原意，不要添加任何新信息，不要解释或评论。
        - 保持输入文本的主要语言；中文输入必须输出中文，不要翻译成英文。
        - 去除"嗯/呃/那个/就是"等口头语；修正明显的语序问题；正确添加标点。
        - 中英混排时英文术语保留原样。
        - 直接输出润色后的纯文本，不要任何前缀、引号或解释。
    """.trimIndent()

    // appendNoThink: 仅对 Qwen3 系列追加 /no_think，关闭其默认 thinking 模式，
    // 避免 <think>…</think> 占满 MAX_NEW_TOKENS 预算。Qwen2.5 不识别该指令，
    // 因此不能无条件附加。
    fun build(rawTranscript: String, appendNoThink: Boolean = false): String =
        systemPrefix() + rawTranscript + userSuffix(appendNoThink)

    /**
     * Phase 5: the constant prefix that ends right before the user's
     * transcript. Cached as a KV-state snapshot so each polish call only
     * prefills the suffix and the transcript itself.
     */
    fun systemPrefix(): String = buildString {
        append("<|im_start|>system\n").append(SYSTEM).append("<|im_end|>\n")
        append("<|im_start|>user\n")
    }

    /**
     * Everything that follows the user's transcript: optional /no_think
     * directive, ChatML user-tag close, and the assistant-tag opener.
     */
    fun userSuffix(appendNoThink: Boolean = false): String = buildString {
        if (appendNoThink) append("\n/no_think")
        append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }
}
