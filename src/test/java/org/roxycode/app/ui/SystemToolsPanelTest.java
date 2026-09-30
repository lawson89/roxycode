package org.roxycode.app.ui;

import org.junit.jupiter.api.Test;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.SystemToolService;
import org.mockito.Mockito;

import javax.swing.table.DefaultTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class SystemToolsPanelTest {

    @Test
    public void testSystemToolsPanelInstantiation() {
        SystemToolService service = Mockito.mock(SystemToolService.class);
        Mockito.when(service.detectTools()).thenReturn(CompletableFuture.completedFuture(new ArrayList<>()));
        SystemToolsPanel panel = new SystemToolsPanel(service);
        assertNotNull(panel);
    }

    @Test
    public void testRefreshToolsPopulatesTableModel() {
        SystemToolService service = Mockito.mock(SystemToolService.class);
        List<SystemTool> tools = new ArrayList<>();
        tools.add(new SystemTool("Test Tool", "1.0", "Detected", "Description"));
        Mockito.when(service.detectTools()).thenReturn(CompletableFuture.completedFuture(tools));

        SystemToolsPanel panel = new SystemToolsPanel(service);
        
        // Wait for async execution and SwingUtilities.invokeLater
        try { Thread.sleep(500); } catch (InterruptedException e) {}

        assertEquals(1, panel.tableModel.getRowCount());
        assertEquals("Test Tool", panel.tableModel.getValueAt(0, 0));
        assertEquals("1.0", panel.tableModel.getValueAt(0, 1));
        assertEquals("Detected", panel.tableModel.getValueAt(0, 2));
        assertEquals("Description", panel.tableModel.getValueAt(0, 3));
    }
}
