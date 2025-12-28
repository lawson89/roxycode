package org.roxycode.core.tools;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import io.micronaut.context.ApplicationContext;
import jakarta.inject.Singleton;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.roxycode.core.FileSystemService;
import org.roxycode.core.Sandbox;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.*;

@Singleton
public class ToolExecutionService {
    private final ExecutorService executorService;
    private final Sandbox sandbox;
    private final FileSystemService fs;
    private final ApplicationContext applicationContext;

    public ToolExecutionService(Sandbox sandbox, FileSystemService fs, ApplicationContext applicationContext) {
        // CachedThreadPool for Platform Threads as per requirements
        this.executorService = Executors.newCachedThreadPool();
        this.sandbox = sandbox;
        this.fs = fs;
        this.applicationContext = applicationContext;
    }

    public Future<String> execute(ToolDefinition tool, Map<String, Object> args) {
        return executorService.submit(() -> runScript(tool, args));
    }

    private String runScript(ToolDefinition tool, Map<String, Object> args) throws IOException {
        String scriptContent = resolveScriptContent(tool);
        if (tool.getSource().endsWith(".js")) {
            return executeJavaScript(scriptContent, args);
        } else {
            return executeGroovy(tool, args);
        }
    }

    private String executeGroovy(ToolDefinition tool, Map<String, Object> args) throws IOException {
        // Prepare Shell
        CompilerConfiguration config = new CompilerConfiguration();
        SecureASTCustomizer secure = new SecureASTCustomizer();
        // Basic security: disallow System.exit to prevent killing the IDE
        //secure.setDisallowedReceivers(Collections.singletonList("java.lang.System")); // Very basic check
        // Note: Full sandboxing in Groovy is complex; this is a baseline.

        config.addCompilationCustomizers(secure);

        Binding binding = new Binding();
        // Inject dependencies
        binding.setVariable("sandbox", sandbox);
        binding.setVariable("fs", fs);
        binding.setVariable("ctx", applicationContext);
        binding.setVariable("args", args);

        GroovyShell shell = new GroovyShell(binding, config);

        String scriptContent = resolveScriptContent(tool);
        Object result = shell.evaluate(scriptContent);

        return result != null ? result.toString() : "";
    }

    private String executeJavaScript(String script, Map<String, Object> args) {
        try (Context context = Context.newBuilder("js")
                .allowAllAccess(true)
                .build()) {
            context.getBindings("js").putMember("sandbox", this.sandbox);
            context.getBindings("js").putMember("fs", this.fs);
            context.getBindings("js").putMember("ctx", this.applicationContext);
            context.getBindings("js").putMember("args", args);
            Value result = context.eval(Source.create("js", script));
            return result != null ? result.toString() : "";
        } catch (Exception e) {
            return "Error executing JavaScript: " + e.getMessage();
        }
    }

    private String resolveScriptContent(ToolDefinition tool) throws IOException {
        String src = tool.getSource();
        if (src.startsWith("file:")) {
            // Relative resolution logic
            String relativePath = src.substring(5); // remove "file:"
            Path scriptPath = tool.getDefinitionLocation().resolve(relativePath).normalize();
            return Files.readString(scriptPath);
        }
        return src; // Inline script
    }
}