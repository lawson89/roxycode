package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import java.awt.Color;
import java.awt.Cursor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.SystemToolService;
import org.roxycode.app.util.Edt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Lazy
public final class SystemToolsPanel extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(SystemToolsPanel.class);
    static final String SUBTITLE_TEXT = "External CLI utilities and runtimes used by RoxyCode for search, version control, symbol indexing, and isolated builds.";
    static final String REQUIRED_BADGE_TEXT = "Required";
    static final String OPTIONAL_BADGE_TEXT = "Optional";
    static final String COPY_INSTALL_PREFIX = "Copy Install: ";
    static final int COPY_FEEDBACK_DELAY_MS = 1500;
    private static final String GROWX_WMIN_0 = "growx, wmin 0";

    private final transient SystemToolService toolService;
    private final JLabel titleLabel;
    private final JLabel subtitleLabel;
    final JButton refreshBtn;
    final JProgressBar progressBar;
    private final JPanel cardsContainer;
    private final JScrollPane scrollPane;
    final List<ToolCard> toolCards = new ArrayList<>();
    transient volatile CompletableFuture<List<SystemTool>> initialToolsFuture = CompletableFuture
            .completedFuture(List.of());

    public SystemToolsPanel(SystemToolService toolService) {
        this.toolService = toolService;
        setLayout(new MigLayout("fill, insets 20, hidemode 3", "[grow, fill]", "[]10[]10[grow, fill]"));
        setOpaque(true);

        titleLabel = new JLabel("System Tools Status");
        subtitleLabel = new JLabel(SUBTITLE_TEXT);

        refreshBtn = new JButton("Refresh Status");
        refreshBtn.addActionListener(e -> refreshTools());

        add(UiComponents.createPageHeader(titleLabel, subtitleLabel, refreshBtn), "growx, wmin 0, wrap");

        progressBar = new JProgressBar();
        progressBar.setVisible(false);
        add(progressBar, "growx, wmin 0, wrap, hidemode 3");

        cardsContainer = UiComponents.createScrollableCardsPanel(
                new MigLayout("fillx, wrap 1, insets 4 2 12 6, gapy 12", "[grow, fill]", ""));
        scrollPane = UiComponents.createBorderlessScrollPane(cardsContainer);
        add(scrollPane, "grow, wmin 0");

        applyThemeStyling();
        loadInitialTools();
    }

    private void loadInitialTools() {
        if (toolService == null) {
            return;
        }
        CompletableFuture<List<SystemTool>> future = toolService.detectTools();
        if (future == null) {
            return;
        }
        this.initialToolsFuture = future;
        if (future.isDone() && !future.isCompletedExceptionally()) {
            List<SystemTool> immediate = future.join();
            Edt.run(() -> populateCards(immediate));
            return;
        }
        runAsyncToolLoad(future);
    }

    public CompletableFuture<List<SystemTool>> refreshTools() {
        if (toolService == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return runAsyncToolLoad(Objects.requireNonNullElseGet(toolService.refreshTools(), toolService::detectTools));
    }

    private CompletableFuture<List<SystemTool>> runAsyncToolLoad(CompletableFuture<List<SystemTool>> future) {
        Edt.run(() -> {
            refreshBtn.setEnabled(false);
            progressBar.setIndeterminate(true);
            progressBar.setVisible(true);
        });
        CompletableFuture<List<SystemTool>> effectiveFuture = Objects.requireNonNullElseGet(future,
                () -> CompletableFuture.completedFuture(List.of()));
        return effectiveFuture.whenCompleteAsync((tools, ex) -> {
            if (future != null && ex == null && tools != null) {
                populateCards(tools);
            }
            progressBar.setIndeterminate(false);
            progressBar.setVisible(false);
            refreshBtn.setEnabled(true);
        }, Edt.EXECUTOR);
    }

    @EventListener
    public void onSystemToolsUpdated(SystemToolsUpdatedEvent event) {
        if (event != null && event.tools() != null) {
            Edt.run(() -> populateCards(event.tools()));
        }
    }

    private void populateCards(List<SystemTool> tools) {
        cardsContainer.removeAll();
        toolCards.clear();
        if (tools != null) {
            List<SystemTool> sortedTools = new ArrayList<>();
            for (SystemTool tool : tools) {
                if (tool != null) {
                    sortedTools.add(tool);
                }
            }
            sortedTools.sort((a, b) -> Boolean.compare(b.required(), a.required()));
            for (SystemTool tool : sortedTools) {
                ToolCard card = new ToolCard(tool, this::openWebsite);
                toolCards.add(card);
                cardsContainer.add(card, GROWX_WMIN_0);
            }
        }
        cardsContainer.revalidate();
        cardsContainer.repaint();
    }

    void openWebsite(String websiteUrl) {
        UiComponents.openUrlOrCopyToClipboard(websiteUrl);
    }

    static Codicons resolveToolIcon(String toolName) {
        if (toolName == null) {
            return Codicons.TOOLS;
        }
        return switch (toolName) {
            case SystemTool.TOOL_RIPGREP -> Codicons.SEARCH;
            case SystemTool.TOOL_GIT -> Codicons.SOURCE_CONTROL;
            case SystemTool.TOOL_CTAGS -> Codicons.SYMBOL_CLASS;
            case SystemTool.TOOL_DOCKER -> Codicons.SERVER_ENVIRONMENT;
            case SystemTool.TOOL_BUBBLEWRAP, SystemTool.TOOL_SEATBELT -> Codicons.SHIELD;
            default -> Codicons.TOOLS;
        };
    }

    static String formatWebsiteDisplay(String websiteUrl) {
        if (StringUtils.isBlank(websiteUrl)) {
            return "";
        }
        String display = StringUtils.removeStart(StringUtils.removeStart(websiteUrl.trim(), "https://"), "http://");
        return StringUtils.removeEnd(StringUtils.removeStart(display, "www."), "/");
    }

    static String resolveInstallCommand(String toolName) {
        return resolveInstallCommand(toolName, SystemUtils.OS_NAME);
    }

    static String resolveInstallCommand(String toolName, String osName) {
        if (StringUtils.isBlank(toolName)) {
            return "";
        }
        String normalizedOs = StringUtils.defaultString(osName).toLowerCase(Locale.ROOT);
        boolean isMac = normalizedOs.contains("mac") || normalizedOs.contains("darwin");
        boolean isWindows = !isMac && normalizedOs.contains("win");
        if (isWindows) {
            return switch (toolName) {
                case SystemTool.TOOL_RIPGREP -> "winget install BurntSushi.ripgrep.MSVC";
                case SystemTool.TOOL_CTAGS -> "winget install UniversalCtags.Ctags";
                case SystemTool.TOOL_GIT -> "winget install Git.Git";
                case SystemTool.TOOL_DOCKER -> "winget install Docker.DockerDesktop";
                default -> "";
            };
        }
        if (isMac) {
            return switch (toolName) {
                case SystemTool.TOOL_RIPGREP -> "brew install ripgrep";
                case SystemTool.TOOL_CTAGS -> "brew install universal-ctags";
                case SystemTool.TOOL_GIT -> "brew install git";
                case SystemTool.TOOL_DOCKER -> "brew install --cask docker";
                default -> "";
            };
        }
        return switch (toolName) {
            case SystemTool.TOOL_RIPGREP -> "sudo apt install ripgrep";
            case SystemTool.TOOL_CTAGS -> "sudo apt install universal-ctags";
            case SystemTool.TOOL_GIT -> "sudo apt install git";
            case SystemTool.TOOL_DOCKER -> "sudo apt install docker.io";
            case SystemTool.TOOL_BUBBLEWRAP -> "sudo apt install bubblewrap";
            default -> "";
        };
    }

    private void applyThemeStyling() {
        Color fg = SwingHtmlUtils.getUiColor(new Color(0x33, 0x33, 0x33), "Label.foreground");
        refreshBtn.setIcon(FontIcon.of(Codicons.REFRESH, 14, fg));
        toolCards.forEach(ToolCard::applyCardTheme);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        if (subtitleLabel != null) {
            applyThemeStyling();
        }
    }

    static final class ToolCard extends JPanel {
        private static final long serialVersionUID = 1L;

        private final transient SystemTool tool;
        final JLabel nameLabel;
        final JLabel requirementBadge;
        final JLabel statusBadge;
        final JTextArea descriptionArea;
        final JLabel versionBadge;
        final JLabel licenseBadge;
        final JButton copyInstallButton;
        final JButton websiteLinkButton;
        private Timer copyFeedbackTimer;

        ToolCard(SystemTool tool, Consumer<String> websiteOpener) {
            this.tool = tool;
            setLayout(new MigLayout("fillx, wrap 1, insets 14 16 14 16, gapy 8", "[grow, fill]", ""));
            UiComponents.styleCardPanel(this);

            JPanel headerRow = new JPanel(new MigLayout("fillx, insets 0, gapx 8", "[][0:0, grow][right]", "[center]"));
            headerRow.setOpaque(false);

            nameLabel = new JLabel(tool.name());
            nameLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +2");
            nameLabel.setIconTextGap(8);
            headerRow.add(nameLabel, "left, wmin 0");

            String badgeText;
            if (tool.required()) {
                badgeText = REQUIRED_BADGE_TEXT;
            } else {
                badgeText = OPTIONAL_BADGE_TEXT;
            }
            requirementBadge = new JLabel(badgeText);
            headerRow.add(requirementBadge, "left");

            statusBadge = new JLabel(tool.status());
            statusBadge.setHorizontalTextPosition(SwingConstants.RIGHT);
            statusBadge.setIconTextGap(6);
            headerRow.add(statusBadge, "right");
            add(headerRow, GROWX_WMIN_0);

            descriptionArea = UiComponents.createReadOnlyTextArea();
            descriptionArea.setText(tool.description());
            add(descriptionArea, GROWX_WMIN_0);

            JPanel footerRow = new JPanel(
                    new MigLayout("fillx, insets 2 0 0 0, gapx 10", "[]10[]push[][right]", "[center]"));
            footerRow.setOpaque(false);

            versionBadge = new JLabel(StringUtils.defaultIfBlank(tool.version(), "Unknown"));
            versionBadge.setIconTextGap(6);
            footerRow.add(versionBadge, "wmin 0");

            boolean hasLicense = StringUtils.isNotBlank(tool.license());
            String licenseText;
            if (hasLicense) {
                licenseText = "License: " + tool.license();
            } else {
                licenseText = "";
            }
            licenseBadge = new JLabel(licenseText);
            licenseBadge.setIconTextGap(5);
            licenseBadge.setVisible(hasLicense);
            footerRow.add(licenseBadge, "wmin 0, hidemode 3");

            String installCmd = resolveInstallCommand(tool.name());
            boolean showInstallCmd = !SystemToolService.STATUS_DETECTED.equalsIgnoreCase(tool.status())
                    && StringUtils.isNotBlank(installCmd);
            String copyBtnText;
            if (showInstallCmd) {
                copyBtnText = COPY_INSTALL_PREFIX + installCmd;
            } else {
                copyBtnText = "";
            }
            copyInstallButton = new JButton(copyBtnText);
            copyInstallButton.setVisible(showInstallCmd);
            copyInstallButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            copyInstallButton.setFocusable(false);
            copyInstallButton.setIconTextGap(6);
            if (showInstallCmd) {
                copyInstallButton.setToolTipText("Copy terminal install command: " + installCmd);
                copyInstallButton.addActionListener(e -> {
                    copyFeedbackTimer = UiComponents.copyWithFeedback(copyInstallButton, copyFeedbackTimer, installCmd,
                            copyBtnText, COPY_FEEDBACK_DELAY_MS);
                });
            }
            footerRow.add(copyInstallButton, "right, hidemode 3");

            String websiteUrl = tool.websiteUrl();
            boolean hasWebsite = StringUtils.isNotBlank(websiteUrl);
            websiteLinkButton = new JButton(formatWebsiteDisplay(websiteUrl));
            websiteLinkButton.setVisible(hasWebsite);
            websiteLinkButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            websiteLinkButton.setFocusable(false);
            websiteLinkButton.setHorizontalTextPosition(SwingConstants.LEFT);
            websiteLinkButton.setIconTextGap(5);
            if (hasWebsite) {
                websiteLinkButton.setToolTipText(websiteUrl);
                websiteLinkButton.addActionListener(e -> websiteOpener.accept(websiteUrl));
            }
            footerRow.add(websiteLinkButton, "right, hidemode 3");

            add(footerRow, GROWX_WMIN_0);
            applyCardTheme();
        }

        void applyCardTheme() {
            Color accentColor = SwingHtmlUtils.getAccentColor();
            Color mutedColor = SwingHtmlUtils.getUiColor(new Color(0x6c, 0x75, 0x7d), "Label.disabledForeground",
                    "Component.infoForeground");
            Color fgColor = SwingHtmlUtils.getUiColor(new Color(0x21, 0x25, 0x29), "Label.foreground");

            String borderHex = UiComponents.getCardBorderHex();
            UiComponents.styleCardPanel(this);

            nameLabel.setForeground(fgColor);
            nameLabel.setIcon(FontIcon.of(resolveToolIcon(tool.name()), 18, accentColor));

            applyStatusBadgeStyle();

            String pillBg = UiComponents.getPillBackgroundDirective();
            requirementBadge.putClientProperty(FlatClientProperties.STYLE,
                    "font: -1; arc: 999; border: 2,8,2,8," + borderHex + ",1,999; background: " + pillBg);
            requirementBadge.setForeground(mutedColor);

            versionBadge.putClientProperty(FlatClientProperties.STYLE,
                    "font: $monospaced.font -1; arc: 8; border: 3,8,3,8," + borderHex + ",1,8; background: " + pillBg);
            versionBadge.setForeground(fgColor);
            versionBadge.setIcon(FontIcon.of(Codicons.VERSIONS, 13, mutedColor));

            licenseBadge.putClientProperty(FlatClientProperties.STYLE,
                    "font: -1; arc: 8; border: 3,8,3,8; background: " + pillBg);
            licenseBadge.setForeground(mutedColor);
            if (licenseBadge.isVisible()) {
                licenseBadge.setIcon(FontIcon.of(Codicons.LAW, 13, mutedColor));
            }

            String accentHex = SwingHtmlUtils.toHex(accentColor);
            copyInstallButton.putClientProperty(FlatClientProperties.STYLE,
                    "font: $monospaced.font -1; arc: 8; margin: 2,8,2,8; borderColor: " + borderHex + "; background: "
                            + pillBg + "; hoverBackground: fade(" + accentHex + ", 14%); pressedBackground: fade("
                            + accentHex + ", 22%)");
            copyInstallButton.setForeground(fgColor);
            if (copyInstallButton.isVisible()) {
                copyInstallButton.setIcon(FontIcon.of(Codicons.TERMINAL, 13, accentColor));
            }

            websiteLinkButton.putClientProperty(FlatClientProperties.STYLE,
                    "font: -1; arc: 8; margin: 2,8,2,8; borderWidth: 0; background: null; hoverBackground: fade("
                            + accentHex + ", 14%); pressedBackground: fade(" + accentHex + ", 22%)");
            websiteLinkButton.setForeground(accentColor);
            if (websiteLinkButton.isVisible()) {
                websiteLinkButton.setIcon(FontIcon.of(Codicons.LINK_EXTERNAL, 13, accentColor));
            }
        }

        private void applyStatusBadgeStyle() {
            String status = tool.status();
            Color statusColor;
            Codicons statusIcon;
            if (SystemToolService.STATUS_DETECTED.equals(status)) {
                statusColor = SwingHtmlUtils.getUiColor(new Color(0x28, 0xa7, 0x45), "Actions.Green");
                statusIcon = Codicons.CHECK;
            } else if (SystemToolService.STATUS_ERROR.equals(status)) {
                statusColor = SwingHtmlUtils.getWarningColor();
                statusIcon = Codicons.WARNING;
            } else {
                statusColor = SwingHtmlUtils.getUiColor(new Color(0xdc, 0x35, 0x45), "Actions.Red");
                statusIcon = Codicons.ERROR;
            }
            String hex = SwingHtmlUtils.toHex(statusColor);
            statusBadge.setForeground(statusColor);
            statusBadge.setIcon(FontIcon.of(statusIcon, 14, statusColor));
            statusBadge.putClientProperty(FlatClientProperties.STYLE,
                    "font: bold -1; arc: 999; border: 3,10,3,10," + hex + ",1,999; background: fade(" + hex + ", 14%)");
        }

        @Override
        public void updateUI() {
            super.updateUI();
            if (nameLabel != null) {
                applyCardTheme();
            }
        }
    }
}
