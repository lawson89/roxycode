/**
 * Event published when agent activity event.
 */
package org.roxycode.app.events;

public record AgentActivityEvent(String conversationId, String statusText) {
}
