package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;

class ChatModelTest {

    @Test
    void testPendingImagesAndPngEncoding() {
        ChatModel model = new ChatModel("task", null, true);
        assertFalse(model.hasPendingImages());

        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        BufferedImage added = model.addPendingImage(img);
        assertSame(img, added);
        assertTrue(model.hasPendingImages());

        List<BufferedImage> snapshot = model.getPendingImages();
        assertEquals(1, snapshot.size());

        List<byte[]> encoded = ChatModel.encodeImagesToPngBytes(model.drainPendingImages());
        assertEquals(1, encoded.size());
        assertTrue(encoded.get(0).length > 0);
        assertFalse(model.hasPendingImages());
        // Snapshot taken before drain remains intact (defensive copy)
        assertEquals(1, snapshot.size());
    }

    @Test
    void testFormatTurnStatus() {
        ChatModel taskModel = new ChatModel("task", null, true);
        taskModel.setCurrentTurn(2);

        assertEquals("Turns: 2", taskModel.formatTurnStatus(null, null));

        AppSettings settings = new AppSettings();
        settings.setMaxAgentToolTurns(5);
        assertEquals("Turns: 2 / 5", taskModel.formatTurnStatus(settings, null));

        ImplementationPlan plan = new ImplementationPlan("Plan", "Goal", List.of("Req"),
                List.of(new ImplementationPlan.TechStep("S1", true), new ImplementationPlan.TechStep("S2", false)),
                true, null, null, null, null, List.of());
        assertEquals("Turns: 2 / 5 | Plan: 1/2 steps", taskModel.formatTurnStatus(settings, plan));

        ChatModel qaModel = new ChatModel("qa", WorkflowPhase.EXPLORE, false);
        qaModel.setCurrentTurn(1);
        assertEquals("Turns: 1 / 5", qaModel.formatTurnStatus(settings, plan));
    }
}
