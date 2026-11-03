package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.roxycode.app.ui.SwingTestUtils.assertContainsAll;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.events.TokenUsageEvent;
import org.roxycode.app.model.GeminiModelConfig;
import org.roxycode.app.service.EnvironmentService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.SettingsService;

class StatusPanelTest {

    @Test
    void testStatusPanelDisplaysContextMeterCacheHitAndSessionTokenCost() {
        EnvironmentService envService = new EnvironmentService();
        SettingsService settingsService = mock(SettingsService.class);
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.getEffectiveGeminiModel()).thenReturn("gemini-3.5-flash");
        when(settingsService.resolveModelConfig("gemini-3.5-flash")).thenReturn(
                new GeminiModelConfig("gemini-3.5-flash", "Gemini 3.5 Flash", "Desc", 1_048_576, 0.30, 0.075, 2.50));

        StatusPanel panel = new StatusPanel(envService, settingsService, projectService);
        assertEquals("Context Usage: 0 / 1.0M (0%)", panel.ctxLabel.getText());
        assertEquals(0, panel.ctxProgressBar.getValue());
        assertEquals("Cache Hit: 0 / 0 (0%)", panel.cacheLabel.getText());
        assertEquals(0, panel.cacheProgressBar.getValue());
        assertEquals("Session: 0 tok ($0.00)", panel.tokenCostLabel.getText());
        assertTrue(panel.memLabel.getText().startsWith("JVM Mem: "));
        assertContainsAll(panel.memLabel.getToolTipText(), "% JVM heap used");

        panel.onTokenUsage(new TokenUsageEvent("task:default", "gemini-3.5-flash", 1_000_000, 50_000, 40_000, 10_000,
                1_500, 500, 52_000, null, 0.0030, 0.0030, 0.00375, 0.00125, 0.011));
        SwingTestUtils.flushEdt();

        assertEquals("Context Usage: 50.0k / 1.0M (5%)", panel.ctxLabel.getText());
        assertEquals(5, panel.ctxProgressBar.getValue());
        assertContainsAll(panel.ctxLabel.getToolTipText(), "50,000 / 1,000,000 tokens (5%)");
        assertEquals(panel.ctxLabel.getToolTipText(), panel.ctxProgressBar.getToolTipText());

        assertEquals("Cache Hit: 40.0k / 50.0k (80%)", panel.cacheLabel.getText());
        assertEquals(80, panel.cacheProgressBar.getValue());
        assertContainsAll(panel.cacheLabel.getToolTipText(), "40,000 / 50,000 input tokens (80%)");
        assertEquals(panel.cacheLabel.getToolTipText(), panel.cacheProgressBar.getToolTipText());

        assertEquals("Session: 52.0k tok ($0.01)", panel.tokenCostLabel.getText());
        assertContainsAll(panel.tokenCostLabel.getToolTipText(), "Calls: 1", "52,000 tok",
                "Uncached In: 10,000 ($0.0030)", "Cached In: 40,000 (80%, $0.0030)", "Out+Think: 2,000 ($0.0050)",
                "Total Cost: $0.01");

        panel.onTokenUsage(new TokenUsageEvent("qa:default", "gemini-3.5-flash", 1_000_000, 100_000, 75_000, 25_000,
                2_000, 1_000, 103_000, 0.0206));
        SwingTestUtils.flushEdt();

        assertEquals("Context Usage: 100.0k / 1.0M (10%)", panel.ctxLabel.getText());
        assertEquals(10, panel.ctxProgressBar.getValue());
        assertEquals("Cache Hit: 75.0k / 100.0k (75%)", panel.cacheLabel.getText());
        assertEquals(75, panel.cacheProgressBar.getValue());
        assertEquals("Session: 155.0k tok ($0.03)", panel.tokenCostLabel.getText());
        assertContainsAll(panel.tokenCostLabel.getToolTipText(), "Calls: 2", "155,000 tok");

        panel.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp/new-project")));
        SwingTestUtils.flushEdt();
        assertEquals("Context Usage: 0 / 1.0M (0%)", panel.ctxLabel.getText());
        assertEquals(0, panel.ctxProgressBar.getValue());
        assertEquals("Cache Hit: 0 / 0 (0%)", panel.cacheLabel.getText());
        assertEquals(0, panel.cacheProgressBar.getValue());
        assertEquals("Session: 155.0k tok ($0.03)", panel.tokenCostLabel.getText());
    }
}
