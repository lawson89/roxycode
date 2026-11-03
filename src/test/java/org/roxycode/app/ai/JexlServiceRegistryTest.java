package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import java.util.List;
import org.roxycode.app.ai.services.FileEditorService;
import org.roxycode.app.ai.services.GenericBuildToolService;
import org.roxycode.app.ai.services.GitService;
import org.roxycode.app.ai.services.MemoryService;
import org.roxycode.app.ai.services.PlanService;
import org.roxycode.app.ai.services.SymbolService;
import org.roxycode.app.ai.services.UserInputService;
import org.roxycode.app.events.SystemToolsUpdatedEvent;
import org.roxycode.app.model.SystemTool;
import org.roxycode.app.service.SystemToolService;

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
        PlanService planService = new PlanService(null, null, null);
        UserInputService userInputService = new UserInputService(null);

        registry.register(editor);
        registry.register(buildTool);
        registry.register(memoryService);
        registry.register(symbolService);
        registry.register(planService);
        registry.register(userInputService);

        assertTrue(registry.getServices(WorkflowPhase.CODE).containsValue(editor));
        assertFalse(registry.getServices(WorkflowPhase.PLAN).containsValue(editor));
        assertFalse(registry.getServices(WorkflowPhase.EXPLORE).containsValue(editor));
        assertFalse(registry.getServices(WorkflowPhase.EXPLORE).containsValue(planService));
        assertTrue(registry.getServices(WorkflowPhase.PLAN).containsValue(planService));

        for (WorkflowPhase phase : WorkflowPhase.values()) {
            assertTrue(registry.getServices(phase).containsValue(buildTool));
            assertTrue(registry.getServices(phase).containsValue(memoryService));
            assertTrue(registry.getServices(phase).containsValue(symbolService));
            UserInputService defaultScoped = assertInstanceOf(UserInputService.class,
                    registry.getServices(phase).get("userInputService"));
            assertEquals(TurnToolContext.DEFAULT_CONVERSATION_ID, defaultScoped.getConversationId());

            UserInputService customScoped = assertInstanceOf(UserInputService.class,
                    registry.getServices(phase, "qa:default").get("userInputService"));
            assertEquals("qa:default", customScoped.getConversationId());
            assertNotSame(userInputService, customScoped);

            assertTrue(registry.getDocumentation(phase).contains("memoryService"));
            assertTrue(registry.getDocumentation(phase).contains("symbolService"));
            assertTrue(registry.getDocumentation(phase).contains("userInputService"));
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

    @Test
    void testGitServiceExcludedWhenGitNotInstalledAndIncludedWhenDetected() {
        SystemToolService systemToolService = mock(SystemToolService.class);
        when(systemToolService.checkGitAvailable()).thenReturn(false);

        JexlServiceRegistry registry = new JexlServiceRegistry(systemToolService);
        GitService gitService = new GitService(null);
        MemoryService memoryService = mock(MemoryService.class);
        registry.register(gitService);
        registry.register(memoryService);

        assertFalse(registry.isGitAvailable());
        for (WorkflowPhase phase : WorkflowPhase.values()) {
            assertFalse(registry.getServices(phase).containsKey("gitService"));
            assertTrue(registry.getServices(phase).containsKey("memoryService"));
            String doc = registry.getDocumentation(phase);
            assertFalse(doc.contains("gitService"));
            assertFalse(doc.contains("interface GitService"));
            assertTrue(doc.contains("memoryService"));
        }
        verify(systemToolService, times(1)).checkGitAvailable();

        // Simulate SystemToolsUpdatedEvent when user installs Git and refreshes tools
        registry.onSystemToolsUpdated(new SystemToolsUpdatedEvent(List.of(
                SystemTool.ofKnownTool(SystemTool.TOOL_GIT, "git version 2.45.0", SystemToolService.STATUS_DETECTED)),
                false));

        assertTrue(registry.isGitAvailable());
        for (WorkflowPhase phase : WorkflowPhase.values()) {
            assertSame(gitService, registry.getServices(phase).get("gitService"));
            String doc = registry.getDocumentation(phase);
            assertTrue(doc.contains("// Global variable exposed in JEXL: gitService"));
            assertTrue(doc.contains("interface GitService"));
        }

        // Simulate SystemToolsUpdatedEvent when Git becomes missing again
        registry.onSystemToolsUpdated(new SystemToolsUpdatedEvent(
                List.of(SystemTool.ofKnownTool(SystemTool.TOOL_GIT, "Not found", SystemToolService.STATUS_MISSING)),
                false));
        assertFalse(registry.isGitAvailable());
        assertFalse(registry.getServices(WorkflowPhase.CODE).containsKey("gitService"));
        assertFalse(registry.getDocumentation(WorkflowPhase.CODE).contains("gitService"));
    }
}
