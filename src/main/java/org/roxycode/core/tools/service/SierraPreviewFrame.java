package org.roxycode.core.tools.service;

import jakarta.inject.Singleton;
import org.httprpc.sierra.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.nio.file.Path;

@Singleton
public class SierraPreviewFrame extends JFrame {
    private final Path path;

    public SierraPreviewFrame(Path path) {
        if (path == null) {
            throw new IllegalArgumentException();
        }

        setTitle(path.getFileName().toString());
        this.path = path;

        refresh();
    }

    private void refresh() {
        JComponent component;
        try {
            component = UILoader.load(path);
            component.setVisible(true);
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
    }

    public void close() {
        setVisible(false);
        dispose();
    }

}
