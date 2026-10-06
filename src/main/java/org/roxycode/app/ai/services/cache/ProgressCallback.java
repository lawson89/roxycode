package org.roxycode.app.ai.services.cache;

/**
 * Callback interface for reporting progress of long-running operations.
 */
public interface ProgressCallback {
    /**
     * Called when progress is made.
     * @param current The current step or units completed.
     * @param total The total steps or units expected.
     * @param message A descriptive message for the current step.
     */
    void onProgress(int current, int total, String message);
}
