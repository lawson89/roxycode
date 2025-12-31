package org.roxycode.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;

/**
 * Tests the LogCaptureService to ensure it correctly intercepts and buffers
 * stdout and stderr streams.
 */
public class LogCaptureServiceTest {

    @Test
    public void testCaptureStdoutAndStderr() {
        // LogCaptureService is a singleton in the app, but we can instantiate for testing
        // Note: In a real test environment, this will affect global System.out/err
        LogCaptureService service = new LogCaptureService();
        
        String stdoutMessage = "Capturing standard output test message";
        String stderrMessage = "Capturing error output test message";
        
        System.out.println(stdoutMessage);
        System.err.println(stderrMessage);

        // Retrieve logs (getting more than we sent to ensure we see them)
        List<String> logs = service.getLogs(50);
        
        boolean foundStdout = logs.stream().anyMatch(line -> line.contains("[OUT] " + stdoutMessage));
        boolean foundStderr = logs.stream().anyMatch(line -> line.contains("[ERR] " + stderrMessage));
        
        assertTrue(foundStdout, "Service should have captured the message sent to System.out");
        assertTrue(foundStderr, "Service should have captured the message sent to System.err");
    }

    @Test
    public void testLogLimitIsRespected() {
        LogCaptureService service = new LogCaptureService();
        
        // Exceed the 1000 line limit
        int totalLines = 1100;
        for (int i = 0; i < totalLines; i++) {
            System.out.println("Line index " + i);
        }
        
        // Request a large number of lines
        List<String> logs = service.getLogs(2000);
        
        // Verify the internal buffer didn't grow beyond 1000
        assertTrue(logs.size() <= 1000, "Log buffer should be limited to 1000 lines. Actual: " + logs.size());
        
        // Verify we have the *latest* lines
        String lastLine = logs.get(logs.size() - 1);
        assertTrue(lastLine.contains("Line index " + (totalLines - 1)), "The captured logs should contain the most recent output");
    }

    @Test
    public void testGetLogsCountParameter() {
        LogCaptureService service = new LogCaptureService();
        
        for (int i = 0; i < 20; i++) {
            System.out.println("Message " + i);
        }
        
        List<String> logs = service.getLogs(5);
        assertEquals(5, logs.size(), "getLogs(count) should return exactly the requested number of lines if available");
        assertTrue(logs.get(4).contains("Message 19"), "Should return the most recent 5 lines");
    }
}
