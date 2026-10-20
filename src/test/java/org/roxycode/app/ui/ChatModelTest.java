package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;

class ChatModelTest {

    @Test
    void testConfigurationAndResolveActivePhase() {
        ChatModel taskModel = new ChatModel("task", null, true);
        assertEquals("task", taskModel.getSessionPrefix());
        assertEquals("task:default", taskModel.getConversationId());
        assertNull(taskModel.getLockedPhase());
        assertTrue(taskModel.isTaskMode());
        assertEquals(WorkflowPhase.CODE, taskModel.resolveActivePhase(WorkflowPhase.CODE));

        ChatModel qaModel = new ChatModel("qa", WorkflowPhase.EXPLORE, false);
        assertEquals("qa:default", qaModel.getConversationId());
        assertEquals(WorkflowPhase.EXPLORE, qaModel.getLockedPhase());
        assertFalse(qaModel.isTaskMode());
        assertEquals(WorkflowPhase.EXPLORE, qaModel.resolveActivePhase(WorkflowPhase.CODE));
    }

    @Test
    void testPendingImagesAndPngEncoding() {
        ChatModel model = new ChatModel("task", null, true);
        assertFalse(model.hasPendingImages());

        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        BufferedImage added = model.addPendingImage(img);
        assertSame(img, added);
        assertTrue(model.hasPendingImages());
        assertEquals(1, model.getPendingImages().size());

        List<byte[]> encoded = model.drainPendingImagesAsPngBytes();
        assertEquals(1, encoded.size());
        assertTrue(encoded.get(0).length > 0);
        assertFalse(model.hasPendingImages());
    }

    @Test
    void testPostTurnActionQueue() {
        ChatModel model = new ChatModel("task", null, true);
        AtomicBoolean ran = new AtomicBoolean(false);
        model.enqueuePostTurnAction(() -> ran.set(true));
        assertEquals(1, model.getPendingPostTurnActions().size());

        List<Runnable> drained = model.drainPostTurnActions();
        assertEquals(1, drained.size());
        assertTrue(model.getPendingPostTurnActions().isEmpty());

        drained.get(0).run();
        assertTrue(ran.get());
    }

    @Test
    void testFormatTurnStatus() {
        ChatModel taskModel = new ChatModel("task", null, true);
        taskModel.setCurrentTurn(2);

        assertEquals("Turns: 2", taskModel.formatTurnStatus(null, null));

        AppSettings settings = new AppSettings();
        settings.setMaxAgentToolTurns(5);
        assertEquals("Turns: 2 / 5", taskModel.formatTurnStatus(settings, null));

        ImplementationPlan plan =
                new ImplementationPlan(
                        "Plan",
                        "Goal",
                        List.of("Req"),
                        List.of(
                                new ImplementationPlan.TechStep("S1", true),
                                new ImplementationPlan.TechStep("S2", false)),
                        true,
                        null,
                        null,
                        null,
                        List.of());
        assertEquals("Turns: 2 / 5 | Plan: 1/2 steps", taskModel.formatTurnStatus(settings, plan));

        ChatModel qaModel = new ChatModel("qa", WorkflowPhase.EXPLORE, false);
        qaModel.setCurrentTurn(1);
        assertEquals("Turns: 1 / 5", qaModel.formatTurnStatus(settings, plan));
    }
}
