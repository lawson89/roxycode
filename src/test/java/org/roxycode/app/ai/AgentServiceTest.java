package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.util.List;
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

    @AgentService(
            value = "testService",
            phases = {"EXPLORE", "PLAN"})
    private static class MockService {}

    @AgentService("defaultService")
    private static class DefaultService {}

    @AgentService(
            value = "wildcardService",
            phases = {"*"})
    private static class WildcardService {}

    @Test
    void testAnnotationProperties() {
        AgentService annotation = MockService.class.getAnnotation(AgentService.class);
        assertNotNull(annotation);
        assertEquals("testService", annotation.value());
        assertArrayEquals(new String[] {"EXPLORE", "PLAN"}, annotation.phases());
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
        assertArrayEquals(new String[] {"*"}, annotation.phases());
    }

    @Test
    void testAgentServicesDoNotHaveRedundantServiceAnnotation() {
        for (Class<?> clazz :
                List.of(
                        GenericBuildToolService.class,
                        GitService.class,
                        GrepService.class,
                        FileReadService.class,
                        FileEditorService.class,
                        MemoryService.class)) {
            assertNotNull(clazz.getDeclaredAnnotation(AgentService.class));
            assertNull(clazz.getDeclaredAnnotation(Service.class));
        }
        assertNull(RepoMapPackerService.class.getDeclaredAnnotation(AgentService.class));
        assertNotNull(RepoMapPackerService.class.getDeclaredAnnotation(Service.class));
    }

    @Test
    void testSingleConstructorBeansDoNotHaveRedundantAutowired() {
        for (Class<?> clazz :
                List.of(
                        GenericBuildToolService.class,
                        ProjectCacheMetaService.class,
                        FileEditorService.class,
                        PlanService.class,
                        ProjectService.class,
                        PromptService.class,
                        SettingsService.class,
                        JexlTool.class,
                        ContextManagerPanel.class,
                        ChatPanel.class,
                        ChatController.class,
                        MainFrame.class,
                        ExploreChatPanel.class,
                        FeatureChatPanel.class,
                        QaWorkspacePanel.class,
                        TaskWorkspacePanel.class)) {
            Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            assertEquals(1, constructors.length, clazz.getSimpleName() + " should have 1 ctor");
            assertNull(constructors[0].getDeclaredAnnotation(Autowired.class));
        }
    }
}
