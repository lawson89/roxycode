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

/** Manages database and AI credentials using the native OS keyring with env var and session fallbacks. */
@Service
public class CredentialStoreService implements AutoCloseable {

    static final String KEYRING_SERVICE_NAME = "org.roxycode.db";
    static final String AI_KEYRING_SERVICE_NAME = "org.roxycode.ai";
    static final String GEMINI_API_KEY_ACCOUNT = "gemini-api-key";
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

    /** Returns {@code true} if a persistent OS keyring backend is available. */
    public boolean isNativeKeyringAvailable() {
        KeyringBackend resolved = getOrInitBackend();
        return resolved != null && resolved.isPersistent();
    }

    /** Stores a password for {@code connectionId} in the OS keyring (or session memory if unavailable). */
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

    /** Deletes any stored password for {@code connectionId} from both OS keyring and session memory. */
    public void deletePassword(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return;
        }
        String key = connectionId.trim();
        clearSessionPassword(key);
        deleteFromKeyring(getOrInitBackend(), KEYRING_SERVICE_NAME, key, "connection '" + key + "'");
    }

    /** Returns {@code true} if a password is stored in the OS keyring or session memory for {@code connectionId}. */
    public boolean hasStoredPassword(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return false;
        }
        String key = connectionId.trim();
        if (sessionPasswords.containsKey(key)) {
            return true;
        }
        String value = readFromKeyring(getOrInitBackend(), KEYRING_SERVICE_NAME, key, null);
        return value != null && !value.isEmpty();
    }

    /** Resolves the runtime password for {@code config} according to its {@link CredentialSource}. */
    public @Nullable String resolvePassword(DatabaseConnectionConfig config) {
        return resolvePassword(config, System::getenv);
    }

    /** Resolves the runtime password for {@code config} using a custom environment lookup function. */
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
        String fromKeyring = readFromKeyring(getOrInitBackend(), KEYRING_SERVICE_NAME, key, "connection '" + key + "'");
        if (fromKeyring != null) {
            return fromKeyring;
        }
        char[] inMemory = sessionPasswords.get(key);
        if (inMemory != null) {
            return new String(inMemory);
        }
        return null;
    }

    /** Loads the Gemini API key from a persistent native OS keyring, or {@code null} if unavailable/unset. */
    public @Nullable String loadGeminiApiKey() {
        KeyringBackend resolved = getPersistentBackend();
        return readFromKeyring(resolved, AI_KEYRING_SERVICE_NAME, GEMINI_API_KEY_ACCOUNT, "Gemini API key");
    }

    /** Stores the Gemini API key in a persistent native OS keyring, returning {@code true} if persisted. */
    public boolean saveGeminiApiKey(@Nullable String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            deleteGeminiApiKey();
            return isNativeKeyringAvailable();
        }
        KeyringBackend resolved = getPersistentBackend();
        if (resolved == null) {
            return false;
        }
        try {
            resolved.setPassword(AI_KEYRING_SERVICE_NAME, GEMINI_API_KEY_ACCOUNT, apiKey);
            return true;
        } catch (PasswordAccessException e) {
            log.warn("OS keyring write failed for Gemini API key; falling back to settings.toml: {}", e.getMessage());
            return false;
        }
    }

    /** Deletes any stored Gemini API key from the persistent native OS keyring. */
    public void deleteGeminiApiKey() {
        deleteFromKeyring(getPersistentBackend(), AI_KEYRING_SERVICE_NAME, GEMINI_API_KEY_ACCOUNT, "Gemini API key");
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

    private @Nullable KeyringBackend getPersistentBackend() {
        KeyringBackend resolved = getOrInitBackend();
        if (resolved == null || !resolved.isPersistent()) {
            return null;
        }
        return resolved;
    }

    private static @Nullable String readFromKeyring(@Nullable KeyringBackend backend, String service, String account,
            @Nullable String logLabel) {
        if (backend == null) {
            return null;
        }
        try {
            return backend.getPassword(service, account);
        } catch (PasswordAccessException e) {
            if (logLabel != null) {
                log.debug("OS keyring read failed for {}: {}", logLabel, e.getMessage());
            }
            return null;
        }
    }

    private static void deleteFromKeyring(@Nullable KeyringBackend backend, String service, String account,
            String logLabel) {
        if (backend == null) {
            return;
        }
        try {
            backend.deletePassword(service, account);
        } catch (PasswordAccessException e) {
            log.debug("No keyring entry deleted for {}: {}", logLabel, e.getMessage());
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