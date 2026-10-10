package org.roxycode.app.ai;

public enum WorkflowMode {
    EXPLORE("Explore"),
    CHANGE("Change");

    private final String displayName;

    WorkflowMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
