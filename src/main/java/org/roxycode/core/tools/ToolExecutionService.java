package org.roxycode.core.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.roxycode.core.tools.service.*;
import org.roxycode.core.Sandbox;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;

@Singleton
public class ToolExecutionService {
    private final ExecutorService executorService;
    private final Sandbox sandbox;
    private final FileSystemService fs;
    private final GrepService grepService;
    private final GitService gitService;
    private final TikaService tikaService;
    private final JavaService javaAnalysisService;
    private final XmlService xmlService;
    private final TomlService tomlService;
    
    private final ObjectMapper objectMapper;

    public ToolExecutionService(Sandbox sandbox,
                                FileSystemService fs,
                                GrepService grepService,
                                GitService gitService,
                                TikaService tikaService,
                                JavaService javaAnalysisService,
                                XmlService xmlService,
                                TomlService tomlService,
                                
                                ObjectMapper objectMapper) {
        // CachedThreadPool for Platform Threads as per requirements
        this.executorService = Executors.newCachedThreadPool();
        this.sandbox = sandbox;
        this.fs = fs;
        this.grepService = grepService;
        this.gitService = gitService;
        this.tikaService = tikaService;
        this.javaAnalysisService = javaAnalysisService;
        this.xmlService = xmlService;
        this.tomlService = tomlService;
        
        this.objectMapper = objectMapper;
    }

    public Future<String> execute(ToolDefinition tool, Map<String, Object> args) {
        return executorService.submit(() -> runScript(tool, args));
    }

    private String runScript(ToolDefinition tool, Map<String, Object> args) throws IOException {
        String scriptContent = resolveScriptContent(tool);
        // All scripts are now treated as JavaScript
        return executeJavaScript(scriptContent, args);
    }

    private String executeJavaScript(String script, Map<String, Object> args) {
        try (Context context = Context.newBuilder("js")
                .allowAllAccess(true)
                .build()) {

            // 1. Bind your services
            context.getBindings("js").putMember("sandbox", this.sandbox);
            context.getBindings("js").putMember("fs", this.fs);
            context.getBindings("js").putMember("grep", this.grepService);
            context.getBindings("js").putMember("git", this.gitService);
            context.getBindings("js").putMember("tika", this.tikaService);
            context.getBindings("js").putMember("java", this.javaAnalysisService);
            context.getBindings("js").putMember("xml", this.xmlService);
            context.getBindings("js").putMember("toml", this.tomlService);
            context.getBindings("js").putMember("json", this.objectMapper);

            // 2. Wrap the Java Map in ProxyObject
            context.getBindings("js").putMember("args", ProxyObject.fromMap(args));

            // 3. Execute
            Value result = context.eval(Source.create("js", script));
            return result != null ? result.toString() : "";

        } catch (Exception e) {
            String stackTrace = ExceptionUtils.getStackTrace(e);
            return "Error executing JavaScript: " + e.getMessage() + "\n" + stackTrace;
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
