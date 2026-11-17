package org.roxycode.app.ai.services;

/**
 * Result of testing a read-only database connection, including optional write-privilege warning detection.
 */
public record DbTestResult(boolean success, boolean writePrivilegeWarning, String serverSummary, String message,
        long durationMs) {

    public boolean isSuccess() {
        return success;
    }

    public boolean isWritePrivilegeWarning() {
        return writePrivilegeWarning;
    }

    public String getServerSummary() {
        return serverSummary;
    }

    public String getMessage() {
        return message;
    }

    public long getDurationMs() {
        return durationMs;
    }
}
