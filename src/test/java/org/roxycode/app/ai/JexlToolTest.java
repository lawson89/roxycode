package org.roxycode.app.ai;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        // result is a JSON string representing the service or its result

        String result = tool.executeJexl("testService.hello('Roxy')");
        assertTrue(result.contains("Hello, Roxy"));
    }

    @Test
    public void testExecuteJexlSuccess() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        JexlTool tool = new JexlTool(registry);
        String result = tool.executeJexl("1 + 1");
        assertTrue(result.contains("\"result\":\"2\""));
    }

    @Test
    public void testExecuteJexlWithVariable() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        JexlTool tool = new JexlTool(registry);
        String result = tool.executeJexl("var x = 10; x * 2");
        assertTrue(result.contains("\"result\":\"20\""));
    }

    @Test
    public void testExecuteJexlNull() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        JexlTool tool = new JexlTool(registry);
        String result = tool.executeJexl("null");
        assertTrue(result.contains("\"result\":\"null\""));
    }

    @Test
    public void testExecuteJexlError() {
        JexlServiceRegistry registry = new JexlServiceRegistry();
        JexlTool tool = new JexlTool(registry);
        String result = tool.executeJexl("if (");
        assertTrue(result.contains("Syntax Error"));
    }
}