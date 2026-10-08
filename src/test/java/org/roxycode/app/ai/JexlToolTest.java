package org.roxycode.app.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class JexlToolTest {

    @AgentService("testService")
    public static class TestService {
        public String hello(String name) {
            return "Hello, " + name;
        }
    }

    @Test
    public void testExecuteJexlWithService() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        registry.register(new TestService());
        
        JexlTool tool = new JexlTool(registry);
        
        String varCheck = tool.executeJexl("testService");
        assertNotEquals("null", varCheck, "testService should be found in context");

        String result = tool.executeJexl("testService.hello('Roxy')");
        assertEquals("Hello, Roxy", result);
    }

    @Test
    public void testExecuteJexlSuccess() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        JexlTool tool = new JexlTool(registry);
        String result = tool.executeJexl("1 + 1");
        assertEquals("2", result);
    }

    @Test
    public void testExecuteJexlWithVariable() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        JexlTool tool = new JexlTool(registry);
        String result = tool.executeJexl("var x = 10; x * 2");
        assertEquals("20", result);
    }

    @Test
    public void testExecuteJexlNull() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        JexlTool tool = new JexlTool(registry);
        String result = tool.executeJexl("null");
        assertEquals("null", result);
    }

    @Test
    public void testExecuteJexlError() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        JexlTool tool = new JexlTool(registry);
        String result = tool.executeJexl("if (");
        assertTrue(result.contains("Syntax Error"));
    }
}