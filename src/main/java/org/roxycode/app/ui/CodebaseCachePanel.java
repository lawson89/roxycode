package org.roxycode.app.ui;

import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.PlainDocument;
import net.miginfocom.swing.MigLayout;
import org.roxycode.app.ai.services.cache.ProjectCacheMetaService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.ProjectCacheMeta;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.TokenUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class CodebaseCachePanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(CodebaseCachePanel.class);
    static final int MAX_DISPLAY_CHARS = 200_000;
    private final transient ProjectCacheMetaService metaService;
    private final transient RepoMapPackerService packerService;
    private final transient PromptService promptService;

    private final JLabel statusLabel = new JLabel("Status: Unknown");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JButton packButton = new JButton("Update Implicit Cache");

    private final JTextArea repoMapArea = new JTextArea();
    private final JTextArea stableContextArea = new JTextArea();
    private final JTextArea sessionContextArea = new JTextArea();
    private transient volatile SwingWorker<CacheStatusData, Void> activeRefreshWorker;
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    record CacheStatusData(
            Optional<ProjectCacheMeta> meta,
            Optional<String> packed,
            long stableTokens,
            Document repoMapDoc,
            Document stableContextDoc,
            Document sessionContextDoc) {}

    public CodebaseCachePanel(
            RepoMapPackerService packerService,
            ProjectCacheMetaService metaService,
            PromptService promptService) {
        this.packerService = packerService;
        this.metaService = metaService;
        this.promptService = promptService;

        initComponents();
        refreshStatus();
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        Edt.run(this::refreshStatus);
    }

    static String truncateForDisplay(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_DISPLAY_CHARS) {
            return text;
        }
        return text.substring(0, MAX_DISPLAY_CHARS)
                + String.format(
                        "\n\n... [Display truncated at 200,000 characters for UI responsiveness; full content (%,d chars) is cached]",
                        text.length());
    }

    private static Document createDisplayDocument(String text) {
        PlainDocument doc = new PlainDocument();
        String display = truncateForDisplay(text);
        if (!display.isEmpty()) {
            try {
                doc.insertString(0, display, null);
            } catch (BadLocationException e) {
                log.warn("Failed to populate display document", e);
            }
        }
        return doc;
    }

    private void initComponents() {
        setLayout(new MigLayout("fill, insets 20", "[grow]", "[]20[]10[grow]"));

        JLabel title = new JLabel("Implicit Cache (Repo Map)");
        title.setFont(new Font("SansSerif", Font.BOLD, 18));
        add(title, "wrap");

        JPanel statusPanel = new JPanel(new MigLayout("insets 0, fillx", "[]10[grow]", "[][]10[]"));
        statusPanel.add(statusLabel, "wrap, span 2");
        statusPanel.add(progressBar, "span 2, growx, wrap");

        JPanel buttonPanel = new JPanel(new MigLayout("insets 0", "[]10[]"));
        buttonPanel.add(packButton);
        statusPanel.add(buttonPanel, "span 2");

        add(statusPanel, "growx, wrap");

        Font monoFont = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        for (JTextArea area : getTextAreas()) {
            area.setEditable(false);
            area.setFont(monoFont);
        }

        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Repo Map (Dynamic)", new JScrollPane(repoMapArea));
        tabbedPane.addTab("Prompts & Docs (Stable)", new JScrollPane(stableContextArea));
        tabbedPane.addTab("Session Context (Dynamic)", new JScrollPane(sessionContextArea));

        add(tabbedPane, "grow, push");

        packButton.addActionListener(e -> runPack());

        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (repoMapArea != null) {
            updateTheme();
        }
    }

    public void updateTheme() {
        Color bg = UIManager.getColor("TextArea.background");
        Color fg = UIManager.getColor("TextArea.foreground");
        Color caret = UIManager.getColor("TextArea.caretForeground");
        for (JTextArea area : getTextAreas()) {
            area.setBackground(bg);
            area.setForeground(fg);
            area.setCaretColor(caret);
        }
    }

    private JTextArea[] getTextAreas() {
        return new JTextArea[] {repoMapArea, stableContextArea, sessionContextArea};
    }

    SwingWorker<CacheStatusData, Void> refreshStatus() {
        SwingWorker<CacheStatusData, Void> worker =
                new SwingWorker<>() {
                    @Override
                    protected CacheStatusData doInBackground() {
                        Optional<ProjectCacheMeta> meta =
                                metaService != null ? metaService.loadMeta() : Optional.empty();
                        Optional<String> packed =
                                metaService != null
                                        ? metaService.loadRepoCache()
                                        : Optional.empty();
                        CacheStatusData data;
                        if (meta.isPresent()) {
                            String repoMapText = packed.orElse("Content not found on disk.");
                            String stableContext = getStableContextString();
                            long stableTokens = TokenUtils.estimateTokens(stableContext);
                            String sessionContext = getSessionContextString();
                            data =
                                    new CacheStatusData(
                                            meta,
                                            packed,
                                            stableTokens,
                                            createDisplayDocument(repoMapText),
                                            createDisplayDocument(stableContext),
                                            createDisplayDocument(sessionContext));
                        } else if (packed.isPresent()) {
                            data =
                                    new CacheStatusData(
                                            meta,
                                            packed,
                                            0,
                                            createDisplayDocument(packed.get()),
                                            createDisplayDocument(""),
                                            createDisplayDocument(""));
                        } else {
                            data =
                                    new CacheStatusData(
                                            meta,
                                            packed,
                                            0,
                                            createDisplayDocument(
                                                    "Repo Map will appear here after generation."),
                                            createDisplayDocument(""),
                                            createDisplayDocument(""));
                        }
                        return data;
                    }

                    @Override
                    protected void done() {
                        if (isCancelled() || this != activeRefreshWorker) {
                            return;
                        }
                        try {
                            applyStatusDataOnEdt(this, get());
                        } catch (Exception e) {
                            log.error("Failed to refresh codebase cache status", e);
                        }
                    }
                };
        activeRefreshWorker = worker;
        worker.execute();
        return worker;
    }

    private void applyStatusDataOnEdt(
            SwingWorker<CacheStatusData, Void> worker, CacheStatusData data) {
        if (worker != activeRefreshWorker) {
            return;
        }
        if (data.meta().isPresent()) {
            ProjectCacheMeta m = data.meta().get();
            String status =
                    String.format(
                            "Status: Last updated on %s | Stable: %,d | Repo Map: %,d | Total: %,d",
                            m.lastUpdated().format(DATE_FORMATTER),
                            data.stableTokens(),
                            m.estimatedTokens(),
                            data.stableTokens() + m.estimatedTokens());
            statusLabel.setText(status);

            repoMapArea.setDocument(data.repoMapDoc());
            repoMapArea.setCaretPosition(0);

            stableContextArea.setDocument(data.stableContextDoc());
            stableContextArea.setCaretPosition(0);

            sessionContextArea.setDocument(data.sessionContextDoc());
            sessionContextArea.setCaretPosition(0);
        } else if (data.packed().isPresent()) {
            statusLabel.setText("Status: Repo Map available locally.");
            repoMapArea.setDocument(data.repoMapDoc());
            repoMapArea.setCaretPosition(0);
        } else {
            statusLabel.setText("Status: No implicit cache found.");
            repoMapArea.setDocument(data.repoMapDoc());
            repoMapArea.setCaretPosition(0);
        }
    }

    private String getStableContextString() {
        return promptService != null ? promptService.getStableContext() : "";
    }

    private String getSessionContextString() {
        if (promptService == null) {
            return "";
        }
        String session = promptService.getSessionContext();
        return session != null ? session.trim() : "";
    }

    JLabel getStatusLabel() {
        return statusLabel;
    }

    JTextArea getRepoMapArea() {
        return repoMapArea;
    }

    JTextArea getStableContextArea() {
        return stableContextArea;
    }

    JTextArea getSessionContextArea() {
        return sessionContextArea;
    }

    private void runPack() {
        packButton.setEnabled(false);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);
        statusLabel.setText("Generating Repo Map...");

        SwingWorker<org.roxycode.app.ai.services.EditorResult, Void> worker =
                new SwingWorker<>() {
                    @Override
                    protected org.roxycode.app.ai.services.EditorResult doInBackground()
                            throws Exception {
                        return packerService.forceRegenerateRepoMap();
                    }

                    @Override
                    protected void done() {
                        try {
                            var result = get();
                            if (result.success()) {
                                statusLabel.setText("Repo Map updated successfully.");
                                refreshStatus();
                            } else {
                                statusLabel.setText("Failed: " + result.errorHint());
                            }
                        } catch (Exception e) {
                            log.error("Repo map generation failed", e);
                            statusLabel.setText("Failed: " + e.getMessage());
                        } finally {
                            packButton.setEnabled(true);
                            progressBar.setIndeterminate(false);
                            progressBar.setVisible(false);
                        }
                    }
                };
        worker.execute();
    }
}
