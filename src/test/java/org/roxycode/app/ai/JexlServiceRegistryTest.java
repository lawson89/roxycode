package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.FileEditorService;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.SymbolService;

class JexlServiceRegistryTest {

    @AgentService("emptyPhasesService")
    static class EmptyPhasesService {
    }

    @Test
    void testEmptyPhasesDisallowsAllPhases() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        EmptyPhasesService emptyService = new EmptyPhasesService();
        registry.register(emptyService);

        for (WorkflowPhase phase : WorkflowPhase.values()) {
            assertFalse(registry.getServices(phase).containsValue(emptyService),
                    "Service with empty phases array should not be exposed in " + phase);
        }
        assertFalse(registry.getServices(null).containsValue(emptyService));
    }

    @Test
    void testServicePhaseFiltering() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        FileEditorService editor = mock(FileEditorService.class);
        GenericBuildToolService buildTool = mock(GenericBuildToolService.class);
        MemoryService memoryService = mock(MemoryService.class);
        SymbolService symbolService = mock(SymbolService.class);
        PlanService planService = new PlanService(null, null, null, null);

        registry.register(editor);
        registry.register(buildTool);
        registry.register(memoryService);
        registry.register(symbolService);
        registry.register(planService);

        assertTrue(registry.getServices(WorkflowPhase.CODE).containsValue(editor));
        assertFalse(registry.getServices(WorkflowPhase.PLAN).containsValue(editor));
        assertFalse(registry.getServices(WorkflowPhase.EXPLORE).containsValue(editor));
        assertFalse(registry.getServices(WorkflowPhase.EXPLORE).containsValue(planService));
        assertTrue(registry.getServices(WorkflowPhase.PLAN).containsValue(planService));

        for (WorkflowPhase phase : WorkflowPhase.values()) {
            assertTrue(registry.getServices(phase).containsValue(buildTool));
            assertTrue(registry.getServices(phase).containsValue(memoryService));
            assertTrue(registry.getServices(phase).containsValue(symbolService));
            assertTrue(registry.getDocumentation(phase).contains("memoryService"));
            assertTrue(registry.getDocumentation(phase).contains("symbolService"));
            assertTrue(registry.getDocumentation(phase).contains("interface SymbolInfo {"));
        }
    }

    public interface ServiceContract {
        String runAction();
    }

    @AgentService(value = "contractService", phases = {"CODE"})
    public static class ContractServiceImpl implements ServiceContract {
        @Override
        @AgentDoc("Runs an action")
        public String runAction() {
            return "ok";
        }
    }

    @Test
    void testRegisterAndGetServicesUnwrapsJdkAndCglibProxiesAndSubclasses() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        org.springframework.aop.framework.ProxyFactory jdkFactory = new org.springframework.aop.framework.ProxyFactory(
                new ContractServiceImpl());
        jdkFactory.setInterfaces(ServiceContract.class);
        Object jdkProxy = jdkFactory.getProxy();

        registry.register(jdkProxy);
        registry.register(null);

        assertSame(jdkProxy, registry.getServices(WorkflowPhase.CODE).get("contractService"));
        assertFalse(registry.getServices(WorkflowPhase.PLAN).containsKey("contractService"));

        String doc = registry.getDocumentation(WorkflowPhase.CODE);
        assertTrue(doc.contains("// Global variable exposed in JEXL: contractService"));
        assertTrue(doc.contains("runAction(): string;"));
    }
}
