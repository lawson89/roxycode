package org.roxycode.core;

import com.google.genai.Client;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import com.google.genai.types.FunctionResponse;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HistoryServiceTest {

    private final SettingsService settingsService = mock(SettingsService.class);
    private final HistoryService historyService = new HistoryService(settingsService);

    @Test
    void testIsSafeStartNode() {
        // Pure user message
        Content pureUser = Content.builder()
                .role("user")
                .parts(List.of(Part.builder().text("Hello").build()))
                .build();
        
        // Tool response (User role)
        Content toolResponse = Content.builder()
                .role("user")
                .parts(List.of(Part.builder()
                        .functionResponse(FunctionResponse.builder()
                                .name("test")
                                .response(Map.of("res", "ok"))
                                .build())
                        .build()))
                .build();

        // Tool response (Function role)
        Content toolResponseFn = Content.builder()
                .role("function")
                .parts(List.of(Part.builder()
                        .functionResponse(FunctionResponse.builder()
                                .name("test")
                                .response(Map.of("res", "ok"))
                                .build())
                        .build()))
                .build();
        
        // Model message
        Content modelMsg = Content.builder()
                .role("model")
                .parts(List.of(Part.builder().text("Hi").build()))
                .build();

        assertTrue(invokeIsSafeStartNode(pureUser), "Pure user should be safe");
        assertFalse(invokeIsSafeStartNode(toolResponse), "Tool response should not be safe");
        assertFalse(invokeIsSafeStartNode(toolResponseFn), "Function role should not be safe");
        assertFalse(invokeIsSafeStartNode(modelMsg), "Model message should not be safe");
    }

    private boolean invokeIsSafeStartNode(Content content) {
        try {
            var method = HistoryService.class.getDeclaredMethod("isSafeStartNode", Content.class);
            method.setAccessible(true);
            return (boolean) method.invoke(historyService, content);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testFindSafeSplitIndex() throws Exception {
        List<Content> history = new ArrayList<>();
        history.add(createMsg("user", "System")); // 0
        history.add(createMsg("user", "Task 1")); // 1
        history.add(createMsg("model", "Thinking")); // 2
        history.add(createMsg("user", "Task 2")); // 3
        history.add(createToolMsg("user", "res")); // 4
        history.add(createMsg("model", "Thinking 2")); // 5
        history.add(createMsg("user", "Task 3")); // 6
        history.add(createMsg("model", "Thinking 3")); // 7
        history.add(createMsg("model", "Thinking 4")); // 8
        history.add(createMsg("model", "Thinking 5")); // 9
        history.add(createMsg("model", "Thinking 6")); // 10
        history.add(createMsg("user", "Task 4")); // 11
        
        // Target index 4 (at a tool message)
        // history.size() is 12. lookAheadLimit = max(4, 12-5=7) = 7.
        int index = invokeFindSafeSplitIndex(history, 4);
        
        // Should look forward up to index 6 and find index 6 (Task 3)
        assertEquals(6, index);
        
        // Now remove Task 3, 4 and more to force backward search
        // We want lookAheadLimit to be <= 4.
        // If history.size() is 9, lookAheadLimit = max(4, 9-5=4) = 4.
        while(history.size() > 9) history.remove(history.size() - 1);
        index = invokeFindSafeSplitIndex(history, 4);
        // Should look backward from 4 and find index 3 (Task 2)
        assertEquals(3, index);
    }

    private int invokeFindSafeSplitIndex(List<Content> history, int targetIndex) throws Exception {
        var method = HistoryService.class.getDeclaredMethod("findSafeSplitIndex", List.class, int.class);
        method.setAccessible(true);
        return (int) method.invoke(historyService, history, targetIndex);
    }

    @Test
    void testFindForcedSplitIndex() throws Exception {
        List<Content> history = new ArrayList<>();
        history.add(createMsg("user", "System")); // 0
        history.add(createMsg("user", "Tool Response")); // 1
        history.add(createToolMsg("user", "res")); // 2
        history.add(createMsg("model", "Thinking")); // 3
        history.add(createToolMsg("user", "res")); // 4
        history.add(createMsg("model", "Thinking 2")); // 5
        history.add(createToolMsg("user", "res")); // 6

        // Target index 4.
        // Forward: index 5 is 'model'
        int index = invokeFindForcedSplitIndex(history, 4);
        assertEquals(5, index);

        // Target index 6.
        // Forward limit: max(6, 7-2=5) = 6. 
        // Forward loop: i=6. isModelNode(history.get(6))? No, it's user tool response.
        // Backward loop: i=5. isModelNode(history.get(5))? Yes.
        index = invokeFindForcedSplitIndex(history, 6);
        assertEquals(5, index);
    }

    private int invokeFindForcedSplitIndex(List<Content> history, int targetIndex) throws Exception {
        var method = HistoryService.class.getDeclaredMethod("findForcedSplitIndex", List.class, int.class);
        method.setAccessible(true);
        return (int) method.invoke(historyService, history, targetIndex);
    }

    @Test
    void testCompactHistory_StandardFlow() {
        // Setup settings
        when(settingsService.getHistoryThreshold()).thenReturn(5);
        when(settingsService.getCompactionChunkSize()).thenReturn(3);
        when(settingsService.getMaxSummaryChunks()).thenReturn(3);

        // Setup History with enough messages
        List<Content> history = new ArrayList<>();
        history.add(createMsg("user", "System Prompt")); // 0
        history.add(createMsg("user", "Msg 1")); // 1
        history.add(createMsg("model", "Msg 2")); // 2
        history.add(createMsg("user", "Msg 3")); // 3
        history.add(createMsg("model", "Msg 4")); // 4
        history.add(createMsg("user", "Msg 5")); // 5 (Safe split point)
        history.add(createMsg("model", "Msg 6")); // 6
        history.add(createMsg("user", "Msg 7")); // 7
        history.add(createMsg("model", "Msg 8")); // 8
        history.add(createMsg("user", "Msg 9")); // 9
        history.add(createMsg("model", "Msg 10")); // 10
        history.add(createMsg("user", "Msg 11")); // 11
        // Size 12.

        // Mock Client
        Client mockClient = mock(Client.class);

        // Execute
        historyService.compactHistory(mockClient, "model-x", history, "Static System Prompt");

        // Verify
        assertEquals(8, history.size(), "History should be compacted to size 8");
        assertEquals("Msg 5", history.get(1).parts().get().get(0).text().get(), "First message after system should be Msg 5");
        
        Content system = history.get(0);
        String systemText = system.parts().get().get(0).text().get();
        assertTrue(systemText.contains("[Context missing due to error]"), "Should contain error summary");
    }
    
    @Test
    void testCompactHistory_NoCompactionNeeded() {
        // Setup settings
        when(settingsService.getHistoryThreshold()).thenReturn(10);
        when(settingsService.getCompactionChunkSize()).thenReturn(3);

        // Setup History
        List<Content> history = new ArrayList<>();
        history.add(createMsg("user", "System Prompt")); // 0
        history.add(createMsg("user", "Msg 1")); // 1
        history.add(createMsg("model", "Msg 2")); // 2
        
        // Size 3. Threshold 10.
        Client mockClient = mock(Client.class);
        
        historyService.compactHistory(mockClient, "model-x", history, "Static System Prompt");
        
        assertEquals(3, history.size(), "History should not change");
        assertEquals("System Prompt", history.get(0).parts().get().get(0).text().get());
    }

    @Test
    void testCompactHistory_ForcedSplit() {
        // Setup settings
        when(settingsService.getHistoryThreshold()).thenReturn(5);
        when(settingsService.getCompactionChunkSize()).thenReturn(3);
        when(settingsService.getMaxSummaryChunks()).thenReturn(3);

        // Setup History with UNSAFE messages in the split zone
        List<Content> history = new ArrayList<>();
        history.add(createMsg("user", "System Prompt")); // 0
        history.add(createMsg("model", "Msg 1")); // 1 (Unsafe)
        history.add(createMsg("model", "Msg 2")); // 2
        history.add(createMsg("model", "Msg 3")); // 3
        history.add(createMsg("model", "Msg 4")); // 4
        history.add(createMsg("model", "Msg 5")); // 5
        history.add(createMsg("model", "Msg 6")); // 6
        history.add(createMsg("user", "Msg 7")); // 7
        history.add(createMsg("model", "Msg 8")); // 8
        history.add(createMsg("user", "Msg 9")); // 9
        history.add(createMsg("model", "Msg 10")); // 10
        history.add(createMsg("user", "Msg 11")); // 11
        // Size 12.

        Client mockClient = mock(Client.class);

        // Execute
        historyService.compactHistory(mockClient, "model-x", history, "Static System Prompt");

        assertEquals(10, history.size(), "History should be compacted to size 10");
        
        Content synthetic = history.get(1);
        assertTrue(synthetic.parts().get().get(0).text().get().contains("Continuing from previous context"), "Should be synthetic message");
    }

    @Test
    void testCompactHistory_StuckAtBeginning() {
        // Setup settings
        when(settingsService.getHistoryThreshold()).thenReturn(5);
        when(settingsService.getCompactionChunkSize()).thenReturn(3);
        when(settingsService.getMaxSummaryChunks()).thenReturn(3);

        // Setup History where only the first message is safe
        List<Content> history = new ArrayList<>();
        history.add(createMsg("user", "System Prompt")); // 0
        history.add(createMsg("user", "Msg 1 (Safe)")); // 1
        history.add(createToolMsg("user", "res 2")); // 2
        history.add(createToolMsg("user", "res 3")); // 3
        history.add(createToolMsg("user", "res 4")); // 4
        history.add(createToolMsg("user", "res 5")); // 5
        history.add(createToolMsg("user", "res 6")); // 6
        history.add(createToolMsg("user", "res 7")); // 7
        history.add(createToolMsg("user", "res 8")); // 8
        history.add(createToolMsg("user", "res 9")); // 9
        history.add(createToolMsg("user", "res 10")); // 10
        history.add(createToolMsg("user", "res 11")); // 11
        // Size 12.

        // Mock Client
        Client mockClient = mock(Client.class);

        // Execute
        historyService.compactHistory(mockClient, "model-x", history, "Static System Prompt");

        // EXPECTED BEHAVIOR: History should be compacted using forced split.
        assertTrue(history.size() < 12, "History should have been compacted even if splitIndex was 1");
        assertTrue(history.get(1).parts().get().get(0).text().get().contains("Continuing from previous context"), 
                "Should have inserted synthetic message");
    }

    private Content createMsg(String role, String text) {
        return Content.builder()
                .role(role)
                .parts(List.of(Part.builder().text(text).build()))
                .build();
    }

    private Content createToolMsg(String role, String res) {
        return Content.builder()
                .role(role)
                .parts(List.of(Part.builder()
                        .functionResponse(FunctionResponse.builder()
                                .name("test")
                                .response(Map.of("result", res))
                                .build())
                        .build()))
                .build();
    }
}
