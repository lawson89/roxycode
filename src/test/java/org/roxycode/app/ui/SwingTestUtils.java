package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Component;
import java.awt.Container;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import javax.swing.text.Element;
import javax.swing.text.html.HTML;
import org.junit.jupiter.api.Assertions;

/** Shared component, EDT, async worker, theme, and HTML document utilities for UI tests. */
final class SwingTestUtils {

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private SwingTestUtils() {}

    static void flushEdt() {
        runOnEdt(() -> {});
    }

    static void runOnEdt(ThrowingRunnable action) {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                action.run();
            } else {
                SwingUtilities.invokeAndWait(
                        () -> {
                            try {
                                action.run();
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
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

    static <T> T awaitWorker(Future<T> worker) {
        try {
            T result = worker != null ? worker.get() : null;
            flushEdt();
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void awaitCondition(BooleanSupplier condition) {
        for (int i = 0; i < 50; i++) {
            flushEdt();
            if (condition.getAsBoolean()) {
                return;
            }
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
            runOnEdt(
                    () -> {
                        FlatDarkLaf.setup();
                        if (component != null) {
                            SwingUtilities.updateComponentTreeUI(component);
                        }
                    });
            assertions.run();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            runOnEdt(
                    () -> {
                        FlatLightLaf.setup();
                        if (component != null) {
                            SwingUtilities.updateComponentTreeUI(component);
                        }
                    });
        }
    }

    static <E> Component renderListCell(JList<E> list, int index, boolean isSelected) {
        E value = list.getModel().getElementAt(index);
        return list.getCellRenderer()
                .getListCellRendererComponent(list, value, index, isSelected, false);
    }

    static <T extends Component> T findComponent(
            Container root, Class<T> type, Predicate<T> predicate) {
        if (root == null) {
            return null;
        }
        for (Component comp : root.getComponents()) {
            if (type.isInstance(comp)) {
                T typed = type.cast(comp);
                if (predicate.test(typed)) {
                    return typed;
                }
            }
            if (comp instanceof Container childContainer) {
                T found = findComponent(childContainer, type, predicate);
                if (found != null) {
                    return found;
                }
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

    static Element findElement(Element root, String tagName, Predicate<Element> predicate) {
        if (root == null) {
            return null;
        }
        if ((tagName == null || tagName.equalsIgnoreCase(root.getName())) && predicate.test(root)) {
            return root;
        }
        for (int i = 0; i < root.getElementCount(); i++) {
            Element found = findElement(root.getElement(i), tagName, predicate);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    static Element findTdByClass(Element root, String className) {
        return findElement(
                root,
                "td",
                elem ->
                        className.equals(
                                String.valueOf(
                                        elem.getAttributes().getAttribute(HTML.Attribute.CLASS))));
    }

    static Element findTdWithText(Element root, String snippet) {
        return findElement(
                root,
                "td",
                elem -> {
                    try {
                        int start = elem.getStartOffset();
                        int end = elem.getEndOffset();
                        return elem.getDocument().getText(start, end - start).contains(snippet);
                    } catch (Exception e) {
                        return false;
                    }
                });
    }
}
