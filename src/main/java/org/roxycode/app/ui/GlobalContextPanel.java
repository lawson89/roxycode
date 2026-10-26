package org.roxycode.app.ui;

import org.roxycode.app.events.SettingsChangedEvent;
import org.roxycode.app.service.PromptService;
import org.roxycode.app.service.SettingsService;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Panel for managing global context folders and previewing discovered top-level text files. */
@Component
@Lazy
public final class GlobalContextPanel extends BaseContextFolderPanel {
    private static final long serialVersionUID = 1L;

    public GlobalContextPanel(PromptService promptService, SettingsService settingsService) {
        super(promptService, settingsService, "Global Context Folders", true);
    }

    @EventListener
    public void onSettingsChanged(SettingsChangedEvent event) {
        handleSettingsChanged(event);
    }
}
