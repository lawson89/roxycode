package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.SwingUtilities;

public final class SwingTestUtils {

    private SwingTestUtils() {
    }

    public static void assertContainsAll(String actual, String... expectedSubstrings) {
        assertNotNull(actual, "Actual string must not be null");
        for (String sub : expectedSubstrings) {
            assertTrue(actual.contains(sub), () -> "Expected '" + sub + "' in: " + actual);
        }
    }

    public static void assertContainsNone(String actual, String... forbiddenSubstrings) {
        assertNotNull(actual, "Actual string must not be null");
        for (String sub : forbiddenSubstrings) {
            assertFalse(actual.contains(sub), () -> "Unexpected '" + sub + "' in: " + actual);
        }
    }

    static void flushEdt() {
        if (SwingUtilities.isEventDispatchThread()) {
            return;
        }
        try {
            SwingUtilities.invokeAndWait(() -> {
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
