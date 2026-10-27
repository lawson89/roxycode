package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.mockito.Mockito;
import org.roxycode.app.RoxyCode;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.SystemToolService;
import org.roxycode.app.ui.SystemToolsPanel.ToolCard;

public class SystemToolsPanelTest {

    @Test
    public void testSystemToolsPanelInstantiationWithCachedTools() throws Exception {
        SystemToolService service = Mockito.mock(SystemToolService.class);
        List<SystemTool> cached =
                List.of(new SystemTool("Ripgrep", "ripgrep 14.1.0", "Detected", "Fast search"));
        when(service.detectTools()).thenReturn(CompletableFuture.completedFuture(cached));

        SystemToolsPanel panel = new SystemToolsPanel(service);
        SwingUtilities.invokeAndWait(() -> {});

        assertNotNull(panel);
        assertFalse(
                panel.getProgressBar().isVisible(),
                "Progress bar should be hidden when loaded from cache");
        assertFalse(
                panel.getProgressBar().isIndeterminate(),
                "Progress bar should not be indeterminate while hidden");
        assertTrue(panel.getRefreshButton().isEnabled());
        assertEquals(1, panel.getToolCards().size());
        ToolCard card = panel.getToolCards().get(0);
        assertEquals("Ripgrep", card.getNameLabel().getText());
        assertEquals("ripgrep 14.1.0", card.getVersionBadge().getText());
        assertEquals("Detected", card.getStatusBadge().getText());
        assertEquals("Fast search", card.getDescriptionArea().getText());
        assertEquals("License: Unlicense / MIT", card.getLicenseBadge().getText());
        assertEquals("github.com/BurntSushi/ripgrep", card.getWebsiteLinkButton().getText());
        assertEquals(
                "https://github.com/BurntSushi/ripgrep",
                card.getWebsiteLinkButton().getToolTipText());

        verify(service, times(1)).detectTools();
        verify(service, never()).hasCachedTools();
        verify(service, never()).getCachedTools();
        verify(service, never()).refreshTools();
    }

    @Test
    public void testRefreshToolsShowsProgressBarDisablesButtonAndPopulatesCards() throws Exception {
        SystemToolService service = Mockito.mock(SystemToolService.class);
        when(service.detectTools()).thenReturn(CompletableFuture.completedFuture(List.of()));

        CompletableFuture<List<SystemTool>> pendingRefresh = new CompletableFuture<>();
        when(service.refreshTools()).thenReturn(pendingRefresh);

        SystemToolsPanel panel = new SystemToolsPanel(service);
        SwingUtilities.invokeAndWait(() -> {});

        assertFalse(panel.getProgressBar().isVisible());
        assertTrue(panel.getRefreshButton().isEnabled());

        // Click "Refresh Status" -> button disabled, indeterminate progress bar visible
        SwingUtilities.invokeAndWait(() -> panel.getRefreshButton().doClick());

        assertFalse(
                panel.getRefreshButton().isEnabled(),
                "Refresh button must be disabled while refresh is in flight");
        assertTrue(
                panel.getProgressBar().isVisible(),
                "Indeterminate progress bar must be visible while refresh is in flight");
        assertTrue(panel.getProgressBar().isIndeterminate());

        // Complete the async refresh
        List<SystemTool> tools = new ArrayList<>();
        tools.add(
                new SystemTool(
                        "Test Tool",
                        "1.0",
                        "Detected",
                        "Description",
                        "MIT",
                        "https://example.com/tool/"));
        pendingRefresh.complete(tools);
        SwingUtilities.invokeAndWait(() -> {});

        assertFalse(
                panel.getProgressBar().isVisible(),
                "Progress bar must be hidden after refresh completes");
        assertFalse(
                panel.getProgressBar().isIndeterminate(),
                "Progress bar must stop indeterminate mode after refresh completes");
        assertTrue(
                panel.getRefreshButton().isEnabled(),
                "Refresh button must be re-enabled after refresh completes");
        assertEquals(1, panel.getToolCards().size());
        ToolCard card = panel.getToolCards().get(0);
        assertEquals("Test Tool", card.getNameLabel().getText());
        assertEquals("1.0", card.getVersionBadge().getText());
        assertEquals("Detected", card.getStatusBadge().getText());
        assertEquals("Description", card.getDescriptionArea().getText());
        assertEquals("License: MIT", card.getLicenseBadge().getText());
        assertEquals("example.com/tool", card.getWebsiteLinkButton().getText());
    }

