package org.roxycode.app.ui;

import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.services.EditorResult;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService.RepoMapSnapshot;
import org.roxycode.app.events.ContextChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
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
    private final transient RepoMapPackerService packerService;
    private final transient PromptService promptService;

    final JLabel statusLabel = new JLabel("Status: Unknown");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    final JButton packButton = new JButton("Update Implicit Cache");

    final JTextArea repoMapArea = new JTextArea();
    final JTextArea stableContextArea = new JTextArea();
    final JTextArea sessionContextArea = new JTextArea();
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    record CacheStatusData(Optional<RepoMapSnapshot> snapshot, long stableTokens, String repoMapText,
            String stableContextText, String sessionContextText) {
    }

    public CodebaseCachePanel(RepoMapPackerService packerService, PromptService promptService) {
        this.packerService = packerService;
        this.promptService = promptService;
        initComponents();
        runPack();
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        Edt.run(this::runPack);
    }

    @EventListener
    public void onContextChanged(ContextChangedEvent event) {
        Edt.run(this::runPack);
    }

    static String truncateForDisplay(String text) {
        String safe = StringUtils.defaultString(text);
        if (safe.length() <= MAX_DISPLAY_CHARS) {
            return safe;
        }
        return safe.substring(0, MAX_DISPLAY_CHARS)
                + """


                        ... [Display truncated at 200,000 characters for UI responsiveness; full content (%,d chars) is cached]"""
                        .formatted(safe.length());
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
        for (JTextArea area : new JTextArea[]{repoMapArea, stableContextArea, sessionContextArea}) {
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

    CompletableFuture<CacheStatusData> refreshStatus() {
        return Edt.supplyAsync(() -> {
            Optional<RepoMapSnapshot> snapshot = Optional.empty();
            if (packerService != null) {
                snapshot = packerService.getCachedSnapshot();
            }
            if (snapshot.isPresent()) {
                RepoMapSnapshot snap = snapshot.get();
                String stableContext = "";
                String sessionContext = "";
                if (promptService != null) {
                    stableContext = StringUtils.defaultString(promptService.getStableContext());
                    sessionContext = StringUtils.trimToEmpty(promptService.getSessionContext());
                }
                return new CacheStatusData(snapshot, TokenUtils.estimateTokens(stableContext),
                        truncateForDisplay(snap.content()), truncateForDisplay(stableContext),
                        truncateForDisplay(sessionContext));
            }
            return new CacheStatusData(snapshot, 0, truncateForDisplay("Repo Map will appear here after generation."),
                    "", "");
        }, this::applyStatusDataOnEdt, e -> log.error("Failed to refresh codebase cache status", e));
    }

    private void applyStatusDataOnEdt(CacheStatusData data) {
        repoMapArea.setText(data.repoMapText());
        repoMapArea.setCaretPosition(0);
        if (data.snapshot().isPresent()) {
            RepoMapSnapshot snap = data.snapshot().get();
            statusLabel.setText(String.format("Status: Last updated on %s | Stable: %,d | Repo Map: %,d | Total: %,d",
                    snap.lastUpdated().format(DATE_FORMATTER), data.stableTokens(), snap.estimatedTokens(),
                    data.stableTokens() + snap.estimatedTokens()));
            stableContextArea.setText(data.stableContextText());
            stableContextArea.setCaretPosition(0);
            sessionContextArea.setText(data.sessionContextText());
            sessionContextArea.setCaretPosition(0);
        } else {
            statusLabel.setText("Status: No implicit cache found.");
        }
    }

    CompletableFuture<EditorResult> runPack() {
        packButton.setEnabled(false);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);
        statusLabel.setText("Generating Repo Map...");

        return Edt.supplyAsync(packerService::forceRegenerateRepoMap, result -> {
            if (result != null && result.success()) {
                statusLabel.setText("Repo Map updated successfully.");
            } else {
                String hint = "Unknown error";
                if (result != null) {
                    hint = result.errorHint();
                }
                statusLabel.setText("Failed: " + hint);
            }
            refreshStatus();
            finishPackUi();
        }, e -> {
            log.error("Repo map generation failed", e);
            statusLabel.setText("Failed: " + e.getMessage());
            finishPackUi();
        });
    }

    private void finishPackUi() {
        packButton.setEnabled(true);
        progressBar.setIndeterminate(false);
        progressBar.setVisible(false);
    }
}
