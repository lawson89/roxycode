package org.roxycode.app.ai;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

public class JexlToolNotificationTest {

    @Test
    public void testListenerNotification() {
        JexlTool tool = new JexlTool();
        AtomicBoolean called = new AtomicBoolean(false);
        
        tool.addListener(event -> {
            called.set(true);
            assertEquals("1 + 1", event.script());
            assertEquals(2, ((Number)event.result()).intValue());
            assertTrue(event.success());
        });
        
        tool.executeJexl("1 + 1");
        assertTrue(called.get());
    }
}
