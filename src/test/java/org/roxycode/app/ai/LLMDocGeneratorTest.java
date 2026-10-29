package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.roxycode.app.ai.services.FileEditorService;
import org.roxycode.app.ai.services.FileReadService;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.SymbolService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.ui.SwingTestUtils;
import org.springframework.aop.framework.ProxyFactory;

class LLMDocGeneratorTest {

    private static Arguments serviceCase(Class<?> clazz, Object instance, String... signatures) {
        return Arguments.of(clazz, instance, List.of(signatures));
    }

    static Stream<Arguments> agentServiceProvider() {
        ProjectService ps = mock(ProjectService.class);
        when(ps.hasActiveProject()).thenReturn(false);
        FileReadService frs = new FileReadService(ps);
        return Stream.of(serviceCase(FileReadService.class, frs,
                "readLines(relativePath: string, startLine: number, endLine: number): string;",
                "readFileNumbered(relativePath: string): string;", "getLineCount(relativePath: string): number;",
                "readFiles(relativePaths: string[]): Map;", "readFilesNumbered(relativePaths: string[]): Map;"),
                serviceCase(FileEditorService.class, new FileEditorService(ps, null),
                        "replaceBlocks(relativePath: string, edits: List): EditorResult;",
                        "replaceBlocks(relativePath: string, edits: Object[]): EditorResult;",
                        "moveFile(sourcePath: string, targetPath: string): EditorResult;",
                        "copyFile(sourcePath: string, targetPath: string): EditorResult;",
                        "mkdirs(relativePath: string): EditorResult;"),
                serviceCase(GrepService.class, new GrepService(ps, frs),
                        "grepWithContext(pattern: string, contextLines: number): EditorResult;",
                        "grepLiteral(text: string): EditorResult;", "grepIgnoreCase(pattern: string): EditorResult;",
                        "findMatchingFiles(pattern: string): List;",
                        "findMatchingFiles(pattern: string, relativePath: string, filePattern: string): List;",
                        "readMatchingFiles(pattern: string): Map;",
                        "readMatchingFiles(pattern: string, relativePath: string, filePattern: string): Map;"),
                serviceCase(GitService.class, new GitService(ps), "getDiffHead(): string;",
                        "getDiffHead(relativePath: string): string;", "getChangedFiles(): List;",
                        "getBlame(relativePath: string, startLine: number, endLine: number): string;"),
                serviceCase(GenericBuildToolService.class, new GenericBuildToolService(ps, null, null),
                        "buildAndTest(): BuildResult;", "compile(): BuildResult;",
                        "runSingleTest(testName: string): BuildResult;", "runTests(): BuildResult;"),
                serviceCase(SymbolService.class,
                        new SymbolService(ps, frs, new GrepService(ps, frs), AiConfig.SHARED_OBJECT_MAPPER),
                        "getFileOutline(relativePath: string): List<SymbolInfo>;",
                        "readSymbol(relativePath: string, symbolName: string): string;",
                        "readSymbols(relativePath: string, symbolNames: string[]): Map;",
                        "findSymbol(symbolName: string): List<SymbolInfo>;", "interface SymbolInfo {",
                        "startLine: number;", "endLine: number;", "signature: string;"),
                serviceCase(PlanService.class, new PlanService(null, null, null, null),
                        "submitPlan(title: string, goal: string, requirements: string[], technicalSteps: string[]): string;",
                        "markStepCompleted(index: number): string;", "markStepsCompleted(indices: number[]): string;",
                        "requestDevelopment(reason: string): string;", "requestReview(reason: string): string;",
                        "requestFixes(reason: string): string;", "requestCompletion(reason: string): string;",
                        "requestPlanRevision(reason: string): string;"));
    }

    @ParameterizedTest
    @MethodSource("agentServiceProvider")
    void testAllAgentServicesExposeAnnotatedMethodsInJexlPermissionsAndLlmDoc(Class<?> serviceClass,
            Object serviceInstance, List<String> expectedSignatures) {
        String doc = new LLMDocGenerator().generateDoc(List.of(serviceInstance));
        assertTrue(doc.contains("interface " + serviceClass.getSimpleName()));

        int documentedMethodCount = 0;
        for (Method method : serviceClass.getMethods()) {
            if (method.getDeclaringClass() == Object.class)
                continue;
            if (method.isAnnotationPresent(AgentDoc.class)) {
                documentedMethodCount++;
                assertTrue(AgentJexlPermissions.INSTANCE.allow(serviceClass, method));
                assertTrue(doc.contains(method.getName() + "("));
            } else {
                assertFalse(AgentJexlPermissions.INSTANCE.allow(serviceClass, method));
            }
        }
        assertTrue(documentedMethodCount > 0);
        expectedSignatures.forEach(sig -> assertTrue(doc.contains(sig)));
    }

    @Test
    void testGenerateDoc() {
        String doc = new LLMDocGenerator().generateDoc(List.of(new MockAgentService()));
        SwingTestUtils.assertContainsAll(doc, "### JEXL API Documentation",
                "// Global variable exposed in JEXL: mockService", "interface MockAgentService",
                "doSomething(input: string): string;", "This is a mock service");
    }

