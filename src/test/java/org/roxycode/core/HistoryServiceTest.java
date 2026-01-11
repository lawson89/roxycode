package org.roxycode.core;

import com.google.genai.types.Content;
import com.google.genai.types.Part;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class HistoryServiceTest {

    @Test
    void testApplySlidingWindowWithPlanContext() {
        SettingsService settings = new SettingsService(null) {
            @Override
            public int getHistoryWindowSize() {
                return 4; // Total size 4
            }
        };
        HistoryService historyService = new HistoryService(settings);

        List<Content> history = new ArrayList<>();
        // Index 0: System Prompt
        history.add(Content.builder().role("system").parts(List.of(Part.builder().text("System").build())).build());
        history.add(Content.builder().role("user").parts(List.of(Part.builder().text("Msg 1").build())).build());
        history.add(Content.builder().role("model").parts(List.of(Part.builder().text("Resp 1").build())).build());
        history.add(Content.builder().role("user").parts(List.of(Part.builder().text("Msg 2").build())).build());
        history.add(Content.builder().role("model").parts(List.of(Part.builder().text("Resp 2").build())).build());
        history.add(Content.builder().role("user").parts(List.of(Part.builder().text("Msg 3").build())).build());

        // We have 6 messages. Window is 4.
        // idealRemovalCount = 2.
        // candidateIndex = 1 + 2 = 3.
        // history.get(3) is "user" (Msg 2). Clean cut.
        
        String planContext = "My Current Plan Content";
        historyService.applySlidingWindow(history, planContext);

        // System, Refresher, Msg 2, Resp 2, Msg 3 = 5 items
        assertEquals(5, history.size());
        assertEquals("system", history.get(0).role().orElse(""));
        assertEquals("user", history.get(1).role().orElse(""));
        assertTrue(history.get(1).parts().get().get(0).text().orElse("").contains(planContext));
    }

    @Test
    void testApplySlidingWindowWithoutPlanContext() {
        SettingsService settings = new SettingsService(null) {
            @Override
            public int getHistoryWindowSize() {
                return 4;
            }
        };
        HistoryService historyService = new HistoryService(settings);

        List<Content> history = new ArrayList<>();
        history.add(Content.builder().role("system").parts(List.of(Part.builder().text("System").build())).build());
        history.add(Content.builder().role("user").parts(List.of(Part.builder().text("Msg 1").build())).build());
        history.add(Content.builder().role("model").parts(List.of(Part.builder().text("Resp 1").build())).build());
        history.add(Content.builder().role("user").parts(List.of(Part.builder().text("Msg 2").build())).build());
        history.add(Content.builder().role("model").parts(List.of(Part.builder().text("Resp 2").build())).build());
        history.add(Content.builder().role("user").parts(List.of(Part.builder().text("Msg 3").build())).build());

        historyService.applySlidingWindow(history, null);

        // System, Msg 2, Resp 2, Msg 3 = 4 items
        assertEquals(4, history.size());
        assertEquals("system", history.get(0).role().orElse(""));
        assertEquals("Msg 2", history.get(1).parts().get().get(0).text().orElse(""));
    }
}
