package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

public class JexlToolNotificationTest {

  @Test
  public void testListenerNotification() {
    JexlServiceRegistry registry = new JexlServiceRegistry();
    JexlTool tool = new JexlTool(registry, WorkflowPhase.EXPLORE);
    AtomicBoolean called = new AtomicBoolean(false);

    tool.addListener(
        event -> {
          called.set(true);
          assertEquals("1 + 1", event.script());
          assertEquals(2, ((Number) event.result()).intValue());
          assertTrue(event.success());
        });

    tool.executeJexl("1 + 1");
    assertTrue(called.get());
  }
}
