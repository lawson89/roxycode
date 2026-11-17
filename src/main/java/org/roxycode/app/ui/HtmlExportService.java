package org.roxycode.app.ui;

import com.formdev.flatlaf.util.SystemFileChooser;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.yaml.front.matter.YamlFrontMatterExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.data.MutableDataHolder;
import com.vladsch.flexmark.util.data.MutableDataSet;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.swing.JOptionPane;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.model.ImplementationPlan;
import org.roxycode.app.util.Edt;
import org.roxycode.app.util.FrontmatterUtils;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates self-contained, light-theme HTML5 exports for Chat sessions, Implementation Plans, Archived Plans/Topics,
 * and standalone PlantUML/ditaa/SVG diagrams, with OS browser printing and file-save support.
 */
final class HtmlExportService {

    private static final Logger log = LoggerFactory.getLogger(HtmlExportService.class);
    private static final DateTimeFormatter EXPORT_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int ARCHIVE_TIMESTAMP_PREFIX_LEN = 19;
    private static final String HTML_EXTENSION = ".html";
    private static final String TEMP_HTML_PREFIX = "roxycode-export-";
    private static final String SPAN_CLOSE = "</span>";
    private static final String DIV_CLOSE = "</div>";

    @FunctionalInterface
    interface BrowserLauncher {
        void browse(Path htmlFile) throws IOException;
    }

