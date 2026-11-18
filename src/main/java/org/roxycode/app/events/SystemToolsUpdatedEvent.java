package org.roxycode.app.events;

import java.util.List;
import java.util.Objects;
import org.roxycode.app.model.SystemTool;

/** Event published when system tools have been detected or refreshed. */
public record SystemToolsUpdatedEvent(List<SystemTool> tools, boolean dockerAvailable) {
    public SystemToolsUpdatedEvent {
        tools = List.copyOf(Objects.requireNonNullElse(tools, List.of()));
    }
}