    @Test
    void testGenerateDocFiltersMethodsAndSupportingTypesByWorkflowPhase() {
        LLMDocGenerator generator = new LLMDocGenerator();
        PhaseFilteredService svc = new PhaseFilteredService();

        String allDoc = generator.generateDoc(List.of(svc));
        SwingTestUtils.assertContainsAll(allDoc, "wildcardMethod(): string;", "planOnlyMethod(): Details;",
                "codeReviewMethod(): Status;", "interface Details {", "enum Status {");
        assertFalse(allDoc.contains("emptyPhasesMethod"));

        String planDoc = generator.generateDoc(List.of(svc), WorkflowPhase.PLAN);
        SwingTestUtils.assertContainsAll(planDoc, "wildcardMethod(): string;", "planOnlyMethod(): Details;",
                "interface Details {");
        assertFalse(planDoc.contains("codeReviewMethod"));
        assertFalse(planDoc.contains("enum Status {"));
        assertFalse(planDoc.contains("emptyPhasesMethod"));

        String codeDoc = generator.generateDoc(List.of(svc), WorkflowPhase.CODE);
        SwingTestUtils.assertContainsAll(codeDoc, "wildcardMethod(): string;", "codeReviewMethod(): Status;",
                "enum Status {");
        assertFalse(codeDoc.contains("planOnlyMethod"));
        assertFalse(codeDoc.contains("interface Details {"));
        assertFalse(codeDoc.contains("emptyPhasesMethod"));
    }

    @Test
    void testGenerateDocUnwrapsSpringCglibProxy() {
        LLMDocGenerator generator = new LLMDocGenerator();
        ProxyFactory proxyFactory = new ProxyFactory(new MockAgentService());
        proxyFactory.setProxyTargetClass(true);

        String doc = generator.generateDoc(List.of(proxyFactory.getProxy()));
        SwingTestUtils.assertContainsAll(doc, "// Global variable exposed in JEXL: mockService",
                "interface MockAgentService {", "* @param input The input string to process",
                "doSomething(input: string): string;");
        assertFalse(doc.contains("SpringCGLIB"));

        SwingTestUtils.assertContainsAll(generator.generateDoc(List.of(new SubclassMockAgentService())),
                "interface SubclassMockAgentService {", "doSomething(input: string): string;",
                "extraAction(): string;");
    }

    @Test
    void testParameterAgentDocAndBuildResultSupportingRecordSchema() {
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.hasActiveProject()).thenReturn(false);
        String doc = new LLMDocGenerator()
                .generateDoc(List.of(new MockAgentService(), new GenericBuildToolService(projectService, null, null)));

        SwingTestUtils.assertContainsAll(doc,
                "executeJexl is a lightweight JavaScript-like orchestration layer over RoxyCode's Java services.",
                "* @param input The input string to process",
                "* @param testName The name of the test class or method (e.g., MyTest or MyTest#myMethod)",
                "// Supporting Result Type: access fields as JS properties (obj.field) or method calls (obj.field())",
                "interface BuildResult {", "success: boolean;", "exitCode: number;", "output: string;",
                "errors: List<String>;", "failedTests: List<String>;", "log: string;");
    }

    @Test
    void testSupportingTypes() {
        SwingTestUtils.assertContainsAll(new LLMDocGenerator().generateDoc(List.of(new ServiceWithTypes())),
                "interface ServiceWithTypes", "getDetails(): Details;", "getStatus(): Status;", "enum Status {",
                "ACTIVE, INACTIVE", "interface Details {", "id: number;", "name: string;", "interface BeanInfoItem {",
                "active: boolean;", "title: string;");
    }

    @Test
    void testTransitiveNestedSupportingTypesNoConcurrentModificationException() {
        String doc = assertDoesNotThrow(() -> new LLMDocGenerator().generateDoc(List.of(new ServiceWithNestedTypes())));
        SwingTestUtils.assertContainsAll(doc, "interface ServiceWithNestedTypes", "getParent(): ParentRecord;",
                "interface ParentRecord {", "child: ChildRecord;", "steps: NestedStep[];", "interface ChildRecord {",
                "status: Status;", "interface NestedStep {", "description: string;", "enum Status {");
    }

    @AgentService(value = "phaseService", phases = {"*"})
    public static class PhaseFilteredService {
        @AgentDoc("Wildcard method")
        public String wildcardMethod() {
            return "ok";
        }

        @AgentDoc(value = "Plan only", phases = {"PLAN"})
        public Details planOnlyMethod() {
            return null;
        }

        @AgentDoc(value = "Code and review", phases = {"CODE", "REVIEW"})
        public Status codeReviewMethod() {
            return null;
        }

        @AgentDoc(value = "Empty phases", phases = {})
        public String emptyPhasesMethod() {
            return "none";
        }
    }

    @AgentService("mockService")
    @AgentDoc("This is a mock service")
    public static class MockAgentService {
        @AgentDoc("Does something")
        public String doSomething(@AgentDoc("The input string to process") String input) {
            return input;
        }
    }

    public static class SubclassMockAgentService extends MockAgentService {
        @Override
        public String doSomething(String input) {
            return super.doSomething(input);
        }

        @AgentDoc("Extra subclass action")
        public String extraAction() {
            return "extra";
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

        @AgentDoc("Get bean info item")
        public BeanInfoItem getBeanInfoItem() {
            return null;
        }
    }

    public static class BeanInfoItem {
        public String getTitle() {
            return "sample";
        }

        public boolean isActive() {
            return true;
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
        ACTIVE, INACTIVE
    }

    public record Details(int id, String name) {
    }

    public record ParentRecord(String title, ChildRecord child, NestedStep[] steps) {
    }

    public record ChildRecord(String label, Status status) {
    }

    public record NestedStep(String description, boolean completed) {
    }
}