    private static final BrowserLauncher DEFAULT_BROWSER_LAUNCHER = htmlFile -> {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw new IOException("Desktop browse action is not supported on this platform");
        }
        Desktop.getDesktop().browse(htmlFile.toUri());
    };

    private static volatile BrowserLauncher browserLauncher = DEFAULT_BROWSER_LAUNCHER;

    private static final DataHolder HTML5_OPTIONS = new MutableDataSet().set(HtmlRenderer.ESCAPE_HTML, true)
            .set(Parser.EXTENSIONS,
                    Arrays.asList(TablesExtension.create(), StrikethroughExtension.create(), TaskListExtension.create(),
                            YamlFrontMatterExtension.create(), new Html5SyntaxHighlightingExtension()))
            .toImmutable();

    private static final Parser HTML5_PARSER = Parser.builder(HTML5_OPTIONS).build();
    private static final HtmlRenderer HTML5_RENDERER = HtmlRenderer.builder(HTML5_OPTIONS).build();

    private static final String LIGHT_EXPORT_CSS = """
            * { box-sizing: border-box; }
            @page { margin: 14mm; }
            body {
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
                font-size: 14px;
                line-height: 1.55;
                color: #1f2937;
                background-color: #f8fafc;
                margin: 0;
                padding: 24px 16px 48px 16px;
            }
            .export-shell {
                max-width: 960px;
                margin: 0 auto;
                background-color: #ffffff;
                border: 1px solid #e2e8f0;
                border-radius: 8px;
                padding: 24px 32px;
                box-shadow: 0 1px 3px rgba(15, 23, 42, 0.06);
            }
            .export-toolbar {
                display: flex;
                align-items: center;
                justify-content: space-between;
                gap: 12px;
                padding-bottom: 16px;
                margin-bottom: 20px;
                border-bottom: 1px solid #e2e8f0;
            }
            .export-title-block h1 {
                margin: 0 0 4px 0;
                font-size: 20px;
                font-weight: 700;
                color: #0f172a;
            }
            .export-subtitle {
                font-size: 12px;
                color: #64748b;
            }
            .print-btn {
                appearance: none;
                border: 1px solid #0284c7;
                background-color: #0284c7;
                color: #ffffff;
                font-family: inherit;
                font-size: 13px;
                font-weight: 600;
                padding: 8px 14px;
                border-radius: 6px;
                cursor: pointer;
                white-space: nowrap;
            }
            .print-btn:hover { background-color: #0369a1; border-color: #0369a1; }
            .meta-card {
                background-color: #f8fafc;
                border: 1px solid #e2e8f0;
                border-radius: 6px;
                padding: 12px 16px;
                margin-bottom: 20px;
                font-size: 13px;
                color: #334155;
            }
            .meta-row { margin: 2px 0; }
            .meta-label { font-weight: 600; color: #0f172a; }
            h1, h2, h3, h4, h5, h6 {
                color: #0f172a;
                margin-top: 20px;
                margin-bottom: 8px;
                line-height: 1.3;
            }
            h1 { font-size: 20px; border-bottom: 1px solid #e2e8f0; padding-bottom: 6px; }
            h2 { font-size: 17px; color: #0369a1; }
            h3 { font-size: 15px; color: #047857; }
            p { margin: 8px 0; }
            ul, ol { margin: 8px 0; padding-left: 24px; }
            li { margin: 3px 0; }
            blockquote {
                margin: 10px 0;
                padding: 6px 14px;
                border-left: 3px solid #cbd5e1;
                color: #475569;
                background-color: #f8fafc;
            }
            a { color: #0284c7; text-decoration: none; }
            a:hover { text-decoration: underline; }
            hr { border: none; border-top: 1px solid #e2e8f0; margin: 18px 0; }
            code {
                font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
                font-size: 12.5px;
                background-color: #f1f5f9;
                color: #0f172a;
                padding: 1px 5px;
                border-radius: 4px;
            }
            pre.jexl-highlight {
                background-color: #f8fafc;
                color: #1e293b;
                border: 1px solid #cbd5e1;
                border-radius: 6px;
                padding: 12px 14px;
                margin: 10px 0;
                overflow-x: auto;
                white-space: pre-wrap;
                word-break: break-word;
            }
            pre.jexl-highlight code {
                background-color: transparent;
                padding: 0;
                font-size: 12.5px;
                line-height: 1.45;
            }
            .mtk-keyword { color: #cf222e; font-weight: 700; }
            .mtk-string { color: #0a3069; }
            .mtk-number { color: #0550ae; }
            .mtk-comment { color: #6e7781; font-style: italic; }
            table {
                border-collapse: collapse;
                width: 100%;
                margin: 12px 0;
                font-size: 13px;
            }
            th, td {
                border: 1px solid #cbd5e1;
                padding: 7px 10px;
                text-align: left;
                vertical-align: top;
            }
            th {
                background-color: #f1f5f9;
                font-weight: 600;
                color: #0f172a;
            }
            .chat-card {
                border: 1px solid #e2e8f0;
                border-left-width: 4px;
                border-radius: 6px;
                padding: 12px 16px;
                margin-bottom: 14px;
            }
            .card-user { background-color: #f0f7ff; border-color: #dbeafe; border-left-color: #0284c7; }
            .card-ai { background-color: #f8fafc; border-color: #e2e8f0; border-left-color: #10b981; }
            .card-system { background-color: #f8fafc; border-color: #e2e8f0; border-left-color: #94a3b8; }
            .chat-card-header {
                display: flex;
                align-items: center;
                justify-content: space-between;
                margin-bottom: 6px;
            }
            .role-badge {
                font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                font-size: 11px;
                font-weight: 700;
                letter-spacing: 0.04em;
            }
            .role-user { color: #0369a1; }
            .role-ai { color: #047857; }
            .role-system { color: #64748b; }
            .timestamp { font-size: 11px; color: #64748b; }
            .attachment-files {
                display: flex;
                flex-wrap: wrap;
                gap: 6px;
                margin: 6px 0 8px 0;
            }
            .file-badge {
                background-color: #e2e8f0;
                border: 1px solid #cbd5e1;
                color: #1e293b;
                font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                font-size: 11px;
                padding: 2px 7px;
                border-radius: 4px;
            }
            .attachment-thumbs {
                display: flex;
                flex-wrap: wrap;
                gap: 8px;
                margin: 6px 0 10px 0;
            }
            .attachment-thumbs img {
                max-width: 240px;
                max-height: 180px;
                border: 1px solid #cbd5e1;
                border-radius: 4px;
            }
            .tool-details {
                background-color: #f8fafc;
                border: 1px solid #e2e8f0;
                border-left: 4px solid #94a3b8;
                border-radius: 6px;
                padding: 8px 12px;
                margin-bottom: 10px;
            }
            .tool-details-error {
                background-color: #fef2f2;
                border-color: #fecaca;
                border-left-color: #ef4444;
            }
            .tool-details summary {
                cursor: pointer;
                font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                font-size: 12px;
                color: #334155;
                display: flex;
                align-items: center;
                justify-content: space-between;
                gap: 8px;
            }
            .tool-status {
                font-weight: 700;
                padding: 1px 6px;
                border-radius: 4px;
                font-size: 11px;
                margin-right: 6px;
            }
            .tool-status-ok { background-color: #dcfce7; color: #166534; }
            .tool-status-err { background-color: #fee2e2; color: #991b1b; }
            .tool-error-box {
                margin-top: 8px;
                padding: 8px 10px;
                background-color: #fee2e2;
                color: #991b1b;
                border-radius: 4px;
                font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                font-size: 12px;
                white-space: pre-wrap;
            }
            .diagram-export {
                background-color: #ffffff;
                border: 1px solid #cbd5e1;
                border-radius: 6px;
                padding: 12px;
                margin: 12px 0;
                text-align: center;
                overflow-x: auto;
            }
            .diagram-export svg,
            .diagram-export img {
                max-width: 100%;
                height: auto;
            }
            @media print {
                .no-print { display: none !important; }
                body {
                    background-color: #ffffff;
                    padding: 0;
                    -webkit-print-color-adjust: exact;
                    print-color-adjust: exact;
                }
                .export-shell {
                    max-width: none;
                    border: none;
                    box-shadow: none;
                    padding: 0;
                }
                .chat-card, .tool-details, .diagram-export, pre.jexl-highlight, table {
                    break-inside: avoid;
                }
            }
            """;

    private HtmlExportService() {
    }

    static void setBrowserLauncherForTesting(BrowserLauncher launcher) {
        if (launcher == null) {
            browserLauncher = DEFAULT_BROWSER_LAUNCHER;
        } else {
            browserLauncher = launcher;
        }
    }

    static String renderMarkdownToHtml5(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        return HTML5_RENDERER.render(HTML5_PARSER.parse(markdown));
    }

    static String buildChatExportHtml(String projectName, String workspaceTitle,
            List<MarkdownPane.PersistedChatEntry> entries) {
        String safeTitle = StringUtils.defaultIfBlank(workspaceTitle, "Chat Session");
        StringBuilder body = new StringBuilder();
        if (StringUtils.isNotBlank(projectName)) {
            body.append("<div class='meta-card'><div class='meta-row'><span class='meta-label'>Project:</span> ")
                    .append(SwingHtmlUtils.escapeHtml(projectName)).append(DIV_CLOSE).append(DIV_CLOSE);
        }
        if (entries != null) {
            for (MarkdownPane.PersistedChatEntry entry : entries) {
                if (entry == null) {
                    continue;
                }
                if (entry.isTool()) {
                    appendToolEntryHtml(body, entry);
                } else {
                    appendChatMessageHtml(body, entry);
                }
            }
        }
        String subtitle = buildExportSubtitle(projectName,
                "Exported " + LocalDateTime.now().format(EXPORT_TIME_FORMATTER));
        return wrapDocument(safeTitle, subtitle, body.toString());
    }

    private static void appendChatMessageHtml(StringBuilder body, MarkdownPane.PersistedChatEntry entry) {
        String role = StringUtils.defaultString(entry.role());
        String cardClass;
        String roleClass;
        String roleLabel;
        if (ChatHtmlTemplates.ROLE_USER.equalsIgnoreCase(role)) {
            cardClass = "card-user";
            roleClass = "role-user";
            roleLabel = "USER";
        } else if (ChatHtmlTemplates.ROLE_AI.equalsIgnoreCase(role)) {
            cardClass = "card-ai";
            roleClass = "role-ai";
            roleLabel = "ROXY";
        } else {
            cardClass = "card-system";
            roleClass = "role-system";
            roleLabel = StringUtils.defaultIfBlank(role.toUpperCase(Locale.ROOT), "SYSTEM");
        }

        body.append("<section class='chat-card ").append(cardClass).append("'>");
        body.append("<div class='chat-card-header'>");
        body.append("<span class='role-badge ").append(roleClass).append("'>")
                .append(SwingHtmlUtils.escapeHtml(roleLabel)).append(SPAN_CLOSE);
        if (StringUtils.isNotBlank(entry.timestamp())) {
            body.append("<span class='timestamp'>").append(SwingHtmlUtils.escapeHtml(entry.timestamp()))
                    .append(SPAN_CLOSE);
        }
        body.append(DIV_CLOSE);

        if (entry.attachedFileNames() != null && !entry.attachedFileNames().isEmpty()) {
            body.append("<div class='attachment-files'>");
            for (String fileName : entry.attachedFileNames()) {
                if (StringUtils.isNotBlank(fileName)) {
                    body.append("<span class='file-badge'>").append(SwingHtmlUtils.escapeHtml(fileName))
                            .append(SPAN_CLOSE);
                }
            }
            body.append(DIV_CLOSE);
        }

        if (entry.thumbnailBase64List() != null && !entry.thumbnailBase64List().isEmpty()) {
            body.append("<div class='attachment-thumbs'>");
            for (String b64 : entry.thumbnailBase64List()) {
                if (StringUtils.isNotBlank(b64)) {
                    body.append("<img src='data:image/png;base64,").append(SwingHtmlUtils.escapeHtml(b64))
                            .append("' alt='Attachment'>");
                }
            }
            body.append(DIV_CLOSE);
        }

        body.append("<div class='chat-card-body'>");
        body.append(renderMarkdownToHtml5(StringUtils.defaultString(entry.content())));
        body.append(DIV_CLOSE).append("</section>");
    }

    private static void appendToolEntryHtml(StringBuilder body, MarkdownPane.PersistedChatEntry entry) {
        String detailsClass = "tool-details";
        String statusClass = "tool-status-ok";
        String statusLabel = "OK";
        if (!entry.success()) {
            detailsClass = "tool-details tool-details-error";
            statusClass = "tool-status-err";
            statusLabel = "ERROR";
        }
        String summaryText = ChatHtmlTemplates.summarizeToolScript(StringUtils.defaultString(entry.content()));
        body.append("<details class='").append(detailsClass).append("'>");
        body.append("<summary>");
        body.append("<span><span class='tool-status ").append(statusClass).append("'>").append(statusLabel)
                .append(SPAN_CLOSE).append("JEXL: ").append(SwingHtmlUtils.escapeHtml(summaryText)).append(SPAN_CLOSE);
        if (StringUtils.isNotBlank(entry.timestamp())) {
            body.append("<span class='timestamp'>").append(SwingHtmlUtils.escapeHtml(entry.timestamp()))
                    .append(SPAN_CLOSE);
        }
        body.append("</summary>");
        body.append(JexlToHtmlConverter.highlight(StringUtils.defaultString(entry.content()), "javascript"));
        if (!entry.success() && StringUtils.isNotBlank(entry.error())) {
            body.append("<div class='tool-error-box'>").append(SwingHtmlUtils.escapeHtml(entry.error()))
                    .append(DIV_CLOSE);
        }
        body.append("</details>");
    }

    static String buildPlanExportHtml(String projectName, ImplementationPlan plan) {
        if (plan == null) {
            return wrapDocument("Implementation Plan", buildExportSubtitle(projectName, "No plan submitted"),
                    "<p>No implementation plan submitted yet.</p>");
        }
        String title = StringUtils.defaultIfBlank(plan.title(), "Implementation Plan");
        int totalSteps = 0;
        if (plan.technicalSteps() != null) {
            totalSteps = plan.technicalSteps().size();
        }
        StringBuilder meta = new StringBuilder("<div class='meta-card'>");
        if (StringUtils.isNotBlank(projectName)) {
            meta.append("<div class='meta-row'><span class='meta-label'>Project:</span> ")
                    .append(SwingHtmlUtils.escapeHtml(projectName)).append(DIV_CLOSE);
        }
        meta.append("<div class='meta-row'><span class='meta-label'>Progress:</span> ")
                .append(plan.completedStepCount()).append(" / ").append(totalSteps).append(" steps completed")
                .append(DIV_CLOSE);
        if (plan.userApproved() && StringUtils.isNotBlank(plan.approvedBy())) {
            meta.append("<div class='meta-row'><span class='meta-label'>Approved by:</span> ")
                    .append(SwingHtmlUtils.escapeHtml(plan.approvedBy()));
            if (StringUtils.isNotBlank(plan.approvalTimestamp())) {
                meta.append(" (").append(SwingHtmlUtils.escapeHtml(plan.approvalTimestamp())).append(")");
            }
            meta.append(DIV_CLOSE);
        }
        meta.append(DIV_CLOSE);

        String renderedBody = renderMarkdownToHtml5(plan.toFullMarkdown());
        String subtitle = buildExportSubtitle(projectName,
                plan.completedStepCount() + " / " + totalSteps + " steps completed");
        return wrapDocument(title, subtitle, meta + renderedBody);
    }

    static String buildArchiveExportHtml(String projectName, MemoryService.ArchiveEntrySummary entry,
            String rawMarkdown, ArchiveBrowserPanel.ArchiveMode mode) {
        if (entry == null) {
            return wrapDocument("Archive Export", buildExportSubtitle(projectName, "No entry selected"),
                    "<p>No archive entry selected.</p>");
        }
        String title = StringUtils.defaultIfBlank(entry.title(), entry.filename());
        String formattedDate = PathUtils.formatTimestampDisplay(entry.timestamp(), ARCHIVE_TIMESTAMP_PREFIX_LEN, "");
        String dateDisplay = StringUtils.defaultIfEmpty(formattedDate, entry.filename());
        String author = StringUtils.defaultIfBlank(entry.createdBy(), "User");

        StringBuilder meta = new StringBuilder("<div class='meta-card'>");
        if (StringUtils.isNotBlank(projectName)) {
            meta.append("<div class='meta-row'><span class='meta-label'>Project:</span> ")
                    .append(SwingHtmlUtils.escapeHtml(projectName)).append(DIV_CLOSE);
        }
        String dateLabel = "Date";
        if (mode != null && StringUtils.isNotBlank(mode.dateLabel)) {
            dateLabel = mode.dateLabel;
        }
        meta.append("<div class='meta-row'><span class='meta-label'>").append(SwingHtmlUtils.escapeHtml(dateLabel))
                .append(":</span> ").append(SwingHtmlUtils.escapeHtml(dateDisplay))
                .append(" &bull; <span class='meta-label'>By:</span> ").append(SwingHtmlUtils.escapeHtml(author));
        if (mode == ArchiveBrowserPanel.ArchiveMode.TOPICS && entry.messageCount() > 0) {
            meta.append(" &bull; <span class='meta-label'>Messages:</span> ").append(entry.messageCount());
        }
        meta.append(DIV_CLOSE);
        meta.append("<div class='meta-row'><span class='meta-label'>File:</span> <code>")
                .append(SwingHtmlUtils.escapeHtml(entry.filename())).append("</code>").append(DIV_CLOSE);
        meta.append(DIV_CLOSE);

        String stripped = FrontmatterUtils.stripFrontmatter(StringUtils.defaultString(rawMarkdown));
        String renderedBody = renderMarkdownToHtml5(stripped);
        return wrapDocument(title, buildExportSubtitle(projectName, dateDisplay), meta + renderedBody);
    }

    static Optional<String> buildDiagramExportHtml(String diagramId) {
        Optional<String> diagramBody = PlantUmlRenderer.renderDiagramExportHtmlById(diagramId);
        if (diagramBody.isEmpty()) {
            return Optional.empty();
        }
        String subtitle = "Standalone Vector / High-Resolution Diagram Export";
        return Optional.of(wrapDocument("Diagram Export", subtitle, diagramBody.get()));
    }

    static CompletableFuture<Path> openHtmlInBrowserAsync(String filePrefix, String htmlContent) {
        String safePrefix = StringUtils.defaultIfBlank(filePrefix, TEMP_HTML_PREFIX);
        String safeHtml = StringUtils.defaultString(htmlContent);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path tempFile = Files.createTempFile(safePrefix, HTML_EXTENSION);
                tempFile.toFile().deleteOnExit();
                Files.writeString(tempFile, safeHtml, StandardCharsets.UTF_8);
                browserLauncher.browse(tempFile);
                return tempFile;
            } catch (IOException e) {
                log.warn("Failed to open HTML export in browser: {}", e.getMessage());
                if (!GraphicsEnvironment.isHeadless()) {
                    showBrowserErrorNotice(e.getMessage());
                }
                return null;
            }
        }, Edt.VIRTUAL_EXECUTOR);
    }

    private static void showBrowserErrorNotice(String errorMessage) {
        org.roxycode.app.util.Edt.run(() -> JOptionPane.showMessageDialog(null,
                "Could not open HTML export in default browser: " + StringUtils.defaultString(errorMessage),
                "HTML Export Error", JOptionPane.ERROR_MESSAGE));
    }

    static Path saveHtmlWithChooser(Component parent, String suggestedFileName, String htmlContent) {
        String baseName = PathUtils.sanitizeSlug(suggestedFileName, 60, "roxycode-export");
        String defaultName = baseName + HTML_EXTENSION;
        SystemFileChooser chooser = new SystemFileChooser();
        chooser.setDialogTitle("Save as HTML");
        chooser.setSelectedFile(new File(defaultName));
        chooser.setFileFilter(
                new SystemFileChooser.FileNameExtensionFilter("HTML Files (*.html, *.htm)", "html", "htm"));
        if (chooser.showSaveDialog(parent) != SystemFileChooser.APPROVE_OPTION) {
            return null;
        }
        File selected = chooser.getSelectedFile();
        if (selected == null) {
            return null;
        }
        Path targetPath = selected.toPath();
        String lowerName = targetPath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".html") && !lowerName.endsWith(".htm")) {
            targetPath = targetPath.resolveSibling(targetPath.getFileName().toString() + HTML_EXTENSION);
        }
        try {
            Files.writeString(targetPath, StringUtils.defaultString(htmlContent), StandardCharsets.UTF_8);
            return targetPath;
        } catch (IOException e) {
            log.warn("Failed to save HTML export to {}: {}", targetPath, e.getMessage());
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(parent, "Failed to save HTML file: " + e.getMessage(), "Save HTML Error",
                        JOptionPane.ERROR_MESSAGE);
            }
            return null;
        }
    }

    static boolean handleDiagramHyperlink(String desc) {
        if (desc == null || !desc.startsWith(PlantUmlRenderer.DIAGRAM_OPEN_PREFIX)) {
            return false;
        }
        String diagramId = desc.substring(PlantUmlRenderer.DIAGRAM_OPEN_PREFIX.length()).trim();
        Optional<String> html = buildDiagramExportHtml(diagramId);
        if (html.isPresent()) {
            openHtmlInBrowserAsync("roxycode-diagram-", html.get());
            return true;
        }
        return false;
    }

    private static String wrapDocument(String title, String subtitleMeta, String bodyHtml) {
        String safeTitle = StringUtils.defaultIfBlank(title, "RoxyCode Export");
        String safeSubtitle = StringUtils.defaultString(subtitleMeta);
        String safeBody = StringUtils.defaultString(bodyHtml);
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>%s</title>
                <style>
                %s
                </style>
                </head>
                <body>
                <div class="export-shell">
                    <div class="export-toolbar no-print">
                        <div class="export-title-block">
                            <h1>%s</h1>
                            <div class="export-subtitle">%s</div>
                        </div>
                        <button type="button" class="print-btn" onclick="window.print()">Print / Save as PDF</button>
                    </div>
                    <main>
                %s
                    </main>
                </div>
                </body>
                </html>
                """.formatted(SwingHtmlUtils.escapeHtml(safeTitle), LIGHT_EXPORT_CSS,
                SwingHtmlUtils.escapeHtml(safeTitle), SwingHtmlUtils.escapeHtml(safeSubtitle), safeBody);
    }

    private static String buildExportSubtitle(String projectName, String detail) {
        if (StringUtils.isBlank(projectName)) {
            return StringUtils.defaultString(detail);
        }
        if (StringUtils.isBlank(detail)) {
            return projectName;
        }
        return projectName + " • " + detail;
    }

    private static final class Html5SyntaxHighlightingExtension implements HtmlRenderer.HtmlRendererExtension {
        @Override
        public void rendererOptions(MutableDataHolder options) {
        }

        @Override
        public void extend(HtmlRenderer.Builder htmlRendererBuilder, String rendererType) {
            htmlRendererBuilder.nodeRendererFactory(new SwingHtmlUtils.CodeBlockNodeRenderer.Factory(true));
        }
    }
}
