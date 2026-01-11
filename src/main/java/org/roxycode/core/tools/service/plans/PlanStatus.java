package org.roxycode.core.tools.service.plans;

public enum PlanStatus {
    AVAILABLE("available"),
    PLANNING("planning"),
    IN_PROGRESS("in_progress"),
    COMPLETE("complete");

    private final String dirName;

    PlanStatus(String dirName) {
        this.dirName = dirName;
    }

    public String getDirName() {
        return dirName;
    }

    /**
     * Case-insensitive lookup for GraalJS compatibility.
     */
    public static PlanStatus fromString(String value) {
        if (value == null) return null;
        for (PlanStatus status : values()) {
            if (status.name().equalsIgnoreCase(value) || status.dirName.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid plan status: " + value);
    }
}
