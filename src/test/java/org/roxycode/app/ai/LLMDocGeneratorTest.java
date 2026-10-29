package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
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
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.SymbolService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.ui.SwingTestUtils;
import org.springframework.aop.framework.ProxyFactory;

class LLMDocGeneratorTest {

    private static final Set<Class<?>> ALLOWED_JS_PARAM_TYPES = Set.of(String.class, String[].class, Object[].class,
            int[].class, List.class, int.class, Integer.class, long.class, Long.class, double.class, Double.class,
            boolean.class, Boolean.class);

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
                serviceCase(MemoryService.class, new MemoryService(null, ps), "readGlobalMemory(): string;",
                        "readProjectMemory(): string;",
                        "saveProjectNote(slug: string, title: string, tags: string, content: string): EditorResult;",
                        "search(pattern: string): EditorResult;", "searchIgnoreCase(pattern: string): EditorResult;"),
                serviceCase(SymbolService.class,
                        new SymbolService(ps, new GrepService(ps, frs), AiConfig.SHARED_OBJECT_MAPPER),
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
                assertJsFriendlyParamsAndReturnedRecordGetters(serviceClass, method);
            } else {
                assertFalse(AgentJexlPermissions.INSTANCE.allow(serviceClass, method));
            }
        }
        assertTrue(documentedMethodCount > 0);
        expectedSignatures.forEach(sig -> assertTrue(doc.contains(sig)));
    }

    private static void assertJsFriendlyParamsAndReturnedRecordGetters(Class<?> serviceClass, Method method) {
        Class<?>[] paramTypes = method.getParameterTypes();
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> paramType = paramTypes[i];
            if (Collection.class.isAssignableFrom(paramType)) {
                int paramIndex = i;
                boolean hasArrayOverload = Arrays.stream(serviceClass.getDeclaredMethods())
                        .anyMatch(m -> m.isAnnotationPresent(AgentDoc.class) && m.getName().equals(method.getName())
                                && m.getParameterCount() == paramTypes.length
                                && m.getParameterTypes()[paramIndex].isArray());
                assertTrue(hasArrayOverload, serviceClass.getSimpleName() + "#" + method.getName()
                        + " accepting Collection/List must also expose an @AgentDoc array overload for JS compatibility");
            }
            assertTrue(ALLOWED_JS_PARAM_TYPES.contains(paramType), serviceClass.getSimpleName() + "#" + method.getName()
                    + " has non-JS-friendly parameter type: " + paramType.getName());
        }
        Class<?> returnType = method.getReturnType();
        if (returnType.isRecord()) {
            assertRecordExposesJavaBeanGetters(returnType);
        }
        if (method.getGenericReturnType() instanceof ParameterizedType pt) {
            for (Type typeArg : pt.getActualTypeArguments()) {
                if (typeArg instanceof Class<?> typeArgClass && typeArgClass.isRecord()) {
                    assertRecordExposesJavaBeanGetters(typeArgClass);
                }
            }
        }
    }

    private static void assertRecordExposesJavaBeanGetters(Class<?> recordType) {
        for (RecordComponent comp : recordType.getRecordComponents()) {
            String compName = comp.getName();
            String cap = Character.toUpperCase(compName.charAt(0)) + compName.substring(1);
            String prefix = "get";
            if (comp.getType() == boolean.class) {
                prefix = "is";
            }
            String getterName = prefix + cap;
            Method getter = assertDoesNotThrow(() -> recordType.getMethod(getterName),
                    recordType.getSimpleName() + " must expose public JavaBean getter " + getterName
                            + "() so JS property access (obj." + compName + ") works in JEXL");
            assertTrue(Modifier.isPublic(getter.getModifiers()));
            assertEquals(comp.getType(), getter.getReturnType());
        }
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
