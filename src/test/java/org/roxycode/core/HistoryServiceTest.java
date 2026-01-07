package org.roxycode.core;

import com.google.genai.types.Content;
import com.google.genai.types.FunctionResponse;
import com.google.genai.types.Part;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoryServiceTest {

    private SettingsService settingsService;
    private HistoryService historyService;

    @BeforeEach
    void setUp() {
        settingsService = mock(SettingsService.class);
        historyService = new HistoryService(settingsService);
    }

    private Content createMsg(String role, String text) {
        return Content.builder()
                .role(role)
                .parts(List.of(Part.builder().text(text).build()))
                .build();
    }

    private Content createToolResponseMsg(String name) {
        return Content.builder()
                .role("user")
                .parts(List.of(Part.builder()
                        .functionResponse(FunctionResponse.builder()
                                .name(name)
                                .response(Map.of("result", "ok"))
                                .build())
                        .build()))
                .build();
    }

    @Test
    void testIsCuttableNode() {
        // Pure user -> Cuttable
        assertTrue(historyService.isCuttableNode(createMsg("user", "hi")));

        // Model -> Cuttable (With synthetic bridge)
        assertTrue(historyService.isCuttableNode(createMsg("model", "hi")));

        // Tool Response -> NOT Cuttable (Breaks chain)
        assertFalse(historyService.isCuttableNode(createToolResponseMsg("fn")));

        // Function Role -> NOT Cuttable
        assertFalse(historyService.isCuttableNode(Content.builder().role("function").build()));
    }

    @Test
    void testApplySlidingWindow_SyntheticInsertion() {
        when(settingsService.getHistoryWindowSize()).thenReturn(3);

        List<Content> history = new ArrayList<>();
        history.add(createMsg("user", "System"));       // 0
        history.add(createMsg("user", "User 1"));       // 1 (remove)
        history.add(createMsg("model", "Call Tool"));   // 2 (remove)
        history.add(createToolResponseMsg("ToolRes"));  // 3 (remove, skipped)
        history.add(createMsg("model", "Next Step"));   // 4 (Cut here!)

        historyService.applySlidingWindow(history);

        assertEquals(3, history.size());

        // FIX: parts().get() unwraps the Optional, then .get(0) gets the first Part
        assertEquals("System", history.get(0).parts().get().get(0).text().get());

        // Index 1 should now be Synthetic User
        assertEquals("user", history.get(1).role().get());
        assertTrue(history.get(1).parts().get().get(0).text().get().contains("trimmed"));

        // Index 2 should be the preserved Model message
        assertEquals("Next Step", history.get(2).parts().get().get(0).text().get());
    }

    @Test
    void testApplySlidingWindow_NaturalCut() {
        when(settingsService.getHistoryWindowSize()).thenReturn(3);
        List<Content> history = new ArrayList<>();
        history.add(createMsg("user", "System"));
        history.add(createMsg("user", "U1"));
        history.add(createMsg("model", "M1"));
        history.add(createMsg("user", "U2")); // Clean cut point
        history.add(createMsg("model", "M2"));

        historyService.applySlidingWindow(history);

        assertEquals(3, history.size());

        // FIX: chained .get() calls
        assertEquals("U2", history.get(1).parts().get().get(0).text().get());
        assertFalse(history.get(1).parts().get().get(0).text().get().contains("Compacted"),
                "Should not add synthetic msg on natural cut");
    }
}