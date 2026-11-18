package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlFeatures;
import org.apache.commons.jexl3.JexlScript;
import org.apache.commons.jexl3.MapContext;
import org.junit.jupiter.api.Test;

class JexlErrorAnalyzerTest {

    private static final JexlEngine ENGINE = new JexlBuilder()
            .features(new JexlFeatures().newInstance(false).fatArrow(true)).strict(true).silent(false).safe(false)
            .create();

    private static JexlErrorAnalyzer.ErrorAnalysis analyzeScript(String script) {
        JexlScript compiled;
        try {
            compiled = ENGINE.createScript(script);
        } catch (Exception e) {
            return JexlErrorAnalyzer.analyze(e, script, true);
        }
        try {
            compiled.execute(new MapContext());
            fail("Expected script to fail: " + script);
            return null;
        } catch (Exception e) {
            return JexlErrorAnalyzer.analyze(e, script, false);
        }
    }

    @Test
    void testSyntaxAndRuntimeErrorClassifications() {
        JexlErrorAnalyzer.ErrorAnalysis syntax = analyzeScript("var a = 1;\nif (");
        assertEquals("SYNTAX_ERROR", syntax.errorCategory());
        assertTrue(syntax.syntaxStage());
        assertEquals(2, syntax.line());
        assertEquals("if (", syntax.offendingLine());
        assertNotNull(syntax.hint());

        JexlErrorAnalyzer.ErrorAnalysis unknownMethod = analyzeScript("'hello'.noSuchMethod();");
        assertEquals("UNKNOWN_METHOD", unknownMethod.errorCategory());
        assertTrue(unknownMethod.hint().contains("noSuchMethod"));

        JexlErrorAnalyzer.ErrorAnalysis unknownProp = analyzeScript("'hello'.noSuchProp;");
        assertEquals("UNKNOWN_PROPERTY", unknownProp.errorCategory());
        assertTrue(unknownProp.hint().contains("noSuchProp"));

        JexlErrorAnalyzer.ErrorAnalysis unknownVar = analyzeScript("unknownService.doWork();");
        assertEquals("UNKNOWN_VARIABLE", unknownVar.errorCategory());
        assertTrue(unknownVar.hint().contains("unknownService"));

        JexlErrorAnalyzer.ErrorAnalysis nullOperand = analyzeScript("null + 1;");
        assertEquals("NULL_OPERAND", nullOperand.errorCategory());
        assertNotNull(nullOperand.hint());

        IllegalStateException wrapped = new IllegalStateException("outer failure",
                new IllegalArgumentException("inner failure"));
        JexlErrorAnalyzer.ErrorAnalysis runtime = JexlErrorAnalyzer.analyze(wrapped, "svc.op()", false);
        assertEquals("EXECUTION_ERROR", runtime.errorCategory());
        assertEquals("inner failure", runtime.error());
        assertFalse(runtime.syntaxStage());
        assertEquals(IllegalStateException.class.getName(), runtime.exceptionType());
        assertEquals(IllegalArgumentException.class.getName(), runtime.rootCauseType());
        assertEquals(2, runtime.causeChain().size());
        assertFalse(runtime.appStackFrames().isEmpty());
        assertTrue(runtime.appStackFrames().get(0).startsWith("JexlErrorAnalyzerTest."));
    }
}