package org.roxycode.app.service;

import com.github.javakeyring.BackendNotSupportedException;
import com.github.javakeyring.Keyring;
import com.github.javakeyring.PasswordAccessException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.roxycode.app.model.CredentialSource;
import org.roxycode.app.model.DatabaseConnectionConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Manages database credentials using the native OS keyring with environment variable and session-only fallbacks.
 */
@Service
public class CredentialStoreService implements AutoCloseable {

    static final String KEYRING_SERVICE_NAME = "org.roxycode.db";
    private static final Logger log = LoggerFactory.getLogger(CredentialStoreService.class);

    interface KeyringBackend {
        @Nullable
        String getPassword(String service, String account) throws PasswordAccessException;

        void setPassword(String service, String account, String password) throws PasswordAccessException;

        void deletePassword(String service, String account) throws PasswordAccessException;

        boolean isPersistent();

        default void closeBackend() {
        }
    }

    @FunctionalInterface
    interface BackendSupplier {
        KeyringBackend create() throws BackendNotSupportedException;
    }

    private final BackendSupplier backendSupplier;
    private final Map<String, char[]> sessionPasswords = new ConcurrentHashMap<>();
    private final Object initLock = new Object();
    private volatile boolean initialized;
    private final AtomicReference<@Nullable KeyringBackend> backendRef = new AtomicReference<>();

    @Autowired
    public CredentialStoreService() {
        this(CredentialStoreService::createSystemKeyringBackend);
    }

    CredentialStoreService(BackendSupplier backendSupplier) {
        this.backendSupplier = backendSupplier;
    }

    /**
     * Returns {@code true} if a persistent OS keyring backend is available for storing database passwords.
     */
    public boolean isNativeKeyringAvailable() {
        KeyringBackend resolved = getOrInitBackend();
        return resolved != null && resolved.isPersistent();
    }

    /**
     * Stores a password for the given connection id in the OS keyring (or session memory if unavailable).
     */
    public void savePassword(String connectionId, String password) {
        if (connectionId == null || connectionId.isBlank() || password == null) {
            return;
        }
        String key = connectionId.trim();
        KeyringBackend resolved = getOrInitBackend();
        if (resolved != null) {
            try {
                resolved.setPassword(KEYRING_SERVICE_NAME, key, password);
                clearSessionPassword(key);
                return;
            } catch (PasswordAccessException e) {
                log.warn("OS keyring write failed for connection '{}'; storing in session memory only: {}", key,
                        e.getMessage());
            }
        }
        sessionPasswords.put(key, password.toCharArray());
    }

    /**
     * Deletes any stored password for the given connection id from both OS keyring and session memory.
     */
    public void deletePassword(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return;
        }
        String key = connectionId.trim();
        clearSessionPassword(key);
        KeyringBackend resolved = getOrInitBackend();
        if (resolved != null) {
            try {
                resolved.deletePassword(KEYRING_SERVICE_NAME, key);
            } catch (PasswordAccessException e) {
                log.debug("No keyring entry deleted for connection '{}': {}", key, e.getMessage());
            }
        }
    }

    /**
     * Returns {@code true} if a password is stored in the OS keyring or session memory for {@code connectionId}.
     */
    public boolean hasStoredPassword(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return false;
        }
        String key = connectionId.trim();
        if (sessionPasswords.containsKey(key)) {
            return true;
        }
        KeyringBackend resolved = getOrInitBackend();
        if (resolved == null) {
            return false;
        }
        try {
            String value = resolved.getPassword(KEYRING_SERVICE_NAME, key);
            return value != null && !value.isEmpty();
        } catch (PasswordAccessException e) {
            return false;
        }
    }

    /**
     * Resolves the runtime password for {@code config} according to its {@link CredentialSource}.
     */
    public @Nullable String resolvePassword(DatabaseConnectionConfig config) {
        return resolvePassword(config, System::getenv);
    }

    /**
     * Resolves the runtime password for {@code config} using a custom environment lookup function.
     */
    public @Nullable String resolvePassword(DatabaseConnectionConfig config,
            Function<String, @Nullable String> envLookup) {
        if (config == null) {
            return null;
        }
        CredentialSource source = config.resolveCredentialSource();
        if (source == CredentialSource.NONE) {
            return null;
        }
        if (source == CredentialSource.ENV) {
            String envVar = config.passwordEnv();
            if (envVar == null || envVar.isBlank()) {
                return null;
            }
            return envLookup.apply(envVar.trim());
        }
        String key = config.id();
        KeyringBackend resolved = getOrInitBackend();
        if (resolved != null) {
            try {
                String fromKeyring = resolved.getPassword(KEYRING_SERVICE_NAME, key);
                if (fromKeyring != null) {
                    return fromKeyring;
                }
            } catch (PasswordAccessException e) {
                log.debug("OS keyring read failed for connection '{}': {}", key, e.getMessage());
            }
        }
        char[] inMemory = sessionPasswords.get(key);
        if (inMemory != null) {
            return new String(inMemory);
        }
        return null;
    }

    @Override
    public void close() {
        for (char[] chars : sessionPasswords.values()) {
            Arrays.fill(chars, '\0');
        }
        sessionPasswords.clear();
        synchronized (initLock) {
            KeyringBackend existing = backendRef.getAndSet(null);
            if (existing != null) {
                try {
                    existing.closeBackend();
                } catch (Exception e) {
                    log.debug("Error closing keyring backend: {}", e.getMessage());
                }
            }
            initialized = false;
        }
    }

    private void clearSessionPassword(String key) {
        char[] removed = sessionPasswords.remove(key);
        if (removed != null) {
            Arrays.fill(removed, '\0');
        }
    }

    private @Nullable KeyringBackend getOrInitBackend() {
        if (initialized) {
            return backendRef.get();
        }
        synchronized (initLock) {
            if (initialized) {
                return backendRef.get();
            }
            if (Boolean.getBoolean("roxycode.testMode")) {
                backendRef.set(null);
                initialized = true;
                return null;
            }
            try {
                backendRef.set(backendSupplier.create());
            } catch (BackendNotSupportedException | UnsatisfiedLinkError | RuntimeException e) {
                log.info("Native OS keyring unavailable ({}); falling back to session-only credential memory.",
                        e.getMessage());
                backendRef.set(null);
            }
            initialized = true;
            return backendRef.get();
        }
    }

    @SuppressWarnings("PMD.CloseResource")
    private static KeyringBackend createSystemKeyringBackend() throws BackendNotSupportedException {
        Keyring keyring = Keyring.create();
        return new KeyringBackend() {
            @Override
            public @Nullable String getPassword(String service, String account) throws PasswordAccessException {
                return keyring.getPassword(service, account);
            }

            @Override
            public void setPassword(String service, String account, String password) throws PasswordAccessException {
                keyring.setPassword(service, account, password);
            }

            @Override
            public void deletePassword(String service, String account) throws PasswordAccessException {
                keyring.deletePassword(service, account);
            }

            @Override
            public boolean isPersistent() {
                return true;
            }

            @Override
            public void closeBackend() {
                try {
                    keyring.close();
                } catch (Exception e) {
                    log.debug("Error closing java-keyring instance: {}", e.getMessage());
                }
            }
        };
    }
}
