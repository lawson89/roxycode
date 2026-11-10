package org.roxycode.app.events;

import org.roxycode.app.model.AppSettings;

/**
 * Event published when application settings are saved.
 *
 * @param settings
 *            the new application settings
 */
public record SettingsChangedEvent(AppSettings settings) {
}