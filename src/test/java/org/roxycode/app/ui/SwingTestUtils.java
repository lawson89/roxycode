package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.swing.*;
import org.junit.jupiter.api.Assertions;

public final class SwingTestUtils {

    static {
        SwingHtmlUtils.setTestMode(true);
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

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
        runOnEdt(() -> {
        });
    }

    static <T> T awaitFuture(CompletableFuture<T> future) {
        if (future == null) {
            flushEdt();
            return null;
        }
        try {
            T result = future.get(5, TimeUnit.SECONDS);
            flushEdt();
            return result;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void invokeUnchecked(ThrowingRunnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void runOnEdt(ThrowingRunnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            invokeUnchecked(action);
        } else {
            invokeUnchecked(() -> SwingUtilities.invokeAndWait(() -> invokeUnchecked(action)));
        }
    }

    static <T> T callOnEdt(Callable<T> supplier) {
        AtomicReference<T> result = new AtomicReference<>();
        runOnEdt(() -> result.set(supplier.call()));
        return result.get();
    }

    static void clickOnEdt(AbstractButton button) {
        runOnEdt(button::doClick);
        flushEdt();
    }

    static void awaitCondition(BooleanSupplier condition) {
        for (int i = 0; i < 50; i++) {
            flushEdt();
            if (condition.getAsBoolean())
                return;
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        flushEdt();
        if (!condition.getAsBoolean()) {
            Assertions.fail("Timed out waiting for condition");
        }
    }

    static void withDarkTheme(JComponent component, ThrowingRunnable assertions) {
        try {
            runOnEdt(() -> {
                FlatDarkLaf.setup();
                if (component != null)
                    SwingUtilities.updateComponentTreeUI(component);
            });
            invokeUnchecked(assertions);
        } finally {
            runOnEdt(() -> {
                FlatLightLaf.setup();
                if (component != null)
                    SwingUtilities.updateComponentTreeUI(component);
            });
        }
    }

    static <E> Component renderListCell(JList<E> list, int index, boolean isSelected) {
        return list.getCellRenderer().getListCellRendererComponent(list, list.getModel().getElementAt(index), index,
                isSelected, false);
    }

    static <T extends Component> T findComponent(Container root, Class<T> type, Predicate<T> predicate) {
        if (root == null)
            return null;
        for (Component comp : root.getComponents()) {
            if (type.isInstance(comp) && predicate.test(type.cast(comp))) {
                return type.cast(comp);
            }
            if (comp instanceof Container child) {
                T found = findComponent(child, type, predicate);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    static <T extends Component> T findComponent(Container root, Class<T> type) {
        return findComponent(root, type, c -> true);
    }

    static JButton findButton(Container root, String text) {
        return findComponent(root, JButton.class, b -> text.equals(b.getText()));
    }
}
