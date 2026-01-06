package org.roxycode.ui;

import com.formdev.flatlaf.FlatLaf;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.*;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.core.*;
import org.roxycode.core.utils.UIUtils;
import org.roxycode.core.tools.service.GitService;
import org.roxycode.ui.views.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.nio.file.FileSystems;
import java.nio.file.Path;

@Singleton
public class MainFrame extends JFrame implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(MainFrame.class);

    // Dependencies
    private final GitService gitService;

    private final GenAIService genAIService;

    private final SettingsService settingsService;

    private final RoxyProjectService roxyProjectService;

    private final Sandbox sandbox;

    private final ThemeService themeService;

    private final NotificationService notificationService;

    // -- VIEWS --
    @Inject
    private ChatView chatView;

    @Inject
    private FilesView filesView;

    @Inject
    private UsageView usageView;

    @Inject
    private SettingsView settingsView;

    @Inject
    private SystemPromptView systemPromptView;

    @Inject
    private MessageHistoryView messageHistoryView;

    @Inject
    private LogsView logsView;

    @Inject
    private CodebaseCacheView codebaseCacheView;

    @Inject
    private GeminiOnlineCachesView geminiOnlineCachesView;

    private Path currentProjectRoot;

    private Timer notificationTimer;

    // --- OUTLETS ---
    @Outlet
    private JComponent mainContentStack;

    @Outlet
    private JLabel gitBranchLabel;

    @Outlet
    private JLabel roxyModeLabel;

    @Outlet
    private JLabel currentModelLabel;

    @Outlet
    private JLabel projectNameLabel;

    @Outlet
    private JLabel currentProjectLabel;

    @Outlet
    private JLabel icon;

    // Notification Bar Outlets
    @Outlet
    private JPanel notificationBar;

    @Outlet
    private JLabel notificationIcon;

    @Outlet
    private JLabel notificationLabel;

    @Outlet
    private JButton closeNotificationButton;

    // Navigation Outlets
    @Outlet
    private JToggleButton navChatButton;

    @Outlet
    private JToggleButton navFilesButton;

    @Outlet
    private JToggleButton navUsageButton;

    @Outlet
    private JToggleButton navSettingsButton;

    @Outlet
    private JToggleButton navSystemPromptButton;

    @Outlet
    private JToggleButton navMessageHistoryButton;

    @Outlet
    private JToggleButton navSummaryQueueButton;

    @Outlet
    private JToggleButton navLogsButton;

    @Outlet
    private JToggleButton navCodebaseCacheButton;

    @Outlet
    private JToggleButton navGeminiCachesButton;

    @Outlet
    private JButton openFolderButton;

    @Inject
    public MainFrame(GitService gitService, GenAIService genAIService, SettingsService settingsService, RoxyProjectService roxyProjectService, Sandbox sandbox, ThemeService themeService, NotificationService notificationService) {
        this.gitService = gitService;
        this.genAIService = genAIService;
        this.settingsService = settingsService;
        this.roxyProjectService = roxyProjectService;
        this.sandbox = sandbox;
        this.themeService = themeService;
        this.notificationService = notificationService;
        setTitle("RoxyCode");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
    }

    @Override
    public void run() {
        themeService.applyTheme(settingsService.getTheme(), this, getAllPanes());
        // 1. Load the Main Shell (Menu, Header, Nav, Empty Stack)
        setContentPane(UILoader.load(this, "MainFrame.xml"));
        // 2. Load individual Views and add them to the stack
        if (mainContentStack != null) {
            mainContentStack.add(chatView);
            mainContentStack.add(filesView);
            mainContentStack.add(usageView);
            mainContentStack.add(settingsView);
            mainContentStack.add(systemPromptView);
            mainContentStack.add(messageHistoryView);
            mainContentStack.add(logsView);
            mainContentStack.add(codebaseCacheView);
            mainContentStack.add(geminiOnlineCachesView);
        }
        // 3. Initialize UI Components
        initIcons();
        // Initialize Notification Bar
        if (notificationBar != null) {
            notificationBar.setVisible(false);
            if (closeNotificationButton != null) {
                closeNotificationButton.setIcon(FontIcon.of(MaterialDesignC.CLOSE, 16));
                closeNotificationButton.addActionListener(e -> notificationBar.setVisible(false));
            }
            notificationService.setListener(this::showNotification);
        }
        // Initialize logic
        currentProjectRoot = FileSystems.getDefault().getPath("").toAbsolutePath();
        if (settingsService.getCurrentProject() == null) {
            settingsService.setCurrentProject(currentProjectRoot.toString());
        }
        sandbox.setRoot(currentProjectRoot.toString());
        roxyProjectService.ensureProjectStructure();
        initGitInfo();
        initListeners();
        updateProjectLabel();
        updateRoxyMode();
        performRescan();
        // Set initial view
        showView("CHAT");
        setSize(1200, 800);
        setLocationRelativeTo(null);
        setVisible(true);
        // Capture initial center for fallback as suggested by user
        GraphicsConfiguration gc = getGraphicsConfiguration();
        if (gc != null) {
            Rectangle bounds = gc.getBounds();
            UIUtils.setInitialScreenCenter(new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2));
        }
    }

    private void showNotification(NotificationService.NotificationRequest request) {
        SwingUtilities.invokeLater(() -> {
            if (notificationBar == null || notificationLabel == null || notificationIcon == null)
                return;
            notificationLabel.setText(request.message());
            Color bgColor;
            Color fgColor;
            Ikon iconCode;
            boolean isDark = FlatLaf.isLafDark();
            switch(request.type()) {
                case SUCCESS:
                    bgColor = isDark ? new Color(30, 50, 30) : new Color(230, 250, 230);
                    fgColor = isDark ? new Color(100, 255, 100) : new Color(0, 120, 0);
                    iconCode = MaterialDesignI.INFORMATION_OUTLINE;
                    break;
                case ERROR:
                    bgColor = isDark ? new Color(60, 30, 30) : new Color(255, 230, 230);
                    fgColor = isDark ? new Color(255, 100, 100) : new Color(150, 0, 0);
                    iconCode = MaterialDesignA.ALERT_CIRCLE_OUTLINE;
                    break;
                case WARNING:
                    bgColor = isDark ? new Color(50, 50, 30) : new Color(255, 250, 230);
                    fgColor = isDark ? new Color(255, 200, 0) : new Color(150, 100, 0);
                    iconCode = MaterialDesignA.ALERT_OUTLINE;
                    break;
                case INFO:
                default:
                    bgColor = isDark ? new Color(30, 40, 60) : new Color(235, 245, 255);
                    fgColor = isDark ? new Color(100, 200, 255) : new Color(0, 80, 200);
                    iconCode = MaterialDesignI.INFORMATION_OUTLINE;
                    break;
            }
            notificationBar.setBackground(bgColor);
            notificationLabel.setForeground(fgColor);
            notificationIcon.setIcon(FontIcon.of(iconCode, 18, fgColor));
            notificationBar.setVisible(true);
            if (notificationTimer != null && notificationTimer.isRunning()) {
                notificationTimer.stop();
            }
            notificationTimer = new Timer(5000, e -> notificationBar.setVisible(false));
            notificationTimer.setRepeats(false);
            notificationTimer.start();
        });
    }

    private void initIcons() {
        java.net.URL iconUrl = getClass().getResource("roxy_logo_transparent.png");
        if (iconUrl != null) {
            ImageIcon roxyIcon = new ImageIcon(iconUrl);
            Image img = roxyIcon.getImage();
            setIconImage(img);
            if (icon != null) {
                Image newImg = img.getScaledInstance(64, 64, Image.SCALE_SMOOTH);
                icon.setIcon(new ImageIcon(newImg));
            }
        } else {
            if (icon != null)
                icon.setIcon(FontIcon.of(MaterialDesignC.CHAT_OUTLINE, 64));
        }
        if (currentModelLabel != null) {
            currentModelLabel.setIcon(FontIcon.of(MaterialDesignR.ROBOT_HAPPY_OUTLINE, 16));
            currentModelLabel.setIconTextGap(6);
            currentModelLabel.setText(settingsService.getGeminiModel());
        }
        // Set Icons for Nav Buttons
        if (navChatButton != null)
            navChatButton.setIcon(FontIcon.of(MaterialDesignC.CHAT_OUTLINE, 16));
        if (navFilesButton != null)
            navFilesButton.setIcon(FontIcon.of(MaterialDesignF.FILE_TREE_OUTLINE, 16));
        if (navUsageButton != null)
            navUsageButton.setIcon(FontIcon.of(MaterialDesignC.CHART_LINE, 16));
        if (navSettingsButton != null)
            navSettingsButton.setIcon(FontIcon.of(MaterialDesignC.COG_OUTLINE, 16));
        if (navSystemPromptButton != null)
            navSystemPromptButton.setIcon(FontIcon.of(MaterialDesignR.ROBOT_OUTLINE, 16));
        if (navMessageHistoryButton != null)
            navMessageHistoryButton.setIcon(FontIcon.of(MaterialDesignM.MESSAGE_TEXT_CLOCK_OUTLINE, 16));
        if (navSummaryQueueButton != null)
            navSummaryQueueButton.setIcon(FontIcon.of(MaterialDesignA.ARCHIVE_OUTLINE, 16));
        if (navLogsButton != null)
            navLogsButton.setIcon(FontIcon.of(MaterialDesignF.FILE_DOCUMENT_OUTLINE, 16));
        if (navCodebaseCacheButton != null)
            navCodebaseCacheButton.setIcon(FontIcon.of(MaterialDesignD.DATABASE_OUTLINE, 16));
        if (navGeminiCachesButton != null)
            navGeminiCachesButton.setIcon(FontIcon.of(MaterialDesignC.CLOUD_OUTLINE, 16));
        if (openFolderButton != null)
            openFolderButton.setIcon(FontIcon.of(MaterialDesignF.FOLDER_OPEN_OUTLINE, 16));
    }

    private void updateProjectLabel() {
        if (currentProjectLabel != null) {
            currentProjectLabel.setText(currentProjectRoot.toString());
        }
    }

    private void initGitInfo() {
        if (gitBranchLabel != null) {
            String branch = gitService.getCurrentBranch(currentProjectRoot);
            gitBranchLabel.setText(branch != null ? branch : "No Git Repo");
        }
        if (projectNameLabel != null) {
            projectNameLabel.setText(currentProjectRoot.getFileName().toString());
        }
    }

    public void updateRoxyMode() {
        if (roxyModeLabel != null) {
            roxyModeLabel.setText(genAIService.getRoxyMode().toString());
        }
    }

    private void initListeners() {
        if (navChatButton != null)
            navChatButton.addActionListener(e -> showView("CHAT"));
        if (navFilesButton != null)
            navFilesButton.addActionListener(e -> showView("FILES"));
        if (navUsageButton != null)
            navUsageButton.addActionListener(e -> showView("USAGE"));
        if (navSettingsButton != null)
            navSettingsButton.addActionListener(e -> showView("SETTINGS"));
        if (navSystemPromptButton != null)
            navSystemPromptButton.addActionListener(e -> showView("SYSTEM_PROMPT"));
        if (navMessageHistoryButton != null)
            navMessageHistoryButton.addActionListener(e -> showView("MESSAGE_HISTORY"));
        if (navSummaryQueueButton != null)
            navSummaryQueueButton.addActionListener(e -> showView("SUMMARY_QUEUE"));
        if (navLogsButton != null)
            navLogsButton.addActionListener(e -> showView("LOGS"));
        if (navCodebaseCacheButton != null)
            navCodebaseCacheButton.addActionListener(e -> showView("CODEBASE_CACHE"));
        if (navGeminiCachesButton != null)
            navGeminiCachesButton.addActionListener(e -> showView("GEMINI_CACHES"));
        if (openFolderButton != null)
            openFolderButton.addActionListener(this::onOpenFolder);
    }

    private void showView(String viewName) {
        chatView.setVisible(false);
        filesView.setVisible(false);
        usageView.setVisible(false);
        settingsView.setVisible(false);
        systemPromptView.setVisible(false);
        messageHistoryView.setVisible(false);
        logsView.setVisible(false);
        codebaseCacheView.setVisible(false);
        geminiOnlineCachesView.setVisible(false);
        switch(viewName) {
            case "CHAT":
                chatView.setVisible(true);
                break;
            case "FILES":
                filesView.refresh();
                filesView.setVisible(true);
                break;
            case "USAGE":
                usageView.refresh();
                usageView.setVisible(true);
                break;
            case "SETTINGS":
                settingsView.setVisible(true);
                break;
            case "SYSTEM_PROMPT":
                systemPromptView.refresh();
                systemPromptView.setVisible(true);
                break;
            case "MESSAGE_HISTORY":
                messageHistoryView.refresh();
                messageHistoryView.setVisible(true);
                break;
            case "LOGS":
                logsView.refresh();
                logsView.setVisible(true);
                break;
            case "CODEBASE_CACHE":
                codebaseCacheView.refresh();
                codebaseCacheView.setVisible(true);
                break;
            case "GEMINI_CACHES":
                geminiOnlineCachesView.refresh();
                geminiOnlineCachesView.setVisible(true);
                break;
        }
    }

    private void onOpenFolder(ActionEvent e) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setCurrentDirectory(currentProjectRoot.toFile());
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            currentProjectRoot = fileChooser.getSelectedFile().toPath().toAbsolutePath();
            sandbox.setRoot(currentProjectRoot.toString());
            settingsService.setCurrentProject(currentProjectRoot.toString());
            roxyProjectService.ensureProjectStructure();
            genAIService.clearHistory();
            updateProjectLabel();
            initGitInfo();
            performRescan();
            chatView.appendSystemMessage("Switched project to " + currentProjectRoot.toString());
        }
    }

    private void performRescan() {
        log.info("Triggering Knowledge Rescan for {}", currentProjectRoot);
        new Thread(() -> {
            genAIService.refreshKnowledge(currentProjectRoot.toString());
            SwingUtilities.invokeLater(() -> chatView.appendSystemMessage("Knowledge base reloaded."));
        }).start();
    }

    public void updateShellStatus() {
        if (currentModelLabel != null) {
            currentModelLabel.setText(settingsService.getGeminiModel());
        }
        updateRoxyMode();
        initGitInfo();
    }

    public JTextPane[] getAllPanes() {
        return new JTextPane[] { chatView.getChatArea(), systemPromptView.getSystemPromptArea(), messageHistoryView.getMessageHistoryArea(), codebaseCacheView.getCacheContentArea() };
    }
}
