package org.roxycode.app.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class JexlToolTest {

    @Test
    public void testExecuteJexlSuccess() {
        JexlTool tool = new JexlTool();
        String result = tool.executeJexl("1 + 1");
        assertEquals("2", result);
    }

    @Test
    public void testExecuteJexlWithVariable() {
        JexlTool tool = new JexlTool();
        // Jexl 3 supports local variables with 'var'
        String result = tool.executeJexl("var x = 10; x * 2");
        assertEquals("20", result);
    }

    @Test
    public void testExecuteJexlNull() {
        JexlTool tool = new JexlTool();
        String result = tool.executeJexl("null");
        assertEquals("null", result);
    }

    @Test
    public void testExecuteJexlError() {
        JexlTool tool = new JexlTool();
        // Syntax error
        String result = tool.executeJexl("if (");
        assertTrue(result.contains("Error executing Jexl script"));
    }
}
