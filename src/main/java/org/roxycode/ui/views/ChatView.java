package org.roxycode.ui.views;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.fife.ui.autocomplete.AutoCompletion;
import org.fife.ui.autocomplete.BasicCompletion;
import org.fife.ui.autocomplete.DefaultCompletionProvider;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.roxycode.core.GenAIService;
import org.roxycode.core.SettingsService;
import org.roxycode.core.SlashCommandService;
import org.roxycode.core.beans.ProjectCacheMeta;
import org.roxycode.core.cache.ProjectCacheMetaService;
import org.roxycode.ui.MarkdownPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.InterruptedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringTokenizer;

@Singleton
public class ChatView extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(ChatView.class);
    private static final DataFlavor URI_LIST_FLAVOR = createUriListFlavor();

    private static DataFlavor createUriListFlavor() {
        try {
            return new DataFlavor("text/uri-list;class=java.lang.String");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private final GenAIService genAIService;

    private final SettingsService settingsService;

    private final SlashCommandService slashCommandService;

    private final org.roxycode.ui.ThemeService themeService;

    private final ProjectCacheMetaService projectCacheMetaService;

    private final MarkdownPane chatArea = new MarkdownPane();

    private final RSyntaxTextArea inputField = new RSyntaxTextArea(3, 20);

    private final List<File> attachedFiles = new ArrayList<>();

    @Outlet
    private JComponent viewChat;

    @Outlet
    private JPanel inputContainer;

    @Outlet
    private JButton sendButton;

    @Outlet
    private JButton stopButton;

    @Outlet
    private JScrollPane chatScrollPane;

    @Outlet
    private JButton attachButton;

    @Outlet
    private JPanel attachmentsContainer;

    @Outlet
    private JButton clearAttachmentsButton;

    @Outlet
    private JLabel msgCountLabel;

    @Outlet
    private JLabel inTokenLabel;

    @Outlet
    private JLabel outTokenLabel;

    @Outlet
    private JLabel currentModelLabel;

    @Outlet
    private JLabel cacheStatusLabel;

    @Outlet
    private JLabel cacheIdLabel;

    @Outlet
    private JLabel cacheExpiryLabel;

    @Inject
    public ChatView(GenAIService genAIService, SettingsService settingsService, org.roxycode.ui.ThemeService themeService, SlashCommandService slashCommandService, ProjectCacheMetaService projectCacheMetaService) {
        this.projectCacheMetaService = projectCacheMetaService;
        this.genAIService = genAIService;
        this.settingsService = settingsService;
        this.themeService = themeService;
        this.slashCommandService = slashCommandService;
        setLayout(new BorderLayout());
    }

    @PostConstruct
    public void init() {
        add(UILoader.load(this, "ChatView.xml"));
        if (chatScrollPane != null) {
            chatScrollPane.setViewportView(chatArea);
        }
        setupInputField();
        themeService.registerPane(chatArea);
        setupDragAndDrop();
        initIcons();
        updateCacheStatus();
        initListeners();
    }

    private void setupInputField() {
        inputField.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        inputField.setLineWrap(true);
        inputField.setWrapStyleWord(true);
        inputField.setAnimateBracketMatching(false);
        inputField.setHighlightCurrentLine(false);
        // Hide the gutter
        JScrollPane sp = new JScrollPane(inputField);
        sp.setBorder(BorderFactory.createEmptyBorder());
        inputContainer.add(sp);
        initAutocomplete();
    }

    private void initAutocomplete() {
        DefaultCompletionProvider provider = new DefaultCompletionProvider() {

            @Override
            protected boolean isValidChar(char ch) {
                return Character.isLetterOrDigit(ch) || ch == '/';
            }

            @Override
            public boolean isAutoActivateOkay(javax.swing.text.JTextComponent tc) {
                String text = getAlreadyEnteredText(tc);
                return text != null && text.startsWith("/");
            }
        };
        for (var info : slashCommandService.getAvailableCommands()) {
            provider.addCompletion(new BasicCompletion(provider, info.command(), info.description()));
        }
        AutoCompletion ac = new AutoCompletion(provider);
        ac.setAutoActivationEnabled(true);
        ac.setAutoActivationDelay(100);
        ac.install(inputField);
        // Explicitly trigger autocomplete when '/' is typed at the start of the input
        inputField.addKeyListener(new java.awt.event.KeyAdapter() {

            @Override
            public void keyTyped(java.awt.event.KeyEvent e) {
                if (e.getKeyChar() == '/') {
                    SwingUtilities.invokeLater(() -> {
                        if (inputField.getCaretPosition() == 1 && inputField.getText().startsWith("/")) {
                            ac.doCompletion();
                        }
                    });
                }
            }
        });
    }

    private void initIcons() {
        updateCacheStatus();
        if (currentModelLabel != null) {
            currentModelLabel.setIcon(org.kordamp.ikonli.swing.FontIcon.of(org.kordamp.ikonli.materialdesign2.MaterialDesignR.ROBOT_HAPPY_OUTLINE, 12));
            currentModelLabel.setIconTextGap(6);
            currentModelLabel.setText(settingsService.getGeminiModel());
        }
        if (msgCountLabel != null) {
            msgCountLabel.setIcon(org.kordamp.ikonli.swing.FontIcon.of(org.kordamp.ikonli.materialdesign2.MaterialDesignM.MESSAGE_TEXT_OUTLINE, 12));
            msgCountLabel.setIconTextGap(4);
        }
        if (inTokenLabel != null) {
            inTokenLabel.setIcon(org.kordamp.ikonli.swing.FontIcon.of(org.kordamp.ikonli.materialdesign2.MaterialDesignA.ARROW_UP_BOLD_OUTLINE, 12));
            inTokenLabel.setIconTextGap(4);
        }
        if (outTokenLabel != null) {
            outTokenLabel.setIcon(org.kordamp.ikonli.swing.FontIcon.of(org.kordamp.ikonli.materialdesign2.MaterialDesignA.ARROW_DOWN_BOLD_OUTLINE, 12));
            outTokenLabel.setIconTextGap(4);
        }
        if (stopButton != null) {
            stopButton.setIcon(org.kordamp.ikonli.swing.FontIcon.of(org.kordamp.ikonli.materialdesign2.MaterialDesignP.PAUSE_CIRCLE_OUTLINE, 16));
        }
        if (attachButton != null) {
            attachButton.setIcon(org.kordamp.ikonli.swing.FontIcon.of(org.kordamp.ikonli.materialdesign2.MaterialDesignA.ATTACHMENT, 16));
        }
        if (clearAttachmentsButton != null) {
            clearAttachmentsButton.setIcon(org.kordamp.ikonli.swing.FontIcon.of(org.kordamp.ikonli.materialdesign2.MaterialDesignC.CLOSE_CIRCLE_OUTLINE, 16));
        }
        if (sendButton != null) {
            sendButton.setIcon(org.kordamp.ikonli.swing.FontIcon.of(org.kordamp.ikonli.materialdesign2.MaterialDesignS.SEND_OUTLINE, 16));
        }
    }

    private void initListeners() {
        if (sendButton != null)
            sendButton.addActionListener(this::onSend);
        if (stopButton != null)
            stopButton.addActionListener(e -> genAIService.stopChat());
        if (attachButton != null)
            attachButton.addActionListener(this::onAttach);
        if (clearAttachmentsButton != null)
            clearAttachmentsButton.addActionListener(e -> {
                attachedFiles.clear();
                updateAttachmentsLabel();
            });
        inputField.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "send-message");
        inputField.getActionMap().put("send-message", new AbstractAction() {

            @Override
            public void actionPerformed(ActionEvent e) {
                onSend(e);
            }
        });
        inputField.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break");
    }

    private void onSend(ActionEvent e) {
        String prompt = inputField.getText().trim();
        if (prompt.isEmpty())
            return;
        if (slashCommandService.isCommand(prompt)) {
            handleSlashCommand(prompt);
            return;
        }
        inputField.setText("");
        List<File> currentAttachments = new ArrayList<>(attachedFiles);
        attachedFiles.clear();
        updateAttachmentsLabel();
        chatArea.appendUserMarkdown(prompt);
        if (!currentAttachments.isEmpty())
            chatArea.appendMarkdown(" *(Attached: " + currentAttachments.size() + " files)*");
        setInputEnabled(false);
        new Thread(() -> {
            try {
                String projectRoot = settingsService.getCurrentProject();
                String response = genAIService.chat(prompt, projectRoot, currentAttachments, (status) -> SwingUtilities.invokeLater(() -> {
                    if (status.startsWith("Thinking"))
                        chatArea.appendStatus(status);
                    else
                        chatArea.appendToolLog(status);
                    updateChatStats();
                }));
                SwingUtilities.invokeLater(() -> {
                    chatArea.appendRoxyMarkdown(response);
                    updateChatStats();
                });
            } catch (Exception ex) {
                log.error("Chat error", ex);
                SwingUtilities.invokeLater(() -> {
                    if (isTimeout(ex))
                        chatArea.appendMarkdown("⏱️ **Request Timeout**");
                    else
                        chatArea.appendMarkdown("❌ **Error:** " + ex.getMessage());
                });
            } finally {
                SwingUtilities.invokeLater(() -> {
                    setInputEnabled(true);
                    updateChatStats();
                });
            }
        }).start();
    }

    private void handleSlashCommand(String prompt) {
        inputField.setText("");
        SlashCommandService.CommandResult result = slashCommandService.execute(prompt);
        chatArea.appendMarkdown("> " + prompt);
        if (result.success()) {
            chatArea.appendMarkdown("\n" + result.message());
        } else {
            chatArea.appendMarkdown("\n❌ " + result.message());
        }
        if (result.action() == SlashCommandService.CommandAction.CLEAR) {
            chatArea.setMarkdown("");
        }
        updateChatStats();
    }

    private void setInputEnabled(boolean enabled) {
        if (sendButton != null)
            sendButton.setEnabled(enabled);
        if (stopButton != null)
            stopButton.setEnabled(!enabled);
        if (inputField != null) {
            inputField.setEnabled(enabled);
            if (enabled)
                inputField.requestFocusInWindow();
        }
        if (attachButton != null)
            attachButton.setEnabled(enabled);
        if (clearAttachmentsButton != null)
            clearAttachmentsButton.setEnabled(enabled);
    }

    private boolean isTimeout(Throwable t) {
        while (t != null) {
            if (t instanceof InterruptedIOException)
                return true;
            if (t.getMessage() != null && t.getMessage().toLowerCase().contains("timeout"))
                return true;
            t = t.getCause();
        }
        return false;
    }

    private void onAttach(ActionEvent e) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setMultiSelectionEnabled(true);
        fileChooser.setCurrentDirectory(new File(settingsService.getCurrentProject()));
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File f : fileChooser.getSelectedFiles()) {
                if (!attachedFiles.contains(f))
                    attachedFiles.add(f);
            }
            updateAttachmentsLabel();
        }
    }

    private void updateAttachmentsLabel() {
        log.info("Updating attachments label with {} files", attachedFiles.size());
        if (attachmentsContainer == null)
            return;
        attachmentsContainer.removeAll();
        if (attachedFiles.isEmpty()) {
            attachmentsContainer.add(new JLabel("None"));
        } else {
            for (File file : attachedFiles) {
                JLabel label = new JLabel(file.getName());
                label.setIcon(org.kordamp.ikonli.swing.FontIcon.of(org.kordamp.ikonli.materialdesign2.MaterialDesignF.FILE_OUTLINE, 16));
                attachmentsContainer.add(label);
            }
        }
        attachmentsContainer.revalidate();
        attachmentsContainer.repaint();
    }

    public void updateChatStats() {
        int msgCount = genAIService.getHistory().size();
        int in = genAIService.getInTokens();
        int out = genAIService.getOutTokens();
        if (msgCountLabel != null) {
            msgCountLabel.setText(String.format("%d Messages", msgCount));
        }
        if (inTokenLabel != null) {
            inTokenLabel.setText(String.format("%d In", in));
        }
        if (outTokenLabel != null) {
            outTokenLabel.setText(String.format("%d Out", out));
        }
        if (currentModelLabel != null) {
            currentModelLabel.setText(settingsService.getGeminiModel());
        }
    }

    public void updateCacheStatus() {
        SwingUtilities.invokeLater(() -> {
            boolean enabled = settingsService.isCacheEnabled();
            if (cacheStatusLabel != null) {
                cacheStatusLabel.setText("Cache: " + (enabled ? "Enabled" : "Disabled"));
                cacheStatusLabel.setForeground(enabled ? new Color(100, 255, 100) : new Color(255, 100, 100));
            }
            if (cacheIdLabel != null) {
                if (enabled) {
                    Optional<ProjectCacheMeta> meta = projectCacheMetaService.getProjectCacheMeta();
                    if (meta.isPresent()) {
                        String fullId = meta.get().geminiCacheId();
                        String displayId = (fullId != null && fullId.length() > 5) ? "..." + fullId.substring(fullId.length() - 5) : (fullId != null ? fullId : "None");
                        cacheIdLabel.setText("ID: " + displayId);
                        if (cacheExpiryLabel != null) {
                            long seconds = projectCacheMetaService.getSecondsUntilExpiration(meta.get());
                            long minutes = seconds / 60;
                            long remainingSeconds = seconds % 60;
                            cacheExpiryLabel.setText(String.format("(Expires in %d:%02d)", minutes, remainingSeconds));
                        }
                    } else {
                        cacheIdLabel.setText("ID: None");
                        if (cacheExpiryLabel != null)
                            cacheExpiryLabel.setText("");
                    }
                } else {
                    cacheIdLabel.setText("");
                    if (cacheExpiryLabel != null)
                        cacheExpiryLabel.setText("");
                }
            }
        });
    }

    public void appendSystemMessage(String msg) {
        chatArea.appendMarkdown("*System: " + msg + "*");
    }

    public MarkdownPane getChatArea() {
        return chatArea;
    }

    private void setupDragAndDrop() {
        if (chatArea != null) {
            chatArea.setDragEnabled(true);
            chatArea.setTransferHandler(new FileTransferHandler(chatArea.getTransferHandler()));
        }
        if (inputField != null) {
            inputField.setDragEnabled(true);
            inputField.setTransferHandler(new FileTransferHandler(inputField.getTransferHandler()));
        }
    }

    List<File> getAttachedFiles() {
        return java.util.Collections.unmodifiableList(attachedFiles);
    }

    class FileTransferHandler extends TransferHandler {
        private final TransferHandler delegate;

        public FileTransferHandler(TransferHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            boolean supported = support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                                (URI_LIST_FLAVOR != null && support.isDataFlavorSupported(URI_LIST_FLAVOR));

            if (supported) {
                support.setDropAction(COPY);
                return true;
            }

            return (delegate != null && delegate.canImport(support));
        }

        @Override
        public boolean importData(TransferSupport support) {
            Transferable transferable = support.getTransferable();
            boolean handled = false;
            log.info(transferable.toString());
            try {
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    log.info("Importing javaFileListFlavor");
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) transferable.getTransferData(DataFlavor.javaFileListFlavor);
                    for (File file : files) {
                        if (!attachedFiles.contains(file)) {
                            attachedFiles.add(file);
                        }
                    }
                    handled = true;
                } else if (URI_LIST_FLAVOR != null && support.isDataFlavorSupported(URI_LIST_FLAVOR)) {
                    log.info("Importing URI_LIST_FLAVOR");
                    String uriList = (String) transferable.getTransferData(URI_LIST_FLAVOR);
                    StringTokenizer st = new StringTokenizer(uriList, "\r\n");
                    while (st.hasMoreTokens()) {
                        String uriStr = st.nextToken().trim();
                        if (uriStr.startsWith("#") || uriStr.isEmpty()) continue;

                        File file = null;
                        try {
                            URI uri = new URI(uriStr);
                            file = new File(uri);
                        } catch (Exception e) {
                            // Fallback for malformed URIs or plain paths
                            try {
                                String path = uriStr;
                                if (path.startsWith("file://")) {
                                    path = path.substring(7);
                                } else if (path.startsWith("file:")) {
                                    path = path.substring(5);
                                }
                                file = new File(path);
                            } catch (Exception ex) {
                                log.warn("Failed to parse URI fallback: {}", uriStr);
                            }
                        }

                        if (file != null && file.exists()) {
                            if (!attachedFiles.contains(file)) {
                                attachedFiles.add(file);
                            }
                        }
                    }
                    handled = true;
                }

                if (handled) {
                    updateAttachmentsLabel();
                    return true;
                }else{
                    log.info("No supported data flavor found for import");
                }
            } catch (Exception e) {
                log.error("Failed to import dropped files", e);
            }

            return delegate != null && delegate.importData(support);
        }
    }
}
