package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ChatPanelTruncationTest {

    @Test
    public void testTruncateResult_Null() {
        assertEquals("null", ChatPanel.truncateResult(null));
    }

    @Test
    public void testTruncateResult_Short() {
        String result = "Hello World";
        assertEquals(result, ChatPanel.truncateResult(result));
    }

    @Test
    public void testTruncateResult_Exact100() {
        String result = "a".repeat(100);
        assertEquals(result, ChatPanel.truncateResult(result));
    }

    @Test
    public void testTruncateResult_Long() {
        String base = "a".repeat(100);
        String result = base + "Extra";
        String expected = base + "... [Truncated]";
        assertEquals(expected, ChatPanel.truncateResult(result));
    }
}