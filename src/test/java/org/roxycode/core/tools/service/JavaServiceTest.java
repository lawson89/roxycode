package org.roxycode.core.tools.service;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.roxycode.core.Sandbox;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.core.tools.service.java.JavaService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class JavaServiceTest {

    @Inject
    JavaService javaAnalysisService;

    @Inject
    Sandbox sandbox;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        sandbox.setRoot(tempDir.toString());
    }

    @Test
    void testAnalyzeFile() throws IOException {
        Path path = tempDir.resolve("TestClass.java");
        Files.writeString(path, """
            package test;
            public class TestClass {
                public void method1() {}
                public void method2() {}
            }
            """);

        JavaService.JavaFileSummary summary = javaAnalysisService.analyzeFile(path.toString());

        assertNotNull(summary);
        assertEquals(1, summary.classes().size());
        assertEquals("TestClass", summary.classes().get(0).name());
        assertFalse(summary.classes().get(0).isInterface());
        assertEquals(2, summary.classes().get(0).methods().size());
    }

    @Test
    void testAnalyzeFileWithFields() throws IOException {
        Path path = tempDir.resolve("TestClassWithFields.java");
        Files.writeString(path, """
            package test;
            public class TestClassWithFields {
                private String field1;
                public int field2 = 10;
                
                public void method1() {}
            }
            """);

        JavaService.JavaFileSummary summary = javaAnalysisService.analyzeFile(path.toString());

        assertNotNull(summary);
        assertEquals(1, summary.classes().size());
        JavaService.ClassSummary classSummary = summary.classes().get(0);
        assertEquals("TestClassWithFields", classSummary.name());
        assertEquals(2, classSummary.fields().size());
        
        JavaService.FieldSummary field1 = classSummary.fields().stream()
                .filter(f -> f.name().equals("field1"))
                .findFirst()
                .orElseThrow();
        assertEquals("String", field1.type());
        
        JavaService.FieldSummary field2 = classSummary.fields().stream()
                .filter(f -> f.name().equals("field2"))
                .findFirst()
                .orElseThrow();
        assertEquals("int", field2.type());
    }

    @Test
    void testGetMethodSource() throws IOException {
        Path path = tempDir.resolve("TestClass.java");
        Files.writeString(path, """
            package test;
            public class TestClass {
                public void method1() {
                    System.out.println("Hello");
                }
            }
            """);

        Optional<String> source = javaAnalysisService.getMethodSource(path.toString(), "TestClass", "method1");

        assertTrue(source.isPresent());
        assertTrue(source.get().contains("System.out.println(\"Hello\");"));
    }

    @Test
    void testReplaceMethod() throws IOException {
        Path path = tempDir.resolve("TestClass.java");
        Files.writeString(path, """
            package test;
            public class TestClass {
                public void method1() {
                    System.out.println("Old");
                }
            }
            """);

        String newMethod = """
            public void method1() {
                System.out.println("New");
            }
            """;

        javaAnalysisService.replaceMethod(path.toString(), "TestClass", "method1", newMethod);

        String updatedContent = Files.readString(path);
        assertTrue(updatedContent.contains("System.out.println(\"New\");"));
        assertFalse(updatedContent.contains("System.out.println(\"Old\");"));
    }

    @Test
    void testGetFieldSource() throws IOException {
        Path path = tempDir.resolve("TestClassWithFields.java");
        Files.writeString(path, """
            package test;
            public class TestClassWithFields {
                private String field1 = "Original";
            }
            """);

        Optional<String> source = javaAnalysisService.getFieldSource(path.toString(), "TestClassWithFields", "field1");

        assertTrue(source.isPresent());
        assertTrue(source.get().contains("private String field1 = \"Original\";"));
    }

    @Test
    void testReplaceField() throws IOException {
        Path path = tempDir.resolve("TestClassWithFields.java");
        Files.writeString(path, """
            package test;
            public class TestClassWithFields {
                private String field1 = "OldValue";
            }
            """);

        String newField = "private String field1 = \"NewValue\";";

        javaAnalysisService.replaceField(path.toString(), "TestClassWithFields", "field1", newField);

        String updatedContent = Files.readString(path);
        assertTrue(updatedContent.contains("private String field1 = \"NewValue\";"));
        assertFalse(updatedContent.contains("OldValue"));
    }

    @Test
    void testGetClassDependencies() throws IOException {
        Path path = tempDir.resolve("DependencyTest.java");
        Files.writeString(path, """
            package test;
            import java.util.List;
            import java.util.ArrayList;
            
            public class DependencyTest extends BaseClass implements MyInterface {
                private OtherClass field;
                
                public void method(ParamClass p) {
                    LocalClass l = new LocalClass();
                }
            }
            """);

        List<String> dependencies = javaAnalysisService.getClassDependencies(path.toString(), "DependencyTest");

        assertNotNull(dependencies);
        assertTrue(dependencies.contains("BaseClass"));
        assertTrue(dependencies.contains("MyInterface"));
        assertTrue(dependencies.contains("OtherClass"));
        assertTrue(dependencies.contains("ParamClass"));
        assertTrue(dependencies.contains("LocalClass"));
        // Check that Java library types are excluded if handled by isJavaLibraryType
        assertFalse(dependencies.contains("String"));
        assertFalse(dependencies.contains("List"));
        assertFalse(dependencies.contains("ArrayList"));
    }

    @Test
    void testAnalyzeFileIncludesDependencies() throws IOException {
        Path path = tempDir.resolve("SummaryTest.java");
        Files.writeString(path, """
            package test;
            public class SummaryTest {
                private Dependency dep;
            }
            """);

        JavaService.JavaFileSummary summary = javaAnalysisService.analyzeFile(path.toString());

        assertNotNull(summary);
        assertEquals(1, summary.classes().size());
        JavaService.ClassSummary classSummary = summary.classes().get(0);
        assertTrue(classSummary.dependencies().contains("Dependency"));
    }
    @Test
    void testJavadocExtraction() throws IOException {
        Path path = tempDir.resolve("JavadocTest.java");
        Files.writeString(path, """
            package test;
            /**
             * Class Javadoc
             */
            public class JavadocTest {
                /**
                 * Field Javadoc
                 */
                private String field;
                
                /**
                 * Method Javadoc
                 */
                public void method() {}
            }
            """);

        JavaService.JavaFileSummary summary = javaAnalysisService.analyzeFile(path.toString());
        JavaService.ClassSummary classSummary = summary.classes().get(0);
        
        assertTrue(classSummary.javadoc().contains("Class Javadoc"));
        assertTrue(classSummary.fields().get(0).javadoc().contains("Field Javadoc"));
        assertTrue(classSummary.methods().get(0).javadoc().contains("Method Javadoc"));

        Optional<String> classJd = javaAnalysisService.getClassJavadoc(path.toString(), "JavadocTest");
        assertTrue(classJd.isPresent());
        assertTrue(classJd.get().contains("Class Javadoc"));

        Optional<String> methodJd = javaAnalysisService.getMethodJavadoc(path.toString(), "JavadocTest", "method");
        assertTrue(methodJd.isPresent());
        assertTrue(methodJd.get().contains("Method Javadoc"));

        Optional<String> fieldJd = javaAnalysisService.getFieldJavadoc(path.toString(), "JavadocTest", "field");
        assertTrue(fieldJd.isPresent());
        assertTrue(fieldJd.get().contains("Field Javadoc"));
    }

    @Test
    void testTextBlockSupport() throws IOException {
        Path path = tempDir.resolve("TextBlockTest.java");
        Files.writeString(path, "public class TextBlockTest { String s = \"\"\"\n" +
                "        hello\n" +
                "        \"\"\"; }");

        JavaService.JavaFileSummary summary = javaAnalysisService.analyzeFile(path.toString());
        assertNotNull(summary);
        assertEquals("TextBlockTest", summary.classes().get(0).name());
    }


    @Test
    void testRecordSupport() throws IOException {
        Path path = tempDir.resolve("RecordTest.java");
        Files.writeString(path, """
            package test;
            public record RecordTest(String name, int value) {
                public void hello() {}
            }
            """);

        JavaService.JavaFileSummary summary = javaAnalysisService.analyzeFile(path.toString());

        assertNotNull(summary);
        assertEquals(1, summary.classes().size());
        JavaService.ClassSummary classSummary = summary.classes().get(0);
        assertEquals("RecordTest", classSummary.name());
        assertEquals(1, classSummary.methods().size());
        assertEquals("hello", classSummary.methods().get(0).name());
    }
}