    @Test
    public void testOnSystemToolsUpdatedEventSynchronizesCardsAndStatuses() throws Exception {
        SystemToolService service = Mockito.mock(SystemToolService.class);
        when(service.detectTools()).thenReturn(CompletableFuture.completedFuture(List.of()));

        SystemToolsPanel panel = new SystemToolsPanel(service);
        SwingUtilities.invokeAndWait(() -> {});
        assertEquals(0, panel.getToolCards().size());

        List<SystemTool> updated =
                List.of(
                        SystemTool.ofKnownTool("Git", "git version 2.43.0", "Detected"),
                        SystemTool.ofKnownTool("Docker", "Client API version mismatch", "Error"),
                        SystemTool.ofKnownTool("Universal Ctags", "Not found", "Missing"));
        panel.onSystemToolsUpdated(new SystemToolsUpdatedEvent(updated, false));
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(3, panel.getToolCards().size());

        ToolCard gitCard = panel.getToolCards().get(0);
        assertEquals("Git", gitCard.getNameLabel().getText());
        assertEquals("Detected", gitCard.getStatusBadge().getText());
        assertEquals(
                Codicons.CHECK,
                assertInstanceOf(FontIcon.class, gitCard.getStatusBadge().getIcon()).getIkon());
        assertEquals(
                Codicons.SOURCE_CONTROL,
                assertInstanceOf(FontIcon.class, gitCard.getNameLabel().getIcon()).getIkon());

        ToolCard dockerCard = panel.getToolCards().get(1);
        assertEquals("Docker", dockerCard.getNameLabel().getText());
        assertEquals("Error", dockerCard.getStatusBadge().getText());
        assertEquals(
                Codicons.WARNING,
                assertInstanceOf(FontIcon.class, dockerCard.getStatusBadge().getIcon()).getIkon());
        assertEquals(
                Codicons.SERVER_ENVIRONMENT,
                assertInstanceOf(FontIcon.class, dockerCard.getNameLabel().getIcon()).getIkon());

        ToolCard ctagsCard = panel.getToolCards().get(2);
        assertEquals("Universal Ctags", ctagsCard.getNameLabel().getText());
        assertEquals("Missing", ctagsCard.getStatusBadge().getText());
        assertEquals(
                Codicons.ERROR,
                assertInstanceOf(FontIcon.class, ctagsCard.getStatusBadge().getIcon()).getIkon());
        assertEquals(
                Codicons.SYMBOL_CLASS,
                assertInstanceOf(FontIcon.class, ctagsCard.getNameLabel().getIcon()).getIkon());
    }

    @Test
    public void testWebsiteLinkClickInvokesBrowserLauncherAndUpdateUiRefreshesTheme()
            throws Exception {
        SystemToolService service = Mockito.mock(SystemToolService.class);
        List<SystemTool> tools =
                List.of(SystemTool.ofKnownTool("Java", "version 25.0.4", "Detected"));
        when(service.detectTools()).thenReturn(CompletableFuture.completedFuture(tools));

        SystemToolsPanel panel = new SystemToolsPanel(service);
        SwingUtilities.invokeAndWait(() -> {});

        AtomicReference<URI> launchedUri = new AtomicReference<>();
        panel.setBrowserLauncher(launchedUri::set);

        ToolCard javaCard = panel.getToolCards().get(0);
        assertEquals("adoptium.net", javaCard.getWebsiteLinkButton().getText());
        SwingUtilities.invokeAndWait(() -> javaCard.getWebsiteLinkButton().doClick());
        assertEquals(URI.create("https://adoptium.net"), launchedUri.get());

        // Fallback when browser launcher throws
        panel.setBrowserLauncher(
                uri -> {
                    throw new UnsupportedOperationException("Headless test");
                });
        assertDoesNotThrow(
                () ->
                        SwingUtilities.invokeAndWait(
                                () -> javaCard.getWebsiteLinkButton().doClick()));

        // Verify updateUI refreshes cards cleanly
        SwingUtilities.invokeAndWait(panel::updateUI);
        assertNotNull(javaCard.getNameLabel().getIcon());
        assertNotNull(javaCard.getStatusBadge().getIcon());
    }

