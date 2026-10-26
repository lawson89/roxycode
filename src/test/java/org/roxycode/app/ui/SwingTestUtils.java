package org.roxycode.app.ui;

import java.awt.Component;
import java.awt.Container;
import java.util.function.Predicate;
import javax.swing.JButton;
import javax.swing.text.Element;

/** Shared component and HTML document tree traversal utilities for UI tests. */
final class SwingTestUtils {

    private SwingTestUtils() {}

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
}
