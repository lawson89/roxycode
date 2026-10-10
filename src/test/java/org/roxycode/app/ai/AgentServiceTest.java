package org.roxycode.app.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentServiceTest {

    @AgentService(value = "testService", phases = {"EXPLORE", "PLAN"})
    private static class MockService {}

    @AgentService("defaultService")
    private static class DefaultService {}

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

    @AgentService(value = "wildcardService", phases = {"*"})
    private static class WildcardService {}

    @Test
    void testWildcardRole() {
        AgentService annotation = WildcardService.class.getAnnotation(AgentService.class);
        assertNotNull(annotation);
        assertEquals("wildcardService", annotation.value());
        assertArrayEquals(new String[]{"*"}, annotation.phases());
    }
}