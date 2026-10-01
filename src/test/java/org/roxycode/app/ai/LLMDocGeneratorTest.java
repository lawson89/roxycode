package org.roxycode.app.ai;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LLMDocGeneratorTest {

    @Test
    void testGenerateDoc() {
        LLMDocGenerator generator = new LLMDocGenerator();
        MockAgentService service = new MockAgentService();
        
        String doc = generator.generateDoc(List.of(service));
        
        assertTrue(doc.contains("### JEXL API Documentation"));
        assertTrue(doc.contains("// Global variable exposed in JEXL: mockService"));
        assertTrue(doc.contains("public class MockAgentService"));
        assertTrue(doc.contains("doSomething"));
        assertTrue(doc.contains("This is a mock service"));
    }

    @AgentService("mockService")
    @AgentDoc("This is a mock service")
    public static class MockAgentService {
        @AgentDoc("Does something")
        public String doSomething(String input) {
            return input;
        }
    }
}
