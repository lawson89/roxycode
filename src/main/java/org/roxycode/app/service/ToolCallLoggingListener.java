package org.roxycode.app.service;

import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.model.AppSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ToolCallLoggingListener {
    public static final String TOOL_CALLS_LOGGER_NAME = "org.roxycode.toolcalls";
    private static final Logger toolCallLogger = LoggerFactory.getLogger(TOOL_CALLS_LOGGER_NAME);

    private final SettingsService settingsService;

    public ToolCallLoggingListener(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @EventListener
    public void onToolExecution(ToolExecutionEvent event) {
        if (event == null || settingsService == null) {
            return;
        }
        AppSettings settings = settingsService.getSettings();
        if (settings == null || !settings.isLogToolCalls()) {
            return;
        }
        String conversationId = Objects.requireNonNullElse(event.conversationId(), "unknown");
        String script = StringUtils.defaultString(event.script());
        String resultText = String.valueOf(event.result());
        if (event.success()) {
            toolCallLogger.info("""
                    Tool Call [conversation={}, success=true]
                    Call:
                    {}
                    Result:
                    {}""", conversationId, script, resultText);
        } else {
            String errorText = Objects.requireNonNullElse(event.error(), "Unknown error");
            toolCallLogger.warn("""
                    Tool Call [conversation={}, success=false]
                    Call:
                    {}
                    Error:
                    {}
                    Result:
                    {}""", conversationId, script, errorText, resultText);
        }
    }
}
