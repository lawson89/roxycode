package org.roxycode.app.events;

import java.nio.file.Path;

/** Event published when the active project root directory changes. */
public record ProjectChangedEvent(Path projectRoot) {}