    @Test
    public void testRoxyCodeInitializeSystemToolsDuringSplashScreen() throws Exception {
        SystemToolService service = Mockito.mock(SystemToolService.class);
        when(service.hasCachedTools()).thenReturn(false);
        when(service.detectTools())
                .thenReturn(
                        CompletableFuture.completedFuture(
                                List.of(new SystemTool("Java", "25", "Detected", "Runtime"))));

        SplashScreenWindow splash = Mockito.mock(SplashScreenWindow.class);
        RoxyCode.initializeSystemTools(service, splash);
        SwingUtilities.invokeAndWait(() -> {});
        verify(splash, times(1)).setStatus("Checking system tools...");
        verify(service, times(1)).detectTools();

        // Subsequent run when cached skips detection
        when(service.hasCachedTools()).thenReturn(true);
        RoxyCode.initializeSystemTools(service, splash);
        SwingUtilities.invokeAndWait(() -> {});
        verify(splash, times(1)).setStatus("Checking system tools...");
        verify(service, times(1)).detectTools();
    }

    @Test
    public void testCardsFitWithinConstrainedViewportWithoutHorizontalOverflow() throws Exception {
        SystemToolService service = Mockito.mock(SystemToolService.class);
        List<SystemTool> tools =
                List.of(
                        SystemTool.ofKnownTool("Ripgrep", "ripgrep 14.1.0", "Detected"),
                        SystemTool.ofKnownTool("Git", "git version 2.43.0", "Detected"),
                        SystemTool.ofKnownTool(
                                "Universal Ctags", "Universal Ctags 5.9.0", "Detected"),
                        SystemTool.ofKnownTool(
                                "Java",
                                "version " + '"' + "25.0.4" + '"' + " 2026-07-21 LTS",
                                "Detected"),
                        SystemTool.ofKnownTool("Docker", "Docker 29.1.3 (API 1.52)", "Detected"));
        when(service.detectTools()).thenReturn(CompletableFuture.completedFuture(tools));

        SystemToolsPanel panel = new SystemToolsPanel(service);
        SwingUtilities.invokeAndWait(
                () -> {
                    panel.setSize(700, 600);
                    layoutRecursively(panel);
                    layoutRecursively(panel);
                });

        int viewportWidth = panel.getCardsContainer().getParent().getWidth();
        assertTrue(viewportWidth > 0 && viewportWidth <= 700, "Viewport width should be <= 700");
        assertTrue(
                panel.getCardsContainer().getWidth() <= viewportWidth,
                "cardsContainer width ("
                        + panel.getCardsContainer().getWidth()
                        + ") must not exceed viewport width ("
                        + viewportWidth
                        + ")");

        for (ToolCard card : panel.getToolCards()) {
            assertTrue(
                    card.getWidth() > 0 && card.getWidth() <= viewportWidth,
                    "Card width ("
                            + card.getWidth()
                            + ") for "
                            + card.getTool().name()
                            + " must not exceed viewport width ("
                            + viewportWidth
                            + ")");
            int headerWidth = card.getStatusBadge().getParent().getWidth();
            int statusRightEdge = card.getStatusBadge().getX() + card.getStatusBadge().getWidth();
            assertTrue(
                    statusRightEdge <= headerWidth,
                    "Status badge right edge ("
                            + statusRightEdge
                            + ") must stay inside header width ("
                            + headerWidth
                            + ") for "
                            + card.getTool().name());

            int footerWidth = card.getWebsiteLinkButton().getParent().getWidth();
            int websiteRightEdge =
                    card.getWebsiteLinkButton().getX() + card.getWebsiteLinkButton().getWidth();
            assertTrue(
                    websiteRightEdge <= footerWidth,
                    "Website link button right edge ("
                            + websiteRightEdge
                            + ") must stay inside footer width ("
                            + footerWidth
                            + ") for "
                            + card.getTool().name());
        }
    }

    private static void layoutRecursively(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container childContainer) {
                layoutRecursively(childContainer);
            }
        }
    }
}
