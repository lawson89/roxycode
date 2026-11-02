package org.roxycode.app.events;

/** Event published when a file or directory is created, modified, or deleted within the project sandbox. */
public record FileChangedEvent(String path, ChangeType changeType) {

    public enum ChangeType {
        CREATED("CREATED"), MODIFIED("MODIFIED"), DELETED("DELETED");

        private final String label;

        ChangeType(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public FileChangedEvent {
        if (changeType == null) {
            changeType = ChangeType.MODIFIED;
        }
    }

    public FileChangedEvent(String path) {
        this(path, ChangeType.MODIFIED);
    }
}
