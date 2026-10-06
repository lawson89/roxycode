package org.roxycode.app.ui;

import net.miginfocom.swing.MigLayout;
import org.roxycode.app.ai.services.cache.GeminiCacheService;
import org.roxycode.app.ai.services.cache.ProjectCacheMetaService;
import org.roxycode.app.ai.services.cache.ProjectPackerService;
import org.roxycode.app.model.cache.ProjectCacheMeta;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public class CodebaseCachePanel extends JPanel {
    private static final Logger log = LoggerFactory.getLogger(CodebaseCachePanel.class);
    private final ProjectService projectService;
    private final SettingsService settingsService;
    private final ProjectPackerService packerService;
    private final ProjectCacheMetaService metaService;
    private final GeminiCacheService geminiCacheService;

    private final JLabel statusLabel = new JLabel("Status: Unknown");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JButton packButton = new JButton("Pack Local Codebase");
    private final JButton uploadButton = new JButton("Upload to Gemini");
    private final JTextArea metaInfoArea = new JTextArea(8, 40);
    private final JTextArea packedCodebaseArea = new JTextArea(20, 60);

    public CodebaseCachePanel(ProjectService projectService, SettingsService settingsService,
                               ProjectPackerService packerService, ProjectCacheMetaService metaService,
                               GeminiCacheService geminiCacheService) {
        this.projectService = projectService;
        this.settingsService = settingsService;
        this.packerService = packerService;
        this.metaService = metaService;
        this.geminiCacheService = geminiCacheService;

        initComponents();
        refreshStatus();
    }

    private void initComponents() {
        setLayout(new MigLayout("fill, insets 20", "[grow]", "[]20[]10[]20[150!]20[grow]"));

        JLabel title = new JLabel("Gemini Codebase Caching");
        title.setFont(new Font("SansSerif", Font.BOLD, 18));
        add(title, "wrap");

        JPanel statusPanel = new JPanel(new MigLayout("insets 0, fillx", "[]10[grow]", "[][]10[]"));
        statusPanel.add(new JLabel("Status:"));
        statusPanel.add(statusLabel, "wrap");
        statusPanel.add(progressBar, "span 2, growx, wrap");
        
        JPanel buttonPanel = new JPanel(new MigLayout("insets 0", "[]10[]"));
        buttonPanel.add(packButton);
        buttonPanel.add(uploadButton);
        statusPanel.add(buttonPanel, "span 2");
        
        add(statusPanel, "growx, wrap");

        metaInfoArea.setEditable(false);
        metaInfoArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        add(new JScrollPane(metaInfoArea), "growx, wrap");

        packedCodebaseArea.setEditable(false);
        packedCodebaseArea.setFont(new Font("Monospaced", Font.PLAIN, 11));
        add(new JScrollPane(packedCodebaseArea), "grow, push");

        packButton.addActionListener(e -> runPack());
        uploadButton.addActionListener(e -> runUpload());
        
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
    }

    private void refreshStatus() {
        Optional<ProjectCacheMeta> meta = metaService.loadMeta();
        Optional<String> packed = metaService.loadRepoCache();

        if (meta.isPresent()) {
            ProjectCacheMeta m = meta.get();
            statusLabel.setText("Cached on " + m.lastCached());
            metaInfoArea.setText("Project: " + m.projectName() + "\n" +
                    "Cache Name: " + m.cacheName() + "\n" +
                    "Last Cached: " + m.lastCached() + "\n" +
                    "TTL: " + m.ttlSeconds() + "s\n" +
                    "Estimated Tokens: " + m.estimatedTokens());
            
            packed.ifPresent(packedCodebaseArea::setText);
            if (packed.isEmpty()) {
                packedCodebaseArea.setText("(Packed codebase content not found on disk)");
            }
        } else {
            statusLabel.setText("No active cache found.");
            metaInfoArea.setText("Metadata will appear here after cache creation.");
            packedCodebaseArea.setText("");
            
            if (packed.isPresent()) {
                 statusLabel.setText("Local codebase packed. Ready to upload.");
                 packedCodebaseArea.setText(packed.get());
            }
        }
        
        uploadButton.setEnabled(packed.isPresent());
    }

    private void runPack() {
        packButton.setEnabled(false);
        uploadButton.setEnabled(false);
        progressBar.setVisible(true);
        progressBar.setValue(0);
        statusLabel.setText("Packing codebase...");

        SwingWorker<Long, ProgressUpdate> worker = new SwingWorker<>() {
            @Override
            protected Long doInBackground() throws Exception {
                var packResult = packerService.packCodebase((current, total, message) -> {
                    publish(new ProgressUpdate(current, total, message));
                });
                if (!packResult.success()) throw new RuntimeException("Packing failed: " + packResult.errorHint());
                
                publish(new ProgressUpdate(0, 1, "Estimating tokens..."));
                return geminiCacheService.estimateTokens(packResult.content());
            }

            @Override
            protected void process(List<ProgressUpdate> chunks) {
                ProgressUpdate last = chunks.get(chunks.size() - 1);
                if (last.total > 0) {
                    progressBar.setIndeterminate(false);
                    progressBar.setMaximum(last.total);
                    progressBar.setValue(last.current);
                } else {
                    progressBar.setIndeterminate(true);
                }
                statusLabel.setText(last.message);
            }

            @Override
            protected void done() {
                try {
                    long tokens = get();
                    statusLabel.setText("Codebase packed. Estimated tokens: " + tokens);
                    refreshStatus();
                } catch (Exception e) {
                    log.error("Packing failed", e);
                    statusLabel.setText("Failed: " + e.getMessage());
                } finally {
                    packButton.setEnabled(true);
                    progressBar.setVisible(false);
                }
            }
        };
        worker.execute();
    }

    private void runUpload() {
        Optional<String> packedContent = metaService.loadRepoCache();
        if (packedContent.isEmpty()) {
            statusLabel.setText("Error: No packed codebase found. Pack first.");
            return;
        }

        packButton.setEnabled(false);
        uploadButton.setEnabled(false);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);
        statusLabel.setText("Uploading to Gemini cache...");

        SwingWorker<String, Void> worker = new SwingWorker<>() {
            private long estimatedTokens = 0;

            @Override
            protected String doInBackground() throws Exception {
                String content = packedContent.get();
                estimatedTokens = geminiCacheService.estimateTokens(content);
                String model = settingsService.getSettings().getGeminiModel();
                return geminiCacheService.createCache(model, content);
            }

            @Override
            protected void done() {
                try {
                    String result = get();
                    if (result.startsWith("Error")) {
                        statusLabel.setText(result);
                    } else {
                        ProjectCacheMeta meta = new ProjectCacheMeta(
                                projectService.getProjectName(),
                                LocalDateTime.now(),
                                result,
                                3600,
                                estimatedTokens
                        );
                        metaService.saveMeta(meta);
                        statusLabel.setText("Upload successful.");
                        refreshStatus();
                    }
                } catch (Exception e) {
                    log.error("Upload failed", e);
                    statusLabel.setText("Upload failed: " + e.getMessage());
                } finally {
                    packButton.setEnabled(true);
                    uploadButton.setEnabled(true);
                    progressBar.setVisible(false);
                }
            }
        };
        worker.execute();
    }

    private static record ProgressUpdate(int current, int total, String message) {}
}