package org.roxycode.app.events;

import org.springframework.lang.Nullable;

/**
 * UI navigation event requesting {@link org.roxycode.app.ui.MainFrame} to switch views and optionally submit an initial
 * prompt.
 */
public record NavigateToViewEvent(String viewId, @Nullable String initialPrompt) {

    /**
     * Creates a simple view navigation event without an initial prompt.
     */
    public NavigateToViewEvent(String viewId) {
        this(viewId, null);
    }
}
