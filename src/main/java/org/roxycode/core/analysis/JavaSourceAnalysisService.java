package org.roxycode.core.analysis;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.utils.SourceRoot;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.StringWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

@Singleton
public class JavaSourceAnalysisService {

    private static final Logger LOG = LoggerFactory.getLogger(JavaSourceAnalysisService.class);

    /**
     * Generates a skeleton of the Java source files in the given directory
     * and writes it to the specified output file.
     *
     * @param sourcePath The root folder of the source code
     * @param outputPath The path to the file where the skeleton will be written
     * @throws IOException If an I/O error occurs
     */
    public void generateSkeletonToFile(Path sourcePath, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
            generateSkeleton(sourcePath, writer);
        }
    }

    /**
     * Generates a skeleton of the Java source files in the given directory
     * and returns it as a string.
     *
     * @param sourcePath The root folder of the source code
     * @return The generated skeleton string
     */
    public String generateSkeletonToString(Path sourcePath) {
        StringWriter stringWriter = new StringWriter();
        try (BufferedWriter writer = new BufferedWriter(stringWriter)) {
            generateSkeleton(sourcePath, writer);
        } catch (IOException e) {
            // Should not happen with StringWriter, but we must handle it
            throw new UncheckedIOException(e);
        }
        return stringWriter.toString();
    }

    /**
     * Scans the given directory for .java files and streams a "skeleton"
     * (signatures + javadocs, no private members) to the provided writer.
     * <p>
     * Note: This method does NOT close the writer.
     *
     * @param sourcePath The root folder of the source code (e.g. src/main/java)
     * @param writer     The open writer to stream the summary to
     */
    protected void generateSkeleton(Path sourcePath, BufferedWriter writer) {
        // Configure Parser
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);

        SourceRoot sourceRoot = new SourceRoot(sourcePath, config);

        try {
            // Parse all files
            List<ParseResult<CompilationUnit>> results = sourceRoot.tryToParse();
            SkeletonVisitor visitor = new SkeletonVisitor();

            for (ParseResult<CompilationUnit> result : results) {
                if (result.isSuccessful() && result.getResult().isPresent()) {
                    CompilationUnit cu = result.getResult().get();
                    try {
                        // Visit the AST and write to the provided writer
                        cu.accept(visitor, writer);
                        writer.write("\n\n"); // Spacing between files
                        writer.flush();       // Optional: flush after every file for real-time streaming
                    } catch (UncheckedIOException | IOException e) {
                        LOG.error("Error writing context for file", e);
                    }
                } else {
                    LOG.warn("Skipped file due to parse error: {}", result.getProblems());
                }
            }
        } catch (IOException e) {
            LOG.error("Failed to read source files from {}", sourcePath, e);
        }
    }

    /**
     * Visitor that writes AST nodes directly to a Writer.
     */
    private static class SkeletonVisitor extends VoidVisitorAdapter<Writer> {

        @Override
        public void visit(ClassOrInterfaceDeclaration n, Writer w) {
            write(w, getJavadoc(n));

            write(w, n.getAccessSpecifier().asString() + " ");
            write(w, n.isInterface() ? "interface " : "class ");
            write(w, n.getNameAsString());

            if (!n.getImplementedTypes().isEmpty()) {
                write(w, " implements " + n.getImplementedTypes().get(0).getNameAsString());
            }
            if (!n.getExtendedTypes().isEmpty()) {
                write(w, " extends " + n.getExtendedTypes().get(0).getNameAsString());
            }

            write(w, " {\n");
            super.visit(n, w);
            write(w, "}\n");
        }

        @Override
        public void visit(FieldDeclaration n, Writer w) {
            if (n.isPrivate()) return;

            write(w, getJavadoc(n));
            write(w, "  " + n.getVariable(0).getTypeAsString() + " " + n.getVariable(0).getNameAsString() + ";\n");
        }

        @Override
        public void visit(MethodDeclaration n, Writer w) {
            if (n.isPrivate()) return;

            write(w, getJavadoc(n));
            write(w, "  ");
            // true, true, true = include modifiers, types, and parameter names
            write(w, n.getDeclarationAsString(true, true, true));
            write(w, ";\n\n");
        }

        // --- Helpers ---

        private String getJavadoc(BodyDeclaration<?> n) {
            return n.getComment()
                    .filter(Comment::isJavadocComment)
                    .map(comment -> {
                        String javadoc = comment.toString();
                        return javadoc.lines()
                                .map(line -> line.trim())
                                .map(line -> "  " + line)
                                .collect(Collectors.joining("\n")) + "\n";
                    })
                    .orElse("");
        }

        private void write(Writer w, String s) {
            try {
                w.write(s);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

}
