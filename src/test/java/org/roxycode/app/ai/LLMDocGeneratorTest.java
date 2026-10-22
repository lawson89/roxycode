package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.service.ProjectService;

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
    void testParameterAgentDocAndBuildResultSupportingRecordSchema() {
        LLMDocGenerator generator = new LLMDocGenerator();
        MockAgentService mockService = new MockAgentService();
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(false);
        GenericBuildToolService buildToolService =
                new GenericBuildToolService(projectService, null, null);

        String doc = generator.generateDoc(List.of(mockService, buildToolService));

        assertTrue(doc.contains("* @param input The input string to process"));
        assertTrue(
                doc.contains(
                        "* @param testName The name of the test class or method (e.g., MyTest or MyTest#myMethod)"));
        assertTrue(
                doc.contains("// Java Record: access fields via method calls, e.g. obj.field()"));
        assertTrue(doc.contains("interface BuildResult {"));
        assertTrue(doc.contains("success: boolean;"));
        assertTrue(doc.contains("exitCode: number;"));
        assertTrue(doc.contains("output: string;"));
        assertTrue(doc.contains("errors: List;"));
        assertTrue(doc.contains("failedTests: List;"));
        assertTrue(doc.contains("log: string;"));
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

    @Test
    void testTransitiveNestedSupportingTypesNoConcurrentModificationException() {
        LLMDocGenerator generator = new LLMDocGenerator();
        ServiceWithNestedTypes service = new ServiceWithNestedTypes();

        String doc = assertDoesNotThrow(() -> generator.generateDoc(List.of(service)));

        assertTrue(doc.contains("interface ServiceWithNestedTypes"));
        assertTrue(doc.contains("getParent(): ParentRecord;"));
        assertTrue(doc.contains("interface ParentRecord {"));
        assertTrue(doc.contains("child: ChildRecord;"));
        assertTrue(doc.contains("steps: NestedStep[];"));
        assertTrue(doc.contains("interface ChildRecord {"));
        assertTrue(doc.contains("status: Status;"));
        assertTrue(doc.contains("interface NestedStep {"));
        assertTrue(doc.contains("description: string;"));
        assertTrue(doc.contains("enum Status {"));
    }

    @AgentService("mockService")
    @AgentDoc("This is a mock service")
    public static class MockAgentService {
        @AgentDoc("Does something")
        public String doSomething(@AgentDoc("The input string to process") String input) {
            return input;
        }
    }

    @AgentService("typedService")
    public static class ServiceWithTypes {
        @AgentDoc("Get details")
        public Details getDetails() {
            return null;
        }

        @AgentDoc("Get status")
        public Status getStatus() {
            return null;
        }
    }

    @AgentService("nestedService")
    public static class ServiceWithNestedTypes {
        @AgentDoc("Get parent record")
        public ParentRecord getParent() {
            return null;
        }
    }

    public enum Status {
        ACTIVE,
        INACTIVE
    }

    public record Details(int id, String name) {}

    public record ParentRecord(String title, ChildRecord child, NestedStep[] steps) {}

    public record ChildRecord(String label, Status status) {}

    public record NestedStep(String description, boolean completed) {}
}
