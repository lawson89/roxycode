package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.events.UserInputRequestEvent;
import org.roxycode.app.events.UserInputRequestEvent.PromptType;

@SuppressWarnings("PMD.NullAssignment")
final class ChatPromptBanner extends JPanel {
    private static final long serialVersionUID = 1L;

    static final String NEW_TOPIC_PROMPT_MESSAGE = "Save the current topic to Past Topics before starting a new one?";
    static final String TOPIC_NAME_LABEL_TEXT = "Topic Name:";
    static final String SAVE_TOPIC_BUTTON_TEXT = "Yes, Save Topic";
    static final String DISCARD_TOPIC_BUTTON_TEXT = "No, Discard";
    static final String RESET_SESSION_PROMPT_MESSAGE = "Reset session? This cancels active tasks and clears chat history.";
    static final String CONFIRM_RESET_BUTTON_TEXT = "Yes, Reset";
    static final String CANCEL_BUTTON_TEXT = "Cancel";

    private final List<Consumer<Boolean>> visibilityListeners = new CopyOnWriteArrayList<>();
    private transient Runnable activePromptCancelAction;

    ChatPromptBanner() {
        super(new MigLayout("insets 0, fillx, wrap 1, gapy 8", "[grow, fill]"));
        SwingHtmlUtils.applyTintedBannerStyle(this, SwingHtmlUtils.getAccentColor(), 10, 16, 20);
        KeyStroke escapeKey = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        ActionListener escapeListener = e -> cancelActivePrompt();
        registerKeyboardAction(escapeListener, escapeKey, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        registerKeyboardAction(escapeListener, escapeKey, JComponent.WHEN_IN_FOCUSED_WINDOW);
        setVisible(false);
    }

    static JLabel createPromptHeaderLabel(String text, Codicons icon) {
        Color accentColor = SwingHtmlUtils.getUiColor(Color.GRAY, "Component.accentColor", "Label.foreground");
        JLabel label = new JLabel(text, FontIcon.of(icon, 16, accentColor), SwingConstants.LEFT);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    void applyThemeStyling() {
        Color accent = SwingHtmlUtils.getAccentColor();
        SwingHtmlUtils.applyTintedBannerStyle(this, accent, 10, 16, 20);
        for (Component child : getComponents()) {
            if (child instanceof JLabel label && label.getIcon() instanceof FontIcon fontIcon) {
                label.setIcon(FontIcon.of(fontIcon.getIkon(), fontIcon.getIconSize(), accent));
            }
        }
        UiComponents.refreshActionButtonIcons(this);
    }

    private void resetWithHeader(String text, Codicons icon) {
        removeAll();
        add(createPromptHeaderLabel(text, icon));
    }

    private static JPanel createPromptActionsRow(JComponent... components) {
        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actionsPanel.setOpaque(false);
        for (JComponent component : components) {
            actionsPanel.add(component);
        }
        return actionsPanel;
    }

    private void showPrompt() {
        setVisible(true);
        revalidate();
        repaint();
        for (Consumer<Boolean> listener : visibilityListeners) {
            listener.accept(Boolean.TRUE);
        }
    }

    void showUserInputPrompt(UserInputRequestEvent event, Consumer<Object> onResponse) {
        resetWithHeader(event.question(), Codicons.QUESTION);

        if (StringUtils.isNotBlank(event.details())) {
            JLabel detailsLabel = new JLabel(event.details());
            detailsLabel.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");
            add(detailsLabel);
        }

        PromptType promptType = event.promptType();
        if (promptType == PromptType.CONFIRM) {
            activePromptCancelAction = () -> onResponse.accept(Boolean.FALSE);
            JButton yesButton = UiComponents.createActionButton("Yes", Codicons.CHECK, null, false,
                    UiComponents.ACCENT_BUTTON_STYLE, e -> onResponse.accept(Boolean.TRUE));
            JButton noButton = UiComponents.createActionButton("No", Codicons.CLOSE, null, false, null,
                    e -> onResponse.accept(Boolean.FALSE));
            add(createPromptActionsRow(yesButton, noButton));
        } else if (promptType == PromptType.SINGLE_SELECT) {
            JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
            optionsPanel.setOpaque(false);
            JComboBox<String> comboBox = new JComboBox<>(event.options().toArray(new String[0]));
            optionsPanel.add(comboBox);
            JButton submitButton = UiComponents.createActionButton("Submit", Codicons.CHECK, null, false,
                    UiComponents.ACCENT_BUTTON_STYLE, e -> onResponse.accept((String) comboBox.getSelectedItem()));
            optionsPanel.add(submitButton);
            add(optionsPanel);
        } else if (promptType == PromptType.MULTI_SELECT) {
            JPanel checksPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
            checksPanel.setOpaque(false);
            List<JCheckBox> checkBoxes = new ArrayList<>(event.options().size());
            for (String option : event.options()) {
                JCheckBox checkBox = new JCheckBox(option);
                checkBox.setOpaque(false);
                checkBoxes.add(checkBox);
                checksPanel.add(checkBox);
            }
            JButton submitButton = UiComponents.createActionButton("Submit", Codicons.CHECK, null, false,
                    UiComponents.ACCENT_BUTTON_STYLE, e -> {
                        List<String> selected = new ArrayList<>();
                        for (JCheckBox cb : checkBoxes) {
                            if (cb.isSelected()) {
                                selected.add(cb.getText());
                            }
                        }
                        onResponse.accept(List.copyOf(selected));
                    });
            checksPanel.add(submitButton);
            add(checksPanel);
        } else if (promptType == PromptType.TEXT) {
            JPanel textPanel = new JPanel(new MigLayout("insets 0, fillx, gap 8", "[grow, fill][]"));
            textPanel.setOpaque(false);
            JTextField textField = new JTextField(StringUtils.defaultString(event.defaultValue()));
            ActionListener submitAction = e -> onResponse.accept(textField.getText());
            textField.addActionListener(submitAction);
            JButton submitButton = UiComponents.createActionButton("Submit", Codicons.CHECK, null, false,
                    UiComponents.ACCENT_BUTTON_STYLE, submitAction);
            textPanel.add(textField, "growx");
            textPanel.add(submitButton);
            add(textPanel);
            SwingUtilities.invokeLater(textField::requestFocusInWindow);
        }

        showPrompt();
    }

    boolean cancelActivePrompt() {
        if (!isVisible() || activePromptCancelAction == null) {
            return false;
        }
        Runnable action = activePromptCancelAction;
        activePromptCancelAction = null;
        action.run();
        return true;
    }

    void hidePrompt() {
        activePromptCancelAction = null;
        boolean wasVisible = isVisible();
        removeAll();
        setVisible(false);
        revalidate();
        repaint();
        if (wasVisible) {
            for (Consumer<Boolean> listener : visibilityListeners) {
                listener.accept(Boolean.FALSE);
            }
        }
    }

    void showResetSessionPrompt(Runnable onConfirm) {
        resetWithHeader(RESET_SESSION_PROMPT_MESSAGE, Codicons.QUESTION);
        activePromptCancelAction = this::hidePrompt;

        JButton confirmButton = UiComponents.createActionButton(CONFIRM_RESET_BUTTON_TEXT, Codicons.TRASH, null, false,
                UiComponents.SUBTLE_RED_BUTTON_STYLE, e -> {
                    hidePrompt();
                    if (onConfirm != null) {
                        onConfirm.run();
                    }
                });
        JButton cancelButton = UiComponents.createActionButton(CANCEL_BUTTON_TEXT, Codicons.CLOSE, null, false, null,
                e -> hidePrompt());
        add(createPromptActionsRow(confirmButton, cancelButton));

        showPrompt();
    }

    void showNewTopicPrompt(String defaultTopicName, Consumer<ChatPanel.NewTopicChoice> onChoice) {
        resetWithHeader(NEW_TOPIC_PROMPT_MESSAGE, Codicons.COMMENT_DISCUSSION);
        Runnable cancelTopicAction = () -> {
            hidePrompt();
            if (onChoice != null) {
                onChoice.accept(new ChatPanel.NewTopicChoice(false, false, ""));
            }
        };
        activePromptCancelAction = cancelTopicAction;

        JPanel fieldPanel = new JPanel(new MigLayout("insets 0, fillx, gap 8", "[][grow, fill]"));
        fieldPanel.setOpaque(false);
        JLabel nameLabel = new JLabel(TOPIC_NAME_LABEL_TEXT);
        JTextField topicNameField = new JTextField(StringUtils.defaultString(defaultTopicName), 30);
        topicNameField.selectAll();
        ActionListener saveAction = e -> {
            String enteredName = topicNameField.getText();
            hidePrompt();
            if (onChoice != null) {
                onChoice.accept(new ChatPanel.NewTopicChoice(true, true, enteredName));
            }
        };
        topicNameField.addActionListener(saveAction);
        fieldPanel.add(nameLabel);
        fieldPanel.add(topicNameField, "growx");
        add(fieldPanel);

        JButton saveButton = UiComponents.createActionButton(SAVE_TOPIC_BUTTON_TEXT, Codicons.CHECK, null, false,
                UiComponents.ACCENT_BUTTON_STYLE, saveAction);
        JButton discardButton = UiComponents.createActionButton(DISCARD_TOPIC_BUTTON_TEXT, Codicons.TRASH, null, false,
                UiComponents.SUBTLE_RED_BUTTON_STYLE, e -> {
                    hidePrompt();
                    if (onChoice != null) {
                        onChoice.accept(new ChatPanel.NewTopicChoice(true, false, ""));
                    }
                });
        JButton cancelButton = UiComponents.createActionButton(CANCEL_BUTTON_TEXT, Codicons.CLOSE, null, false, null,
                e -> cancelTopicAction.run());
        add(createPromptActionsRow(saveButton, discardButton, cancelButton));

        showPrompt();
        SwingUtilities.invokeLater(() -> {
            topicNameField.selectAll();
            topicNameField.requestFocusInWindow();
        });
    }

    void addVisibilityListener(Consumer<Boolean> listener) {
        if (listener != null) {
            visibilityListeners.add(listener);
        }
    }
}
