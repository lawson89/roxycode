package org.roxycode.core.service;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Singleton
public class JavaAnalysisServiceImpl implements JavaAnalysisService {

    @PostConstruct
    public void init() {
        StaticJavaParser.getParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    @Override
    public JavaFileSummary analyzeFile(Path path) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(path);

        List<String> imports = cu.getImports().stream()
                .map(ImportDeclaration::getNameAsString)
                .collect(Collectors.toList());

        List<ClassSummary> classes = cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .map(this::summarizeClass)
                .collect(Collectors.toList());

        return new JavaFileSummary(classes, imports);
    }

    private ClassSummary summarizeClass(ClassOrInterfaceDeclaration n) {
        List<MethodSummary> methods = n.getMethods().stream()
                .map(this::summarizeMethod)
                .collect(Collectors.toList());
        return new ClassSummary(n.getNameAsString(), methods, n.isInterface());
    }

    private MethodSummary summarizeMethod(MethodDeclaration m) {
        return new MethodSummary(
                m.getNameAsString(),
                m.getDeclarationAsString(),
                m.getBegin().map(p -> p.line).orElse(-1),
                m.getEnd().map(p -> p.line).orElse(-1)
        );
    }

    @Override
    public Optional<String> getMethodSource(Path path, String className, String methodName) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(path);
            return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(c -> c.getNameAsString().equals(className))
                    .flatMap(c -> c.getMethodsByName(methodName).stream())
                    .findFirst()
                    .map(m -> m.toString());
        } catch (IOException e) {
            return Optional.empty();
        }
    }
}
