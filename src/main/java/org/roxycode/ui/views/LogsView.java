package org.roxycode.ui.views;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.roxycode.core.LogCaptureService;
import org.roxycode.core.SettingsService;

import javax.swing.*;
import java.awt.*;
import java.util.List;

@Singleton
public class LogsView extends JPanel {

    private final LogCaptureService logCaptureService;
    private final SettingsService settingsService;
    private final JTextArea logsArea = new JTextArea();

    @Outlet
    private JComponent viewLogs;

    @Outlet
    private JScrollPane logsScrollPane;

    @Outlet
    private JButton refreshLogsButton;

    @Inject
    public LogsView(LogCaptureService logCaptureService, SettingsService settingsService) {
        this.logCaptureService = logCaptureService;
        this.settingsService = settingsService;
        setLayout(new BorderLayout());
    }

    @PostConstruct
    public void init() {
        add(UILoader.load(this, "LogsView.xml"));
        if (logsScrollPane != null) {
            logsArea.setEditable(false);
            logsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            logsScrollPane.setViewportView(logsArea);
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
        logsArea.setText(String.join("\n", logs));
        if (settingsService.isLogAutoScroll()) {
            logsArea.setCaretPosition(logsArea.getDocument().getLength());
        }
    }
}
