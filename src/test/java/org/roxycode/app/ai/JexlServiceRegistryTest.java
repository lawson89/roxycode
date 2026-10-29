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

    @AgentService(value = "service1", phases = {"CODE"})
    static class Service1 {
    }

    @AgentService(value = "service2", phases = {"*"})
    static class Service2 {
    }

    @AgentService("emptyPhasesService")
    static class EmptyPhasesService {
    }

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

        registry.register(editor);
        registry.register(buildTool);
        registry.register(memoryService);
        registry.register(symbolService);

        assertTrue(registry.getServices(WorkflowPhase.CODE).containsValue(editor));
        assertFalse(registry.getServices(WorkflowPhase.PLAN).containsValue(editor));
        assertFalse(registry.getServices(WorkflowPhase.EXPLORE).containsValue(editor));

        for (WorkflowPhase phase : WorkflowPhase.values()) {
            assertTrue(registry.getServices(phase).containsValue(buildTool));
            assertTrue(registry.getServices(phase).containsValue(memoryService));
            assertTrue(registry.getServices(phase).containsValue(symbolService));
            assertTrue(registry.getDocumentation(phase).contains("memoryService"));
            assertTrue(registry.getDocumentation(phase).contains("symbolService"));
            assertTrue(registry.getDocumentation(phase).contains("interface SymbolInfo {"));
        }
    }

    @Test
    void testPlanServicePhaseAndMethodFiltering() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        PlanService planService = new PlanService(null, null, null, null);
        registry.register(planService);

        assertFalse(registry.getServices(WorkflowPhase.EXPLORE).containsValue(planService));
        assertFalse(registry.getDocumentation(WorkflowPhase.EXPLORE).contains("planService"));

        String planDoc = registry.getDocumentation(WorkflowPhase.PLAN);
        assertTrue(planDoc.contains("submitPlan("));
        assertTrue(planDoc.contains("requestDevelopment("));
        assertFalse(planDoc.contains("markStepCompleted("));
        assertFalse(planDoc.contains("requestReview("));
        assertFalse(planDoc.contains("requestCompletion("));

        String codeDoc = registry.getDocumentation(WorkflowPhase.CODE);
        assertTrue(codeDoc.contains("markStepCompleted("));
        assertTrue(codeDoc.contains("markStepsCompleted("));
        assertTrue(codeDoc.contains("requestReview("));
        assertTrue(codeDoc.contains("requestPlanRevision("));
        assertFalse(codeDoc.contains("submitPlan("));
        assertFalse(codeDoc.contains("requestDevelopment("));
        assertFalse(codeDoc.contains("requestCompletion("));

        String reviewDoc = registry.getDocumentation(WorkflowPhase.REVIEW);
        assertTrue(reviewDoc.contains("markStepCompleted("));
        assertTrue(reviewDoc.contains("requestFixes("));
        assertTrue(reviewDoc.contains("requestCompletion("));
        assertFalse(reviewDoc.contains("submitPlan("));
        assertFalse(reviewDoc.contains("addTechnicalStep("));
        assertFalse(reviewDoc.contains("requestReview("));
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
