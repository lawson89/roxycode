package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import org.junit.jupiter.api.Test;
import org.roxycode.app.events.TokenUsageEvent;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;

class StatusPanelTest {

    @Test
    void testStatusPanelDisplaysSessionTokenCostAndMemory() {
        EnvironmentService envService = new EnvironmentService();
        SettingsService settingsService = mock(SettingsService.class);
        ProjectService projectService = mock(ProjectService.class);

        StatusPanel panel = new StatusPanel(envService, settingsService, projectService);
        assertEquals("Session: 0 tok ($0.00)", panel.tokenCostLabel.getText());
        assertTrue(panel.memLabel.getText().startsWith("JVM Mem: "));
        assertContainsAll(panel.memLabel.getToolTipText(), "% JVM heap used");

        panel.onTokenUsage(new TokenUsageEvent("task:default", "gemini-3.5-flash", 1_000_000, 50_000, 40_000, 10_000,
                1_500, 500, 52_000, null, 0.0030, 0.0030, 0.00375, 0.00125, 0.011));
        SwingTestUtils.flushEdt();

        assertEquals("Session: 52.0k tok ($0.01)", panel.tokenCostLabel.getText());
        assertContainsAll(panel.tokenCostLabel.getToolTipText(), "Calls: 1", "52,000 tok",
                "Uncached In: 10,000 ($0.0030)", "Cached In: 40,000 (80%, $0.0030)", "Out+Think: 2,000 ($0.0050)",
                "Total Cost: $0.01");

        panel.onTokenUsage(new TokenUsageEvent("qa:default", "gemini-3.5-flash", 1_000_000, 100_000, 75_000, 25_000,
                2_000, 1_000, 103_000, 0.0206));
        SwingTestUtils.flushEdt();

        assertEquals("Session: 155.0k tok ($0.03)", panel.tokenCostLabel.getText());
        assertContainsAll(panel.tokenCostLabel.getToolTipText(), "Calls: 2", "155,000 tok");

        assertEquals("Session: 155.0k tok ($0.03)", panel.tokenCostLabel.getText());
    }
}
