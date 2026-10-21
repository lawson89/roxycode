package org.roxycode.app.ui;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Model encapsulating the state and configuration of a chat session. */
public final class ChatModel {
    private static final Logger log = LoggerFactory.getLogger(ChatModel.class);
    public static final String QA_SESSION_PREFIX = "qa";
    public static final String TASK_SESSION_PREFIX = "task";
    public static final String DEFAULT_CONVERSATION_SUFFIX = ":default";
    public static final String QA_CONVERSATION_ID = QA_SESSION_PREFIX + DEFAULT_CONVERSATION_SUFFIX;
    public static final String TASK_CONVERSATION_ID =
            TASK_SESSION_PREFIX + DEFAULT_CONVERSATION_SUFFIX;

    private final String sessionPrefix;
    private final String conversationId;
    private final WorkflowPhase lockedPhase;
    private final boolean taskMode;

    private final AtomicBoolean turnCancelled = new AtomicBoolean(false);
    private final List<BufferedImage> pendingImages = new ArrayList<>();

    private int currentTurn = 0;
    private boolean loading = false;

    public ChatModel(String sessionPrefix, WorkflowPhase lockedPhase, boolean taskMode) {
        this.sessionPrefix = sessionPrefix;
        this.conversationId = defaultConversationId(sessionPrefix);
        this.lockedPhase = lockedPhase;
        this.taskMode = taskMode;
    }

    public static String defaultConversationId(String sessionPrefix) {
        return sessionPrefix + DEFAULT_CONVERSATION_SUFFIX;
    }

    public String getSessionPrefix() {
        return sessionPrefix;
    }

    public String getConversationId() {
        return conversationId;
    }

    public WorkflowPhase getLockedPhase() {
        return lockedPhase;
    }

    public boolean isTaskMode() {
        return taskMode;
    }

    public WorkflowPhase resolveActivePhase(WorkflowPhase workflowCurrentPhase) {
        return lockedPhase != null ? lockedPhase : workflowCurrentPhase;
    }

    public int getCurrentTurn() {
        return currentTurn;
    }

    public void setCurrentTurn(int currentTurn) {
        this.currentTurn = currentTurn;
    }

    public boolean isLoading() {
        return loading;
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
    }

    public boolean isTurnCancelled() {
        return turnCancelled.get();
    }

    public void setTurnCancelled(boolean cancelled) {
        turnCancelled.set(cancelled);
    }

    public BufferedImage addPendingImage(Image img) {
        BufferedImage bimage;
        if (img instanceof BufferedImage buffered) {
            bimage = buffered;
        } else {
            bimage =
                    new BufferedImage(
                            img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
            Graphics2D bGr = bimage.createGraphics();
            bGr.drawImage(img, 0, 0, null);
            bGr.dispose();
        }
        pendingImages.add(bimage);
        return bimage;
    }

    public boolean removePendingImage(BufferedImage image) {
        return pendingImages.remove(image);
    }

    public List<BufferedImage> getPendingImages() {
        return Collections.unmodifiableList(pendingImages);
    }

    public boolean hasPendingImages() {
        return !pendingImages.isEmpty();
    }

    public void clearPendingImages() {
        pendingImages.clear();
    }

    public List<byte[]> drainPendingImagesAsPngBytes() {
        List<byte[]> imageBytes = new ArrayList<>();
        for (BufferedImage bimg : pendingImages) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(bimg, "png", baos);
                imageBytes.add(baos.toByteArray());
            } catch (Exception ex) {
                log.error("Failed to encode image", ex);
            }
        }
        pendingImages.clear();
        return imageBytes;
    }

    public String formatTurnStatus(AppSettings settings, ImplementationPlan plan) {
        StringBuilder sb = new StringBuilder("Turns: ").append(currentTurn);
        if (settings != null) {
            int maxTurns = settings.getMaxAgentToolTurns();
            sb.append(" / ").append(maxTurns);
        }
        if (taskMode && plan != null && !plan.technicalSteps().isEmpty()) {
            int completed = 0;
            for (ImplementationPlan.TechStep step : plan.technicalSteps()) {
                if (step.completed()) {
                    completed++;
                }
            }
            int total = plan.technicalSteps().size();
            sb.append(" | Plan: ").append(completed).append("/").append(total).append(" steps");
        }
        return sb.toString();
    }
}
