package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roxycode.app.events.ToolExecutionEvent;
import org.roxycode.app.model.AppSettings;
import org.slf4j.LoggerFactory;

class ToolCallLoggingListenerTest {

    private SettingsService settingsService;
    private AppSettings settings;
    private ToolCallLoggingListener listener;
    private Logger toolCallsLogger;
    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void setUp() {
        settingsService = mock(SettingsService.class);
        settings = new AppSettings();
        when(settingsService.getSettings()).thenReturn(settings);
        listener = new ToolCallLoggingListener(settingsService);

        toolCallsLogger = (Logger) LoggerFactory.getLogger(ToolCallLoggingListener.TOOL_CALLS_LOGGER_NAME);
        listAppender = new ListAppender<>();
        listAppender.start();
        toolCallsLogger.addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        if (toolCallsLogger != null && listAppender != null) {
            toolCallsLogger.detachAppender(listAppender);
            listAppender.stop();
        }
    }

    @Test
    void testOnToolExecutionLoggingAndGuardClauses() {
        // Disabled
        settings.setLogToolCalls(false);
        listener.onToolExecution(new ToolExecutionEvent("task:default", "script", "res", true, null));
        assertTrue(listAppender.list.isEmpty());

        // Enabled - Success
        settings.setLogToolCalls(true);
        listener.onToolExecution(new ToolExecutionEvent("task:default", "fileReadService.readFile('pom.xml')",
                "<project/>", true, null));
        assertEquals(1, listAppender.list.size());
        ILoggingEvent logEvent = listAppender.list.get(0);
        assertEquals(Level.INFO, logEvent.getLevel());
        assertTrue(logEvent.getFormattedMessage().contains("conversation=task:default, success=true"));

        // Enabled - Error
        listAppender.list.clear();
        listener.onToolExecution(
                new ToolExecutionEvent("qa:default", "planService.submitPlan(...)", null, false, "Not allowed"));
        assertEquals(1, listAppender.list.size());
        assertEquals(Level.WARN, listAppender.list.get(0).getLevel());
        assertTrue(listAppender.list.get(0).getFormattedMessage().contains("success=false"));

        // Guard clauses
        listAppender.list.clear();
        listener.onToolExecution(null);
        when(settingsService.getSettings()).thenReturn(null);
        listener.onToolExecution(new ToolExecutionEvent("task:default", "script", "res", true, null));
        ToolCallLoggingListener nullServiceListener = new ToolCallLoggingListener(null);
        nullServiceListener.onToolExecution(new ToolExecutionEvent("task:default", "script", "res", true, null));
        assertTrue(listAppender.list.isEmpty());
    }

}
