package org.roxycode.app.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.jexl3.JexlFeatures;
import org.apache.commons.jexl3.JexlInfo;
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
    void testSyntaxErrorClassificationsAndLocationExtraction() {
        String multilineStr = "var x = " + '"' + "line1" + "\n" + "line2" + '"' + ";";
        JexlErrorAnalyzer.ErrorAnalysis multilineAnalysis = analyzeScript(multilineStr);
        assertEquals("MULTILINE_QUOTED_STRING", multilineAnalysis.errorCategory());
        assertEquals(1, multilineAnalysis.line());
        assertNotNull(multilineAnalysis.offendingLine());
        assertTrue(multilineAnalysis.hint().contains("backticks"));

        String constScript = "var a = 1;\nconst b = 2;";
        JexlErrorAnalyzer.ErrorAnalysis constAnalysis = JexlErrorAnalyzer
                .analyze(new JexlException.Parsing(new JexlInfo("script", 2, 1), "const"), constScript, true);
        assertEquals("JS_CONST_LET", constAnalysis.errorCategory());
        assertEquals(2, constAnalysis.line());
        assertEquals("const b = 2;", constAnalysis.offendingLine());
        assertTrue(constAnalysis.hint().contains("'var'"));

        JexlErrorAnalyzer.ErrorAnalysis bracketMap = analyzeScript("var m = ['key': 1];");
        assertEquals("BRACKET_MAP_LITERAL", bracketMap.errorCategory());
        assertTrue(bracketMap.hint().contains("curly braces"));

        JexlErrorAnalyzer.ErrorAnalysis forOf = analyzeScript("for (var x of [1, 2]) { x; }");
        assertEquals("JS_FOR_OF_OR_IN", forOf.errorCategory());
        assertTrue(forOf.hint().contains("':'"));

        JexlErrorAnalyzer.ErrorAnalysis strictEq = JexlErrorAnalyzer
                .analyze(new JexlException.Parsing(new JexlInfo("script", 1, 7), "==="), "if (1 === 1) { 1; }", true);
        assertEquals("STRICT_EQUALITY", strictEq.errorCategory());
        assertTrue(strictEq.hint().contains("'=='"));

        JexlErrorAnalyzer.ErrorAnalysis shorthand = analyzeScript("var a = 1; var m = { 'k': 1, a };");
        assertEquals("OBJECT_SHORTHAND", shorthand.errorCategory());
        assertTrue(shorthand.hint().contains("explicit key-value"));

        JexlErrorAnalyzer.ErrorAnalysis genericSyntax = analyzeScript("if (");
        assertEquals("SYNTAX_ERROR", genericSyntax.errorCategory());
        assertNotNull(genericSyntax.hint());
    }

    @Test
    void testRuntimeErrorClassifications() {
        JexlErrorAnalyzer.ErrorAnalysis arrayAdd = analyzeScript("var list = ['a', 'b']; list.add('c');");
        assertEquals("ARRAY_ADD_MUTATION", arrayAdd.errorCategory());
        assertTrue(arrayAdd.hint().contains("[..., ...]"));

        JexlErrorAnalyzer.ErrorAnalysis unsolvableMethod = analyzeScript("'hello'.noSuchMethod();");
        assertEquals("UNSOLVABLE_METHOD", unsolvableMethod.errorCategory());
        assertTrue(unsolvableMethod.hint().contains("noSuchMethod"));

        JexlErrorAnalyzer.ErrorAnalysis unsolvableProp = analyzeScript("'hello'.noSuchProp;");
        assertEquals("UNSOLVABLE_PROPERTY", unsolvableProp.errorCategory());
        assertTrue(unsolvableProp.hint().contains("noSuchProp"));

        JexlErrorAnalyzer.ErrorAnalysis unsolvableVar = analyzeScript("unknownService.doWork();");
        assertEquals("UNSOLVABLE_PROPERTY", unsolvableVar.errorCategory());
        assertTrue(unsolvableVar.hint().contains("unknownService"));

        JexlErrorAnalyzer.ErrorAnalysis runtime = JexlErrorAnalyzer
                .analyze(new IllegalArgumentException("Bad argument"), "foo()", false);
        assertEquals("RUNTIME_ERROR", runtime.errorCategory());
        assertEquals("Bad argument", runtime.error());
        assertNotNull(runtime.hint());
    }
}
