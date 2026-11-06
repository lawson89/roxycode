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

    static Stream<Arguments> agentServiceProvider() {
        ProjectService ps = mock(ProjectService.class);
        when(ps.hasActiveProject()).thenReturn(false);
        FileReadService frs = new FileReadService(ps);
        return Stream.of(Arguments.of(FileReadService.class, frs),
                Arguments.of(FileEditorService.class, new FileEditorService(ps, null)),
                Arguments.of(GrepService.class, new GrepService(ps, frs)),
                Arguments.of(GitService.class, new GitService(ps)),
                Arguments.of(GenericBuildToolService.class, new GenericBuildToolService(ps, null, null, null, null)),
                Arguments.of(MemoryService.class, new MemoryService(null, ps, null)),
                Arguments.of(SymbolService.class,
                        new SymbolService(ps, new GrepService(ps, frs), AiConfig.SHARED_OBJECT_MAPPER)),
                Arguments.of(PlanService.class, new PlanService(null, null, null)));
    }

    @ParameterizedTest
    @MethodSource("agentServiceProvider")
    void testAllAgentServicesExposeAnnotatedMethodsInJexlPermissionsAndLlmDoc(Class<?> serviceClass,
            Object serviceInstance) {
        String doc = new LLMDocGenerator().generateDoc(List.of(serviceInstance), null);
        assertTrue(doc.contains("interface " + serviceClass.getSimpleName()));

        int documentedMethodCount = 0;
        for (Method method : serviceClass.getMethods()) {
            if (method.getDeclaringClass() == Object.class) {
                continue;
            }
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
                assertTrue(hasArrayOverload, serviceClass.getSimpleName() + "#" + method.getName());
            }
            assertTrue(ALLOWED_JS_PARAM_TYPES.contains(paramType));
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
            Method getter = assertDoesNotThrow(() -> recordType.getMethod(getterName));
            assertTrue(Modifier.isPublic(getter.getModifiers()));
            assertEquals(comp.getType(), getter.getReturnType());
        }
    }

    @Test
    void testGenerateDocPhaseFilteringCglibUnwrappingAndNestedTypes() {
        LLMDocGenerator generator = new LLMDocGenerator();
        PhaseFilteredService svc = new PhaseFilteredService();

        String planDoc = generator.generateDoc(List.of(svc), WorkflowPhase.PLAN);
        SwingTestUtils.assertContainsAll(planDoc,
                "// Plan only\n    planOnlyMethod(): ParentRecord;\n"
                        + "    // Wildcard method\n    wildcardMethod(): string;",
                "interface ParentRecord {", "child: ChildRecord;", "enum Status {");
        assertFalse(planDoc.contains("@param"));
        assertFalse(planDoc.contains("codeReviewMethod"));
        assertFalse(planDoc.contains("emptyPhasesMethod"));

        String codeDoc = generator.generateDoc(List.of(svc), WorkflowPhase.CODE);
        SwingTestUtils.assertContainsAll(codeDoc, "wildcardMethod(): string;", "codeReviewMethod(): Status;");
        assertFalse(codeDoc.contains("planOnlyMethod"));

        ProxyFactory proxyFactory = new ProxyFactory(svc);
        proxyFactory.setProxyTargetClass(true);
        String proxyDoc = generator.generateDoc(List.of(proxyFactory.getProxy()), null);
        assertTrue(proxyDoc.contains("interface PhaseFilteredService {"));
        assertFalse(proxyDoc.contains("SpringCGLIB"));
    }

    @AgentService(value = "phaseService", phases = {"*"})
    public static class PhaseFilteredService {
        @AgentDoc("Wildcard method")
        public String wildcardMethod() {
            return "ok";
        }

        @AgentDoc(value = "Plan only", phases = {"PLAN"})
        public ParentRecord planOnlyMethod() {
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

    public enum Status {
        ACTIVE, INACTIVE
    }

    public record ParentRecord(String title, ChildRecord child) {
    }

    public record ChildRecord(String label, Status status) {
    }
}
