package com.hank.flow.open.llm

import com.hank.flow.open.llm.PolishEngine.Companion.cleanPolishOutput
import org.junit.Assert.assertEquals
import org.junit.Test

class PolishEngineTest {

    @Test
    fun cleanPolishOutputRemovesQwenThinkingBlock() {
        val output = """
            <think>

            </think>

            今天我们测试文本润色链路。
        """.trimIndent()

        assertEquals("今天我们测试文本润色链路。", output.cleanPolishOutput())
    }

    @Test
    fun cleanPolishOutputRemovesWrappingQuotesAfterThinkingBlock() {
        val output = "<think>检查原意。</think>\"今天我们测试文本润色链路。\""

        assertEquals("今天我们测试文本润色链路。", output.cleanPolishOutput())
    }
}
