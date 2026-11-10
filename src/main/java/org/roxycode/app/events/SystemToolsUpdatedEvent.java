package org.roxycode.app.events;

import java.util.List;
import org.roxycode.app.model.SystemTool;

/**
 * Event published when system tools have been detected or refreshed.
 *
 * @param tools
 *            the list of available system tools
 * @param dockerAvailable
 *            true if Docker is available
 */
public record SystemToolsUpdatedEvent(List<SystemTool> tools, boolean dockerAvailable) {
    public SystemToolsUpdatedEvent {
        if (tools == null) {
            tools = List.of();
        } else {
            tools = List.copyOf(tools);
        }
    }
}