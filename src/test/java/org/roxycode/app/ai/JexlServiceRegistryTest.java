package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.roxycode.app.ai.services.FileEditorService;
import org.roxycode.app.ai.services.GenericBuildToolService;

class JexlServiceRegistryTest {

  @AgentService(
      value = "service1",
      phases = {"CODE"})
  static class Service1 {}

  @AgentService(
      value = "service2",
      phases = {"*"})
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

  @Test
  void testFileEditorServiceFiltering() {
    JexlServiceRegistry registry = new JexlServiceRegistry();
    FileEditorService editor = mock(FileEditorService.class);
    registry.register(editor);

    // FileEditorService has @AgentService(value = "fileEditorService", phases = {"CODE"})
    // It should be available in CODE but not in PLAN/EXPLORE

    assertTrue(registry.getServices(WorkflowPhase.CODE).containsValue(editor));
    assertFalse(registry.getServices(WorkflowPhase.PLAN).containsValue(editor));
    assertFalse(registry.getServices(WorkflowPhase.EXPLORE).containsValue(editor));
  }

  @Test
  void testBuildToolServiceAvailableInPlanPhase() {
    JexlServiceRegistry registry = new JexlServiceRegistry();
    GenericBuildToolService buildTool = mock(GenericBuildToolService.class);
    registry.register(buildTool);

    assertTrue(registry.getServices(WorkflowPhase.PLAN).containsValue(buildTool));
    assertTrue(registry.getServices(WorkflowPhase.EXPLORE).containsValue(buildTool));
    assertTrue(registry.getServices(WorkflowPhase.CODE).containsValue(buildTool));
    assertTrue(registry.getServices(WorkflowPhase.REVIEW).containsValue(buildTool));
  }
}
