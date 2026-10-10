package org.roxycode.app.ui;

import net.miginfocom.swing.MigLayout;
import org.roxycode.app.ai.services.cache.GeminiCacheService;
import org.roxycode.app.ai.services.cache.ProjectCacheMetaService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.model.ProjectCacheMeta;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.service.ProjectAnalysisService;
import org.roxycode.app.ai.WorkflowService;
import org.roxycode.app.ai.JexlServiceRegistry;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.WorkflowPhase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

public class CodebaseCachePanel extends JPanel {
    private static final Logger log = LoggerFactory.getLogger(CodebaseCachePanel.class);
    private final ProjectCacheMetaService metaService;
    private final RepoMapPackerService packerService;
    private final org.roxycode.app.service.PromptService promptService;
    private final GeminiCacheService geminiCacheService;
    private final WorkflowService workflowService;
    private final JexlServiceRegistry jexlServiceRegistry;
    private final GitService gitService;
    private final ProjectAnalysisService projectAnalysisService;

    private final JLabel statusLabel = new JLabel("Status: Unknown");
    private final JProgressBar progressBar = new JProgressBar(0, 100);
    private final JButton packButton = new JButton("Update Implicit Cache");
    
    private final JTextArea repoMapArea = new JTextArea();
    private final JTextArea stableContextArea = new JTextArea();
    private final JTextArea sessionContextArea = new JTextArea();
    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public CodebaseCachePanel(ProjectService projectService, SettingsService settingsService,
                                RepoMapPackerService packerService, ProjectCacheMetaService metaService,
                                GeminiCacheService geminiCacheService, org.roxycode.app.service.PromptService promptService,
                                WorkflowService workflowService, JexlServiceRegistry jexlServiceRegistry,
                                GitService gitService, ProjectAnalysisService projectAnalysisService) {
        this.packerService = packerService;
        this.metaService = metaService;
        this.promptService = promptService;
        this.geminiCacheService = geminiCacheService;
        this.workflowService = workflowService;
        this.jexlServiceRegistry = jexlServiceRegistry;
        this.gitService = gitService;
        this.projectAnalysisService = projectAnalysisService;

        initComponents();
        refreshStatus();
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

        repoMapArea.setEditable(false);
        repoMapArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        stableContextArea.setEditable(false);
        stableContextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        sessionContextArea.setEditable(false);
        sessionContextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        
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
        repoMapArea.setBackground(UIManager.getColor("TextArea.background"));
        repoMapArea.setForeground(UIManager.getColor("TextArea.foreground"));
        repoMapArea.setCaretColor(UIManager.getColor("TextArea.caretForeground"));
        stableContextArea.setBackground(UIManager.getColor("TextArea.background"));
        stableContextArea.setForeground(UIManager.getColor("TextArea.foreground"));
        stableContextArea.setCaretColor(UIManager.getColor("TextArea.caretForeground"));
        sessionContextArea.setBackground(UIManager.getColor("TextArea.background"));
        sessionContextArea.setForeground(UIManager.getColor("TextArea.foreground"));
        sessionContextArea.setCaretColor(UIManager.getColor("TextArea.caretForeground"));
    }

    private void refreshStatus() {
        Optional<ProjectCacheMeta> meta = metaService.loadMeta();
        Optional<String> packed = metaService.loadRepoCache();

        if (meta.isPresent()) {
            ProjectCacheMeta m = meta.get();
            long stableTokens = geminiCacheService.estimateTokens(getStableContextString());
            String status = String.format("Status: Last updated on %s | Stable: %,d | Repo Map: %,d | Total: %,d", 
                m.lastUpdated().format(dateFormatter), stableTokens, m.estimatedTokens(), stableTokens + m.estimatedTokens());
            statusLabel.setText(status);
            
            repoMapArea.setText(packed.orElse("Content not found on disk."));
            updateStableContext();
            updateSessionContext();
            repoMapArea.setCaretPosition(0);
        } else {
            statusLabel.setText("Status: No implicit cache found.");
            repoMapArea.setText("Repo Map will appear here after generation.");
            
            if (packed.isPresent()) {
                 statusLabel.setText("Status: Repo Map available locally.");
                 repoMapArea.setText(packed.get());
                 repoMapArea.setCaretPosition(0);
            }
        }
    }

    private String getStableContextString() {
        StringBuilder sb = new StringBuilder();
        sb.append("# Stable Context Artifacts\n\n");
        sb.append("## Core Workflow Prompt\n").append(promptService.loadCoreWorkflowPrompt()).append("\n\n");
        sb.append("## JEXL Context (jexl.md)\n").append(promptService.loadJexlContext()).append("\n\n");
        sb.append("## Additional Prompts\n").append(promptService.loadAllPrompts()).append("\n\n");
        sb.append("## Additional Docs\n").append(promptService.loadAllDocs());
        String projectContext = promptService.loadProjectContext();
        if (projectContext != null && !projectContext.isBlank()) {
            sb.append("\n\n## Project Context Docs").append(projectContext);
        }
        return sb.toString();
    }

    private void updateStableContext() {
        stableContextArea.setText(getStableContextString());
        stableContextArea.setCaretPosition(0);
    }

    private String getSessionContextString() {
        StringBuilder sb = new StringBuilder();
        WorkflowPhase currentPhase = workflowService.getCurrentPhase();
        

        sb.append("## SESSION CONTEXT\n");
        sb.append("DOMINANT LANGUAGE: ").append(projectAnalysisService.getDominantLanguage()).append("\n");
        sb.append("CURRENT PHASE: ").append(currentPhase.name()).append("\n");
        
        sb.append("You have access to the following JEXL tools:\n")
          .append(jexlServiceRegistry.getDocumentation(currentPhase));
        
        String gitStatus = gitService.getStatus();
        if (gitStatus != null && !gitStatus.isEmpty() && !gitStatus.startsWith("Error")) {
            sb.append("\n\n## GIT STATUS\n").append(gitStatus);
        }
        
        return sb.toString();
    }

    private void updateSessionContext() {
        sessionContextArea.setText(getSessionContextString());
        sessionContextArea.setCaretPosition(0);
    }

    private void runPack() {
        packButton.setEnabled(false);
        progressBar.setVisible(true);
        progressBar.setIndeterminate(true);
        statusLabel.setText("Generating Repo Map...");

        SwingWorker<org.roxycode.app.ai.services.EditorResult, Void> worker = new SwingWorker<>() {
            @Override
            protected org.roxycode.app.ai.services.EditorResult doInBackground() throws Exception {
                return packerService.generateRepoMap();
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
                    progressBar.setVisible(false);
                }
            }
        };
        worker.execute();
    }
}
