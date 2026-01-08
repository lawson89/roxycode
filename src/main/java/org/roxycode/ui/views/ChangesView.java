package org.roxycode.ui.views;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.httprpc.sierra.Outlet;
import org.httprpc.sierra.UILoader;
import org.roxycode.core.RoxyProjectService;
import org.roxycode.core.tools.service.GitRunner;
import javax.swing.text.html.HTMLEditorKit;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.swing.FontIcon;
import org.roxycode.ui.ThemeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;

@Singleton
public class ChangesView extends JPanel {

    private static final Logger log = LoggerFactory.getLogger(ChangesView.class);

    private final RoxyProjectService roxyProjectService;
    private final ThemeService themeService;

    private final JTextPane changesArea = new JTextPane();

    @Outlet
    private JComponent viewChanges;

    @Outlet
    private JScrollPane changesScrollPane;

    @Inject
    public ChangesView(RoxyProjectService roxyProjectService, ThemeService themeService) {
        this.roxyProjectService = roxyProjectService;
        this.themeService = themeService;
        setLayout(new BorderLayout());
    }

    @PostConstruct
    public void init() {
        add(UILoader.load(this, "ChangesView.xml"));
        changesArea.setEditable(false);
        changesArea.setContentType("text/html");
        changesArea.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        
        HTMLEditorKit kit = new HTMLEditorKit();
        changesArea.setEditorKit(kit);
        
        if (changesScrollPane != null) {
            changesScrollPane.setViewportView(changesArea);
        }
        themeService.registerPane(changesArea);
        setupContextMenu();
        refresh();
    }

    public void refresh() {
        Path currentProjectRoot = roxyProjectService.getProjectRoot();
        if (currentProjectRoot != null) {
            populateChangesList(currentProjectRoot);
        }
    }

    private void populateChangesList(Path rootPath) {
        String statusOutput = GitRunner.runGitCommand(rootPath, "status", "--porcelain");
        StringBuilder sb = new StringBuilder();

        if (statusOutput != null && !statusOutput.isEmpty() && !statusOutput.startsWith("Error:")) {
            sb.append("<div style='font-family: monospace;'>");
            String[] lines = statusOutput.split("\\R");
            for (String line : lines) {
                if (line.length() < 4) continue;
                String rawStatus = line.substring(0, 2);
                String filePath = line.substring(3).trim();
                
                // Handle renames: "R  old -> new"
                if (filePath.contains(" -> ")) {
                    filePath = filePath.split(" -> ")[1];
                }

                String trimmedStatus = rawStatus.trim();
                String icon = switch (trimmedStatus) {
                    case "M" -> "📝";
                    case "A" -> "➕";
                    case "D" -> "❌";
                    case "R" -> "🚚";
                    case "??" -> "❓";
                    default -> "🔹";
                };

                                int lastSlash = filePath.lastIndexOf('/');
                String fileName;
                String parentDir;
                if (lastSlash != -1) {
                    fileName = filePath.substring(lastSlash + 1);
                    parentDir = filePath.substring(0, lastSlash);
                } else {
                    fileName = filePath;
                    parentDir = "";
                }

                sb.append(icon).append(" [").append(rawStatus).append("] ").append(fileName);
                if (!parentDir.isEmpty()) {
                    sb.append(" <span style='color: #888888;'>(").append(parentDir).append(")</span>");
                }
                sb.append("<br/>");
            }
            sb.append("</div>");
        } else if (statusOutput != null && statusOutput.startsWith("Error:")) {
            sb.append("⚠️ <b>Not a git repository</b>");
        } else {
            sb.append("✅ <b>No changes</b>");
        }
        
        changesArea.setText("<html><body style='font-family: sans-serif; padding: 10px;'>" + sb.toString() + "</body></html>");
    }

    private void setupContextMenu() {
        JPopupMenu popupMenu = new JPopupMenu();
        JMenuItem copyItem = new JMenuItem("Copy");
        copyItem.setIcon(FontIcon.of(MaterialDesignC.CONTENT_COPY, 16));
        copyItem.addActionListener(e -> changesArea.copy());
        popupMenu.add(copyItem);
        changesArea.setComponentPopupMenu(popupMenu);
    }

}
