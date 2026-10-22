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
import org.roxycode.app.ai.services.cache.RepoMapPackerService;
import org.roxycode.app.ui.ExploreChatPanel;
import org.roxycode.app.ui.FeatureChatPanel;
import org.roxycode.app.ui.MainFrame;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

class AgentServiceTest {

    @AgentService(
            value = "testService",
            phases = {"EXPLORE", "PLAN"})
    private static class MockService {}

    @AgentService("defaultService")
    private static class DefaultService {}

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

    @AgentService(
            value = "wildcardService",
            phases = {"*"})
    private static class WildcardService {}

    @Test
    void testWildcardRole() {
        AgentService annotation = WildcardService.class.getAnnotation(AgentService.class);
        assertNotNull(annotation);
        assertEquals("wildcardService", annotation.value());
        assertArrayEquals(new String[] {"*"}, annotation.phases());
    }

    @Test
    void testAgentServicesDoNotHaveRedundantServiceAnnotation() {
        List<Class<?>> agentServiceClasses =
                List.of(
                        GenericBuildToolService.class,
                        GitService.class,
                        GrepService.class,
                        FileReadService.class,
                        FileEditorService.class);

        for (Class<?> clazz : agentServiceClasses) {
            assertNotNull(
                    clazz.getDeclaredAnnotation(AgentService.class),
                    clazz.getSimpleName() + " should have @AgentService");
            assertNull(
                    clazz.getDeclaredAnnotation(Service.class),
                    clazz.getSimpleName()
                            + " should not declare redundant @Service alongside @AgentService");
        }

        assertNull(
                RepoMapPackerService.class.getDeclaredAnnotation(AgentService.class),
                "RepoMapPackerService should not be exposed as an @AgentService");
        assertNotNull(
                RepoMapPackerService.class.getDeclaredAnnotation(Service.class),
                "RepoMapPackerService should be annotated with @Service");
    }

    @Test
    void testSingleConstructorBeansDoNotHaveRedundantAutowired() {
                List<Class<?>> singleCtorClasses =
                List.of(
                        GenericBuildToolService.class,
                        FileEditorService.class,
                        MainFrame.class,
                        ExploreChatPanel.class,
                        FeatureChatPanel.class);

        for (Class<?> clazz : singleCtorClasses) {
            Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            assertEquals(1, constructors.length, clazz.getSimpleName() + " should have 1 ctor");
            assertNull(
                    constructors[0].getDeclaredAnnotation(Autowired.class),
                    clazz.getSimpleName()
                            + " single constructor should not have redundant @Autowired");
        }
    }
}
