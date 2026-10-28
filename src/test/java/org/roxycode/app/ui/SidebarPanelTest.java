package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;

class SidebarPanelTest {

    @Test
    void testSidebarPanelInstantiation() {
        assertNotNull(new SidebarPanel(card -> {
        }));
    }

    @Test
    void testSidebarStructureAndBranding() {
        SidebarPanel sidebar = new SidebarPanel(card -> {
        });
        Component[] components = sidebar.getComponents();
        assertEquals(12, components.length);

        int headerCount = 0;
        int buttonCount = 0;
        JPanel brandingPanel = null;
        for (Component c : components) {
            if (c instanceof JLabel)
                headerCount++;
            if (c instanceof JButton)
                buttonCount++;
            if (c instanceof JPanel p)
                brandingPanel = p;
        }

        assertNotNull(brandingPanel);
        assertEquals(3, headerCount);
        assertEquals(8, buttonCount);
        assertNull(SwingTestUtils.findButton(sidebar, "Global Context"));
        assertNull(SwingTestUtils.findButton(sidebar, "Project Context"));
        assertNotNull(SwingTestUtils.findButton(sidebar, "Context"));

        boolean foundLogoPanel = false;
        boolean foundTitleLabel = false;
        int brandingLabels = 0;

        for (Component c : brandingPanel.getComponents()) {
            if (c instanceof JPanel && c.getPreferredSize().equals(new Dimension(88, 88))) {
                foundLogoPanel = true;
                c.setSize(88, 88);
                BufferedImage img = new BufferedImage(88, 88, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = img.createGraphics();
                RoundedImagePanel rounded = assertInstanceOf(RoundedImagePanel.class, c);
                assertDoesNotThrow(() -> rounded.paintComponent(g2));
                BufferedImage firstCached = rounded.getCachedBuffer();
                assertNotNull(firstCached);
                assertDoesNotThrow(() -> rounded.paintComponent(g2));
                assertSame(firstCached, rounded.getCachedBuffer());
                c.setSize(64, 64);
                assertDoesNotThrow(() -> rounded.paintComponent(g2));
                assertNotSame(firstCached, rounded.getCachedBuffer());
                g2.dispose();
            } else if (c instanceof JLabel label) {
                brandingLabels++;
                if ("RoxyCode".equals(label.getText()))
                    foundTitleLabel = true;
            }
        }

        assertTrue(foundLogoPanel);
        assertNotNull(sidebar.getBorder());
        assertTrue(foundTitleLabel);
        assertEquals(1, brandingLabels);
    }

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
        assertEquals(Codicons.GIT_PULL_REQUEST, assertInstanceOf(FontIcon.class, taskBtn.getIcon()).getIkon());
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
