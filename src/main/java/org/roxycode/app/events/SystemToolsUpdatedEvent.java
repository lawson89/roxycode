package org.roxycode.app.events;

import java.util.List;
import org.roxycode.app.model.SystemTool;

/** Event published when system tools have been detected or refreshed. */
public record SystemToolsUpdatedEvent(List<SystemTool> tools, boolean dockerAvailable) {
    public SystemToolsUpdatedEvent {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
