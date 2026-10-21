package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.DefaultEditorKit;
import javax.swing.undo.UndoManager;
import net.miginfocom.swing.MigLayout;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.events.AgentTurnCompleteEvent;
import org.roxycode.app.events.AgentTurnEvent;
import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.service.AiService;
import org.roxycode.app.service.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

/** Panel for the chat interface, including output display and user input area. */
public class ChatPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(ChatPanel.class);

    private final transient ChatModel model;
    private final transient ChatController controller;

    private final MarkdownPane outputArea;
    private final JTextArea inputArea;
    private final JButton sendButton;
    private final JButton stopButton;
    private final JButton attachImageButton;
    private final JButton approveButton;
    private final JButton rejectButton;
    private final JButton resetSessionButton;
    private final JPanel inlineActionPanel;
    private final JLabel transitionLabel;
    private final JLabel turnLabel;
    private final JScrollPane outputScrollPane;
    private final JPanel attachmentPanel;
    private final List<Consumer<Boolean>> loadingListeners = new ArrayList<>();

    public ChatPanel(
            AiService aiService,
            PlanService planService,
            ApplicationEventPublisher eventPublisher,
            ChatMemory chatMemory,
            String sessionPrefix,
            WorkflowPhase lockedPhase,
            boolean isTaskMode,
            SettingsService settingsService) {
        this(
                aiService,
                planService,
                eventPublisher,
                chatMemory,
                sessionPrefix,
                lockedPhase,
                isTaskMode,
                settingsService,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    public ChatPanel(
            AiService aiService,
            PlanService planService,
            ApplicationEventPublisher eventPublisher,
            ChatMemory chatMemory,
            String sessionPrefix,
            WorkflowPhase lockedPhase,
            boolean isTaskMode,
            SettingsService settingsService,
            Executor turnExecutor) {
        this.model = new ChatModel(sessionPrefix, lockedPhase, isTaskMode);
        this.controller =
                new ChatController(
                        this.model,
                        this,
                        aiService,
                        planService,
                        eventPublisher,
                        chatMemory,
                        settingsService,
                        turnExecutor);

        attachmentPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        attachmentPanel.setOpaque(false);
        attachmentPanel.setVisible(false);

        setLayout(new BorderLayout());

        // Output section
        outputArea = new MarkdownPane();

        // Create inline action panel (Decision Bar)
        inlineActionPanel = new JPanel(new MigLayout("insets 10 20 10 20, gap 15", "", "center"));
        inlineActionPanel.putClientProperty(
                FlatClientProperties.STYLE,
                "background: darken($TextPane.background, 3%); "
                        + "border: 1,0,0,0,$Component.borderColor");
        inlineActionPanel.setVisible(false);

        Color accentColor = UIManager.getColor("Component.accentColor");
        if (accentColor == null) accentColor = UIManager.getColor("Label.foreground");
        if (accentColor == null) accentColor = Color.GRAY;

        transitionLabel =
                new JLabel(
                        "Workflow Transition",
                        FontIcon.of(Codicons.ROCKET, 16, accentColor),
                        SwingConstants.LEFT);
        transitionLabel.setFont(transitionLabel.getFont().deriveFont(Font.BOLD));
        transitionLabel.putClientProperty(
                FlatClientProperties.STYLE, "foreground: $Component.accentColor");

        approveButton = new JButton("Approve", FontIcon.of(Codicons.CHECK, 16));
        approveButton.setEnabled(false);
        approveButton.putClientProperty(
                FlatClientProperties.STYLE, "background: $Actions.Green; foreground: #ffffff");
        approveButton.addActionListener(e -> controller.approvePhase());

        rejectButton = new JButton("Reject", FontIcon.of(Codicons.CLOSE, 16));
        rejectButton.setEnabled(false);
        rejectButton.putClientProperty(
                FlatClientProperties.STYLE, "background: $Actions.Red; foreground: #ffffff");
        rejectButton.addActionListener(e -> controller.rejectPhase());

        inlineActionPanel.add(transitionLabel);
        inlineActionPanel.add(Box.createHorizontalStrut(10));
        inlineActionPanel.add(approveButton);
        inlineActionPanel.add(rejectButton);

        outputScrollPane = new JScrollPane(outputArea);
        outputScrollPane.setHorizontalScrollBarPolicy(
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        updateOutputScrollPaneBorder();

        // Autoscroll to bottom
        outputArea.addComponentListener(
                new java.awt.event.ComponentAdapter() {
                    @Override
                    public void componentResized(java.awt.event.ComponentEvent e) {
                        SwingUtilities.invokeLater(
                                () -> {
                                    JScrollBar vertical = outputScrollPane.getVerticalScrollBar();
                                    vertical.setValue(vertical.getMaximum());
                                });
                    }
                });

        Component topComponent;
        if (isTaskMode) {
            JPanel taskOutputPanel = new JPanel(new BorderLayout());
            taskOutputPanel.add(outputScrollPane, BorderLayout.CENTER);
            taskOutputPanel.add(inlineActionPanel, BorderLayout.SOUTH);
            topComponent = taskOutputPanel;
        } else {
            topComponent = outputScrollPane;
        }

        // Input section
        JPanel inputSection =
                new JPanel(
                        new MigLayout("fill, insets 6 10 10 10", "[grow, fill][]", "[grow, fill]"));
        inputSection.setMinimumSize(new Dimension(0, 85));

        inputArea = new JTextArea();
        inputArea.setRows(3);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setMargin(new Insets(8, 10, 8, 10));
        inputArea.putClientProperty(
                FlatClientProperties.PLACEHOLDER_TEXT,
                "Ask Roxy... (Enter to send, Shift+Enter for new line, Ctrl+V to paste image)");
        JScrollPane inputScrollPane = new JScrollPane(inputArea);

        JPanel inputAreaContainer = new JPanel(new BorderLayout());
        inputAreaContainer.setOpaque(false);
        inputAreaContainer.add(attachmentPanel, BorderLayout.NORTH);
        inputAreaContainer.add(inputScrollPane, BorderLayout.CENTER);

        sendButton = new JButton("Send", FontIcon.of(Codicons.CHEVRON_RIGHT, 16));
        sendButton.setMargin(new Insets(6, 14, 6, 14));
        sendButton.putClientProperty(
                FlatClientProperties.STYLE,
                "arc: 8; background: $Component.accentColor; foreground: $List.selectionForeground");

        stopButton = new JButton("Stop", FontIcon.of(Codicons.DEBUG_STOP, 16));
        stopButton.setEnabled(false);
        stopButton.setMargin(new Insets(6, 14, 6, 14));
        stopButton.putClientProperty(FlatClientProperties.STYLE, "arc: 8");
        stopButton.addActionListener(e -> controller.stopTurn());

        attachImageButton = new JButton("Attach Image", FontIcon.of(Codicons.FILE_MEDIA, 16));
        attachImageButton.setToolTipText("Attach Image");
        attachImageButton.setMargin(new Insets(4, 10, 4, 10));
        attachImageButton.putClientProperty(
                FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        attachImageButton.putClientProperty(
                FlatClientProperties.STYLE, "arc: 8; foreground: $Label.disabledForeground");
        attachImageButton.addActionListener(e -> chooseImageAttachment());

        resetSessionButton = new JButton("Reset Session", FontIcon.of(Codicons.REFRESH, 16));
        resetSessionButton.setMargin(new Insets(4, 10, 4, 10));
        resetSessionButton.putClientProperty(
                FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        resetSessionButton.putClientProperty(
                FlatClientProperties.STYLE, "arc: 8; foreground: $Label.disabledForeground");
        resetSessionButton.addActionListener(e -> controller.resetSession());

        turnLabel = new JLabel();
        turnLabel.putClientProperty(
                FlatClientProperties.STYLE,
                "font: $small.font; foreground: $Label.disabledForeground");
        updateTurnLabel();

        JPanel buttonPanel = new JPanel(new MigLayout("insets 0, gap 5", "[]", "[]"));
        buttonPanel.setOpaque(false);
        buttonPanel.add(sendButton, "growx, wrap");
        buttonPanel.add(stopButton, "growx, wrap");
        buttonPanel.add(attachImageButton, "growx, wrap");
        buttonPanel.add(resetSessionButton, "growx, wrap");
        buttonPanel.add(turnLabel, "center");

        inputSection.add(inputAreaContainer, "grow");
        inputSection.add(buttonPanel, "aligny top");

        JSplitPane splitPane =
                new JSplitPane(JSplitPane.VERTICAL_SPLIT, topComponent, inputSection);
        splitPane.setResizeWeight(1.0);
        splitPane.setBorder(BorderFactory.createEmptyBorder());

        add(splitPane, BorderLayout.CENTER);

        sendButton.addActionListener(this::sendMessage);

        setupInputContextMenu();
        setupUndoRedo();
        setupKeyboardShortcuts();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (outputScrollPane != null) {
            updateOutputScrollPaneBorder();
        }
    }

    private void updateOutputScrollPaneBorder() {
        outputScrollPane.setBorder(
                BorderFactory.createMatteBorder(
                        0, 0, 1, 0, UIManager.getColor("Component.borderColor")));
    }

    @EventListener
    public void onAgentTurn(AgentTurnEvent event) {
        controller.onAgentTurn(event);
    }

    @EventListener
    public void onToolExecution(ToolExecutionEvent event) {
        controller.onToolExecution(event);
    }

    @EventListener
    public void onAgentTurnComplete(AgentTurnCompleteEvent event) {
        controller.onAgentTurnComplete(event);
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        controller.onSettingsChanged(event);
    }

    void updateTurnLabel() {
        turnLabel.setText(controller.formatCurrentTurnStatus());
    }

    JLabel getTurnLabel() {
        return turnLabel;
    }

    ChatModel getModel() {
        return model;
    }

    ChatController getController() {
        return controller;
    }

    void appendMessage(String role, String content) {
        outputArea.appendMessage(role, content);
    }

    void appendToolLog(String toolName, String output, boolean success, String error) {
        outputArea.appendToolLog(toolName, output, success, error);
    }

    void clearOutput() {
        outputArea.clear();
    }

    void clearInputText() {
        inputArea.setText("");
    }

    void clearAttachments() {
        attachmentPanel.removeAll();
        attachmentPanel.setVisible(false);
    }

    void showTransitionBanner(String path) {
        transitionLabel.setText(path);
        approveButton.setText("Approve");
        approveButton.setEnabled(true);
        rejectButton.setEnabled(true);
        inlineActionPanel.setVisible(true);
    }

    void hideTransitionBanner() {
        approveButton.setEnabled(false);
        rejectButton.setEnabled(false);
        inlineActionPanel.setVisible(false);
    }

    boolean confirmResetSession() {
        int result =
                JOptionPane.showConfirmDialog(
                        this,
                        "Reset session? This cancels active tasks and clears chat history.",
                        "Reset",
                        JOptionPane.YES_NO_OPTION);
        return result == JOptionPane.YES_OPTION;
    }

    private void sendMessage(ActionEvent e) {
        if (e != null) {
            log.debug("Send message triggered by: {}", e.getActionCommand());
        }
        controller.sendMessage(inputArea.getText());
    }

    private void chooseImageAttachment() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(
                new FileNameExtensionFilter(
                        "Image Files (png, jpg, jpeg, gif, bmp)",
                        "png",
                        "jpg",
                        "jpeg",
                        "gif",
                        "bmp"));
        int returnVal = chooser.showOpenDialog(this);
        if (returnVal == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            if (file != null) {
                try {
                    BufferedImage img = ImageIO.read(file);
                    if (img != null) {
                        addPendingImage(img);
                    }
                } catch (Exception ex) {
                    log.error("Failed to load attached image", ex);
                }
            }
        }
    }

    private void addPendingImage(Image img) {
        BufferedImage bimage = model.addPendingImage(img);

        Image thumbnail = bimage.getScaledInstance(64, 64, Image.SCALE_SMOOTH);
        JLabel thumbLabel = new JLabel(new ImageIcon(thumbnail));
        thumbLabel.setBorder(
                BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")));

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(thumbLabel, BorderLayout.CENTER);

        JButton removeBtn = new JButton(FontIcon.of(Codicons.CLOSE, 12));
        removeBtn.putClientProperty(
                FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        removeBtn.setMargin(new Insets(0, 0, 0, 0));
        removeBtn.addActionListener(
                ae -> {
                    model.removePendingImage(bimage);
                    attachmentPanel.remove(wrapper);
                    attachmentPanel.setVisible(model.hasPendingImages());
                    attachmentPanel.revalidate();
                    attachmentPanel.repaint();
                });
        wrapper.add(removeBtn, BorderLayout.NORTH);

        attachmentPanel.add(wrapper);
        attachmentPanel.setVisible(true);
        attachmentPanel.revalidate();
        attachmentPanel.repaint();
    }

    void setLoading(boolean loading) {
        model.setLoading(loading);
        if (loading) {
            stopButton.setEnabled(true);
            SwingUtilities.invokeLater(stopButton::requestFocusInWindow);
            sendButton.setEnabled(false);
            attachImageButton.setEnabled(false);
            inputArea.setEnabled(false);
            if (model.isTaskMode()) {
                approveButton.setEnabled(false);
                rejectButton.setEnabled(false);
            }
        } else {
            sendButton.setEnabled(true);
            attachImageButton.setEnabled(true);
            inputArea.setEnabled(true);
            if (model.isTaskMode()) {
                boolean hasPending = controller.hasPendingPhase();
                approveButton.setEnabled(hasPending);
                rejectButton.setEnabled(hasPending);
            }
            stopButton.setEnabled(false);
            SwingUtilities.invokeLater(inputArea::requestFocusInWindow);
        }
        sendButton.setText(loading ? "Sending..." : "Send");
        for (Consumer<Boolean> listener : loadingListeners) {
            listener.accept(loading);
        }
    }

    public void addLoadingListener(Consumer<Boolean> listener) {
        if (listener != null) {
            loadingListeners.add(listener);
        }
    }

    public MarkdownPane getOutputArea() {
        return outputArea;
    }

    public JTextArea getInputArea() {
        return inputArea;
    }

    public JButton getSendButton() {
        return sendButton;
    }

    public JButton getStopButton() {
        return stopButton;
    }

    public JButton getAttachImageButton() {
        return attachImageButton;
    }

    public void focusInput() {
        inputArea.requestFocusInWindow();
        inputArea.setCaretPosition(inputArea.getDocument().getLength());
    }

    private void setupUndoRedo() {
        UndoManager undoManager = new UndoManager();
        inputArea.getDocument().addUndoableEditListener(e -> undoManager.addEdit(e.getEdit()));

        InputMap inputMap = inputArea.getInputMap();
        ActionMap actionMap = inputArea.getActionMap();

        KeyStroke undoStroke =
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_Z, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        inputMap.put(undoStroke, "Undo");
        actionMap.put(
                "Undo",
                new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        if (undoManager.canUndo()) {
                            undoManager.undo();
                        }
                    }
                });

        KeyStroke redoStrokeY =
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_Y, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        KeyStroke redoStrokeZ =
                KeyStroke.getKeyStroke(
                        KeyEvent.VK_Z,
                        Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()
                                | java.awt.event.InputEvent.SHIFT_DOWN_MASK);
        inputMap.put(redoStrokeY, "Redo");
        inputMap.put(redoStrokeZ, "Redo");
        actionMap.put(
                "Redo",
                new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        if (undoManager.canRedo()) {
                            undoManager.redo();
                        }
                    }
                });
    }

    private void setupInputContextMenu() {
        inputArea.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        if (e.isPopupTrigger()) showPopup(e);
                    }

                    @Override
                    public void mouseReleased(MouseEvent e) {
                        if (e.isPopupTrigger()) showPopup(e);
                    }

                    private void showPopup(MouseEvent e) {
                        inputArea.requestFocusInWindow();
                        JPopupMenu menu = new JPopupMenu();
                        menu.add(new JMenuItem(new DefaultEditorKit.CutAction())).setText("Cut");
                        menu.add(new JMenuItem(new DefaultEditorKit.CopyAction())).setText("Copy");
                        menu.add(new JMenuItem(new DefaultEditorKit.PasteAction()))
                                .setText("Paste");
                        menu.addSeparator();
                        JMenuItem selectAll = new JMenuItem("Select All");
                        selectAll.addActionListener(ae -> inputArea.selectAll());
                        menu.add(selectAll);
                        menu.show(e.getComponent(), e.getX(), e.getY());
                    }
                });
    }

    private void setupKeyboardShortcuts() {
        InputMap im = inputArea.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = inputArea.getActionMap();

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send-message");
        am.put(
                "send-message",
                new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        sendMessage(e);
                    }
                });

        im.put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK),
                "insert-newline");
        am.put(
                "insert-newline",
                new AbstractAction() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        inputArea.insert("\n", inputArea.getCaretPosition());
                    }
                });

        Action defaultPaste = inputArea.getActionMap().get(DefaultEditorKit.pasteAction);
        inputArea
                .getActionMap()
                .put(
                        DefaultEditorKit.pasteAction,
                        new AbstractAction() {
                            @Override
                            public void actionPerformed(ActionEvent e) {
                                Clipboard clipboard =
                                        Toolkit.getDefaultToolkit().getSystemClipboard();
                                if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                                    try {
                                        Image img =
                                                (Image) clipboard.getData(DataFlavor.imageFlavor);
                                        addPendingImage(img);
                                    } catch (Exception ex) {
                                        log.error("Failed to paste image", ex);
                                    }
                                } else {
                                    defaultPaste.actionPerformed(e);
                                }
                            }
                        });
    }
}
