package org.roxycode.ui.views;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.roxycode.cache.CodebasePackerService;
import org.roxycode.cache.GeminiCacheService;
import org.roxycode.core.utils.UIUtils;
import org.roxycode.core.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Singleton
public class CodebaseCacheView extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(CodebaseCacheView.class);

    private final SettingsService settingsService;

    private final CodebasePackerService codebasePackerService;

    private final GeminiCacheService geminiCacheService;

    private final JTextPane cacheContentArea = new JTextPane();

    private final org.roxycode.ui.ThemeService themeService;

    @Outlet
    private JLabel cachePathLabel;

    @Outlet
    private JLabel cacheLastModifiedLabel;

    @Outlet
    private JLabel cacheTokenCountLabel;

    @Outlet
    private JButton rebuildCacheButton;

    @Outlet
    private JLabel onlineCacheIdLabel;

    @Outlet
    private JLabel onlineCacheTimestampLabel;

    @Outlet
    private JButton pushCacheButton;

    @Outlet
    private JScrollPane cacheContentScrollPane;

    @Inject
    public CodebaseCacheView(SettingsService settingsService, CodebasePackerService codebasePackerService, GeminiCacheService geminiCacheService, org.roxycode.ui.ThemeService themeService) {
        this.settingsService = settingsService;
        this.codebasePackerService = codebasePackerService;
        this.geminiCacheService = geminiCacheService;
        this.themeService = themeService;
        setLayout(new BorderLayout());
    }

    @PostConstruct
    public void init() {
        add(UILoader.load(this, "CodebaseCacheView.xml"));
        if (cacheContentScrollPane != null) {
            cacheContentScrollPane.setViewportView(cacheContentArea);
        }
        themeService.registerPane(cacheContentArea);
        initListeners();
    }

    private void initListeners() {
        if (rebuildCacheButton != null)
            rebuildCacheButton.addActionListener(e -> onRebuildCache());
        if (pushCacheButton != null)
            pushCacheButton.addActionListener(e -> onPushCache());
    }

    public String readFirstBytesSimple(File file, int size) throws IOException {
        try (InputStream is = FileUtils.openInputStream(file)) {
            // 1. Read the bytes
            byte[] bytes = IOUtils.toByteArray(is, size);
            // 2. Convert to String using UTF-8
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    public void refresh() {
        try {
            Path cacheFile = codebasePackerService.getCacheFilePath();
            boolean exists = Files.exists(cacheFile);
            if (exists) {
                long size = Files.size(cacheFile);
                cachePathLabel.setText(cacheFile.toString());
                cacheLastModifiedLabel.setText(Files.getLastModifiedTime(cacheFile).toString());
                String content = "";
                if (size > 0) {
                    content = readFirstBytesSimple(cacheFile.toFile(), 50000);
                }
                cacheTokenCountLabel.setText(String.format("%,d tokens (estimated)", size / 4));
                cacheContentArea.setText(content);
            } else {
                cachePathLabel.setText("No cache found");
                cacheLastModifiedLabel.setText("-");
                cacheTokenCountLabel.setText("0");
                cacheContentArea.setText("*No cache file exists for this project.*");
            }
            if (pushCacheButton != null)
                pushCacheButton.setEnabled(exists);
            Path projectRoot = settingsService.getCurrentProjectPath();
            geminiCacheService.getProjectCacheMeta(projectRoot).ifPresentOrElse(meta -> {
                onlineCacheIdLabel.setText(meta.geminiCacheId());
                onlineCacheTimestampLabel.setText(meta.generatedAt());
            }, () -> {
                onlineCacheIdLabel.setText("Not Pushed");
                onlineCacheTimestampLabel.setText("-");
            });
        } catch (Exception e) {
            log.error("Error refreshing cache view", e);
        }
    }

    private void onRebuildCache() {
        rebuildCacheButton.setEnabled(false);
        cacheContentArea.setText("*Rebuilding codebase cache...*");
        new Thread(() -> {
            try {
                codebasePackerService.buildProjectCache();
                SwingUtilities.invokeLater(() -> {
                    refresh();
                    rebuildCacheButton.setEnabled(true);
                });
            } catch (Exception e) {
                log.error("Error rebuilding cache", e);
                SwingUtilities.invokeLater(() -> {
                    refresh();
                    rebuildCacheButton.setEnabled(true);
                    JOptionPane pane = new JOptionPane("Error rebuilding cache: " + e.getMessage(), JOptionPane.ERROR_MESSAGE);
                    JDialog dialog = pane.createDialog(this, "Error");
                    UIUtils.centerDialog(dialog, this);
                    dialog.setVisible(true);
                });
            }
        }).start();
    }

    private void onPushCache() {
        pushCacheButton.setEnabled(false);
        new Thread(() -> {
            try {
                Path projectRoot = settingsService.getCurrentProjectPath();
                geminiCacheService.pushCache(projectRoot);
                SwingUtilities.invokeLater(() -> {
                    refresh();
                    pushCacheButton.setEnabled(true);
                    JOptionPane pane = new JOptionPane("Cache pushed successfully.", JOptionPane.INFORMATION_MESSAGE);
                    JDialog dialog = pane.createDialog(this, "Success");
                    UIUtils.centerDialog(dialog, this);
                    dialog.setVisible(true);
                });
            } catch (Exception e) {
                log.error("Error pushing cache", e);
                SwingUtilities.invokeLater(() -> {
                    refresh();
                    pushCacheButton.setEnabled(true);
                    JOptionPane pane = new JOptionPane("Error pushing cache: " + e.getMessage(), JOptionPane.ERROR_MESSAGE);
                    JDialog dialog = pane.createDialog(this, "Error");
                    UIUtils.centerDialog(dialog, this);
                    dialog.setVisible(true);
                });
            }
        }).start();
    }

    public JTextPane getCacheContentArea() {
        return cacheContentArea;
    }
}
