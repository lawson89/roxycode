package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.function.Consumer;
import javax.swing.JButton;
import org.junit.jupiter.api.Test;

class SidebarPanelTest {

    @Test
    @SuppressWarnings("unchecked")
    void testSidebarNavigation() {
        Consumer<String> navAction = mock(Consumer.class);
        SidebarPanel sidebar = new SidebarPanel(navAction);

        JButton qaBtn = SwingTestUtils.findButton(sidebar, "Codebase Q&A");
        JButton taskBtn = SwingTestUtils.findButton(sidebar, "Feature Request");
        JButton contextBtn = SwingTestUtils.findButton(sidebar, "Context");
        JButton toolsBtn = SwingTestUtils.findButton(sidebar, "System Tools");

        assertNotNull(qaBtn);
        assertNotNull(taskBtn);
        assertNotNull(contextBtn);
        assertNotNull(toolsBtn);
        assertTrue(qaBtn.isSelected());
        assertFalse(taskBtn.isSelected());

        qaBtn.doClick();
        verify(navAction).accept("QA_VIEW");
        taskBtn.doClick();
        verify(navAction).accept("TASK_VIEW");
        contextBtn.doClick();
        verify(navAction).accept("CONTEXT_MGR");

        sidebar.selectCard("TOOLS");
        assertTrue(toolsBtn.isSelected());
        assertFalse(contextBtn.isSelected());
        assertFalse(qaBtn.isSelected());

        sidebar.selectCard("QA_VIEW");
        assertTrue(qaBtn.isSelected());
        assertFalse(toolsBtn.isSelected());
    }

    @Test
    void testSetQaAndTaskBusyAndWaitingApprovalUpdatesSidebarButtons() {
        SidebarPanel sidebar = new SidebarPanel(card -> {
        });
        assertFalse(sidebar.isQaBusy());
        assertFalse(sidebar.isTaskBusy());
        assertFalse(sidebar.isTaskWaitingApproval());

        sidebar.setQaBusy(true);
        assertTrue(sidebar.isQaBusy());
        assertFalse(sidebar.isTaskBusy());

        sidebar.setQaBusy(false);
        assertFalse(sidebar.isQaBusy());

        sidebar.setTaskBusy(true);
        assertTrue(sidebar.isTaskBusy());

        sidebar.setTaskWaitingApproval(true);
        assertTrue(sidebar.isTaskWaitingApproval());

        sidebar.setTaskBusy(false);
        assertFalse(sidebar.isTaskBusy());
        assertTrue(sidebar.isTaskWaitingApproval());

        sidebar.setTaskWaitingApproval(false);
        assertFalse(sidebar.isTaskWaitingApproval());
    }
}
