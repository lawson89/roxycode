package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppIconRendererTest {

    @Test
    void testExtractMonogramVariants() {
        assertEquals("", AppIconRenderer.extractMonogram(null));
        assertEquals("", AppIconRenderer.extractMonogram(""));
        assertEquals("", AppIconRenderer.extractMonogram("   "));
        assertEquals("", AppIconRenderer.extractMonogram("No Project"));
        assertEquals("", AppIconRenderer.extractMonogram("Select Project..."));

        assertEquals("RC", AppIconRenderer.extractMonogram("roxy-code"));
        assertEquals("AS", AppIconRenderer.extractMonogram("auth_service"));
        assertEquals("MA", AppIconRenderer.extractMonogram("myApp"));
        assertEquals("RC", AppIconRenderer.extractMonogram("RoxyCode"));
        assertEquals("MP", AppIconRenderer.extractMonogram("My Project"));
        assertEquals("PO", AppIconRenderer.extractMonogram("portal"));
        assertEquals("X", AppIconRenderer.extractMonogram("x"));
        assertEquals("CL", AppIconRenderer.extractMonogram("c++-lang"));
    }

    @Test
    void testResolveProjectColorDeterminism() {
        Color c1 = AppIconRenderer.resolveProjectColor("roxy-code");
        Color c2 = AppIconRenderer.resolveProjectColor("roxy-code");
        Color c3 = AppIconRenderer.resolveProjectColor("ROXY-CODE");
        assertEquals(c1, c2);
        assertEquals(c1, c3);

        Color nullColor = AppIconRenderer.resolveProjectColor(null);
        assertNotNull(nullColor);
        assertTrue(AppIconRenderer.PROJECT_PALETTE.contains(nullColor));

        Color emptyColor = AppIconRenderer.resolveProjectColor("  ");
        assertNotNull(emptyColor);
        assertTrue(AppIconRenderer.PROJECT_PALETTE.contains(emptyColor));

        assertEquals(8, AppIconRenderer.PROJECT_PALETTE.size());

        Color roxyColor = AppIconRenderer.resolveProjectColor("roxycode");
        assertEquals(new Color(0x16, 0xa3, 0x4a), roxyColor);
        for (Color paletteColor : AppIconRenderer.PROJECT_PALETTE) {
            assertTrue(
                    paletteColor.getGreen() >= paletteColor.getRed() || paletteColor.getBlue() > paletteColor.getRed(),
                    "Palette color should avoid alarming red/orange hues: " + paletteColor);
        }
    }

    @Test
    void testRenderAppIconMultiResolutionAndStates() {
        List<Image> images = AppIconRenderer.renderAppIconImages("roxy-code", AppIconRenderer.AgentStatus.IDLE, false);
        assertEquals(4, images.size());

        for (Image img : images) {
            assertTrue(img instanceof BufferedImage);
            BufferedImage bimg = (BufferedImage) img;
            assertTrue(bimg.getWidth() > 0);
            assertTrue(bimg.getHeight() > 0);
            assertEquals(BufferedImage.TYPE_INT_ARGB, bimg.getType());
        }

        BufferedImage idleImg = AppIconRenderer.renderAppIcon(64, "myApp", AppIconRenderer.AgentStatus.IDLE, false);
        BufferedImage busyImg = AppIconRenderer.renderAppIcon(64, "myApp", AppIconRenderer.AgentStatus.BUSY, false);
        assertNotNull(busyImg);
        assertEquals(64, busyImg.getWidth());
        assertEquals(64, busyImg.getHeight());
        // BUSY does not paint a top-right dot; top-right corner matches IDLE
        assertEquals(idleImg.getRGB(56, 8), busyImg.getRGB(56, 8));

        BufferedImage waitingImg = AppIconRenderer.renderAppIcon(64, "myApp",
                AppIconRenderer.AgentStatus.WAITING_FEEDBACK, true);
        assertNotNull(waitingImg);

        BufferedImage noProjImg = AppIconRenderer.renderAppIcon(32, null, AppIconRenderer.AgentStatus.IDLE, false);
        assertNotNull(noProjImg);

        List<Image> cachedFirst = AppIconRenderer.renderAppIconImages("roxy-code", AppIconRenderer.AgentStatus.IDLE,
                false);
        List<Image> cachedSecond = AppIconRenderer.renderAppIconImages("roxy-code", AppIconRenderer.AgentStatus.IDLE,
                false);
        assertSame(cachedFirst, cachedSecond);
    }
}
