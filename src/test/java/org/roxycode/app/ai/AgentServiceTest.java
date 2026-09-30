package org.roxycode.app.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentServiceTest {

    @AgentService(value = "testService", roles = {"RESEARCH", "ANALYST"})
    private static class MockService {}

    @AgentService("defaultService")
    private static class DefaultService {}

    @Test
    void testAnnotationProperties() {
        AgentService annotation = MockService.class.getAnnotation(AgentService.class);
        assertNotNull(annotation);
        assertEquals("testService", annotation.value());
        assertArrayEquals(new String[]{"RESEARCH", "ANALYST"}, annotation.roles());
    }

    @Test
    void testDefaultValues() {
        AgentService annotation = DefaultService.class.getAnnotation(AgentService.class);
        assertNotNull(annotation);
        assertEquals("defaultService", annotation.value());
        assertEquals(0, annotation.roles().length);
    }

    @AgentService(value = "wildcardService", roles = {"*"})
    private static class WildcardService {}

    @Test
    void testWildcardRole() {
        AgentService annotation = WildcardService.class.getAnnotation(AgentService.class);
        assertNotNull(annotation);
        assertEquals("wildcardService", annotation.value());
        assertArrayEquals(new String[]{"*"}, annotation.roles());
    }
}