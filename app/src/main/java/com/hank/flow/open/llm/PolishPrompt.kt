package com.hank.flow.open.llm

/**
 * Qwen2.5 chat template for transcript polishing.
 * Keeps the system role focused on fidelity: do not add content, do not
 * change semantics — only clean disfluencies and improve readability.
 */
object PolishPrompt {

    private val SYSTEM = """
        你是一个语音转录润色助手。
        - 任务：将下面的口语转录文本润色为通顺、规范的书面表达。
        - 必须保留原意，不要添加任何新信息，不要解释或评论。
        - 去除"嗯/呃/那个/就是"等口头语；修正明显的语序问题；正确添加标点。
        - 中英混排时英文术语保留原样。
        - 直接输出润色后的纯文本，不要任何前缀、引号或解释。
    """.trimIndent()

    fun build(rawTranscript: String): String = buildString {
        append("<|im_start|>system\n").append(SYSTEM).append("<|im_end|>\n")
        append("<|im_start|>user\n").append(rawTranscript).append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }
}
