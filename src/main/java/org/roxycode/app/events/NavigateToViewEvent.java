package org.roxycode.app.events;

import org.springframework.lang.Nullable;

/** UI navigation event requesting view switch and optional initial prompt. */
public record NavigateToViewEvent(String viewId, @Nullable String initialPrompt) {

    public NavigateToViewEvent(String viewId) {
        this(viewId, null);
    }
}