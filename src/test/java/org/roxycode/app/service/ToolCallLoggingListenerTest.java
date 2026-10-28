package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import java.io.InputStream;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
    void testDoesNotLogWhenLogToolCallsIsDisabled() {
        settings.setLogToolCalls(false);

        listener.onToolExecution(new ToolExecutionEvent("task:default", "fileReadService.readFile('pom.xml')",
                "<project/>", true, null));

        assertTrue(listAppender.list.isEmpty());
    }

    @Test
    void testLogsSuccessfulToolCallWhenEnabled() {
        settings.setLogToolCalls(true);

        listener.onToolExecution(new ToolExecutionEvent("task:default", "fileReadService.readFile('pom.xml')",
                "<project/>", true, null));

        assertEquals(1, listAppender.list.size());
        ILoggingEvent logEvent = listAppender.list.get(0);
        assertEquals(Level.INFO, logEvent.getLevel());
        String formatted = logEvent.getFormattedMessage();
        assertTrue(formatted.contains("conversation=task:default, success=true"));
        assertTrue(formatted.contains("fileReadService.readFile('pom.xml')"));
        assertTrue(formatted.contains("<project/>"));
    }

    @Test
    void testLogsFailedToolCallWithErrorWhenEnabled() {
        settings.setLogToolCalls(true);

        listener.onToolExecution(new ToolExecutionEvent("qa:default", "planService.submitPlan(...)", null, false,
                "Not allowed in EXPLORE"));

        assertEquals(1, listAppender.list.size());
        ILoggingEvent logEvent = listAppender.list.get(0);
        assertEquals(Level.WARN, logEvent.getLevel());
        String formatted = logEvent.getFormattedMessage();
        assertTrue(formatted.contains("conversation=qa:default, success=false"));
        assertTrue(formatted.contains("planService.submitPlan(...)"));
        assertTrue(formatted.contains("Not allowed in EXPLORE"));
    }

    @Test
    void testHandlesNullEventOrSettingsGracefully() {
        settings.setLogToolCalls(true);
        listener.onToolExecution(null);

        when(settingsService.getSettings()).thenReturn(null);
        listener.onToolExecution(new ToolExecutionEvent("task:default", "script", "res", true, null));

        ToolCallLoggingListener nullServiceListener = new ToolCallLoggingListener(null);
        nullServiceListener.onToolExecution(new ToolExecutionEvent("task:default", "script", "res", true, null));

        assertTrue(listAppender.list.isEmpty());
    }

    @Test
    void testLogbackSpringXmlConfiguresRoxyAndToolCallsRollingAppenders(@TempDir Path tempDir) throws Exception {
        LoggerContext context = new LoggerContext();
        context.putProperty("ROXYCODE_LOG_DIR", tempDir.toString());
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);

        try (InputStream is = getClass().getClassLoader().getResourceAsStream("logback-spring.xml")) {
            assertNotNull(is, "logback-spring.xml should be present on classpath");
            configurator.doConfigure(is);
        }

        Logger rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        RollingFileAppender<ILoggingEvent> roxyAppender = (RollingFileAppender<ILoggingEvent>) rootLogger
                .getAppender("ROXY_FILE");
        assertNotNull(roxyAppender);
        assertTrue(roxyAppender.getFile().endsWith("roxy.log"));
        FixedWindowRollingPolicy roxyRolling = (FixedWindowRollingPolicy) roxyAppender.getRollingPolicy();
        assertEquals(1, roxyRolling.getMinIndex());
        assertEquals(3, roxyRolling.getMaxIndex());
        assertTrue(roxyAppender.getTriggeringPolicy() instanceof SizeBasedTriggeringPolicy);

        Logger tcLogger = context.getLogger(ToolCallLoggingListener.TOOL_CALLS_LOGGER_NAME);
        assertFalse(tcLogger.isAdditive());
        RollingFileAppender<ILoggingEvent> tcAppender = (RollingFileAppender<ILoggingEvent>) tcLogger
                .getAppender("TOOLCALLS_FILE");
        assertNotNull(tcAppender);
        assertTrue(tcAppender.getFile().endsWith("toolcalls.log"));
        FixedWindowRollingPolicy tcRolling = (FixedWindowRollingPolicy) tcAppender.getRollingPolicy();
        assertEquals(1, tcRolling.getMinIndex());
        assertEquals(3, tcRolling.getMaxIndex());
        assertTrue(tcAppender.getTriggeringPolicy() instanceof SizeBasedTriggeringPolicy);

        context.stop();
    }
}
