package com.hank.flow.open.insertion

import org.junit.Assert.assertEquals
import org.junit.Test

class TextInsertionPlanTest {

    @Test
    fun appends_when_no_selection_and_field_empty() {
        val state = TextInsertionState(
            currentText = "",
            isShowingHint = false,
            rawSelectionStart = -1,
            rawSelectionEnd = -1,
        )
        val plan = state.planInsertion("hello")
        assertEquals("hello", plan.newText)
        assertEquals(5, plan.cursor)
        assertEquals(0, plan.rangeStart)
        assertEquals(0, plan.rangeEnd)
    }

    @Test
    fun appends_when_field_has_text_and_no_selection() {
        val state = TextInsertionState(
            currentText = "abc",
            isShowingHint = false,
            rawSelectionStart = -1,
            rawSelectionEnd = -1,
        )
        val plan = state.planInsertion("XY")
        assertEquals("abcXY", plan.newText)
        assertEquals(5, plan.cursor)
        assertEquals(3, plan.rangeStart)
        assertEquals(3, plan.rangeEnd)
    }

    @Test
    fun inserts_at_caret_position_when_selection_is_a_collapsed_caret() {
        val state = TextInsertionState(
            currentText = "abcdef",
            isShowingHint = false,
            rawSelectionStart = 3,
            rawSelectionEnd = 3,
        )
        val plan = state.planInsertion("XY")
        assertEquals("abcXYdef", plan.newText)
        assertEquals(5, plan.cursor)
    }

    @Test
    fun replaces_selected_range_when_selection_is_nonzero() {
        val state = TextInsertionState(
            currentText = "abcdef",
            isShowingHint = false,
            rawSelectionStart = 2,
            rawSelectionEnd = 5,
        )
        val plan = state.planInsertion("Z")
        assertEquals("abZf", plan.newText)
        assertEquals(3, plan.cursor)
        assertEquals(2, plan.rangeStart)
        assertEquals(5, plan.rangeEnd)
    }

    @Test
    fun treats_field_as_empty_when_node_is_showing_hint_text() {
        // This is the bug case from INC-SERVICE-0002:
        // AccessibilityNodeInfo.getText() returns the hint placeholder when the
        // field is actually empty. The inserter must NOT append after the hint.
        val state = TextInsertionState(
            currentText = "Search settings",
            isShowingHint = true,
            rawSelectionStart = -1,
            rawSelectionEnd = -1,
        )
        val plan = state.planInsertion("hello world")
        assertEquals("hello world", plan.newText)
        assertEquals(11, plan.cursor)
        assertEquals(0, plan.rangeStart)
        assertEquals(0, plan.rangeEnd)
    }

    @Test
    fun coerces_out_of_range_selection_to_field_length() {
        val state = TextInsertionState(
            currentText = "abc",
            isShowingHint = false,
            rawSelectionStart = 99,
            rawSelectionEnd = 200,
        )
        val plan = state.planInsertion("X")
        assertEquals("abcX", plan.newText)
        assertEquals(4, plan.cursor)
        assertEquals(3, plan.rangeStart)
        assertEquals(3, plan.rangeEnd)
    }

    @Test
    fun coerces_negative_selection_end_when_start_is_set() {
        val state = TextInsertionState(
            currentText = "abc",
            isShowingHint = false,
            rawSelectionStart = 1,
            rawSelectionEnd = -1,
        )
        val plan = state.planInsertion("X")
        assertEquals("aXbc", plan.newText)
        assertEquals(2, plan.cursor)
        assertEquals(1, plan.rangeStart)
        assertEquals(1, plan.rangeEnd)
    }

    @Test
    fun inserting_empty_string_is_an_identity_operation() {
        val state = TextInsertionState(
            currentText = "abc",
            isShowingHint = false,
            rawSelectionStart = -1,
            rawSelectionEnd = -1,
        )
        val plan = state.planInsertion("")
        assertEquals("abc", plan.newText)
        assertEquals(3, plan.cursor)
    }
}
