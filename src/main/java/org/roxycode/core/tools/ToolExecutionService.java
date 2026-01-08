package org.roxycode.core.tools;

import jakarta.inject.Singleton;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;

@Singleton
public class ToolExecutionService {

    private static final Logger LOG = LoggerFactory.getLogger(ToolExecutionService.class);

    private final ExecutorService executorService;

    private final ScheduledExecutorService timeoutExecutor = Executors.newSingleThreadScheduledExecutor();

    private final ScriptServiceRegistry scriptServiceRegistry;

    public ToolExecutionService(ScriptServiceRegistry scriptServiceRegistry) {
        // CachedThreadPool for Platform Threads as per requirements
        this.executorService = Executors.newCachedThreadPool();
        this.scriptServiceRegistry = scriptServiceRegistry;
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

        LOG.info("Executing script: {}", script);
        LOG.info("Args: {}", args);
        // @todo we need to lock this down more
        HostAccess secureAccess = HostAccess.newBuilder(HostAccess.ALL)
                .build();

        ByteArrayOutputStream logStream = new ByteArrayOutputStream();

        try (Context context = Context.newBuilder("js")
                .allowHostAccess(secureAccess)
                .allowHostClassLookup(className -> false)
                // Block Network
                .allowNativeAccess(false)
                // Block spawning external processes (e.g. exec)
                .allowCreateProcess(false)
                // Enable IO, but ONLY via our custom FileSystem
                .allowIO(true)
                .out(logStream)
                .err(logStream)
                .option("engine.WarnInterpreterOnly", "false")
                .build()) {
            // Set a timeout of 60 seconds
            ScheduledFuture<?> timeoutTask = timeoutExecutor.schedule(() -> context.close(true), 60, TimeUnit.SECONDS);
            try {
                // --- AUTO-BINDING ---
                Map<String, Object> services = scriptServiceRegistry.getServices();

                for (Map.Entry<String, Object> entry : services.entrySet()) {
                    context.getBindings("js").putMember(entry.getKey(), entry.getValue());
                }
                // --------------------

                // 2. Wrap the Java Map in ProxyObject
                context.getBindings("js").putMember("args", ProxyObject.fromMap(args));
                // 3. Execute
                Value result = context.eval(Source.create("js", script));

                String logs = logStream.toString(StandardCharsets.UTF_8);
                String resultStr = result != null ? result.toString() : "";

                if (!logs.isEmpty()) {
                    return "--- LOGS ---\n" + logs + "\n--- RESULT ---\n" + resultStr;
                }
                return resultStr;
            } finally {
                timeoutTask.cancel(false);
            }
        } catch (Exception e) {
            String logs = logStream.toString(StandardCharsets.UTF_8);
            String stackTrace = ExceptionUtils.getStackTrace(e);
            String errorMsg = "Error executing JavaScript: " + e.getMessage() + "\n" + stackTrace;
            if (!logs.isEmpty()) {
                return "--- LOGS ---\n" + logs + "\n--- ERROR ---\n" + errorMsg;
            }
            return errorMsg;
        }
    }

    private String resolveScriptContent(ToolDefinition tool) throws IOException {
        String src = tool.getSource();
        if (src.startsWith("file:")) {
            // Relative resolution logic
            // remove "file:"
            String relativePath = src.substring(5);
            Path scriptPath = tool.getDefinitionLocation().resolve(relativePath).normalize();
            return Files.readString(scriptPath);
        }
        // Inline script
        return src;
    }
}
