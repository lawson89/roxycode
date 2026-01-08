package org.roxycode.core.tools.service;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.roxycode.core.Sandbox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@MicronautTest
public class RefactoringServiceTest {

    @Inject
    RefactoringService refactoringService;

    @Inject
    Sandbox sandbox;

    @TempDir
    Path tempDir;

    @Test
    void testOrganizeImports() throws IOException {
        sandbox.setRoot(tempDir);
        Path javaFile = tempDir.resolve("TestFile.java");
        String content = "package test;\n" +
                "import java.util.List;\n" +
                "import java.util.ArrayList;\n" +
                "import java.util.HashSet;\n" +
                "public class TestFile {\n" +
                "    List<String> list = new ArrayList<>();\n" +
                "}";
        Files.writeString(javaFile, content);

        refactoringService.organizeImports("TestFile.java");

        String newContent = Files.readString(javaFile);
        assertTrue(newContent.contains("import java.util.List;"));
        assertTrue(newContent.contains("import java.util.ArrayList;"));
        assertFalse(newContent.contains("import java.util.HashSet;"));
    }

    @Test
    void testRenameClassProjectWide() throws IOException {
        sandbox.setRoot(tempDir);
        
        Path dir1 = tempDir.resolve("org/example");
        Files.createDirectories(dir1);
        Path file1 = dir1.resolve("OldClass.java");
        Files.writeString(file1, "package org.example;\npublic class OldClass { }");

        Path file2 = dir1.resolve("UsageSamePackage.java");
        Files.writeString(file2, "package org.example;\npublic class UsageSamePackage {\n    OldClass o = new OldClass();\n}");

        Path dir2 = tempDir.resolve("org/other");
        Files.createDirectories(dir2);
        Path file3 = dir2.resolve("UsageOtherPackage.java");
        Files.writeString(file3, "package org.other;\nimport org.example.OldClass;\npublic class UsageOtherPackage {\n    OldClass o = new OldClass();\n}");

        refactoringService.renameClass("org.example.OldClass", "NewClass");

        assertFalse(Files.exists(file1));
        Path newFile1 = dir1.resolve("NewClass.java");
        assertTrue(Files.exists(newFile1));
        assertTrue(Files.readString(newFile1).contains("public class NewClass"));

        assertTrue(Files.readString(file2).contains("NewClass o = new NewClass()"));

        String content3 = Files.readString(file3);
        assertTrue(content3.contains("import org.example.NewClass;"));
        assertTrue(content3.contains("NewClass o = new NewClass()"));
    }

    @Test
    void testExtractMethod() throws IOException {
        sandbox.setRoot(tempDir);
        Path javaFile = tempDir.resolve("ExtractTest.java");
        String content = "public class ExtractTest {\n" +
                "    public void main() {\n" +
                "        System.out.println(\"Step 1\");\n" +
                "        System.out.println(\"Step 2\");\n" +
                "        System.out.println(\"Step 3\");\n" +
                "    }\n" +
                "}";
        Files.writeString(javaFile, content);

        refactoringService.extractMethod("ExtractTest.java", 4, 5, "helper");

        String newContent = Files.readString(javaFile);
        assertTrue(newContent.contains("helper();"));
        assertTrue(newContent.contains("private void helper() {"));
        assertTrue(newContent.contains("System.out.println(\"Step 2\");"));
        assertTrue(newContent.contains("System.out.println(\"Step 3\");"));
    }

    @Test
    void testExtractInterface() throws IOException {
        sandbox.setRoot(tempDir);
        Path javaFile = tempDir.resolve("MyClass.java");
        String content = "package com.test;\n" +
                "public class MyClass {\n" +
                "    public void methodA() { }\n" +
                "    public void methodB(String s) { }\n" +
                "}";
        Files.writeString(javaFile, content);

        refactoringService.extractInterface("MyClass.java", "MyInterface", List.of("methodA", "methodB"));

        assertTrue(Files.exists(tempDir.resolve("MyInterface.java")));
        String interfaceContent = Files.readString(tempDir.resolve("MyInterface.java"));
        assertTrue(interfaceContent.contains("interface MyInterface"));
        assertTrue(interfaceContent.contains("void methodA();"));
        assertTrue(interfaceContent.contains("void methodB(String s);"));

        String classContent = Files.readString(javaFile);
        assertTrue(classContent.contains("public class MyClass implements MyInterface"));
    }

    @Test
    void testMoveClass() throws IOException {
        sandbox.setRoot(tempDir);
        
        Path dir1 = tempDir.resolve("org/old");
        Files.createDirectories(dir1);
        Path file1 = dir1.resolve("MoveMe.java");
        Files.writeString(file1, "package org.old;\npublic class MoveMe { }");

        Path dir2 = tempDir.resolve("org/other");
        Files.createDirectories(dir2);
        Path file2 = dir2.resolve("Usage.java");
        Files.writeString(file2, "package org.other;\nimport org.old.MoveMe;\npublic class Usage {\n    MoveMe m = new MoveMe();\n}");

        refactoringService.moveClass("org.old.MoveMe", "org.newpackage");

        assertFalse(Files.exists(file1));
        assertTrue(Files.exists(tempDir.resolve("org/newpackage/MoveMe.java")));
        assertTrue(Files.readString(tempDir.resolve("org/newpackage/MoveMe.java")).contains("package org.newpackage;"));

        String usageContent = Files.readString(file2);
        assertTrue(usageContent.contains("import org.newpackage.MoveMe;"));
        assertFalse(usageContent.contains("import org.old.MoveMe;"));
    }
}
