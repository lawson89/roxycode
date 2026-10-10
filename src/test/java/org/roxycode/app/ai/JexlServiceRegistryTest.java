package org.roxycode.app.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JexlServiceRegistryTest {

    @AgentService(value = "service1", phases = {"CODE"})
    static class Service1 {}

    @AgentService(value = "service2", phases = {"*"})
    static class Service2 {}

    @Test
    void testCaching() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(new Service1());
        registry.register(new Service2());

        String doc1 = registry.getDocumentation(WorkflowPhase.CODE);
        String doc2 = registry.getDocumentation(WorkflowPhase.CODE);
        
        assertSame(doc1, doc2);
        
        String doc3 = registry.getDocumentation(WorkflowPhase.PLAN);
        assertNotSame(doc1, doc3);
        assertFalse(doc3.contains("service1"));
        assertTrue(doc3.contains("service2"));
    }
}
