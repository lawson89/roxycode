package org.roxycode.app.ui;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.WorkflowPhase;
import org.roxycode.app.ai.services.FileReadService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ChatModel {
    private static final Logger log = LoggerFactory.getLogger(ChatModel.class);
    public static final String QA_SESSION_PREFIX = "qa";
    public static final String TASK_SESSION_PREFIX = "task";
    public static final String DEFAULT_CONVERSATION_SUFFIX = ":default";
    public static final String TASK_CONVERSATION_ID = PlanService.TASK_CONVERSATION_ID;
    static final int MAX_TEXT_ATTACHMENT_CHARS = 500_000;
    static final int TEXT_HEAD_CHARS = 100_000;
    static final int TEXT_TAIL_CHARS = 400_000;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "bmp");
    private static final String PDF_EXTENSION = "pdf";

    enum AttachmentType {
        IMAGE, PDF, TEXT
    }

    record PendingAttachment(AttachmentType type, String fileName, String filePath, BufferedImage image,
            byte[] pdfBytes, String textContent) {
    }

    private final String conversationId;
    private final WorkflowPhase lockedPhase;
    private final boolean taskMode;
    private final AtomicBoolean turnCancelled = new AtomicBoolean(false);
    private final List<PendingAttachment> pendingAttachments = new ArrayList<>();
    private volatile int currentTurn = 0;
    private volatile long lastPromptTokens = 0;
    private volatile long lastCachedInputTokens = 0;
    private volatile int lastContextWindowTokens = 1000000; // Default fallback

    public ChatModel(String sessionPrefix, WorkflowPhase lockedPhase, boolean taskMode) {
        this.conversationId = defaultConversationId(sessionPrefix);
        this.lockedPhase = lockedPhase;
        this.taskMode = taskMode;
    }

    public static String defaultConversationId(String sessionPrefix) {
        return sessionPrefix + DEFAULT_CONVERSATION_SUFFIX;
    }

    public String getConversationId() {
        return conversationId;
    }

    public boolean isTaskMode() {
        return taskMode;
    }

    public WorkflowPhase resolveActivePhase(WorkflowPhase workflowCurrentPhase) {
        if (lockedPhase != null) {
            return lockedPhase;
        }
        return workflowCurrentPhase;
    }

    public int getCurrentTurn() {
        return currentTurn;
    }

    public void setCurrentTurn(int currentTurn) {
        this.currentTurn = currentTurn;
    }

    public long getLastPromptTokens() {
        return lastPromptTokens;
    }

    public void setLastPromptTokens(long lastPromptTokens) {
        this.lastPromptTokens = lastPromptTokens;
    }

    public long getLastCachedInputTokens() {
        return lastCachedInputTokens;
    }

    public void setLastCachedInputTokens(long lastCachedInputTokens) {
        this.lastCachedInputTokens = lastCachedInputTokens;
    }

    public int getLastContextWindowTokens() {
        return lastContextWindowTokens;
    }

    public void setLastContextWindowTokens(int lastContextWindowTokens) {
        this.lastContextWindowTokens = lastContextWindowTokens;
    }

    public boolean isTurnCancelled() {
        return turnCancelled.get();
    }

    public void setTurnCancelled(boolean cancelled) {
        turnCancelled.set(cancelled);
    }

    public void addPendingAttachment(PendingAttachment attachment) {
        if (attachment == null) {
            return;
        }
        synchronized (pendingAttachments) {
            pendingAttachments.add(attachment);
        }
    }

    public boolean removePendingAttachment(PendingAttachment attachment) {
        if (attachment == null) {
            return false;
        }
        synchronized (pendingAttachments) {
            return pendingAttachments.remove(attachment);
        }
    }

    public boolean hasPendingAttachments() {
        synchronized (pendingAttachments) {
            return !pendingAttachments.isEmpty();
        }
    }

    public void clearPendingAttachments() {
        synchronized (pendingAttachments) {
            pendingAttachments.clear();
        }
    }

    public List<PendingAttachment> drainPendingAttachments() {
        synchronized (pendingAttachments) {
            List<PendingAttachment> snapshot = List.copyOf(pendingAttachments);
            pendingAttachments.clear();
            return snapshot;
        }
    }

    public BufferedImage addPendingImage(Image img) {
        BufferedImage bimage = toBufferedImage(img);
        addPendingAttachment(new PendingAttachment(AttachmentType.IMAGE, "pasted-image.png", "", bimage, null, null));
        return bimage;
    }

    private static BufferedImage toBufferedImage(Image img) {
        if (img instanceof BufferedImage buffered) {
            return buffered;
        }
        BufferedImage bimage = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB);
        Graphics2D bGr = bimage.createGraphics();
        bGr.drawImage(img, 0, 0, null);
        bGr.dispose();
        return bimage;
    }

    public boolean removePendingImage(BufferedImage image) {
        if (image == null) {
            return false;
        }
        synchronized (pendingAttachments) {
            for (int i = 0; i < pendingAttachments.size(); i++) {
                PendingAttachment att = pendingAttachments.get(i);
                if (att.type() == AttachmentType.IMAGE && Objects.equals(att.image(), image)) {
                    pendingAttachments.remove(i);
                    return true;
                }
            }
            return false;
        }
    }

    public boolean hasPendingImages() {
        synchronized (pendingAttachments) {
            for (PendingAttachment att : pendingAttachments) {
                if (att.type() == AttachmentType.IMAGE) {
                    return true;
                }
            }
            return false;
        }
    }

    public void clearPendingImages() {
        clearPendingAttachments();
    }

    public List<BufferedImage> drainPendingImages() {
        synchronized (pendingAttachments) {
            List<BufferedImage> images = new ArrayList<>();
            List<PendingAttachment> remaining = new ArrayList<>();
            for (PendingAttachment att : pendingAttachments) {
                if (att.type() == AttachmentType.IMAGE && att.image() != null) {
                    images.add(att.image());
                } else {
                    remaining.add(att);
                }
            }
            pendingAttachments.clear();
            pendingAttachments.addAll(remaining);
            return List.copyOf(images);
        }
    }

    public static PendingAttachment loadFileAttachment(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        String fileName = path.getFileName().toString();
        String filePath = path.toAbsolutePath().normalize().toString();
        String ext = PathUtils.getLowerExtension(fileName, false);
        try {
            if (IMAGE_EXTENSIONS.contains(ext)) {
                BufferedImage img = ImageIO.read(path.toFile());
                if (img == null) {
                    return null;
                }
                return new PendingAttachment(AttachmentType.IMAGE, fileName, filePath, img, null, null);
            }
            if (PDF_EXTENSION.equals(ext)) {
                byte[] pdfBytes = Files.readAllBytes(path);
                if (pdfBytes.length == 0) {
                    return null;
                }
                return new PendingAttachment(AttachmentType.PDF, fileName, filePath, null, pdfBytes, null);
            }
            if (FileReadService.isBinaryFile(path)) {
                log.warn("Skipping binary file attachment: {}", filePath);
                return null;
            }
            String rawText = Files.readString(path, StandardCharsets.UTF_8);
            String truncatedText = truncateTextAttachmentContent(rawText);
            return new PendingAttachment(AttachmentType.TEXT, fileName, filePath, null, null, truncatedText);
        } catch (Exception ex) {
            log.error("Failed to load file attachment: {}", filePath, ex);
            return null;
        }
    }

    static String truncateTextAttachmentContent(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= MAX_TEXT_ATTACHMENT_CHARS) {
            return content;
        }
        int omittedChars = content.length() - TEXT_HEAD_CHARS - TEXT_TAIL_CHARS;
        return content.substring(0, TEXT_HEAD_CHARS) + "\n\n... [truncated " + omittedChars
                + " characters from middle of file; showing first " + TEXT_HEAD_CHARS + " and last " + TEXT_TAIL_CHARS
                + " characters] ...\n\n" + content.substring(content.length() - TEXT_TAIL_CHARS);
    }

    public static String formatPromptWithTextAttachments(String userPrompt, List<PendingAttachment> attachments) {
        String basePrompt = StringUtils.defaultString(userPrompt);
        if (attachments == null || attachments.isEmpty()) {
            return basePrompt;
        }
        StringBuilder sb = new StringBuilder(basePrompt);
        for (PendingAttachment att : attachments) {
            if (att != null && att.type() == AttachmentType.TEXT) {
                sb.append("\n\n<attached_file name=").append('"').append(escapeXmlAttribute(att.fileName())).append('"')
                        .append(" path=").append('"').append(escapeXmlAttribute(att.filePath())).append('"')
                        .append(">\n").append(StringUtils.defaultString(att.textContent()))
                        .append("\n</attached_file>");
            }
        }
        return sb.toString();
    }

    private static String escapeXmlAttribute(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace(String.valueOf('"'), "&quot;").replace("<", "&lt;").replace(">",
                "&gt;");
    }

    public static List<BufferedImage> extractAttachedImages(List<PendingAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<BufferedImage> images = new ArrayList<>();
        for (PendingAttachment att : attachments) {
            if (att != null && att.type() == AttachmentType.IMAGE && att.image() != null) {
                images.add(att.image());
            }
        }
        return List.copyOf(images);
    }

    public static List<byte[]> encodeImageAttachments(List<PendingAttachment> attachments) {
        return encodeImagesToPngBytes(extractAttachedImages(attachments));
    }

    public static List<byte[]> encodePdfAttachments(List<PendingAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<byte[]> pdfs = new ArrayList<>();
        for (PendingAttachment att : attachments) {
            if (att != null && att.type() == AttachmentType.PDF && att.pdfBytes() != null
                    && att.pdfBytes().length > 0) {
                pdfs.add(att.pdfBytes());
            }
        }
        return List.copyOf(pdfs);
    }

    public static List<String> extractAttachedFileNames(List<PendingAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<String> fileNames = new ArrayList<>();
        for (PendingAttachment att : attachments) {
            if (att != null && att.type() != AttachmentType.IMAGE && StringUtils.isNotBlank(att.fileName())) {
                fileNames.add(att.fileName());
            }
        }
        return List.copyOf(fileNames);
    }

    public static List<byte[]> encodeImagesToPngBytes(List<BufferedImage> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        List<byte[]> imageBytes = new ArrayList<>(images.size());
        for (BufferedImage bimg : images) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(bimg, "png", baos);
                imageBytes.add(baos.toByteArray());
            } catch (Exception ex) {
                log.error("Failed to encode image", ex);
            }
        }
        return imageBytes;
    }

    public String formatTurnStatus(AppSettings settings, ImplementationPlan plan) {
        StringBuilder sb = new StringBuilder("Turn ").append(currentTurn);
        if (settings != null) {
            sb.append("/").append(settings.getMaxAgentToolTurns());
        }
        if (taskMode && plan != null && !plan.technicalSteps().isEmpty()) {
            sb.append(" • ").append(plan.completedStepCount()).append("/").append(plan.technicalSteps().size())
                    .append(" steps");
        }
        return sb.toString();
    }
}
