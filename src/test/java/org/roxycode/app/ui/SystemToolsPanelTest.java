package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.Container;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.SystemToolService;
import org.roxycode.app.ui.SystemToolsPanel.ToolCard;

public class SystemToolsPanelTest {

    private static void assertCardState(ToolCard card, String name, String status, String expectedRequirementText) {
        assertEquals(name, card.getNameLabel().getText());
        assertEquals(status, card.getStatusBadge().getText());
        assertEquals(expectedRequirementText, card.getRequirementBadge().getText());
    }

    @Test
    public void testSystemToolsPanelInstantiationWithCachedTools() {
        SystemToolService service = mock(SystemToolService.class);
        when(service.detectTools()).thenReturn(CompletableFuture
                .completedFuture(List.of(new SystemTool("Ripgrep", "ripgrep 14.1.0", "Detected", "Fast search"))));

        SystemToolsPanel panel = new SystemToolsPanel(service);
        SwingTestUtils.flushEdt();

        assertFalse(panel.getProgressBar().isVisible());
        assertFalse(panel.getProgressBar().isIndeterminate());
        assertTrue(panel.getRefreshButton().isEnabled());
        assertEquals(1, panel.getToolCards().size());
        ToolCard card = panel.getToolCards().get(0);
        assertEquals("Ripgrep", card.getNameLabel().getText());
        assertEquals("Required", card.getRequirementBadge().getText());
        assertEquals("ripgrep 14.1.0", card.getVersionBadge().getText());
        assertEquals("Detected", card.getStatusBadge().getText());
        assertEquals("Fast search", card.getDescriptionArea().getText());
        assertEquals("License: Unlicense / MIT", card.getLicenseBadge().getText());
        assertEquals("github.com/BurntSushi/ripgrep", card.getWebsiteLinkButton().getText());
        assertEquals("https://github.com/BurntSushi/ripgrep", card.getWebsiteLinkButton().getToolTipText());

        verify(service, times(1)).detectTools();
        verify(service, never()).hasCachedTools();
        verify(service, never()).getCachedTools();
        verify(service, never()).refreshTools();
    }

    @Test
    public void testRefreshToolsShowsProgressBarDisablesButtonAndPopulatesCards() {
        SystemToolService service = mock(SystemToolService.class);
        when(service.detectTools()).thenReturn(CompletableFuture.completedFuture(List.of()));

        CompletableFuture<List<SystemTool>> pendingRefresh = new CompletableFuture<>();
        when(service.refreshTools()).thenReturn(pendingRefresh);

        SystemToolsPanel panel = new SystemToolsPanel(service);
        SwingTestUtils.flushEdt();
        assertFalse(panel.getProgressBar().isVisible());
        assertTrue(panel.getRefreshButton().isEnabled());

        SwingTestUtils.clickOnEdt(panel.getRefreshButton());
        assertFalse(panel.getRefreshButton().isEnabled());
        assertTrue(panel.getProgressBar().isVisible());
        assertTrue(panel.getProgressBar().isIndeterminate());

        pendingRefresh.complete(List
                .of(new SystemTool("Test Tool", "1.0", "Detected", "Description", "MIT", "https://example.com/tool/")));
        SwingTestUtils.flushEdt();

        assertFalse(panel.getProgressBar().isVisible());
        assertFalse(panel.getProgressBar().isIndeterminate());
        assertTrue(panel.getRefreshButton().isEnabled());
        assertEquals(1, panel.getToolCards().size());
        ToolCard card = panel.getToolCards().get(0);
        assertEquals("Test Tool", card.getNameLabel().getText());
        assertEquals("Optional", card.getRequirementBadge().getText());
        assertEquals("1.0", card.getVersionBadge().getText());
        assertEquals("Detected", card.getStatusBadge().getText());
        assertEquals("Description", card.getDescriptionArea().getText());
        assertEquals("License: MIT", card.getLicenseBadge().getText());
        assertEquals("example.com/tool", card.getWebsiteLinkButton().getText());
    }

