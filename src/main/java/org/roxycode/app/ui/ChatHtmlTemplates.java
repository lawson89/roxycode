package org.roxycode.app.ui;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.roxycode.app.model.ChatTranscriptEntry;

public final class ChatHtmlTemplates {

    public static final String ROLE_AI = ChatTranscriptEntry.ROLE_AI;
    public static final String ROLE_USER = ChatTranscriptEntry.ROLE_USER;
    public static final String ROLE_SYSTEM = ChatTranscriptEntry.ROLE_SYSTEM;
    private static final String CAT_EARS = "/\\_/\\";
    public static final String COPY_CELL_ID_PREFIX = "copy-cell-";
    public static final String TOOL_BODY_CELL_ID_PREFIX = "tool-body-cell-";
    public static final int AUTO_COLLAPSE_LINE_THRESHOLD = 4;
    private static final int SINGLE_LINE_COUNT = 1;
    private static final int MAX_SUMMARY_CALLS = 3;
    private static final int MAX_SUMMARY_LINE_LENGTH = 80;
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern SERVICE_CALL_PATTERN = Pattern
            .compile("([a-zA-Z_][a-zA-Z0-9_]*Service[.][a-zA-Z_][a-zA-Z0-9_]*)[ 	]*[(]");
    private static final Pattern MOVE_TO_FEATURE_MENTION_PATTERN = Pattern.compile(
            "(?:<strong>|<b>|<code>)?" + Pattern.quote("[Move to Feature Request]") + "(?:</code>|</b>|</strong>)?");
    private static final Pattern APPLY_BUILD_TOML_MENTION_PATTERN = Pattern.compile(
            "(?:<strong>|<b>|<code>)?" + Pattern.quote("[Apply to build.toml]") + "(?:</code>|</b>|</strong>)?");

    private ChatHtmlTemplates() {
    }

    public static String renderMoveToActionHtml(int messageIndex, boolean moved) {
        if (moved) {
            return "<span class='copy-feedback'>&#10003; Sent to Feature Development</span>";
        }
        return "<a class='action-cta-link' href='feature:" + messageIndex + "'>&#8599; Move to Feature Request</a>";
    }

    static String linkifyMoveToFeatureMentions(String renderedHtml, int messageIndex) {
        return linkifyActionMentions(renderedHtml, messageIndex, "[Move to Feature Request]",
                MOVE_TO_FEATURE_MENTION_PATTERN, renderMoveToActionHtml(messageIndex, false));
    }

    public static String renderApplyBuildTomlActionHtml(int messageIndex, boolean applied) {
        if (applied) {
            return "<span class='copy-feedback'>&#10003; Applied to build.toml</span>";
        }
        return "<a class='action-cta-link' href='buildtoml:" + messageIndex + "'>&#9881; Apply to build.toml</a>";
    }

    static String linkifyApplyBuildTomlMentions(String renderedHtml, int messageIndex) {
        return linkifyActionMentions(renderedHtml, messageIndex, "[Apply to build.toml]",
                APPLY_BUILD_TOML_MENTION_PATTERN, renderApplyBuildTomlActionHtml(messageIndex, false));
    }

    private static String linkifyActionMentions(String renderedHtml, int messageIndex, String literalMarker,
            Pattern pattern, String linkHtml) {
        if (StringUtils.isBlank(renderedHtml) || messageIndex < 0 || !renderedHtml.contains(literalMarker)) {
            return StringUtils.defaultString(renderedHtml);
        }
        return pattern.matcher(renderedHtml).replaceAll(Matcher.quoteReplacement(linkHtml));
    }

    public static String renderCopyActionHtml(int messageIndex, boolean copied) {
        if (copied) {
            return "<span class='copy-feedback'>&#10003; Copied!</span>";
        }
        return "<a class='copy-action' href='copy:" + messageIndex + "'>Copy</a>";
    }

