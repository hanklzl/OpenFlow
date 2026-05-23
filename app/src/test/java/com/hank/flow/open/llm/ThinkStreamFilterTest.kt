package com.hank.flow.open.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkStreamFilterTest {

    private fun feed(filter: ThinkStreamFilter, vararg pieces: String): String =
        buildString { pieces.forEach { append(filter.consume(it)) } }

    @Test
    fun emitsTextWithoutThinkBlock() {
        val f = ThinkStreamFilter()
        assertEquals("你好世界", feed(f, "你好", "世界"))
    }

    @Test
    fun dropsCompleteThinkBlockBetweenTokens() {
        val f = ThinkStreamFilter()
        assertEquals("hello", feed(f, "<think>", "x", "</think>", "hello"))
    }

    @Test
    fun holdsBackUnclosedThinkUntilCloses() {
        val f = ThinkStreamFilter()
        val out = StringBuilder()
        out.append(f.consume("<think>"))
        out.append(f.consume("draft"))
        // still inside <think>, nothing emitted
        assertEquals("", out.toString())
        out.append(f.consume("</think>final"))
        assertEquals("final", out.toString())
    }

    @Test
    fun holdsBackPartialOpenTagUntilResolved() {
        val f = ThinkStreamFilter()
        // "<th" alone could become "<think>" → hold back
        val first = f.consume("hello<th")
        assertEquals("hello", first)
        // confirms it IS <think>
        val second = f.consume("ink>discard</think>world")
        assertEquals("world", second)
    }

    @Test
    fun holdsBackPartialTagFalseAlarm() {
        val f = ThinkStreamFilter()
        // "<a" looks like an open tag but isn't <think>
        val first = f.consume("ab<a")
        assertEquals("ab", first)
        // arrives ">" — the buffered "<a>" should now be emittable
        val second = f.consume(">cd")
        assertEquals("<a>cd", second)
    }

    @Test
    fun cumulativeOutputMatchesNonStreamingCleaning() {
        val f = ThinkStreamFilter()
        val pieces = listOf(
            "<thi", "nk>", "心理活动", "</thi", "nk>",
            "今天 ", "天气", " 不错", "。",
        )
        val streamed = buildString { pieces.forEach { append(f.consume(it)) } }
        assertEquals("今天 天气 不错。", streamed)
    }

    @Test
    fun toleratesEmptyPiece() {
        val f = ThinkStreamFilter()
        assertEquals("", f.consume(""))
        assertEquals("hi", f.consume("hi"))
    }
}
