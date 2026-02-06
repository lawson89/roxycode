package org.roxycode;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class JsExecutionServiceTest {

    @Inject
    JsExecutionService jsExecutionService;

    @Test
    void testSimpleCalculation() {
        JsExecutionResult result = jsExecutionService.execute("1 + 1");
        assertTrue(result.success());
        assertEquals("2", result.result());
    }

    @Test
    void testLogging() {
        JsExecutionResult result = jsExecutionService.execute("console.log('hello'); console.error('world'); 'done'");
        assertTrue(result.success());
        assertEquals("done", result.result());
        assertTrue(result.logs().contains("hello"));
        assertTrue(result.logs().contains("world"));
    }

    @Test
    void testSecuritySandbox() {
        JsExecutionResult result = jsExecutionService.execute("java.lang.System.exit(1)");
        assertFalse(result.success());
        assertNotNull(result.error());
    }
}