    public static String renderMessageActionCellContent(String role, int messageIndex, boolean copied,
            boolean showMoveAction, boolean movedToFeature, boolean showApplyBuildTomlAction,
            boolean appliedToBuildToml) {
        String normalizedRole = StringUtils.defaultString(role).toLowerCase(java.util.Locale.ROOT);
        StringBuilder actions = new StringBuilder();
        if (ROLE_AI.equals(normalizedRole) && showApplyBuildTomlAction) {
            actions.append(renderApplyBuildTomlActionHtml(messageIndex, appliedToBuildToml)).append("&nbsp;&nbsp;");
        }
        if (ROLE_AI.equals(normalizedRole) && showMoveAction) {
            actions.append(renderMoveToActionHtml(messageIndex, movedToFeature)).append("&nbsp;&nbsp;");
        }
        actions.append(renderCopyActionHtml(messageIndex, copied));
        return actions.toString();
    }

    public static String renderUserAttachmentsHtml(List<String> thumbnailBase64List) {
        return renderUserAttachmentsHtml(thumbnailBase64List, List.of());
    }

    public static String renderUserAttachmentsHtml(List<String> thumbnailBase64List, List<String> attachedFileNames) {
        boolean hasThumbs = thumbnailBase64List != null && !thumbnailBase64List.isEmpty();
        boolean hasFiles = attachedFileNames != null && !attachedFileNames.isEmpty();
        if (!hasThumbs && !hasFiles) {
            return "";
        }
        StringBuilder cells = new StringBuilder();
        if (hasThumbs) {
            for (String base64Png : thumbnailBase64List) {
                UiComponents.HtmlImagePreview preview = UiComponents.writeBase64ThumbnailToTempUrl(base64Png);
                if (preview != null) {
                    cells.append("<td valign='middle' style='padding-right: 8px; padding-bottom: 6px;'>")
                            .append("<img src='").append(SwingHtmlUtils.escapeHtml(preview.url())).append("' width='")
                            .append(preview.width()).append("' height='").append(preview.height()).append("'></td>");
                }
            }
        }
        if (hasFiles) {
            for (String fileName : attachedFileNames) {
                if (StringUtils.isBlank(fileName)) {
                    continue;
                }
                String prefix;
                if (fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
                    prefix = "[PDF] ";
                } else {
                    prefix = "[FILE] ";
                }
                cells.append("<td valign='middle' style='padding-right: 6px; padding-bottom: 6px;'>")
                        .append("<span class='attachment-file-badge'>")
                        .append(SwingHtmlUtils.escapeHtml(prefix + fileName)).append("</span></td>");
            }
        }
        if (cells.isEmpty()) {
            return "";
        }
        return "<table border='0' cellpadding='0' cellspacing='0' style='margin-top: 6px; margin-bottom: 2px;'><tr>"
                + cells + "</tr></table>";
    }

    private static String unwrapSingleParagraph(String html) {
        String trimmed = StringUtils.defaultString(html).trim();
        if (trimmed.startsWith("<p>") && trimmed.endsWith("</p>") && trimmed.indexOf("<p>", 3) < 0) {
            return trimmed.substring(3, trimmed.length() - 4).trim();
        }
        return trimmed;
    }

    public static String renderChatEntry(String role, String content, String timestamp, int messageIndex,
            boolean copied, boolean showMoveAction, boolean movedToFeature, boolean showApplyBuildTomlAction,
            boolean appliedToBuildToml, List<String> thumbnailBase64List) {
        return renderChatEntry(role, content, timestamp, messageIndex, copied, showMoveAction, movedToFeature,
                showApplyBuildTomlAction, appliedToBuildToml, thumbnailBase64List, List.of());
    }

