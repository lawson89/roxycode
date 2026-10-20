package org.roxycode.app.util;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ExceptionUtilsTest {

    @Test
    void testFindCauseAndGetRootCause() {
        IOException root = new IOException("disk full");
        IllegalStateException mid = new IllegalStateException("state error", root);
        RuntimeException top = new RuntimeException("top error", mid);

        assertSame(root, ExceptionUtils.findCause(top, IOException.class));
        assertSame(mid, ExceptionUtils.findCause(top, IllegalStateException.class));
        assertNull(ExceptionUtils.findCause(top, InterruptedException.class));
        assertSame(root, ExceptionUtils.getRootCause(top));
        assertNull(ExceptionUtils.getRootCause(null));
    }

    @Test
    void testHasCauseWithMessage() {
        IllegalArgumentException root = new IllegalArgumentException("MAX_TOOL_TURNS_EXCEEDED");
        RuntimeException top = new RuntimeException("Wrapper", root);

        assertTrue(ExceptionUtils.hasCauseWithMessage(top, "MAX_TOOL_TURNS_EXCEEDED"));
        assertFalse(ExceptionUtils.hasCauseWithMessage(top, "NON_EXISTENT"));
    }
}
