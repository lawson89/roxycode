package org.roxycode.app.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.roxycode.app.events.DatabaseConfigChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.CredentialSource;
import org.roxycode.app.model.DatabaseConnectionConfig;
import org.roxycode.app.model.ProjectDatabaseGrant;
import org.roxycode.app.util.PathUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

/**
 * Persists and resolves global read-only database connections ({@code ~/.roxycode/databases.toml}) and per-project
 * database grants ({@code <projectDataDir>/databases.toml}).
 */
@Service
public class DatabaseConfigService {

    static final String DATABASES_TOML_FILENAME = "databases.toml";

    /**
     * TOML root wrapper for global database connections.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GlobalDatabasesFile(@JsonProperty("connections") List<DatabaseConnectionConfig> connections) {
        public GlobalDatabasesFile {
            if (connections == null) {
                connections = List.of();
            } else {
                connections = List.copyOf(connections);
            }
        }
    }

    /**
     * TOML root wrapper for per-project database grants.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectDatabasesFile(@JsonProperty("grants") List<ProjectDatabaseGrant> grants) {
        public ProjectDatabasesFile {
            if (grants == null) {
                grants = List.of();
            } else {
                grants = List.copyOf(grants);
            }
        }
    }

    /**
     * Effective granted database connection for the active project.
     */
    public record ResolvedDatabaseConnection(DatabaseConnectionConfig config, ProjectDatabaseGrant grant,
            String effectiveDefaultSchema, String notes) {
    }

    private final SettingsService settingsService;
    private final ProjectService projectService;
    private final CredentialStoreService credentialStoreService;
    private final TomlMapper tomlMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final Object lock = new Object();

    private @Nullable Path cachedGlobalPath;
    private @Nullable List<DatabaseConnectionConfig> cachedGlobalConnections;
    private @Nullable Path cachedProjectPath;
    private @Nullable List<ProjectDatabaseGrant> cachedProjectGrants;

    public DatabaseConfigService(SettingsService settingsService, ProjectService projectService,
            CredentialStoreService credentialStoreService, TomlMapper tomlMapper,
            @Nullable ApplicationEventPublisher eventPublisher) {
        this.settingsService = settingsService;
        this.projectService = projectService;
        this.credentialStoreService = credentialStoreService;
        this.tomlMapper = tomlMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Clears cached per-project grants when the active project changes.
     */
    @SuppressWarnings("PMD.NullAssignment")
    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        synchronized (lock) {
            cachedProjectPath = null;
            cachedProjectGrants = null;
        }
    }

    /**
     * Returns all globally configured database connections.
     */
    public List<DatabaseConnectionConfig> listGlobalConnections() {
        synchronized (lock) {
            Path globalPath = resolveGlobalDatabasesPath();
            if (cachedGlobalConnections != null && globalPath.equals(cachedGlobalPath)) {
                return cachedGlobalConnections;
            }
            GlobalDatabasesFile file = PathUtils.readTomlOrDefault(globalPath, GlobalDatabasesFile.class,
                    new GlobalDatabasesFile(List.of()), tomlMapper);
            List<DatabaseConnectionConfig> loaded = List.copyOf(file.connections());
            cachedGlobalPath = globalPath;
            cachedGlobalConnections = loaded;
            return loaded;
        }
    }

    /**
     * Looks up a global database connection by id.
     */
    public Optional<DatabaseConnectionConfig> findGlobalConnection(String connectionId) {
        return findById(listGlobalConnections(), connectionId, DatabaseConnectionConfig::id);
    }

    /**
     * Saves (inserts or updates) a global database connection and optionally updates its stored password.
     */
    public DatabaseConnectionConfig saveGlobalConnection(DatabaseConnectionConfig config, @Nullable String newPassword)
            throws IOException {
        if (config == null) {
            throw new IllegalArgumentException("Database connection config must not be null.");
        }
        synchronized (lock) {
            List<DatabaseConnectionConfig> current = new ArrayList<>(listGlobalConnections());
            List<DatabaseConnectionConfig> updated = new ArrayList<>(current.size() + 1);
            boolean replaced = false;
            for (DatabaseConnectionConfig existing : current) {
                if (existing.id().equals(config.id())) {
                    updated.add(config);
                    replaced = true;
                } else {
                    updated.add(existing);
                }
            }
            if (!replaced) {
                updated.add(config);
            }

            Path globalPath = resolveGlobalDatabasesPath();
            PathUtils.writeToml(globalPath, new GlobalDatabasesFile(updated), tomlMapper);
            PathUtils.setOwnerOnlyPermissionsIfPosix(globalPath);
            cachedGlobalPath = globalPath;
            cachedGlobalConnections = List.copyOf(updated);

            if (newPassword != null && !newPassword.isEmpty()
                    && config.resolveCredentialSource() == CredentialSource.KEYRING) {
                credentialStoreService.savePassword(config.id(), newPassword);
            }
        }
        publishConfigChanged(true);
        return config;
    }

