package org.roxycode.ui.views;

import org.roxycode.core.utils.UIUtils;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.roxycode.core.LogCaptureService;
import org.roxycode.core.SettingsService;
import org.roxycode.ui.ThemeService;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.List;

@Singleton
public class LogsView extends JPanel {

    private final LogCaptureService logCaptureService;
    private final SettingsService settingsService;
    private final ThemeService themeService;
    private final JTextPane logsArea = new JTextPane();

    @Outlet
    private JComponent viewLogs;

    @Outlet
    private JScrollPane logsScrollPane;

    @Outlet
    private JButton refreshLogsButton;

    @Inject
    public LogsView(LogCaptureService logCaptureService, SettingsService settingsService, ThemeService themeService) {
        this.logCaptureService = logCaptureService;
        this.settingsService = settingsService;
        this.themeService = themeService;
        setLayout(new BorderLayout());
    }

    @PostConstruct
    public void init() {
        add(UILoader.load(this, "LogsView.xml"));
        if (logsScrollPane != null) {
            logsArea.setEditable(false);
            logsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            logsScrollPane.setViewportView(logsArea);
            themeService.registerPane(logsArea);
            UIUtils.addContextMenu(logsArea);
        }
        initListeners();
    }

    private void initListeners() {
        if (refreshLogsButton != null) {
            refreshLogsButton.addActionListener(e -> refresh());
        }
    }

    public void refresh() {
        if (logsArea == null)
            return;

        List<String> logs = logCaptureService.getLogs(settingsService.getLogLinesCount());
        
        logsArea.setText("");
        StyledDocument doc = logsArea.getStyledDocument();
        
        StyleContext sc = StyleContext.getDefaultStyleContext();
        
        AttributeSet defaultAttr = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, logsArea.getForeground());
        AttributeSet errAttr = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, Color.RED);
        AttributeSet outAttr = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, new Color(0, 150, 0)); // Dark green
        AttributeSet warnAttr = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, Color.ORANGE);
        AttributeSet infoAttr = sc.addAttribute(SimpleAttributeSet.EMPTY, StyleConstants.Foreground, new Color(0, 100, 200)); // Blueish

        try {
            for (String line : logs) {
                AttributeSet attr = defaultAttr;
                
                if (line.startsWith("[ERR]")) {
                    attr = errAttr;
                } else if (line.startsWith("[OUT]")) {
                    attr = outAttr;
                }
                
                // Secondary check for log levels inside the string
                String upperLine = line.toUpperCase();
                if (upperLine.contains(" ERROR ") || upperLine.contains(" SEVERE ")) {
                    attr = errAttr;
                } else if (upperLine.contains(" WARN ") || upperLine.contains(" WARNING ")) {
                    attr = warnAttr;
                } else if (upperLine.contains(" INFO ")) {
                    attr = infoAttr;
                }

                doc.insertString(doc.getLength(), line + "\n", attr);
            }
        } catch (BadLocationException e) {
            // Should not happen
        }

        logsArea.setCaretPosition(doc.getLength());
    }
}
