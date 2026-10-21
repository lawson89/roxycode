package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.service.PromptService;

class ContextManagerPanelTest {

    @Test
    void testOnProjectChangedClearsEditorAndRefreshesFileList() throws Exception {
        PromptService promptService = mock(PromptService.class);
        when(promptService.listContextFiles()).thenReturn(List.of("old.md"));
        when(promptService.readContextFile("old.md")).thenReturn("# Old Content");

        ContextManagerPanel panel = new ContextManagerPanel(promptService);
        for (int i = 0; i < 50; i++) {
            SwingUtilities.invokeAndWait(() -> {});
            if (panel.getListModel().getSize() == 1) {
                break;
            }
            Thread.sleep(20);
        }
        assertEquals(1, panel.getListModel().getSize());

        panel.loadSelectedFileAsync("old.md").get();
        SwingUtilities.invokeAndWait(() -> {});
        panel.getTextArea().setText("# Old Content");

        when(promptService.listContextFiles()).thenReturn(List.of("new1.md", "new2.md"));
        panel.onProjectChanged(new ProjectChangedEvent(Path.of("/tmp/new-project")));

        for (int i = 0; i < 50; i++) {
            SwingUtilities.invokeAndWait(() -> {});
            if (panel.getListModel().getSize() == 2) {
                break;
            }
            Thread.sleep(20);
        }

        assertEquals("", panel.getTextArea().getText());
        assertEquals(2, panel.getListModel().getSize());
        assertEquals("new1.md", panel.getListModel().getElementAt(0));
        assertEquals("new2.md", panel.getListModel().getElementAt(1));
    }
}
