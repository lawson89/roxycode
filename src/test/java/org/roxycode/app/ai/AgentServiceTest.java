package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.FileEditorService;
import org.roxycode.app.ai.services.FileReadService;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.GrepService;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.cache.ProjectCacheMetaService;
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.service.ProjectService;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.SettingsService;
import org.roxycode.app.ui.ChatController;
import org.roxycode.app.ui.ChatPanel;
import org.roxycode.app.ui.ContextManagerPanel;
import org.roxycode.app.ui.ExploreChatPanel;
import org.roxycode.app.ui.FeatureChatPanel;
import org.roxycode.app.ui.MainFrame;
import org.roxycode.app.ui.QaWorkspacePanel;
import org.roxycode.app.ui.TaskWorkspacePanel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

class AgentServiceTest {

    private static final List<Class<?>> AGENT_SERVICES = List.of(GenericBuildToolService.class, GitService.class,
            GrepService.class, FileReadService.class, FileEditorService.class, MemoryService.class);

    private static final Set<Class<?>> ALLOWED_JS_PARAM_TYPES = Set.of(String.class, String[].class, int.class,
            Integer.class, long.class, Long.class, double.class, Double.class, boolean.class, Boolean.class);

    @AgentService(value = "testService", phases = {"EXPLORE", "PLAN"})
    private static class MockService {
    }

    @AgentService("defaultService")
    private static class DefaultService {
    }

    @AgentService(value = "wildcardService", phases = {"*"})
    private static class WildcardService {
    }

    @Test
    void testAnnotationProperties() {
        AgentService annotation = MockService.class.getAnnotation(AgentService.class);
        assertNotNull(annotation);
        assertEquals("testService", annotation.value());
        assertArrayEquals(new String[]{"EXPLORE", "PLAN"}, annotation.phases());
    }

    @Test
    void testDefaultValues() {
        AgentService annotation = DefaultService.class.getAnnotation(AgentService.class);
        assertNotNull(annotation);
        assertEquals("defaultService", annotation.value());
        assertEquals(0, annotation.phases().length);
    }

    @Test
    void testWildcardRole() {
        AgentService annotation = WildcardService.class.getAnnotation(AgentService.class);
        assertNotNull(annotation);
        assertEquals("wildcardService", annotation.value());
        assertArrayEquals(new String[]{"*"}, annotation.phases());
    }

    @Test
    void testAgentServicesDoNotHaveRedundantServiceAnnotation() {
        for (Class<?> clazz : AGENT_SERVICES) {
            assertNotNull(clazz.getDeclaredAnnotation(AgentService.class));
            assertNull(clazz.getDeclaredAnnotation(Service.class));
        }
        assertNull(RepoMapPackerService.class.getDeclaredAnnotation(AgentService.class));
        assertNotNull(RepoMapPackerService.class.getDeclaredAnnotation(Service.class));
    }

    @Test
    void testSingleConstructorBeansDoNotHaveRedundantAutowired() {
        for (Class<?> clazz : List.of(GenericBuildToolService.class, ProjectCacheMetaService.class,
                FileEditorService.class, FileReadService.class, GrepService.class, GitService.class,
                MemoryService.class, PlanService.class, ProjectService.class, PromptService.class,
                SettingsService.class, JexlTool.class, ContextManagerPanel.class, ChatPanel.class, ChatController.class,
                MainFrame.class, ExploreChatPanel.class, FeatureChatPanel.class, QaWorkspacePanel.class,
                TaskWorkspacePanel.class)) {
            Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            assertEquals(1, constructors.length, clazz.getSimpleName() + " should have 1 ctor");
            assertNull(constructors[0].getDeclaredAnnotation(Autowired.class));
        }
    }

    @Test
    void testAgentDocMethodsUseJsFriendlyParamTypesAndExposeGettersOnReturnedRecords() throws Exception {
        for (Class<?> serviceClass : AGENT_SERVICES) {
            for (Method method : serviceClass.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(AgentDoc.class)) {
                    continue;
                }
                for (Class<?> paramType : method.getParameterTypes()) {
                    assertFalse(Collection.class.isAssignableFrom(paramType),
                            serviceClass.getSimpleName() + "#" + method.getName()
                                    + " should accept array/varargs instead of Collection/List for JS compatibility");
                    assertTrue(ALLOWED_JS_PARAM_TYPES.contains(paramType), serviceClass.getSimpleName() + "#"
                            + method.getName() + " has non-JS-friendly parameter type: " + paramType.getName());
                }
                Class<?> returnType = method.getReturnType();
                if (returnType.isRecord()) {
                    for (RecordComponent comp : returnType.getRecordComponents()) {
                        String compName = comp.getName();
                        String cap = Character.toUpperCase(compName.charAt(0)) + compName.substring(1);
                        String getterName = (comp.getType() == boolean.class ? "is" : "get") + cap;
                        Method getter = assertDoesNotThrow(() -> returnType.getMethod(getterName),
                                returnType.getSimpleName() + " must expose public JavaBean getter " + getterName
                                        + "() so JS property access (obj." + compName + ") works in JEXL");
                        assertTrue(Modifier.isPublic(getter.getModifiers()));
                        assertEquals(comp.getType(), getter.getReturnType());
                    }
                }
            }
        }
    }
}
