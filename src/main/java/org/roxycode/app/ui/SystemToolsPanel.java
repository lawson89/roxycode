package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatLaf;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
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
    private static final String MIG_COL_GROW_FILL = "[0:0, grow, fill]";
    private static final String MIG_GROWX_WMIN_ZERO = "growx, wmin 0";

    private final transient SystemToolService toolService;
    private final JLabel titleLabel;
    private final JLabel subtitleLabel;
    private final JButton refreshBtn;
    private final JProgressBar progressBar;
    private final JPanel cardsContainer;
    private final JScrollPane scrollPane;
    private final List<ToolCard> toolCards = new ArrayList<>();
    private transient Consumer<URI> browserLauncher = SystemToolsPanel::browseWithDesktop;
    private transient volatile CompletableFuture<List<SystemTool>> initialToolsFuture = CompletableFuture
            .completedFuture(List.of());

    public SystemToolsPanel(SystemToolService toolService) {
        this.toolService = toolService;
        setLayout(new MigLayout("fill, insets 20, hidemode 3", MIG_COL_GROW_FILL, "[]10[]10[grow, fill]"));
        setOpaque(true);

        titleLabel = new JLabel("System Tools Status");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 18f));
        subtitleLabel = new JLabel(SUBTITLE_TEXT);

        JPanel titleBlock = new JPanel(new MigLayout("insets 0, wrap 1, gapy 2", MIG_COL_GROW_FILL, "[]"));
        titleBlock.setOpaque(false);
        titleBlock.add(titleLabel, MIG_GROWX_WMIN_ZERO);
        titleBlock.add(subtitleLabel, MIG_GROWX_WMIN_ZERO);

        refreshBtn = new JButton("Refresh Status");
        refreshBtn.addActionListener(e -> refreshTools());

        JPanel header = new JPanel(new MigLayout("fillx, insets 0", "[0:0, grow, fill][]", "[center]"));
        header.setOpaque(false);
        header.add(titleBlock, MIG_GROWX_WMIN_ZERO);
        header.add(refreshBtn);
        add(header, "growx, wmin 0, wrap");

        progressBar = new JProgressBar();
        progressBar.setVisible(false);
        add(progressBar, "growx, wmin 0, wrap, hidemode 3");

        cardsContainer = new ScrollableCardsPanel();
        cardsContainer.setLayout(new MigLayout("fillx, wrap 1, insets 4 2 12 6, gapy 12", MIG_COL_GROW_FILL, ""));
        cardsContainer.setOpaque(true);

        scrollPane = new JScrollPane(cardsContainer);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setOpaque(true);
        scrollPane.getViewport().setOpaque(true);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        add(scrollPane, "grow, wmin 0");

        applyThemeStyling();
        loadInitialTools();
    }

    private void loadInitialTools() {
        if (toolService == null)
            return;
        CompletableFuture<List<SystemTool>> future = toolService.detectTools();
        if (future == null)
            return;
        this.initialToolsFuture = future;
        if (future.isDone() && !future.isCompletedExceptionally()) {
            List<SystemTool> immediate = future.join();
            Edt.run(() -> populateCards(immediate));
            return;
        }
        runAsyncToolLoad(future);
    }

    CompletableFuture<List<SystemTool>> getInitialToolsFuture() {
        return initialToolsFuture;
    }

    public CompletableFuture<List<SystemTool>> refreshTools() {
        if (toolService == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        CompletableFuture<List<SystemTool>> future = toolService.refreshTools();
        return runAsyncToolLoad(future != null ? future : toolService.detectTools());
    }

    private CompletableFuture<List<SystemTool>> runAsyncToolLoad(CompletableFuture<List<SystemTool>> future) {
        Edt.run(() -> {
            refreshBtn.setEnabled(false);
            progressBar.setIndeterminate(true);
            progressBar.setVisible(true);
        });
        CompletableFuture<List<SystemTool>> effectiveFuture = future != null
                ? future
                : CompletableFuture.completedFuture(List.of());
        return effectiveFuture.whenComplete((tools, ex) -> Edt.run(() -> {
            if (future != null && ex == null && tools != null) {
                populateCards(tools);
            }
            progressBar.setIndeterminate(false);
            progressBar.setVisible(false);
            refreshBtn.setEnabled(true);
        }));
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
            for (SystemTool tool : tools) {
                if (tool != null) {
                    ToolCard card = new ToolCard(tool, this::openWebsite);
                    toolCards.add(card);
                    cardsContainer.add(card, MIG_GROWX_WMIN_ZERO);
                }
            }
        }
        cardsContainer.revalidate();
        cardsContainer.repaint();
    }

    void openWebsite(String websiteUrl) {
        if (StringUtils.isBlank(websiteUrl))
            return;
        try {
            URI uri = URI.create(websiteUrl.trim());
            Consumer<URI> launcher = browserLauncher;
            if (launcher != null) {
                launcher.accept(uri);
                return;
            }
        } catch (Exception e) {
            log.debug("Browser launch failed for {}, falling back to clipboard: {}", websiteUrl, e.getMessage());
        }
        SwingHtmlUtils.copyToClipboard(websiteUrl.trim());
    }

    private static void browseWithDesktop(URI uri) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(uri);
                return;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to browse " + uri, e);
            }
        }
        throw new UnsupportedOperationException("Desktop browse action is not supported");
    }

    void setBrowserLauncher(Consumer<URI> browserLauncher) {
        this.browserLauncher = browserLauncher;
    }

    JButton getRefreshButton() {
        return refreshBtn;
    }

    JProgressBar getProgressBar() {
        return progressBar;
    }

    JPanel getCardsContainer() {
        return cardsContainer;
    }

    List<ToolCard> getToolCards() {
        return Collections.unmodifiableList(toolCards);
    }

    static Codicons resolveToolIcon(String toolName) {
        if (toolName == null)
            return Codicons.TOOLS;
        return switch (toolName) {
            case SystemTool.TOOL_RIPGREP -> Codicons.SEARCH;
            case SystemTool.TOOL_GIT -> Codicons.SOURCE_CONTROL;
            case SystemTool.TOOL_CTAGS -> Codicons.SYMBOL_CLASS;
            case SystemTool.TOOL_DOCKER -> Codicons.SERVER_ENVIRONMENT;
            default -> Codicons.TOOLS;
        };
    }

    static String formatWebsiteDisplay(String websiteUrl) {
        if (StringUtils.isBlank(websiteUrl))
            return "";
        String display = StringUtils.removeStart(StringUtils.removeStart(websiteUrl.trim(), "https://"), "http://");
        return StringUtils.removeEnd(StringUtils.removeStart(display, "www."), "/");
    }

    private void applyThemeStyling() {
        Color muted = SwingHtmlUtils.getUiColor(new Color(0x66, 0x66, 0x66), "Label.disabledForeground",
                "Component.infoForeground");
        Color fg = SwingHtmlUtils.getUiColor(new Color(0x33, 0x33, 0x33), "Label.foreground");
        subtitleLabel.setForeground(muted);
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

    private static final class ScrollableCardsPanel extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(visibleRect.height - 32, 32);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    static final class ToolCard extends JPanel {
        private static final long serialVersionUID = 1L;

        private final transient SystemTool tool;
        private final JLabel nameLabel;
        private final JLabel requirementBadge;
        private final JLabel statusBadge;
        private final JTextArea descriptionArea;
        private final JLabel versionBadge;
        private final JLabel licenseBadge;
        private final JButton websiteLinkButton;

        ToolCard(SystemTool tool, Consumer<String> websiteOpener) {
            this.tool = tool;
            setLayout(new MigLayout("fillx, wrap 1, insets 14 16 14 16, gapy 8", MIG_COL_GROW_FILL, ""));
            setOpaque(true);

            JPanel headerRow = new JPanel(new MigLayout("fillx, insets 0, gapx 8", "[][0:0, grow][right]", "[center]"));
            headerRow.setOpaque(false);

            nameLabel = new JLabel(tool.name());
            nameLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold +2");
            nameLabel.setIconTextGap(8);
            headerRow.add(nameLabel, "left, wmin 0");

            requirementBadge = new JLabel(tool.required() ? REQUIRED_BADGE_TEXT : OPTIONAL_BADGE_TEXT);
            headerRow.add(requirementBadge, "left");

            statusBadge = new JLabel(tool.status());
            statusBadge.setHorizontalTextPosition(SwingConstants.RIGHT);
            statusBadge.setIconTextGap(6);
            headerRow.add(statusBadge, "right");
            add(headerRow, MIG_GROWX_WMIN_ZERO);

            descriptionArea = new JTextArea(tool.description());
            descriptionArea.setEditable(false);
            descriptionArea.setLineWrap(true);
            descriptionArea.setWrapStyleWord(true);
            descriptionArea.setOpaque(false);
            descriptionArea.setFocusable(false);
            descriptionArea.setBorder(BorderFactory.createEmptyBorder());
            add(descriptionArea, MIG_GROWX_WMIN_ZERO);

            JPanel footerRow = new JPanel(
                    new MigLayout("fillx, insets 2 0 0 0, gapx 10", "[]10[]push[right]", "[center]"));
            footerRow.setOpaque(false);

            versionBadge = new JLabel(StringUtils.defaultIfBlank(tool.version(), "Unknown"));
            versionBadge.setIconTextGap(6);
            footerRow.add(versionBadge, "wmin 0");

            boolean hasLicense = StringUtils.isNotBlank(tool.license());
            licenseBadge = new JLabel(hasLicense ? "License: " + tool.license() : "");
            licenseBadge.setIconTextGap(5);
            licenseBadge.setVisible(hasLicense);
            footerRow.add(licenseBadge, "wmin 0, hidemode 3");

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

            add(footerRow, MIG_GROWX_WMIN_ZERO);
            applyCardTheme();
        }

        void applyCardTheme() {
            boolean dark = FlatLaf.isLafDark();
            Color accentColor = SwingHtmlUtils.getAccentColor();
            Color borderColor = SwingHtmlUtils.getUiColor(
                    dark ? new Color(0x3c, 0x3f, 0x41) : new Color(0xd8, 0xdc, 0xe0), "Component.borderColor");
            Color mutedColor = SwingHtmlUtils.getUiColor(new Color(0x6c, 0x75, 0x7d), "Label.disabledForeground",
                    "Component.infoForeground");
            Color fgColor = SwingHtmlUtils.getUiColor(new Color(0x21, 0x25, 0x29), "Label.foreground");

            String borderHex = SwingHtmlUtils.toHex(borderColor);
            String bgDirective = dark ? "lighten($Panel.background, 3%)" : "darken($Panel.background, 2%)";
            putClientProperty(FlatClientProperties.STYLE,
                    "arc: 12; border: 1,1,1,1," + borderHex + ",1,12; background: " + bgDirective);

            nameLabel.setForeground(fgColor);
            nameLabel.setIcon(FontIcon.of(resolveToolIcon(tool.name()), 18, accentColor));

            Font baseFont = UIManager.getFont("Label.font");
            if (baseFont != null) {
                descriptionArea.setFont(baseFont);
            }
            descriptionArea.setForeground(mutedColor);

            applyStatusBadgeStyle();

            String pillBg = dark ? "lighten($Panel.background, 7%)" : "darken($Panel.background, 5%)";
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
                statusColor = SwingHtmlUtils.getUiColor(new Color(0xd9, 0x77, 0x06), "Component.warning.borderColor",
                        "Actions.Yellow");
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

        SystemTool getTool() {
            return tool;
        }

        JLabel getNameLabel() {
            return nameLabel;
        }

        JLabel getRequirementBadge() {
            return requirementBadge;
        }

        JLabel getStatusBadge() {
            return statusBadge;
        }

        JTextArea getDescriptionArea() {
            return descriptionArea;
        }

        JLabel getVersionBadge() {
            return versionBadge;
        }

        JLabel getLicenseBadge() {
            return licenseBadge;
        }

        JButton getWebsiteLinkButton() {
            return websiteLinkButton;
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
