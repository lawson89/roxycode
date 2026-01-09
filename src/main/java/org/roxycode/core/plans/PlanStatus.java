package org.roxycode.core.plans;

public enum PlanStatus {
    AVAILABLE("available"),
    IN_PROGRESS("in_progress"),
    COMPLETE("complete");

    private final String dirName;

    PlanStatus(String dirName) {
        this.dirName = dirName;
    }

    public String getDirName() {
        return dirName;
    }

    public static PlanStatus fromDirName(String dirName) {
        for (PlanStatus status : values()) {
            if (status.dirName.equals(dirName)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown directory name: " + dirName);
    }
}
