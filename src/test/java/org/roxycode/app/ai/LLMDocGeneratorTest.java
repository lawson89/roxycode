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
        assertTrue(doc.contains("```typescript"));
        assertTrue(doc.contains("// Global variable exposed in JEXL: mockService"));
        assertTrue(doc.contains("interface MockAgentService"));
        assertTrue(doc.contains("doSomething(input: string): string;"));
        assertTrue(doc.contains("This is a mock service"));
    }

    @Test
    void testSupportingTypes() {
        LLMDocGenerator generator = new LLMDocGenerator();
        ServiceWithTypes service = new ServiceWithTypes();
        
        String doc = generator.generateDoc(List.of(service));
        
        assertTrue(doc.contains("interface ServiceWithTypes"));
        assertTrue(doc.contains("getDetails(): Details;"));
        assertTrue(doc.contains("getStatus(): Status;"));
        
        assertTrue(doc.contains("enum Status {"));
        assertTrue(doc.contains("ACTIVE, INACTIVE"));
        
        assertTrue(doc.contains("interface Details {"));
        assertTrue(doc.contains("id: number;"));
        assertTrue(doc.contains("name: string;"));
    }

    @AgentService("mockService")
    @AgentDoc("This is a mock service")
    public static class MockAgentService {
        @AgentDoc("Does something")
        public String doSomething(String input) {
            return input;
        }
    }

    @AgentService("typedService")
    public static class ServiceWithTypes {
        @AgentDoc("Get details")
        public Details getDetails() { return null; }
        @AgentDoc("Get status")
        public Status getStatus() { return null; }
    }

    public enum Status { ACTIVE, INACTIVE }
    public record Details(int id, String name) {}
}
