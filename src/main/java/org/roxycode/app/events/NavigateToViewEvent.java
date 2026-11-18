package org.roxycode.app.events;

import org.springframework.lang.Nullable;

/**
 * UI navigation event requesting {@link org.roxycode.app.ui.MainFrame} to switch views and optionally submit an initial
 * prompt or apply generated {@code build.toml} content.
 */
public record NavigateToViewEvent(String viewId, @Nullable String initialPrompt, @Nullable String generatedToml) {

    /**
     * Creates a simple view navigation event without an initial prompt or TOML payload.
     */
    public NavigateToViewEvent(String viewId) {
        this(viewId, null, null);
    }
}