    public static String renderChatEntry(String role, String content, String timestamp, int messageIndex,
            boolean copied, boolean showMoveAction, boolean movedToFeature, boolean showApplyBuildTomlAction,
            boolean appliedToBuildToml, List<String> thumbnailBase64List, List<String> attachedFileNames) {
        String normalizedRole = StringUtils.defaultString(role).toLowerCase(java.util.Locale.ROOT);
        if (ROLE_SYSTEM.equalsIgnoreCase(normalizedRole)) {
            String inlineContent = unwrapSingleParagraph(content);
            String safeTime = SwingHtmlUtils.escapeHtml(StringUtils.defaultString(timestamp));
            String timeSpan = "";
            if (!safeTime.isBlank()) {
                timeSpan = "&nbsp;&nbsp;<span class='timestamp'>" + safeTime + "</span>";
            }
            return "<div class='system-notice'>" + inlineContent + timeSpan + "</div>";
        }
        String safeRole = SwingHtmlUtils.escapeHtml(normalizedRole);
        String indicator = switch (normalizedRole) {
            case ROLE_AI -> "<span class='role-ai'>ROXY " + CAT_EARS + "</span>";
            case ROLE_USER -> "<span class='role-user'>USER</span>";
            default -> "&nbsp;";
        };
        String actionCell = "";
        if ((ROLE_AI.equals(normalizedRole) || ROLE_USER.equals(normalizedRole)) && messageIndex >= 0) {
            actionCell = "<td id='" + COPY_CELL_ID_PREFIX + messageIndex
                    + "' align='right' valign='middle' nowrap='nowrap'>"
                    + renderMessageActionCellContent(normalizedRole, messageIndex, copied, showMoveAction,
                            movedToFeature, showApplyBuildTomlAction, appliedToBuildToml)
                    + "</td>";
        }
        String attachmentsHtml = "";
        if (ROLE_USER.equals(normalizedRole)) {
            attachmentsHtml = renderUserAttachmentsHtml(thumbnailBase64List, attachedFileNames);
        }
        String renderedBody = StringUtils.defaultString(content);
        if (ROLE_AI.equals(normalizedRole) && showMoveAction && messageIndex >= 0) {
            renderedBody = linkifyMoveToFeatureMentions(renderedBody, messageIndex);
        }
        if (ROLE_AI.equals(normalizedRole) && showApplyBuildTomlAction && messageIndex >= 0) {
            renderedBody = linkifyApplyBuildTomlMentions(renderedBody, messageIndex);
        }

        return """
                <table class="%s-entry" width="100%%" cellpadding="0" cellspacing="0" border="0">
                    <tr>
                        <td class="%s-accent" width="4" nowrap="nowrap">&nbsp;</td>
                        <td class="message-body" width="100%%" style="padding: 10px 14px;">
                            <table width="100%%" cellpadding="0" cellspacing="0" border="0">
                                <tr>
                                    <td align="left" valign="middle">%s&nbsp;&nbsp;<span class="timestamp">%s</span></td>
                                    %s
                                </tr>
                            </table>
                            %s<div class="content" style="margin-top: 4px;">%s</div>
                        </td>
                    </tr>
                </table>
                <table width="100%%" cellpadding="0" cellspacing="0" border="0">
                    <tr><td height="8">&nbsp;</td></tr>
                </table>
                """
                .formatted(safeRole, safeRole, indicator,
                        SwingHtmlUtils.escapeHtml(StringUtils.defaultString(timestamp)), actionCell, attachmentsHtml,
                        renderedBody);
    }

    public static int countToolScriptLines(String script) {
        if (script == null || script.isEmpty()) {
            return 0;
        }
        return (int) script.lines().count();
    }

    public static boolean shouldDefaultToolExpanded(String script, boolean success) {
        if (!success) {
            return true;
        }
        return countToolScriptLines(script) <= AUTO_COLLAPSE_LINE_THRESHOLD;
    }

