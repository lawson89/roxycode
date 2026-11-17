package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javakeyring.BackendNotSupportedException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.roxycode.app.model.CredentialSource;
import org.roxycode.app.model.DatabaseConnectionConfig;

class CredentialStoreServiceTest {

    @Test
    void delegatesToPersistentKeyringBackendWhenAvailable() {
        String prevTestMode = System.getProperty("roxycode.testMode");
        System.clearProperty("roxycode.testMode");
        Map<String, String> store = new HashMap<>();
        CredentialStoreService.KeyringBackend backend = new CredentialStoreService.KeyringBackend() {
            @Override
            public String getPassword(String service, String account) {
                return store.get(service + ":" + account);
            }

            @Override
            public void setPassword(String service, String account, String password) {
                store.put(service + ":" + account, password);
            }

            @Override
            public void deletePassword(String service, String account) {
                store.remove(service + ":" + account);
            }

            @Override
            public boolean isPersistent() {
                return true;
            }
        };

        try (CredentialStoreService service = new CredentialStoreService(() -> backend)) {
            assertTrue(service.isNativeKeyringAvailable());
            DatabaseConnectionConfig cfg = sampleConfig("qa-pg", CredentialSource.KEYRING, "");
            service.savePassword("qa-pg", "s3cr3t");
            assertTrue(service.hasStoredPassword("qa-pg"));
            assertEquals("s3cr3t", service.resolvePassword(cfg));

            service.deletePassword("qa-pg");
            assertFalse(service.hasStoredPassword("qa-pg"));
            assertNull(service.resolvePassword(cfg));
        } finally {
            restoreTestMode(prevTestMode);
        }
    }

    @Test
    void fallsBackToSessionMemoryWhenKeyringBackendUnsupported() {
        String prevTestMode = System.getProperty("roxycode.testMode");
        System.clearProperty("roxycode.testMode");
        try (CredentialStoreService service = new CredentialStoreService(() -> {
            throw new BackendNotSupportedException("headless");
        })) {
            assertFalse(service.isNativeKeyringAvailable());
            DatabaseConnectionConfig cfg = sampleConfig("qa-pg", CredentialSource.KEYRING, "");
            service.savePassword("qa-pg", "session-pass");
            assertTrue(service.hasStoredPassword("qa-pg"));
            assertEquals("session-pass", service.resolvePassword(cfg));

            service.deletePassword("qa-pg");
            assertFalse(service.hasStoredPassword("qa-pg"));
            assertNull(service.resolvePassword(cfg));
        } finally {
            restoreTestMode(prevTestMode);
        }
    }

    @Test
    void resolvesEnvironmentAndNoneCredentialSources() {
        try (CredentialStoreService service = new CredentialStoreService()) {
            DatabaseConnectionConfig envCfg = sampleConfig("qa-env", CredentialSource.ENV, "QA_DB_PASS");
            assertEquals("from-env", service.resolvePassword(envCfg, key -> {
                if ("QA_DB_PASS".equals(key)) {
                    return "from-env";
                }
                return null;
            }));
            assertNull(service.resolvePassword(envCfg, key -> null));

            DatabaseConnectionConfig noneCfg = sampleConfig("qa-none", CredentialSource.NONE, "");
            assertNull(service.resolvePassword(noneCfg, key -> "ignored"));
        }
    }

    private static DatabaseConnectionConfig sampleConfig(String id, CredentialSource source, String envVar) {
        return new DatabaseConnectionConfig(id, id, "postgresql", "jdbc:postgresql://localhost:5432/db", "reader",
                source.getId(), envVar, List.of(), "", "", 15, 200);
    }

    private static void restoreTestMode(String prev) {
        if (prev != null) {
            System.setProperty("roxycode.testMode", prev);
        } else {
            System.clearProperty("roxycode.testMode");
        }
    }
}
