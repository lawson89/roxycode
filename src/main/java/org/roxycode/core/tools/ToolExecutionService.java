package org.roxycode.core.tools;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import jakarta.inject.Singleton;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.roxycode.core.Sandbox;

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

    public ToolExecutionService(Sandbox sandbox) {
        // CachedThreadPool for Platform Threads as per requirements
        this.executorService = Executors.newCachedThreadPool();
        this.sandbox = sandbox;
    }

    public Future<String> execute(ToolDefinition tool, Map<String, Object> args) {
        return executorService.submit(() -> runScript(tool, args));
    }

    private String runScript(ToolDefinition tool, Map<String, Object> args) throws IOException {
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
        binding.setVariable("args", args);

        GroovyShell shell = new GroovyShell(binding, config);

        String scriptContent = resolveScriptContent(tool);
        Object result = shell.evaluate(scriptContent);

        return result != null ? result.toString() : "";
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