    public static String summarizeToolScript(String script) {
        if (script == null || script.isBlank()) {
            return "Empty script (0 lines)";
        }
        String plain = stripHtmlTagsAndUnescape(script);
        int lineCount = countToolScriptLines(plain);
        Set<String> distinctCalls = new LinkedHashSet<>();
        Matcher matcher = SERVICE_CALL_PATTERN.matcher(plain);
        while (matcher.find()) {
            distinctCalls.add(matcher.group(1));
        }
        String baseSummary;
        if (!distinctCalls.isEmpty()) {
            List<String> callList = new ArrayList<>(distinctCalls);
            int limit = Math.min(MAX_SUMMARY_CALLS, callList.size());
            baseSummary = String.join(", ", callList.subList(0, limit));
            if (callList.size() > MAX_SUMMARY_CALLS) {
                baseSummary = baseSummary + " (+" + (callList.size() - MAX_SUMMARY_CALLS) + " more)";
            }
        } else {
            String firstNonBlank = "";
            for (String line : plain.lines().toList()) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    firstNonBlank = trimmed;
                    if (!trimmed.startsWith("//") && !trimmed.startsWith("#")) {
                        break;
                    }
                }
            }
            baseSummary = StringUtils.abbreviate(firstNonBlank, MAX_SUMMARY_LINE_LENGTH);
        }
        String lineSuffix;
        if (lineCount == SINGLE_LINE_COUNT) {
            lineSuffix = " (1 line)";
        } else {
            lineSuffix = " (" + lineCount + " lines)";
        }
        return baseSummary + lineSuffix;
    }

    private static String stripHtmlTagsAndUnescape(String input) {
        String withoutTags = HTML_TAG_PATTERN.matcher(input).replaceAll("");
        return StringEscapeUtils.unescapeHtml4(withoutTags);
    }

    public static String renderToolLog(String output, boolean success, String error, String timestamp, int messageIndex,
            boolean expanded) {
        String tableClass;
        String accentClass;
        if (success) {
            tableClass = "tool-call";
            accentClass = "tool-accent";
        } else {
            tableClass = "tool-call-error";
            accentClass = "tool-accent-error";
        }
        String idAttr = "";
        if (messageIndex >= 0) {
            idAttr = " id='" + TOOL_BODY_CELL_ID_PREFIX + messageIndex + "'";
        }
        String bodyCellContent = renderToolBodyCellContent(output, success, error, timestamp, messageIndex, expanded);
        return """
                <table class="%s" width="100%%" cellpadding="0" cellspacing="0" border="0">
                    <tr>
                        <td class="%s" width="4" nowrap="nowrap">&nbsp;</td>
                        <td%s class="tool-body-container" width="100%%" style="padding: 6px 12px 8px 12px;">
                            %s
                        </td>
                    </tr>
                </table>
                <table width="100%%" cellpadding="0" cellspacing="0" border="0">
                    <tr><td height="8">&nbsp;</td></tr>
                </table>
                """.formatted(tableClass, accentClass, idAttr, bodyCellContent);
    }

    public static String renderToolBodyCellContent(String output, boolean success, String error, String timestamp,
            int messageIndex, boolean expanded) {
        String safeOutput = StringUtils.defaultString(output);
        String statusHtml;
        if (success) {
            statusHtml = "<span class='tool-result'>&#10003; Executed successfully</span>";
        } else {
            String errorText;
            if (error == null || error.trim().isEmpty()) {
                errorText = "Unknown error";
            } else {
                errorText = SwingHtmlUtils.escapeHtml(error);
            }
            statusHtml = "<span class='tool-error'>&#10007; Error: " + errorText + "</span>";
        }
        int lineCount = Math.max(1, countToolScriptLines(safeOutput));
        String toggleLinkHtml = "";
        if (messageIndex >= 0) {
            String toggleLabel;
            if (expanded) {
                toggleLabel = "&#9662; Hide";
            } else {
                toggleLabel = "&#9656; Show " + lineCount + " lines";
            }
            toggleLinkHtml = "<a class='tool-toggle' href='toggle-tool:" + messageIndex + "'>" + toggleLabel
                    + "</a>&nbsp;&nbsp;";
        }
        String bodyHtml;
        if (expanded) {
            bodyHtml = "<pre class='tool-body'>" + safeOutput + "</pre>";
        } else {
            bodyHtml = "<div class='tool-summary'>" + SwingHtmlUtils.escapeHtml(summarizeToolScript(safeOutput))
                    + "</div>";
        }
        return """
                <table width="100%%" cellpadding="0" cellspacing="0" border="0">
                    <tr>
                        <td align="left" valign="middle"><span class="role-tool">&#9889; JEXL TOOL</span>&nbsp;&nbsp;<span class="timestamp">%s</span></td>
                        <td align="right" valign="middle">%s%s</td>
                    </tr>
                </table>
                %s
                """
                .formatted(SwingHtmlUtils.escapeHtml(StringUtils.defaultString(timestamp)), toggleLinkHtml, statusHtml,
                        bodyHtml);
    }

    private static String renderStarterRow(String leftCard, String rightCard) {
        return "<tr>" + leftCard + rightCard + "</tr>";
    }

    private static String renderStarterCard(String accentVariant, String badgeText, String title, String description,
            String promptText, String linkLabel) {
        String encodedPrompt = URLEncoder.encode(promptText, StandardCharsets.UTF_8);
        return """
                <td width="50%%" valign="top">
                    <table class="starter-card" width="100%%" cellpadding="0" cellspacing="0" border="0">
                        <tr>
                            <td class="starter-accent-%s" width="4" nowrap="nowrap">&nbsp;</td>
                            <td width="100%%" style="padding: 10px 12px;" valign="top">
                                <div><span class="starter-badge-%s">&nbsp;%s&nbsp;</span>&nbsp;&nbsp;<a class="starter-title-link" href="prompt:%s">%s</a></div>
                                <div class="starter-desc">%s</div>
                                <table cellpadding="0" cellspacing="0" border="0" style="margin-top: 6px;">
                                    <tr>
                                        <td class="starter-cta-pill-%s" style="padding: 3px 8px;">
                                            <a class="starter-link-%s" href="prompt:%s">&#9656; %s</a>
                                        </td>
                                    </tr>
                                </table>
                            </td>
                        </tr>
                    </table>
                </td>
                """
                .formatted(accentVariant, accentVariant, badgeText, encodedPrompt, title, description, accentVariant,
                        accentVariant, encodedPrompt, linkLabel);
    }

    public static String renderWelcomeCard(boolean isTaskMode) {
        if (isTaskMode) {
            String row1 = renderStarterRow(
                    renderStarterCard("blue", "FEATURE", "Build a New Feature",
                            "Plan, implement, and verify new end-to-end functionality",
                            "I want to implement a new feature: ", "Draft a feature implementation plan"),
                    renderStarterCard("purple", "BUGFIX", "Fix a Bug or Regression",
                            "Diagnose root cause, fix the defect, and add regression tests",
                            "Help me diagnose and fix a bug in: ", "Diagnose and fix a bug"));
            String row2 = renderStarterRow(
                    renderStarterCard("amber", "REFACTOR", "Refactor &amp; Clean Up Code",
                            "Eliminate dead, duplicated, or overly complex code",
                            "Identify and clean up dead, duplicated, or overly complex code in: ",
                            "Identify cleanup opportunities"),
                    renderStarterCard("emerald", "TESTING", "Add Unit Test Coverage",
                            "Add pragmatic unit tests for business logic and edge cases",
                            "Add pragmatic unit tests for the business logic and edge cases in: ",
                            "Add pragmatic unit tests"));
            return """
                    <table class="welcome-card" width="100%%" cellpadding="0" cellspacing="0" border="0">
                        <tr>
                            <td class="ai-accent" width="4" nowrap="nowrap">&nbsp;</td>
                            <td width="100%%" style="padding: 10px 14px;">
                                <div class="welcome-title">ROXY %s &mdash; Autonomous Feature Development (1. Plan &rarr; 2. Code &rarr; 3. Review)</div>
                                <div class="welcome-hint" style="margin-top: 4px;">
                                    Describe a task or click a starter prompt below to populate the composer:
                                </div>
                                <table width="100%%" cellpadding="0" cellspacing="8" border="0" style="margin-top: 6px;">
                                    %s
                                    %s
                                </table>
                                <table width="100%%" cellpadding="0" cellspacing="0" border="0" style="margin-top: 6px; margin-bottom: 6px;">
                                    <tr><td class="hr-line" height="1"></td></tr>
                                </table>
                                <div class="welcome-hint" style="margin-top: 4px;">
                                    1. <b>Plan</b> (draft &amp; approve) &bull; 2. <b>Code</b> (edit, compile &amp; test) &bull; 3. <b>Review</b> (audit diff &amp; sign-off)
                                </div>
                            </td>
                        </tr>
                    </table>
                    """
                    .formatted(CAT_EARS, row1, row2);
        }
        String row1 = renderStarterRow(
                renderStarterCard("blue", "ARCHITECTURE", "Architecture &amp; Data Flow",
                        "Map out core services, entry points, and request lifecycles",
                        "Explain the high-level architecture and key services in this repository.",
                        "Explain architecture &amp; key services"),
                renderStarterCard("purple", "GIT HISTORY", "Recent Git Activity &amp; Diffs",
                        "Inspect recent commits and uncommitted working-tree changes",
                        "Summarize the recent commits and any uncommitted working-tree changes.",
                        "Summarize recent commits &amp; diffs"));
        String row2 = renderStarterRow(
                renderStarterCard("amber", "BUILD &amp; TOOLS", "Configure build.toml &amp; Sandbox",
                        "Auto-detect project toolchains, test runners, and sandbox paths",
                        "Inspect my project files and toolchain and recommend a build.toml configuration.",
                        "Recommend a build.toml config"),
                renderStarterCard("emerald", "BLUEPRINT", "Scope a Feature Blueprint",
                        "Explore requirements and design before sending to Build Feature",
                        "Help me explore and design a new feature so I can move it to Build Feature.",
                        "Design a feature blueprint"));
        return """
                <table class="welcome-card" width="100%%" cellpadding="0" cellspacing="0" border="0">
                    <tr>
                        <td class="ai-accent" width="4" nowrap="nowrap">&nbsp;</td>
                        <td width="100%%" style="padding: 10px 14px;">
                            <div class="welcome-title">ROXY %s &mdash; Technical Mentor (Read-Only Explore Mode)</div>
                            <div class="welcome-hint" style="margin-top: 4px;">
                                Explore your codebase in read-only mode, or click a starter prompt below to populate the composer:
                            </div>
                            <table width="100%%" cellpadding="0" cellspacing="8" border="0" style="margin-top: 6px;">
                                %s
                                %s
                            </table>
                            <table width="100%%" cellpadding="0" cellspacing="0" border="0" style="margin-top: 6px; margin-bottom: 6px;">
                                <tr><td class="hr-line" height="1"></td></tr>
                            </table>
                            <div class="welcome-hint" style="margin-top: 4px;">
                                Type <b>@</b> or click <b>@ Mention</b> to reference files &bull; <b>Enter</b> to send, <b>Shift+Enter</b> for newline &bull; <b>Ctrl+V</b> to paste images
                            </div>
                        </td>
                    </tr>
                </table>
                """
                .formatted(CAT_EARS, row1, row2);
    }

    public static String renderSystemNotice(String message) {
        return "<div class='pruning-notice'>" + SwingHtmlUtils.escapeHtml(message) + "</div>";
    }
}
