package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.roxycode.app.RoxyCode;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.SystemToolService;

public class SystemToolsPanelTest {

    @Test
    public void testSystemToolsPanelInstantiationWithCachedTools() throws Exception {
        SystemToolService service = Mockito.mock(SystemToolService.class);
        List<SystemTool> cached =
                List.of(new SystemTool("Ripgrep", "14.1.0", "Detected", "Fast search"));
        when(service.hasCachedTools()).thenReturn(true);
        when(service.getCachedTools()).thenReturn(cached);

        SystemToolsPanel panel = new SystemToolsPanel(service);
        SwingUtilities.invokeAndWait(() -> {});

        assertNotNull(panel);
        assertFalse(
                panel.getProgressBar().isVisible(),
                "Progress bar should be hidden when loaded from cache");
        assertTrue(panel.getRefreshButton().isEnabled());
        assertEquals(1, panel.tableModel.getRowCount());
        assertEquals("Ripgrep", panel.tableModel.getValueAt(0, 0));
        verify(service, never()).detectTools();
        verify(service, never()).refreshTools();
    }

    @Test
    public void testRefreshToolsShowsProgressBarDisablesButtonAndPopulatesTable() throws Exception {
        SystemToolService service = Mockito.mock(SystemToolService.class);
        when(service.hasCachedTools()).thenReturn(true);
        when(service.getCachedTools()).thenReturn(List.of());

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
        tools.add(new SystemTool("Test Tool", "1.0", "Detected", "Description"));
        pendingRefresh.complete(tools);
        SwingUtilities.invokeAndWait(() -> {});

        assertFalse(
                panel.getProgressBar().isVisible(),
                "Progress bar must be hidden after refresh completes");
        assertTrue(
                panel.getRefreshButton().isEnabled(),
                "Refresh button must be re-enabled after refresh completes");
        assertEquals(1, panel.tableModel.getRowCount());
        assertEquals("Test Tool", panel.tableModel.getValueAt(0, 0));
        assertEquals("1.0", panel.tableModel.getValueAt(0, 1));
        assertEquals("Detected", panel.tableModel.getValueAt(0, 2));
        assertEquals("Description", panel.tableModel.getValueAt(0, 3));
    }

    @Test
    public void testOnSystemToolsUpdatedEventSynchronizesTable() throws Exception {
        SystemToolService service = Mockito.mock(SystemToolService.class);
        when(service.hasCachedTools()).thenReturn(true);
        when(service.getCachedTools()).thenReturn(List.of());

        SystemToolsPanel panel = new SystemToolsPanel(service);
        SwingUtilities.invokeAndWait(() -> {});
        assertEquals(0, panel.tableModel.getRowCount());

        List<SystemTool> updated =
                List.of(new SystemTool("Docker", "Docker 28.0.1", "Detected", "Testcontainers"));
        panel.onSystemToolsUpdated(new SystemToolsUpdatedEvent(updated, true));
        SwingUtilities.invokeAndWait(() -> {});

        assertEquals(1, panel.tableModel.getRowCount());
        assertEquals("Docker", panel.tableModel.getValueAt(0, 0));
        assertEquals("Detected", panel.tableModel.getValueAt(0, 2));
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
}
