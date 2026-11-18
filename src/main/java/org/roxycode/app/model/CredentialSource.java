package org.roxycode.app.model;

import java.util.Locale;

/** Defines how a database connection password is supplied. */
public enum CredentialSource {
    KEYRING("keyring", "OS Keyring"), ENV("env", "Environment Variable"), NONE("none", "None");

    private final String id;
    private final String displayName;

    CredentialSource(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** Parses a credential source identifier or display name, defaulting to {@link #KEYRING}. */
    public static CredentialSource fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return KEYRING;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (CredentialSource source : values()) {
            if (source.id.equals(normalized) || source.displayName.toLowerCase(Locale.ROOT).equals(normalized)) {
                return source;
            }
        }
        return KEYRING;
    }

    @Override
    public String toString() {
        return displayName;
    }
}