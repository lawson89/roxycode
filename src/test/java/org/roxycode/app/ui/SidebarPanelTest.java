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
        SidebarPanel sidebar = new SidebarPanel(card -> {});
        assertNotNull(sidebar);
    }

    @Test
    void testSidebarStructureAndBranding() {
        SidebarPanel sidebar = new SidebarPanel(card -> {});

        Component[] components = sidebar.getComponents();

        // Expected: 1 BrandingPanel, 3 Headers, 9 NavButtons = 13
        assertEquals(13, components.length, "Sidebar should have exactly 13 components");

        int headerCount = 0;
        int buttonCount = 0;
        JPanel brandingPanel = null;

        for (Component c : components) {
            if (c instanceof JLabel) headerCount++;
            if (c instanceof JButton) buttonCount++;
            if (c instanceof JPanel) brandingPanel = (JPanel) c;
        }

        assertNotNull(brandingPanel, "Should have a branding header panel");
        assertEquals(3, headerCount, "Should have 3 headers (WORKSPACES, CONTEXT, CONFIG)");
        assertEquals(9, buttonCount, "Should have 9 navigation buttons");

        boolean foundLogoPanel = false;
        boolean foundTitleLabel = false;
        int brandingLabels = 0;

        for (Component c : brandingPanel.getComponents()) {
            if (c instanceof JPanel) {
                Dimension pref = c.getPreferredSize();
                if (pref.width == 88 && pref.height == 88) {
                    foundLogoPanel = true;
                    c.setSize(88, 88);
                    BufferedImage img = new BufferedImage(88, 88, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g2 = img.createGraphics();
                    assertInstanceOf(RoundedImagePanel.class, c);
                    RoundedImagePanel rounded = (RoundedImagePanel) c;
                    assertDoesNotThrow(() -> rounded.paintComponent(g2));
                    BufferedImage firstCached = rounded.getCachedBuffer();
                    assertNotNull(
                            firstCached, "RoundedImagePanel should cache scaled BufferedImage");
                    assertDoesNotThrow(() -> rounded.paintComponent(g2));
                    assertSame(
                            firstCached,
                            rounded.getCachedBuffer(),
                            "RoundedImagePanel should reuse cached BufferedImage for same size");
                    c.setSize(64, 64);
                    assertDoesNotThrow(() -> rounded.paintComponent(g2));
                    assertNotSame(
                            firstCached,
                            rounded.getCachedBuffer(),
                            "RoundedImagePanel should recreate cached BufferedImage on resize");
                    g2.dispose();
                }
            } else if (c instanceof JLabel) {
                brandingLabels++;
                JLabel label = (JLabel) c;
                if ("RoxyCode".equals(label.getText())) {
                    foundTitleLabel = true;
                }
            }
        }

        assertTrue(foundLogoPanel, "Should have an 88x88 logo panel in branding header");
        assertNotNull(sidebar.getBorder(), "Sidebar should have a right separator border");
        assertTrue(foundTitleLabel, "Should have 'RoxyCode' title label in branding header");
        assertEquals(
                1,
                brandingLabels,
                "Branding header should only contain the RoxyCode title label (no username)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSidebarNavigation() {
        Consumer<String> navAction = mock(Consumer.class);

        SidebarPanel sidebar = new SidebarPanel(navAction);

        JButton qaBtn = null;
        JButton taskBtn = null;
        for (Component c : sidebar.getComponents()) {
            if (c instanceof JButton) {
                JButton b = (JButton) c;
                if ("Codebase Q&A".equals(b.getText())) qaBtn = b;
                if ("Feature Request".equals(b.getText())) taskBtn = b;
            }
        }

        assertNotNull(qaBtn, "QA Button should be found");
        assertNotNull(taskBtn, "Task Button should be found");
        assertInstanceOf(FontIcon.class, taskBtn.getIcon());
        assertEquals(Codicons.GIT_PULL_REQUEST, ((FontIcon) taskBtn.getIcon()).getIkon());

        assertTrue(qaBtn.isSelected(), "Codebase Q&A button should be selected on startup");
        assertFalse(
                taskBtn.isSelected(), "Feature Request button should not be selected on startup");

        qaBtn.doClick();
        verify(navAction).accept("QA_VIEW");

        taskBtn.doClick();
        verify(navAction).accept("TASK_VIEW");
    }

    @Test
    void testSetQaAndTaskBusyAndWaitingApprovalUpdatesSidebarButtons() {
        SidebarPanel sidebar = new SidebarPanel(card -> {});
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
