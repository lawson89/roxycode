package org.roxycode.app.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class AgentDocTest {

    @AgentDoc(
        value = "Test class",
        examples = {"example1", "example2"},
        notes = {"note1"}
    )
    static class TestClass {
        @AgentDoc("Test field")
        public String testField;

        @AgentDoc(value = "Test method", examples = {"methodExample"})
        public void testMethod(@AgentDoc("Test param") String param) {}
    }

    @Test
    public void testAnnotationValues() throws Exception {
        AgentDoc classDoc = TestClass.class.getAnnotation(AgentDoc.class);
        assertNotNull(classDoc);
        assertEquals("Test class", classDoc.value());
        assertArrayEquals(new String[]{"example1", "example2"}, classDoc.examples());
        assertArrayEquals(new String[]{"note1"}, classDoc.notes());

        Field field = TestClass.class.getField("testField");
        AgentDoc fieldDoc = field.getAnnotation(AgentDoc.class);
        assertNotNull(fieldDoc);
        assertEquals("Test field", fieldDoc.value());

        Method method = TestClass.class.getMethod("testMethod", String.class);
        AgentDoc methodDoc = method.getAnnotation(AgentDoc.class);
        assertNotNull(methodDoc);
        assertEquals("Test method", methodDoc.value());
        assertArrayEquals(new String[]{"methodExample"}, methodDoc.examples());

        AgentDoc paramDoc = (AgentDoc) method.getParameters()[0].getAnnotation(AgentDoc.class);
        assertNotNull(paramDoc);
        assertEquals("Test param", paramDoc.value());
    }
}
