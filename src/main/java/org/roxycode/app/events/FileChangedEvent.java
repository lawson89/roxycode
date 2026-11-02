package org.roxycode.app.events;

import java.util.Objects;

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

        public static ChangeType ofExisted(boolean existed) {
            if (existed) {
                return MODIFIED;
            }
            return CREATED;
        }
    }

    public FileChangedEvent {
        changeType = Objects.requireNonNullElse(changeType, ChangeType.MODIFIED);
    }

    public FileChangedEvent(String path) {
        this(path, ChangeType.MODIFIED);
    }
}