    @Test
    public void testOnSystemToolsUpdatedEventSynchronizesCardsAndStatuses() {
        SystemToolService service = mock(SystemToolService.class);
        when(service.detectTools()).thenReturn(CompletableFuture.completedFuture(List.of()));

        SystemToolsPanel panel = new SystemToolsPanel(service);
        SwingTestUtils.flushEdt();
        assertEquals(0, panel.getToolCards().size());

        panel.onSystemToolsUpdated(
                new SystemToolsUpdatedEvent(List.of(SystemTool.ofKnownTool("Git", "git version 2.43.0", "Detected"),
                        SystemTool.ofKnownTool("Docker", "Client API version mismatch", "Error"),
                        SystemTool.ofKnownTool("Universal Ctags", "Not found", "Missing")), false));
        SwingTestUtils.flushEdt();

        assertEquals(3, panel.getToolCards().size());
        assertCardState(panel.getToolCards().get(0), "Git", "Detected", "Required");
        assertCardState(panel.getToolCards().get(1), "Docker", "Error", "Optional");
        assertCardState(panel.getToolCards().get(2), "Universal Ctags", "Missing", "Required");
    }

    @Test
    public void testWebsiteLinkClickInvokesBrowserLauncher() {
        SystemToolService service = mock(SystemToolService.class);
        when(service.detectTools()).thenReturn(CompletableFuture
                .completedFuture(List.of(SystemTool.ofKnownTool("Git", "git version 2.43.0", "Detected"))));

        SystemToolsPanel panel = new SystemToolsPanel(service);
        SwingTestUtils.flushEdt();

        AtomicReference<URI> launchedUri = new AtomicReference<>();
        panel.setBrowserLauncher(launchedUri::set);

        ToolCard gitCard = panel.getToolCards().get(0);
        assertEquals("git-scm.com", gitCard.getWebsiteLinkButton().getText());
        SwingTestUtils.clickOnEdt(gitCard.getWebsiteLinkButton());
        assertEquals(URI.create("https://git-scm.com"), launchedUri.get());

        panel.setBrowserLauncher(uri -> {
            throw new UnsupportedOperationException("Headless test");
        });
        assertDoesNotThrow(() -> SwingTestUtils.clickOnEdt(gitCard.getWebsiteLinkButton()));
    }

    @Test
    public void testCardsFitWithinConstrainedViewportWithoutHorizontalOverflow() {
        SystemToolService service = mock(SystemToolService.class);
        when(service.detectTools()).thenReturn(CompletableFuture
                .completedFuture(List.of(SystemTool.ofKnownTool("Ripgrep", "ripgrep 14.1.0", "Detected"),
                        SystemTool.ofKnownTool("Git", "git version 2.43.0", "Detected"),
                        SystemTool.ofKnownTool("Universal Ctags", "Universal Ctags 5.9.0", "Detected"),
                        SystemTool.ofKnownTool("Docker", "Docker 29.1.3 (API 1.52)", "Detected"))));

        SystemToolsPanel panel = new SystemToolsPanel(service);
        SwingTestUtils.runOnEdt(() -> {
            panel.setSize(700, 600);
            layoutRecursively(panel);
            layoutRecursively(panel);
        });

        int viewportWidth = panel.getCardsContainer().getParent().getWidth();
        assertTrue(viewportWidth > 0 && viewportWidth <= 700);
        assertTrue(panel.getCardsContainer().getWidth() <= viewportWidth);

        for (ToolCard card : panel.getToolCards()) {
            assertTrue(card.getWidth() > 0 && card.getWidth() <= viewportWidth);
            assertTrue(card.getStatusBadge().getX() + card.getStatusBadge().getWidth() <= card.getStatusBadge()
                    .getParent().getWidth());
            assertTrue(card.getWebsiteLinkButton().getX() + card.getWebsiteLinkButton().getWidth() <= card
                    .getWebsiteLinkButton().getParent().getWidth());
        }
    }

    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (var child : container.getComponents()) {
            if (child instanceof Container childContainer)
                layoutRecursively(childContainer);
        }
    }
}