    /**
     * Deletes a global database connection and removes any stored keyring password.
     */
    public boolean deleteGlobalConnection(String connectionId) throws IOException {
        if (connectionId == null || connectionId.isBlank()) {
            return false;
        }
        String target = connectionId.trim();
        boolean removed = false;
        synchronized (lock) {
            List<DatabaseConnectionConfig> current = listGlobalConnections();
            List<DatabaseConnectionConfig> updated = new ArrayList<>(current.size());
            for (DatabaseConnectionConfig existing : current) {
                if (existing.id().equals(target)) {
                    removed = true;
                } else {
                    updated.add(existing);
                }
            }
            if (!removed) {
                return false;
            }
            Path globalPath = resolveGlobalDatabasesPath();
            PathUtils.writeToml(globalPath, new GlobalDatabasesFile(updated), tomlMapper);
            PathUtils.setOwnerOnlyPermissionsIfPosix(globalPath);
            cachedGlobalPath = globalPath;
            cachedGlobalConnections = List.copyOf(updated);
            credentialStoreService.deletePassword(target);
        }
        publishConfigChanged(true);
        return true;
    }

    /**
     * Returns all raw grants configured for the active project (or an empty list when no project is open).
     */
    public List<ProjectDatabaseGrant> listProjectGrants() {
        synchronized (lock) {
            Path projectPath = ProjectService.resolveProjectDataPathSafely(projectService, DATABASES_TOML_FILENAME);
            if (projectPath == null) {
                return List.of();
            }
            if (cachedProjectGrants != null && projectPath.equals(cachedProjectPath)) {
                return cachedProjectGrants;
            }
            ProjectDatabasesFile file = PathUtils.readTomlOrDefault(projectPath, ProjectDatabasesFile.class,
                    new ProjectDatabasesFile(List.of()), tomlMapper);
            List<ProjectDatabaseGrant> loaded = List.copyOf(file.grants());
            cachedProjectPath = projectPath;
            cachedProjectGrants = loaded;
            return loaded;
        }
    }

    /**
     * Replaces the active project's database grants in {@code <projectDataDir>/databases.toml}.
     */
    public List<ProjectDatabaseGrant> saveProjectGrants(List<ProjectDatabaseGrant> grants) throws IOException {
        Path projectPath = ProjectService.resolveProjectDataPathSafely(projectService, DATABASES_TOML_FILENAME);
        if (projectPath == null) {
            throw new IllegalStateException("No active project is open.");
        }
        Map<String, ProjectDatabaseGrant> deduplicated = new LinkedHashMap<>();
        if (grants != null) {
            for (ProjectDatabaseGrant grant : grants) {
                if (grant != null && !grant.connectionId().isBlank()) {
                    deduplicated.put(grant.connectionId(), grant);
                }
            }
        }
        List<ProjectDatabaseGrant> normalized = List.copyOf(deduplicated.values());
        synchronized (lock) {
            PathUtils.writeToml(projectPath, new ProjectDatabasesFile(normalized), tomlMapper);
            PathUtils.setOwnerOnlyPermissionsIfPosix(projectPath);
            cachedProjectPath = projectPath;
            cachedProjectGrants = normalized;
        }
        publishConfigChanged(false);
        return normalized;
    }

    /**
     * Resolves all active granted database connections for the current project, filtering out dangling grants.
     */
    public List<ResolvedDatabaseConnection> listActiveProjectConnections() {
        List<ProjectDatabaseGrant> grants = listProjectGrants();
        if (grants.isEmpty()) {
            return List.of();
        }
        Map<String, DatabaseConnectionConfig> byId = new LinkedHashMap<>();
        for (DatabaseConnectionConfig config : listGlobalConnections()) {
            byId.put(config.id(), config);
        }
        List<ResolvedDatabaseConnection> resolved = new ArrayList<>();
        for (ProjectDatabaseGrant grant : grants) {
            DatabaseConnectionConfig config = byId.get(grant.connectionId());
            if (config != null) {
                String effectiveSchema = grant.defaultSchema();
                if (effectiveSchema.isBlank()) {
                    effectiveSchema = config.defaultSchema();
                }
                resolved.add(new ResolvedDatabaseConnection(config, grant, effectiveSchema, grant.notes()));
            }
        }
        return List.copyOf(resolved);
    }

    /**
     * Finds an active granted connection for the current project by connection id.
     */
    public Optional<ResolvedDatabaseConnection> findActiveProjectConnection(String connectionId) {
        return findById(listActiveProjectConnections(), connectionId, resolved -> resolved.config().id());
    }

    private static <T> Optional<T> findById(List<T> items, String connectionId, Function<T, String> idExtractor) {
        if (connectionId == null || connectionId.isBlank()) {
            return Optional.empty();
        }
        String target = connectionId.trim();
        for (T item : items) {
            if (target.equals(idExtractor.apply(item))) {
                return Optional.of(item);
            }
        }
        return Optional.empty();
    }

    private Path resolveGlobalDatabasesPath() {
        return settingsService.getRoxyHomeDir().resolve(DATABASES_TOML_FILENAME);
    }

    private void publishConfigChanged(boolean globalScope) {
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new DatabaseConfigChangedEvent(globalScope));
        }
    }
}
