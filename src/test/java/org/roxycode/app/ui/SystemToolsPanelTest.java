package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
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
        when(service.detectTools()).thenReturn(CompletableFuture.completedFuture(
                List.of(new SystemTool("Ripgrep", "ripgrep 14.1.0", "Detected", "Fast search", null, null, false))));

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

        SwingTestUtils.runOnEdt(() -> panel.getRefreshButton().doClick());
        SwingTestUtils.runOnEdt(() -> {
            assertFalse(panel.getRefreshButton().isEnabled());
            assertTrue(panel.getProgressBar().isVisible());
            assertTrue(panel.getProgressBar().isIndeterminate());
        });

        pendingRefresh.complete(List.of(new SystemTool("Test Tool", "1.0", "Detected", "Description", "MIT",
                "https://example.com/tool/", false)));
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
        ToolCard ctagsCard = panel.getToolCards().get(0);
        ToolCard gitCard = panel.getToolCards().get(1);
        ToolCard dockerCard = panel.getToolCards().get(2);
        assertCardState(ctagsCard, "Universal Ctags", "Missing", "Required");
        assertCardState(gitCard, "Git", "Detected", "Optional");
        assertCardState(dockerCard, "Docker", "Error", "Optional");

        assertTrue(ctagsCard.getCopyInstallButton().isVisible());
        assertTrue(ctagsCard.getCopyInstallButton().getText().startsWith(SystemToolsPanel.COPY_INSTALL_PREFIX));
        assertFalse(gitCard.getCopyInstallButton().isVisible());
        assertTrue(dockerCard.getCopyInstallButton().isVisible());

        SwingTestUtils.clickOnEdt(ctagsCard.getCopyInstallButton());
        assertEquals("Copied!", ctagsCard.getCopyInstallButton().getText());
    }

    @Test
    public void testResolveInstallCommandForWindowsMacAndLinux() {
        assertEquals("winget install BurntSushi.ripgrep.MSVC",
                SystemToolsPanel.resolveInstallCommand("Ripgrep", "Windows 11"));
        assertEquals("winget install UniversalCtags.Ctags",
                SystemToolsPanel.resolveInstallCommand("Universal Ctags", "Windows 10"));
        assertEquals("winget install Git.Git", SystemToolsPanel.resolveInstallCommand("Git", "Windows 11"));
        assertEquals("winget install Docker.DockerDesktop",
                SystemToolsPanel.resolveInstallCommand("Docker", "Windows 11"));

        assertEquals("brew install ripgrep", SystemToolsPanel.resolveInstallCommand("Ripgrep", "Mac OS X"));
        assertEquals("brew install universal-ctags",
                SystemToolsPanel.resolveInstallCommand("Universal Ctags", "Mac OS X"));
        assertEquals("brew install git", SystemToolsPanel.resolveInstallCommand("Git", "Darwin"));
        assertEquals("brew install --cask docker", SystemToolsPanel.resolveInstallCommand("Docker", "Mac OS X"));

        assertEquals("sudo apt install ripgrep", SystemToolsPanel.resolveInstallCommand("Ripgrep", "Linux"));
        assertEquals("sudo apt install universal-ctags",
                SystemToolsPanel.resolveInstallCommand("Universal Ctags", "Linux"));
        assertEquals("sudo apt install git", SystemToolsPanel.resolveInstallCommand("Git", "Linux"));
        assertEquals("sudo apt install docker.io", SystemToolsPanel.resolveInstallCommand("Docker", "Linux"));
        assertEquals("", SystemToolsPanel.resolveInstallCommand("UnknownTool", "Linux"));
        assertEquals("", SystemToolsPanel.resolveInstallCommand(null, "Linux"));
    }
}
