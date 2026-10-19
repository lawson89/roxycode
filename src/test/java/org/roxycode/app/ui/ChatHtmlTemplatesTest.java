package org.roxycode.app.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ChatHtmlTemplatesTest {

  @Test
  void testRenderChatEntry() {
    String role = "user";
    String content = "<p>Hello</p>";
    String timestamp = "12:00:00";
    String result = ChatHtmlTemplates.renderChatEntry(role, content, timestamp);

    assertTrue(result.contains("user-entry"));
    assertTrue(result.contains("<p>Hello</p>"));
    assertTrue(result.contains("12:00:00"));
    assertFalse(result.contains("copy-action"));
  }

  @Test
  void testRenderAiChatEntryWithCopyAction() {
    String result =
        ChatHtmlTemplates.renderChatEntry("ai", "<p>Response</p>", "12:00:00", 2, false);

    assertTrue(result.contains("ai-entry"));
    assertTrue(result.contains("href='copy:2'"));
    assertTrue(result.contains("copy-action"));
    assertTrue(result.contains("&#128203; Copy"));
    assertFalse(result.contains("copy-feedback"));
  }

  @Test
  void testRenderAiChatEntryWithCopiedFeedback() {
    String result = ChatHtmlTemplates.renderChatEntry("ai", "<p>Response</p>", "12:00:00", 2, true);

    assertTrue(result.contains("ai-entry"));
    assertTrue(result.contains("copy-feedback"));
    assertTrue(result.contains("&#10003; Copied!"));
    assertFalse(result.contains("href='copy:2'"));
  }

  @Test
  void testRenderUserChatEntryDoesNotIncludeCopyAction() {
    String result =
        ChatHtmlTemplates.renderChatEntry("user", "<p>Prompt</p>", "12:00:00", 0, false);

    assertTrue(result.contains("user-entry"));
    assertFalse(result.contains("copy-action"));
    assertFalse(result.contains("href='copy:0'"));
  }

  @Test
  void testRenderDivider() {
    String result = ChatHtmlTemplates.renderDivider();
    assertTrue(result.contains("chat-divider"));
  }

  @Test
  void testRenderTimestamp() {
    String timestamp = "12:00:00";
    String result = ChatHtmlTemplates.renderTimestamp(timestamp);
    assertTrue(result.contains("chat-timestamp"));
    assertTrue(result.contains("12:00:00"));
  }

  @Test
  void testRenderToolLog() {
    String toolName = "git";
    String output = "diff output";
    String result = ChatHtmlTemplates.renderToolLog(toolName, output);

    assertTrue(result.contains("tool-call"));
    assertTrue(result.contains("git"));
    assertTrue(result.contains("diff output"));
  }

  @Test
  void testRenderToolLogEmptyToolName() {
    String toolName = "";
    String output = "jexl output";
    String result = ChatHtmlTemplates.renderToolLog(toolName, output);

    assertTrue(result.contains("tool-call"));
    assertFalse(result.contains("tool-header"));
    assertTrue(result.contains("jexl output"));
  }

  @Test
  void testHtmlEscapingInTemplates() {
    String toolResult = ChatHtmlTemplates.renderToolLog("<script>alert(1)</script>", "output");
    assertFalse(toolResult.contains("<script>"));
    assertTrue(toolResult.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));

    String entryResult =
        ChatHtmlTemplates.renderChatEntry("user<bad>", "<p>Safe</p>", "12:00<img src=x>");
    assertTrue(entryResult.contains("user&lt;bad&gt;-entry"));
    assertTrue(entryResult.contains("12:00&lt;img src=x&gt;"));
    assertFalse(entryResult.contains("<img src=x>"));

    String tsResult = ChatHtmlTemplates.renderTimestamp("<b>time</b>");
    assertTrue(tsResult.contains("&lt;b&gt;time&lt;/b&gt;"));
    assertFalse(tsResult.contains("<b>time</b>"));
  }
}
