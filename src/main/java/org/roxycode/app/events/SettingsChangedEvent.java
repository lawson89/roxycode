package org.roxycode.app.events;

import org.roxycode.app.model.AppSettings;

/** Event published when application settings are saved. */
public record SettingsChangedEvent(AppSettings settings) {}
