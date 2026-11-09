package org.roxycode.app.ui;

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
    private ChatHtmlTemplates() {
    }

    public static String renderMoveToActionHtml(int messageIndex) {
        return "<a class='feature-action' href='feature:" + messageIndex + "'>[Move to Feature Request]</a>";
    }

    public static String renderCopyActionHtml(int messageIndex, boolean copied) {
        if (copied) {
            return "<span class='copy-feedback'>&#10003; Copied!</span>";
        }
        return "<a class='copy-action' href='copy:" + messageIndex + "'>[Copy]</a>";
    }

    public static String renderAiActionCellContent(int messageIndex, boolean copied, boolean showMoveAction) {
        String moveAction = "";
        if (showMoveAction) {
            moveAction = renderMoveToActionHtml(messageIndex) + "&nbsp;&nbsp;";
        }
        return moveAction + renderCopyActionHtml(messageIndex, copied);
    }

    public static String renderChatEntry(String role, String content, String timestamp, int messageIndex,
            boolean copied, boolean showMoveAction) {
        String normalizedRole = StringUtils.defaultString(role);
        String safeRole = SwingHtmlUtils.escapeHtml(normalizedRole);
        String indicator = switch (normalizedRole) {
            case ROLE_AI -> "<span class='role-ai'>ROXY " + CAT_EARS + "</span>";
            case ROLE_USER -> "<span class='role-user'>USER</span>";
            case ROLE_SYSTEM -> "<span class='role-system'>SYSTEM</span>";
            default -> "&nbsp;";
        };
        String actionCell = "";
        if (ROLE_AI.equals(role) && messageIndex >= 0) {
            actionCell = "<td id='" + COPY_CELL_ID_PREFIX + messageIndex
                    + "' align='right' valign='middle' nowrap='nowrap'>"
                    + renderAiActionCellContent(messageIndex, copied, showMoveAction) + "</td>";
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
                            <div class="content" style="margin-top: 4px;">%s</div>
                        </td>
                    </tr>
                </table>
                <table width="100%%" cellpadding="0" cellspacing="0" border="0">
                    <tr><td height="8">&nbsp;</td></tr>
                </table>
                """
                .formatted(safeRole, safeRole, indicator,
                        SwingHtmlUtils.escapeHtml(StringUtils.defaultString(timestamp)), actionCell,
                        StringUtils.defaultString(content));
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

    public static String renderToolLog(String output, boolean success, String error, String timestamp) {
        return renderToolLog(output, success, error, timestamp, -1, shouldDefaultToolExpanded(output, success));
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
                toggleLabel = "[Hide code]";
            } else {
                toggleLabel = "[Show " + lineCount + " lines]";
            }
            toggleLinkHtml = "<a class='copy-link' href='toggle-tool:" + messageIndex + "'>" + toggleLabel
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

    public static String renderWelcomeCard(boolean isTaskMode) {
        if (isTaskMode) {
            return """
                    <table class="welcome-card" width="100%%" cellpadding="0" cellspacing="0" border="0">
                        <tr>
                            <td class="ai-accent" width="4" nowrap="nowrap">&nbsp;</td>
                            <td width="100%%" style="padding: 10px 14px;">
                                <div class="welcome-title">ROXY %s &mdash; Autonomous Feature Development (Plan &rarr; Code &rarr; Review)</div>
                                <div class="welcome-hint" style="margin-top: 6px;">
                                    Build features, refactor modules, and fix bugs through a structured 3-phase lifecycle with human-in-the-loop approval and automated verification:
                                </div>
                                <table width="100%%" cellpadding="2" cellspacing="0" border="0" style="margin-top: 8px;">
                                    <tr>
                                        <td class="welcome-hint">1. <b>Plan</b>: Describe the feature or bugfix; Roxy drafts a step-by-step implementation plan for your approval.</td>
                                    </tr>
                                    <tr>
                                        <td class="welcome-hint">2. <b>Code</b>: Once you click <b>Approve</b>, Roxy edits files, compiles, and runs unit tests autonomously.</td>
                                    </tr>
                                    <tr>
                                        <td class="welcome-hint">3. <b>Review</b>: Roxy audits the git diff and test results before requesting final sign-off.</td>
                                    </tr>
                                    <tr>
                                        <td class="welcome-hint">&bull; <b>Tip</b>: Hover over the <b>Plan &rarr; Code &rarr; Review</b> phase pills above for lifecycle details, or ask Roxy in <b>Codebase Q&amp;A</b> whenever you need help using RoxyCode!</td>
                                    </tr>
                                </table>
                            </td>
                        </tr>
                    </table>
                    """
                    .formatted(CAT_EARS);
        }
        return """
                <table class="welcome-card" width="100%%" cellpadding="0" cellspacing="0" border="0">
                    <tr>
                        <td class="ai-accent" width="4" nowrap="nowrap">&nbsp;</td>
                        <td width="100%%" style="padding: 10px 14px;">
                            <div class="welcome-title">ROXY %s &mdash; Technical Mentor (Read-Only Explore Mode)</div>
                            <div class="welcome-hint" style="margin-top: 6px;">
                                Explore your codebase, ask architecture questions, and debug issues safely without modifying any files &mdash; or ask Roxy how to use and configure <b>RoxyCode</b> itself:
                            </div>
                            <table width="100%%" cellpadding="2" cellspacing="0" border="0" style="margin-top: 8px;">
                                <tr>
                                    <td class="welcome-hint">&bull; <b>Ask about your codebase</b>: <i>"Explain how @PromptService.java assembles the system prompt."</i></td>
                                </tr>
                                <tr>
                                    <td class="welcome-hint">&bull; <b>Ask about RoxyCode</b>: <i>"How do I configure build.toml for Gradle or Docker sandboxing?"</i> &bull; <i>"How do Context folders and Agent Memory work?"</i></td>
                                </tr>
                                <tr>
                                    <td class="welcome-hint">&bull; Type <b>@</b> or click <b>@ Mention</b> to reference files &bull; <b>Enter</b> to send, <b>Shift+Enter</b> for newline &bull; <b>Ctrl+V</b> to paste images</td>
                                </tr>
                            </table>
                        </td>
                    </tr>
                </table>
                """
                .formatted(CAT_EARS);
    }

    public static String renderSystemNotice(String message) {
        return "<div class='pruning-notice'>" + SwingHtmlUtils.escapeHtml(message) + "</div>";
    }
}
