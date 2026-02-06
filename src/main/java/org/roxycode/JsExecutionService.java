package org.roxycode;

import jakarta.inject.Singleton;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

@Singleton
public class JsExecutionService {
    private static final Logger LOG = LoggerFactory.getLogger(JsExecutionService.class);

    /**
     * Executes JavaScript code in a secure sandbox and captures output.
     * @param script The JS code to run.
     * @return A JsExecutionResult containing result, logs, and status.
     */
    public JsExecutionResult execute(String script) {
        LOG.info("Executing script in sandbox...");
        
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        
        try (Context context = Context.newBuilder("js")
                .allowIO(false)
                .allowNativeAccess(false)
                .allowCreateThread(false)
                .allowHostClassLookup(s -> false)
                .allowHostAccess(HostAccess.EXPLICIT)
                .option("engine.WarnInterpreterOnly", "false")
                .out(outputStream)
                .err(outputStream)
                .build()) {
            
            Value result = context.eval("js", script);
            String logs = outputStream.toString(StandardCharsets.UTF_8);
            return new JsExecutionResult(true, String.valueOf(result), null, logs);
            
        } catch (Exception e) {
            LOG.error("JS Execution failed: {}", e.getMessage());
            String logs = outputStream.toString(StandardCharsets.UTF_8);
            return new JsExecutionResult(false, null, e.getMessage(), logs);
        }
    }
}
