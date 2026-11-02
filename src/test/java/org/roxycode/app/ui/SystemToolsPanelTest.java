package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.codicons.Codicons;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.SystemToolService;
import org.roxycode.app.ui.SystemToolsPanel.ToolCard;

public class SystemToolsPanelTest {

    @Test
    public void testSystemToolsPanelPopulationRefreshAndEvents() {
        SystemToolService service = mock(SystemToolService.class);
        when(service.detectTools()).thenReturn(CompletableFuture.completedFuture(
                List.of(new SystemTool("Ripgrep", "ripgrep 14.1.0", "Detected", "Fast search", null, null, false))));

        CompletableFuture<List<SystemTool>> pendingRefresh = new CompletableFuture<>();
        when(service.refreshTools()).thenReturn(pendingRefresh);

        SystemToolsPanel panel = new SystemToolsPanel(service);
        SwingTestUtils.flushEdt();

        assertFalse(panel.progressBar.isVisible());
        assertTrue(panel.refreshBtn.isEnabled());
        assertEquals(1, panel.toolCards.size());
        ToolCard initialCard = panel.toolCards.get(0);
        assertEquals("Ripgrep", initialCard.nameLabel.getText());
        assertEquals("Required", initialCard.requirementBadge.getText());
        assertEquals("ripgrep 14.1.0", initialCard.versionBadge.getText());
        assertEquals("github.com/BurntSushi/ripgrep", initialCard.websiteLinkButton.getText());

        SwingTestUtils.runOnEdt(panel.refreshBtn::doClick);
        SwingTestUtils.runOnEdt(() -> {
            assertFalse(panel.refreshBtn.isEnabled());
            assertTrue(panel.progressBar.isVisible());
        });

        pendingRefresh.complete(List.of(new SystemTool("Test Tool", "1.0", "Detected", "Description", "MIT",
                "https://example.com/tool/", false)));
        SwingTestUtils.flushEdt();

        assertFalse(panel.progressBar.isVisible());
        assertTrue(panel.refreshBtn.isEnabled());
        assertEquals("example.com/tool", panel.toolCards.get(0).websiteLinkButton.getText());

        panel.onSystemToolsUpdated(
                new SystemToolsUpdatedEvent(List.of(SystemTool.ofKnownTool("Git", "git version 2.43.0", "Detected"),
                        SystemTool.ofKnownTool("Docker", "Client API version mismatch", "Error"),
                        SystemTool.ofKnownTool("Universal Ctags", "Not found", "Missing")), false));
        SwingTestUtils.flushEdt();

        assertEquals(3, panel.toolCards.size());
        ToolCard ctagsCard = panel.toolCards.get(0);
        assertEquals("Universal Ctags", ctagsCard.nameLabel.getText());
        assertEquals("Missing", ctagsCard.statusBadge.getText());
        assertTrue(ctagsCard.copyInstallButton.isVisible());
        assertFalse(panel.toolCards.get(1).copyInstallButton.isVisible());
        assertTrue(panel.toolCards.get(2).copyInstallButton.isVisible());

        SwingTestUtils.clickOnEdt(ctagsCard.copyInstallButton);
        assertEquals("Copied!", ctagsCard.copyInstallButton.getText());
    }

    @Test
    public void testResolveInstallCommandForWindowsMacAndLinux() {
        assertEquals("winget install BurntSushi.ripgrep.MSVC",
                SystemToolsPanel.resolveInstallCommand("Ripgrep", "Windows 11"));
        assertEquals("brew install universal-ctags",
                SystemToolsPanel.resolveInstallCommand("Universal Ctags", "Mac OS X"));
        assertEquals("brew install --cask docker", SystemToolsPanel.resolveInstallCommand("Docker", "Darwin"));
        assertEquals("sudo apt install bubblewrap",
                SystemToolsPanel.resolveInstallCommand(SystemTool.TOOL_BUBBLEWRAP, "Linux"));
        assertEquals(Codicons.SHIELD, SystemToolsPanel.resolveToolIcon(SystemTool.TOOL_BUBBLEWRAP));
        assertEquals(Codicons.SHIELD, SystemToolsPanel.resolveToolIcon(SystemTool.TOOL_SEATBELT));
        assertEquals("", SystemToolsPanel.resolveInstallCommand("UnknownTool", "Linux"));
        assertEquals("", SystemToolsPanel.resolveInstallCommand(null, "Linux"));
    }
}
