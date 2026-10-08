package org.roxycode.app.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JexlServiceRegistryTest {

    @AgentService(value = "service1", roles = {"SENIOR_DEVELOPER"})
    static class Service1 {}

    @AgentService(value = "service2", roles = {"*"})
    static class Service2 {}

    @Test
    void testCaching() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(new Service1());
        registry.register(new Service2());

        String doc1 = registry.getDocumentation(AgentRole.SENIOR_DEVELOPER);
        String doc2 = registry.getDocumentation(AgentRole.SENIOR_DEVELOPER);
        
        assertSame(doc1, doc2, "Documentation should be cached and return the same instance");
        
        String doc3 = registry.getDocumentation(AgentRole.LEAD_ARCHITECT);
        assertNotSame(doc1, doc3, "Different roles should have different documentation");
        assertFalse(doc3.contains("service1"), "Lead Architect should not see service1");
        assertTrue(doc3.contains("service2"), "Lead Architect should see wildcard service2");
    }

    @Test
    void testCacheInvalidation() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(new Service1());

        String doc1 = registry.getDocumentation(AgentRole.SENIOR_DEVELOPER);
        
        registry.register(new Service2());
        String doc2 = registry.getDocumentation(AgentRole.SENIOR_DEVELOPER);
        
        assertNotSame(doc1, doc2, "Cache should be invalidated after registration");
        assertTrue(doc2.contains("service2"), "New documentation should contain service2");
    }
}