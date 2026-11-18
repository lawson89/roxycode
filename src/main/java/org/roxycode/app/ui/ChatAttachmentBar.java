package org.roxycode.app.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.SystemFileChooser;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Insets;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDragEvent;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.miginfocom.swing.MigLayout;
import org.apache.commons.lang3.StringUtils;
import org.kordamp.ikonli.codicons.Codicons;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.app.ui.ChatModel.AttachmentType;
import org.roxycode.app.ui.ChatModel.PendingAttachment;
import org.roxycode.app.util.Edt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages pending file/image/PDF attachment chips, async loading, and drag-and-drop ingestion for {@link ChatPanel}.
 */
final class ChatAttachmentBar extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(ChatAttachmentBar.class);
    static final int THUMBNAIL_SIZE = 64;

    private final transient ChatModel model;
    private final transient BooleanSupplier projectReadySupplier;
    private final AtomicLong attachmentLoadEpoch = new AtomicLong();

    ChatAttachmentBar(ChatModel model, BooleanSupplier projectReadySupplier) {
        super(new FlowLayout(FlowLayout.LEFT, 10, 5));
        this.model = model;
        this.projectReadySupplier = projectReadySupplier;
        setOpaque(false);
        setVisible(false);
    }

    void clearAttachments() {
        attachmentLoadEpoch.incrementAndGet();
        removeAll();
        setVisible(false);
    }

    void chooseAttachment(Component parent) {
        SystemFileChooser.FileNameExtensionFilter supportedFilter = new SystemFileChooser.FileNameExtensionFilter(
                "Images, PDFs, and Text/Log Files", "png", "jpg", "jpeg", "gif", "bmp", "pdf", "log", "jsonl", "txt",
                "md", "json", "toml", "xml", "csv", "yaml", "yml", "java", "kt", "py", "js", "ts", "sh");
        File selectedFile = UiComponents.chooseOpenFile(parent, "Attach File", supportedFilter);
        if (selectedFile != null) {
            loadAttachmentAsync(selectedFile);
        }
    }

    CompletableFuture<PendingAttachment> loadAttachmentAsync(File file) {
        if (file == null) {
            return CompletableFuture.completedFuture(null);
        }
        long expectedEpoch = attachmentLoadEpoch.get();
        return Edt.supplyAsync(() -> ChatModel.loadFileAttachment(file.toPath()), attachment -> {
            if (attachmentLoadEpoch.get() != expectedEpoch) {
                return;
            }
            if (attachment != null) {
                addPendingAttachment(attachment);
            }
        });
    }

    CompletableFuture<List<PendingAttachment>> loadAttachmentsAsync(List<File> files) {
        if (files == null || files.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        long expectedEpoch = attachmentLoadEpoch.get();
        List<File> snapshot = List.copyOf(files);
        return Edt.supplyAsync(() -> {
            List<PendingAttachment> loaded = new ArrayList<>();
            for (File file : snapshot) {
                if (file != null) {
                    PendingAttachment att = ChatModel.loadFileAttachment(file.toPath());
                    if (att != null) {
                        loaded.add(att);
                    }
                }
            }
            return List.copyOf(loaded);
        }, loaded -> {
            if (attachmentLoadEpoch.get() != expectedEpoch) {
                return;
            }
            for (PendingAttachment att : loaded) {
                addPendingAttachment(att);
            }
        });
    }

    void addPendingImage(Image img) {
        if (img == null) {
            return;
        }
        PendingAttachment attachment = model.addPendingImage(img);
        appendAttachmentChip(attachment);
    }

    void addPendingAttachment(PendingAttachment attachment) {
        if (attachment == null) {
            return;
        }
        model.addPendingAttachment(attachment);
        appendAttachmentChip(attachment);
    }

    private void appendAttachmentChip(PendingAttachment attachment) {
        if (attachment.type() == AttachmentType.IMAGE && attachment.image() != null) {
            add(createImageAttachmentChip(attachment, attachment.image()));
        } else {
            add(createFileAttachmentChip(attachment));
        }
        setVisible(true);
        revalidate();
        repaint();
    }

    private JPanel createImageAttachmentChip(PendingAttachment attachment, BufferedImage bimage) {
        JLabel thumbLabel = new JLabel(new ImageIcon(UiComponents.createThumbnail(bimage, THUMBNAIL_SIZE)));
        thumbLabel.setBorder(
                BorderFactory.createLineBorder(SwingHtmlUtils.getUiColor(Color.GRAY, "Component.borderColor")));

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.add(thumbLabel, BorderLayout.CENTER);
        wrapper.add(createAttachmentRemoveButton(attachment, wrapper), BorderLayout.NORTH);
        return wrapper;
    }

    private JButton createAttachmentRemoveButton(PendingAttachment attachment, JPanel wrapper) {
        JButton removeBtn = new JButton(FontIcon.of(Codicons.CLOSE, 12));
        removeBtn.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        removeBtn.setMargin(new Insets(0, 0, 0, 0));
        removeBtn.addActionListener(ae -> {
            model.removePendingAttachment(attachment);
            remove(wrapper);
            setVisible(model.hasPendingAttachments());
            revalidate();
            repaint();
        });
        return removeBtn;
    }

    private JPanel createFileAttachmentChip(PendingAttachment attachment) {
        Codicons icon;
        if (attachment.type() == AttachmentType.PDF) {
            icon = Codicons.FILE_PDF;
        } else {
            icon = Codicons.FILE_CODE;
        }
        JPanel wrapper = new JPanel(new MigLayout("insets 3 6 3 4", "[]4[]4[]", "center"));
        wrapper.setOpaque(false);
        wrapper.setBorder(
                BorderFactory.createLineBorder(SwingHtmlUtils.getUiColor(Color.GRAY, "Component.borderColor")));
        if (StringUtils.isNotBlank(attachment.filePath())) {
            wrapper.setToolTipText(attachment.filePath());
        }

        JLabel iconLabel = new JLabel(FontIcon.of(icon, 14));
        JLabel nameLabel = new JLabel(attachment.fileName());
        nameLabel.putClientProperty(FlatClientProperties.STYLE, "font: -1");

        wrapper.add(iconLabel);
        wrapper.add(nameLabel);
        wrapper.add(createAttachmentRemoveButton(attachment, wrapper));
        return wrapper;
    }

    void installDragAndDrop(Component... targets) {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        for (Component target : targets) {
            installDropTarget(target);
        }
    }

    private void installDropTarget(Component component) {
        if (component == null) {
            return;
        }
        new DropTarget(component, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            public void dragEnter(DropTargetDragEvent dtde) {
                updateDragStatus(dtde);
            }

            @Override
            public void dragOver(DropTargetDragEvent dtde) {
                updateDragStatus(dtde);
            }

            @Override
            public void drop(DropTargetDropEvent dtde) {
                handleDrop(dtde);
            }
        });
    }

    private void updateDragStatus(DropTargetDragEvent dtde) {
        if (!projectReadySupplier.getAsBoolean()) {
            dtde.rejectDrag();
            return;
        }
        if (dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                || dtde.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            dtde.acceptDrag(DnDConstants.ACTION_COPY);
        } else {
            dtde.rejectDrag();
        }
    }

    void handleDrop(DropTargetDropEvent dtde) {
        if (dtde == null || !projectReadySupplier.getAsBoolean()) {
            if (dtde != null) {
                dtde.rejectDrop();
            }
            return;
        }
        if (!dtde.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                && !dtde.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            dtde.rejectDrop();
            return;
        }
        dtde.acceptDrop(DnDConstants.ACTION_COPY);
        boolean success = ingestTransferable(dtde.getTransferable());
        dtde.dropComplete(success);
    }

    boolean ingestTransferable(Transferable transferable) {
        if (transferable == null) {
            return false;
        }
        try {
            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                Object data = transferable.getTransferData(DataFlavor.javaFileListFlavor);
                if (data instanceof List<?> rawList) {
                    List<File> files = new ArrayList<>();
                    for (Object item : rawList) {
                        if (item instanceof File file) {
                            files.add(file);
                        } else if (item instanceof Path path) {
                            files.add(path.toFile());
                        }
                    }
                    if (!files.isEmpty()) {
                        loadAttachmentsAsync(files);
                        return true;
                    }
                }
            } else if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                Object data = transferable.getTransferData(DataFlavor.imageFlavor);
                if (data instanceof Image img) {
                    addPendingImage(img);
                    return true;
                }
            }
        } catch (Exception ex) {
            log.error("Failed to ingest dropped attachment", ex);
        }
        return false;
    }
}
