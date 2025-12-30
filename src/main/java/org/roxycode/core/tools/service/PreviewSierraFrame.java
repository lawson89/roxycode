package org.roxycode.core.tools.service;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import jakarta.inject.Singleton;
import org.httprpc.sierra.ColumnPanel;
import org.httprpc.sierra.HorizontalAlignment;
import org.httprpc.sierra.Spacer;
import org.httprpc.sierra.TextPane;
import org.httprpc.sierra.UILoader;
import org.httprpc.sierra.VerticalAlignment;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;

@Singleton
public class PreviewSierraFrame extends JFrame {
    private Path path;

    private static final String REFRESH_ACTION_KEY = "refresh";
    private static final String TOGGLE_DARK_MODE_ACTION_KEY = "toggle-dark-mode";

    public PreviewSierraFrame(Path path) {
        if (path == null) {
            throw new IllegalArgumentException();
        }

        setTitle(path.getFileName().toString());

        this.path = path;

        var inputMap = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        var actionMap = rootPane.getActionMap();

        var shortcutModifier = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, shortcutModifier, false), REFRESH_ACTION_KEY);
        actionMap.put(REFRESH_ACTION_KEY, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                refresh();
            }
        });

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_D, shortcutModifier, false), TOGGLE_DARK_MODE_ACTION_KEY);
        actionMap.put(TOGGLE_DARK_MODE_ACTION_KEY, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (FlatLaf.isLafDark()) {
                    FlatLightLaf.setup();
                } else {
                    FlatDarkLaf.setup();
                }

                refresh();
            }
        });

        refresh();
    }

    private void refresh() {
        JComponent component;
        try {
            component = UILoader.load(path);
        } catch (Exception exception) {
            var columnPanel = new ColumnPanel();

            columnPanel.setSpacing(8);
            columnPanel.setBorder(new EmptyBorder(8, 8, 8, 8));

            columnPanel.add(new Spacer(), 1.0);

            columnPanel.add(new JLabel(UIManager.getIcon("OptionPane.errorIcon"), SwingConstants.CENTER));

            var messageTextPane = new TextPane(exception.getMessage());

            messageTextPane.setWrapText(true);
            messageTextPane.setHorizontalAlignment(HorizontalAlignment.CENTER);
            messageTextPane.setVerticalAlignment(VerticalAlignment.CENTER);

            columnPanel.add(messageTextPane);

            columnPanel.add(new Spacer(), 1.0);

            component = columnPanel;
        }

        setContentPane(component);

        revalidate();
    }


    public void preview() {
        setSize(1200, 800);
        setLocationRelativeTo(null);
        //setVisible(true);
    }

    public void close() {
        setVisible(false);
        dispose();
    }

}
