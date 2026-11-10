package org.roxycode.app.events;

import java.util.Objects;

/**
 * Event published when a file or directory is created, modified, or deleted within the project sandbox.
 *
 * @param path
 *            the path of the changed file
 * @param changeType
 *            the type of change
 */
public record FileChangedEvent(String path, ChangeType changeType) {

    /**
     * The type of file change.
     */
    public enum ChangeType {
        CREATED("CREATED"), MODIFIED("MODIFIED"), DELETED("DELETED");

        private final String label;

        ChangeType(String label) {
            this.label = label;
        }

        /**
         * Gets the label of the change type.
         *
         * @return the label
         */
        public String getLabel() {
            return label;
        }

        /**
         * Returns the change type based on whether the file previously existed.
         *
         * @param existed
         *            true if the file existed, false otherwise
         * @return MODIFIED if existed, CREATED otherwise
         */
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

    /**
     * Constructs a FileChangedEvent with the default change type of MODIFIED.
     *
     * @param path
     *            the path of the changed file
     */
    public FileChangedEvent(String path) {
        this(path, ChangeType.MODIFIED);
    }
}