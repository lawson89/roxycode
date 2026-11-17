package org.roxycode.app.ai.services;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import java.io.IOException;
import java.io.Reader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.Nullable;
import org.roxycode.app.ai.AgentDoc;
import org.roxycode.app.ai.AgentService;
import org.roxycode.app.events.DatabaseConfigChangedEvent;
import org.roxycode.app.events.ProjectChangedEvent;
import org.roxycode.app.model.CredentialSource;
import org.roxycode.app.model.DatabaseConnectionConfig;
import org.roxycode.app.model.DatabaseType;
import org.roxycode.app.service.CredentialStoreService;
import org.roxycode.app.service.DatabaseConfigService;
import org.roxycode.app.service.DatabaseConfigService.ResolvedDatabaseConnection;
import org.roxycode.app.util.ExceptionUtils;
import org.roxycode.app.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Provides read-only JDBC database inspection and query execution for the AI agent and UI connection tester.
 */
@Service
@AgentService(value = "dbService", phases = {"*"})
public class DbService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DbService.class);
    private static final String DOC_CONNECTION_ID = "The granted connection id";

    private static final int MAX_CELL_CHARS = 4000;
    private static final int IDLE_EVICTION_SECONDS = 90;
    private static final int VALIDATION_TIMEOUT_SECONDS = 3;

    private static final class CachedConnection {
        private final Connection connection;
        private final String fingerprint;
        private final Object execLock = new Object();

        private CachedConnection(Connection connection, String fingerprint) {
            this.connection = connection;
            this.fingerprint = fingerprint;
        }
    }

    private static final class CachedDriverLoader {
        private final String stamp;
        private final URLClassLoader classLoader;

        private CachedDriverLoader(String stamp, URLClassLoader classLoader) {
            this.stamp = stamp;
            this.classLoader = classLoader;
        }
    }

    private final DatabaseConfigService databaseConfigService;
    private final CredentialStoreService credentialStoreService;
    private final Cache<String, CachedConnection> connectionCache;
    private final Map<String, CachedDriverLoader> driverLoaderCache = new ConcurrentHashMap<>();

    public DbService(DatabaseConfigService databaseConfigService, CredentialStoreService credentialStoreService) {
        this.databaseConfigService = databaseConfigService;
        this.credentialStoreService = credentialStoreService;
        this.connectionCache = Caffeine.newBuilder().expireAfterAccess(IDLE_EVICTION_SECONDS, TimeUnit.SECONDS)
                .removalListener(this::onConnectionRemoved).build();
    }

    @EventListener
    public void onDatabaseConfigChanged(DatabaseConfigChangedEvent event) {
        evictAllConnections();
    }

    @EventListener
    public void onProjectChanged(ProjectChangedEvent event) {
        evictAllConnections();
    }

    @Override
    public void close() {
        evictAllConnections();
        for (CachedDriverLoader loader : driverLoaderCache.values()) {
            closeClassLoaderQuietly(loader.classLoader);
        }
        driverLoaderCache.clear();
    }

    /**
     * Evicts and closes all cached read-only database connections.
     */
    public void evictAllConnections() {
        connectionCache.invalidateAll();
        connectionCache.cleanUp();
    }

    @AgentDoc("Lists all read-only database connections granted to the active project.")
    public List<DbConnectionSummary> listConnections() {
        List<ResolvedDatabaseConnection> active = databaseConfigService.listActiveProjectConnections();
        List<DbConnectionSummary> summaries = new ArrayList<>(active.size());
        for (ResolvedDatabaseConnection item : active) {
            DatabaseConnectionConfig cfg = item.config();
            summaries.add(new DbConnectionSummary(cfg.id(), cfg.displayName(), cfg.type(), cfg.description(),
                    item.effectiveDefaultSchema(), item.notes(), cfg.queryTimeoutSeconds(), cfg.maxRows()));
        }
        return List.copyOf(summaries);
    }

    @AgentDoc("Lists available schemas (or catalogs if schemas are unsupported) for a granted connection.")
    public List<String> listSchemas(@AgentDoc(DOC_CONNECTION_ID) String connectionId) {
        ResolvedDatabaseConnection resolved = requireActiveConnection(connectionId);
        return executeWithConnection(resolved, conn -> {
            DatabaseMetaData meta = conn.getMetaData();
            Set<String> schemas = new LinkedHashSet<>();
            try (ResultSet rs = meta.getSchemas()) {
                while (rs.next()) {
                    String schemaName = rs.getString("TABLE_SCHEM");
                    if (schemaName != null && !schemaName.isBlank()) {
                        schemas.add(schemaName.trim());
                    }
                }
            }
            if (schemas.isEmpty()) {
                try (ResultSet rs = meta.getCatalogs()) {
                    while (rs.next()) {
                        String catalogName = rs.getString("TABLE_CAT");
                        if (catalogName != null && !catalogName.isBlank()) {
                            schemas.add(catalogName.trim());
                        }
                    }
                }
            }
            return List.copyOf(schemas);
        });
    }

    @AgentDoc("Lists tables and views in the connection's default schema (or all schemas if unset).")
    public List<DbTableSummary> listTables(@AgentDoc(DOC_CONNECTION_ID) String connectionId) {
        ResolvedDatabaseConnection resolved = requireActiveConnection(connectionId);
        return listTables(connectionId, resolved.effectiveDefaultSchema());
    }

    @AgentDoc("Lists tables and views matching an optional schema pattern for a granted connection.")
    public List<DbTableSummary> listTables(@AgentDoc(DOC_CONNECTION_ID) String connectionId,
            @AgentDoc("Schema pattern (e.g. 'public', or empty/null for all schemas)") @Nullable String schemaPattern) {
        ResolvedDatabaseConnection resolved = requireActiveConnection(connectionId);
        String normalizedSchema = StringUtils.trimToNull(schemaPattern);
        return executeWithConnection(resolved, conn -> {
            DatabaseMetaData meta = conn.getMetaData();
            List<DbTableSummary> tables = new ArrayList<>();
            String[] types = new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"};
            try (ResultSet rs = meta.getTables(null, normalizedSchema, "%", types)) {
                while (rs.next()) {
                    String schema = StringUtils.defaultString(rs.getString("TABLE_SCHEM"));
                    String name = StringUtils.defaultString(rs.getString("TABLE_NAME"));
                    String tableType = StringUtils.defaultString(rs.getString("TABLE_TYPE"));
                    String remarks = StringUtils.defaultString(rs.getString("REMARKS"));
                    tables.add(new DbTableSummary(schema, name, tableType, remarks));
                }
            }
            return List.copyOf(tables);
        });
    }

    @AgentDoc("Describes columns, primary keys, indexes, and foreign keys for a table in the default schema.")
    public DbTableDescription describeTable(@AgentDoc(DOC_CONNECTION_ID) String connectionId,
            @AgentDoc("The table name") String tableName) {
        ResolvedDatabaseConnection resolved = requireActiveConnection(connectionId);
        return describeTable(connectionId, resolved.effectiveDefaultSchema(), tableName);
    }

    @AgentDoc("Describes columns, primary keys, indexes, and foreign keys for a table in the specified schema.")
    public DbTableDescription describeTable(@AgentDoc(DOC_CONNECTION_ID) String connectionId,
            @AgentDoc("The schema name (or empty/null for default)") @Nullable String schema,
            @AgentDoc("The table name") String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Table name must not be blank.");
        }
        ResolvedDatabaseConnection resolved = requireActiveConnection(connectionId);
        String effectiveSchema = StringUtils.trimToNull(schema);
        String targetTable = tableName.trim();

        return executeWithConnection(resolved, conn -> {
            DatabaseMetaData meta = conn.getMetaData();
            String resolvedSchema = effectiveSchema;
            String resolvedTableType = "TABLE";
            try (ResultSet tableRs = meta.getTables(null, effectiveSchema, targetTable, null)) {
                if (tableRs.next()) {
                    String foundSchema = StringUtils.defaultString(tableRs.getString("TABLE_SCHEM"));
                    if (!foundSchema.isBlank()) {
                        resolvedSchema = foundSchema;
                    }
                    String foundType = StringUtils.defaultString(tableRs.getString("TABLE_TYPE"));
                    if (!foundType.isBlank()) {
                        resolvedTableType = foundType;
                    }
                }
            }

            Set<String> pkColumns = new LinkedHashSet<>();
            try (ResultSet pkRs = meta.getPrimaryKeys(null, resolvedSchema, targetTable)) {
                while (pkRs.next()) {
                    String colName = pkRs.getString("COLUMN_NAME");
                    if (colName != null) {
                        pkColumns.add(colName);
                    }
                }
            }

            List<DbColumnInfo> columns = new ArrayList<>();
            try (ResultSet colRs = meta.getColumns(null, resolvedSchema, targetTable, "%")) {
                while (colRs.next()) {
                    String colName = StringUtils.defaultString(colRs.getString("COLUMN_NAME"));
                    String typeName = StringUtils.defaultString(colRs.getString("TYPE_NAME"));
                    int columnSize = colRs.getInt("COLUMN_SIZE");
                    int nullableFlag = colRs.getInt("NULLABLE");
                    boolean nullable = nullableFlag != DatabaseMetaData.columnNoNulls;
                    String defaultValue = StringUtils.defaultString(colRs.getString("COLUMN_DEF"));
                    String remarks = StringUtils.defaultString(colRs.getString("REMARKS"));
                    columns.add(new DbColumnInfo(colName, typeName, columnSize, nullable, defaultValue,
                            pkColumns.contains(colName), remarks));
                }
            }

            Map<String, IndexAccumulator> indexMap = new LinkedHashMap<>();
            try (ResultSet idxRs = meta.getIndexInfo(null, resolvedSchema, targetTable, false, true)) {
                while (idxRs.next()) {
                    String idxName = idxRs.getString("INDEX_NAME");
                    String colName = idxRs.getString("COLUMN_NAME");
                    if (idxName == null || idxName.isBlank() || colName == null || colName.isBlank()) {
                        continue;
                    }
                    boolean unique = !idxRs.getBoolean("NON_UNIQUE");
                    IndexAccumulator acc = indexMap.get(idxName);
                    if (acc == null) {
                        acc = new IndexAccumulator(idxName, unique);
                        indexMap.put(idxName, acc);
                    }
                    acc.columns.add(colName);
                }
            } catch (SQLException e) {
                log.debug("Index metadata unavailable for {}: {}", targetTable, e.getMessage());
            }
            List<DbIndexInfo> indexes = new ArrayList<>(indexMap.size());
            for (IndexAccumulator acc : indexMap.values()) {
                indexes.add(new DbIndexInfo(acc.name, acc.unique, List.copyOf(acc.columns)));
            }

            List<DbForeignKeyInfo> foreignKeys = new ArrayList<>();
            try (ResultSet fkRs = meta.getImportedKeys(null, resolvedSchema, targetTable)) {
                while (fkRs.next()) {
                    String fkName = StringUtils.defaultString(fkRs.getString("FK_NAME"));
                    String fkCol = StringUtils.defaultString(fkRs.getString("FKCOLUMN_NAME"));
                    String pkTable = StringUtils.defaultString(fkRs.getString("PKTABLE_NAME"));
                    String pkCol = StringUtils.defaultString(fkRs.getString("PKCOLUMN_NAME"));
                    foreignKeys.add(new DbForeignKeyInfo(fkName, fkCol, pkTable, pkCol));
                }
            } catch (SQLException e) {
                log.debug("Foreign key metadata unavailable for {}: {}", targetTable, e.getMessage());
            }

            return new DbTableDescription(StringUtils.defaultString(resolvedSchema), targetTable, resolvedTableType,
                    List.copyOf(columns), List.copyOf(indexes), List.copyOf(foreignKeys));
        });
    }

    @AgentDoc("Executes a read-only SQL query (SELECT, WITH, EXPLAIN, or safe SHOW/PRAGMA) with configured row/timeout caps.")
    public DbQueryResult query(@AgentDoc(DOC_CONNECTION_ID) String connectionId,
            @AgentDoc("The read-only SQL query") String sql) {
        ResolvedDatabaseConnection resolved = requireActiveConnection(connectionId);
        return query(connectionId, sql, resolved.config().maxRows());
    }

    @AgentDoc("Executes a read-only SQL query with an explicit row cap (clamped to connection maxRows).")
    public DbQueryResult query(@AgentDoc(DOC_CONNECTION_ID) String connectionId,
            @AgentDoc("The read-only SQL query") String sql,
            @AgentDoc("Maximum rows to return (clamped to connection maxRows)") int maxRows) {
        ResolvedDatabaseConnection resolved = requireActiveConnection(connectionId);
        String validatedSql = ReadOnlySqlGuard.validateReadOnlySql(sql);
        DatabaseConnectionConfig cfg = resolved.config();
        int effectiveLimit = resolveEffectiveMaxRows(maxRows, cfg.maxRows());
        return executeQueryInternal(resolved, validatedSql, effectiveLimit);
    }

    @AgentDoc("Generates an execution plan for a read-only SELECT statement using the database dialect's EXPLAIN syntax.")
    public DbQueryResult explain(@AgentDoc(DOC_CONNECTION_ID) String connectionId,
            @AgentDoc("The SELECT SQL statement to explain") String sql) {
        ResolvedDatabaseConnection resolved = requireActiveConnection(connectionId);
        String validatedSelect = ReadOnlySqlGuard.validateSelectOnly(sql);
        DatabaseType dbType = resolved.config().resolveType();
        if (dbType == DatabaseType.SQLSERVER) {
            throw new IllegalArgumentException(
                    "EXPLAIN is not supported for SQL Server over read-only statement execution.");
        }
        String explainSql = buildExplainSql(dbType, validatedSelect);
        return executeQueryInternal(resolved, explainSql, resolved.config().maxRows());
    }

    /**
     * Tests a global connection configuration in strict read-only mode without requiring a project grant.
     */
    public DbTestResult testConnection(DatabaseConnectionConfig config, @Nullable String overridePassword) {
        if (config == null || config.url().isBlank()) {
            return new DbTestResult(false, false, "", "JDBC URL must not be blank.", 0L);
        }
        long startNanos = System.nanoTime();
        String password = overridePassword;
        if (password == null) {
            password = credentialStoreService.resolvePassword(config);
        }
        try (Connection conn = openReadOnlyConnection(config, password)) {
            try {
                DatabaseMetaData meta = conn.getMetaData();
                String product = StringUtils.defaultString(meta.getDatabaseProductName());
                String version = StringUtils.defaultString(meta.getDatabaseProductVersion());
                String summary = (product + " " + version).trim();
                boolean writeWarning = detectWritePrivileges(conn, config);
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                String message = "Connected in read-only mode.";
                if (writeWarning) {
                    message = "Connected in read-only mode (warning: account may hold write privileges on the server).";
                }
                log.info("Database connection test succeeded for '{}' ({}) in {} ms [{}]", config.id(), config.type(),
                        durationMs, summary);
                return new DbTestResult(true, writeWarning, summary, message, durationMs);
            } finally {
                rollbackQuietly(conn);
            }
        } catch (Exception e) {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            log.warn("Database connection test failed for '{}' ({}): {}", config.id(), config.type(),
                    ExceptionUtils.getRootCauseMessage(e), e);
            return new DbTestResult(false, false, "", "Connection failed: " + formatConnectionFailureMessage(e),
                    durationMs);
        }
    }

    private DbQueryResult executeQueryInternal(ResolvedDatabaseConnection resolved, String sql, int effectiveLimit) {
        DatabaseConnectionConfig cfg = resolved.config();
        long startNanos = System.nanoTime();
        return executeWithConnection(resolved, conn -> {
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(cfg.queryTimeoutSeconds());
                stmt.setMaxRows(effectiveLimit + 1);
                boolean hasResultSet = stmt.execute(sql);
                if (!hasResultSet) {
                    long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                    return new DbQueryResult(cfg.id(), sql, List.of(), List.of(), 0, false, durationMs);
                }
                try (ResultSet rs = stmt.getResultSet()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columnCount = meta.getColumnCount();
                    List<String> columns = new ArrayList<>(columnCount);
                    for (int i = 1; i <= columnCount; i++) {
                        String label = meta.getColumnLabel(i);
                        if (label == null || label.isBlank()) {
                            label = meta.getColumnName(i);
                        }
                        if (label == null || label.isBlank()) {
                            label = "col_" + i;
                        }
                        columns.add(label);
                    }

                    List<Map<String, Object>> rows = new ArrayList<>();
                    boolean truncated = false;
                    while (rs.next()) {
                        if (rows.size() >= effectiveLimit) {
                            truncated = true;
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>(columnCount);
                        for (int i = 1; i <= columnCount; i++) {
                            String colKey = columns.get(i - 1);
                            Object formatted = formatCellValue(rs.getObject(i));
                            row.put(colKey, formatted);
                        }
                        rows.add(row);
                    }
                    long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                    return new DbQueryResult(cfg.id(), sql, List.copyOf(columns), List.copyOf(rows), rows.size(),
                            truncated, durationMs);
                }
            }
        });
    }

    private ResolvedDatabaseConnection requireActiveConnection(String connectionId) {
        return databaseConfigService.findActiveProjectConnection(connectionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Database connection '" + connectionId + "' is not granted to the active project."));
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    private <T> T executeWithConnection(ResolvedDatabaseConnection resolved, SqlWork<T> work) {
        DatabaseConnectionConfig cfg = resolved.config();
        String fingerprint = buildConnectionFingerprint(cfg);
        CachedConnection cached = borrowConnection(cfg, fingerprint);
        synchronized (cached.execLock) {
            try {
                if (!isConnectionUsable(cached.connection)) {
                    connectionCache.invalidate(cfg.id());
                    cached = borrowConnection(cfg, fingerprint);
                }
                applyDefaultSchemaIfPossible(cached.connection, resolved.effectiveDefaultSchema());
                return work.apply(cached.connection);
            } catch (SQLException e) {
                if (isConnectionBroken(cached.connection)) {
                    connectionCache.invalidate(cfg.id());
                }
                throw new IllegalStateException("Database error on '" + cfg.id() + "': " + e.getMessage(), e);
            } finally {
                rollbackQuietly(cached.connection);
            }
        }
    }

    @SuppressWarnings("PMD.CloseResource")
    private CachedConnection borrowConnection(DatabaseConnectionConfig cfg, String fingerprint) {
        CachedConnection existing = connectionCache.getIfPresent(cfg.id());
        if (existing != null && existing.fingerprint.equals(fingerprint) && isConnectionUsable(existing.connection)) {
            return existing;
        }
        if (existing != null) {
            connectionCache.invalidate(cfg.id());
        }
        String password = credentialStoreService.resolvePassword(cfg);
        try {
            Connection conn = openReadOnlyConnection(cfg, password);
            CachedConnection created = new CachedConnection(conn, fingerprint);
            connectionCache.put(cfg.id(), created);
            return created;
        } catch (Exception e) {
            log.warn("Failed to open read-only connection '{}' ({}): {}", cfg.id(), cfg.type(),
                    ExceptionUtils.getRootCauseMessage(e), e);
            throw new IllegalStateException(
                    "Failed to open read-only connection '" + cfg.id() + "': " + formatConnectionFailureMessage(e), e);
        }
    }

    Connection openReadOnlyConnection(DatabaseConnectionConfig config, @Nullable String password) throws Exception {
        DatabaseType dbType = config.resolveType();
        String effectiveUrl = normalizeUrlForReadOnly(config.url(), dbType, config.driverPaths());
        Properties props = buildConnectionProperties(config, dbType, password);
        DriverResolution resolution = resolveDriver(config, effectiveUrl);

        ClassLoader previousCl = Thread.currentThread().getContextClassLoader();
        Connection conn = null;
        try {
            Thread.currentThread().setContextClassLoader(resolution.classLoader());
            if (DriverManager.getLoginTimeout() <= 0) {
                DriverManager.setLoginTimeout(config.queryTimeoutSeconds());
            }
            conn = resolution.driver().connect(effectiveUrl, props);
            if (conn == null) {
                throw new SQLException("JDBC driver returned null connection for URL: " + effectiveUrl);
            }
            configureReadOnlySession(conn, dbType);
            return conn;
        } catch (Exception e) {
            closeQuietly(conn);
            throw e;
        } finally {
            Thread.currentThread().setContextClassLoader(previousCl);
        }
    }

    private record DriverResolution(Driver driver, ClassLoader classLoader) {
    }

    private DriverResolution resolveDriver(DatabaseConnectionConfig config, String effectiveUrl) throws Exception {
        if (!config.driverPaths().isEmpty()) {
            URLClassLoader customLoader = getOrCreateDriverClassLoader(config.driverPaths());
            Driver driver = findDriverInClassLoader(customLoader, config.driverClass(), effectiveUrl);
            if (driver == null) {
                throw new IllegalStateException(
                        "No JDBC driver found in custom driverPaths accepting URL: " + effectiveUrl);
            }
            return new DriverResolution(driver, customLoader);
        }

        ClassLoader appLoader = Thread.currentThread().getContextClassLoader();
        if (appLoader == null) {
            appLoader = ClassLoader.getSystemClassLoader();
        }
        Driver driver = findDriverInClassLoader(appLoader, config.driverClass(), effectiveUrl);
        if (driver == null) {
            throw new IllegalStateException("No bundled JDBC driver found accepting URL: " + effectiveUrl);
        }
        return new DriverResolution(driver, appLoader);
    }

    private @Nullable Driver findDriverInClassLoader(ClassLoader loader, String explicitDriverClass,
            String effectiveUrl) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(loader);
            if (explicitDriverClass != null && !explicitDriverClass.isBlank()) {
                Class<?> clazz = Class.forName(explicitDriverClass.trim(), true, loader);
                if (!Driver.class.isAssignableFrom(clazz)) {
                    throw new IllegalArgumentException(
                            "Configured driverClass does not implement java.sql.Driver: " + explicitDriverClass);
                }
                return (Driver) clazz.getDeclaredConstructor().newInstance();
            }
            ServiceLoader<Driver> serviceLoader = ServiceLoader.load(Driver.class, loader);
            for (Driver candidate : serviceLoader) {
                if (candidate.acceptsURL(effectiveUrl)) {
                    return candidate;
                }
            }
            return null;
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private URLClassLoader getOrCreateDriverClassLoader(List<String> driverPaths) throws IOException {
        List<Path> jarFiles = expandDriverJars(driverPaths);
        if (jarFiles.isEmpty()) {
            throw new IOException("No .jar files found in configured driverPaths: " + driverPaths);
        }
        String cacheKey = String.join("|", driverPaths);
        StringBuilder stampBuilder = new StringBuilder();
        List<URL> urls = new ArrayList<>(jarFiles.size());
        for (Path jar : jarFiles) {
            long mtime = Files.getLastModifiedTime(jar).toMillis();
            long size = Files.size(jar);
            stampBuilder.append(jar).append(':').append(size).append(':').append(mtime).append(';');
            urls.add(jar.toUri().toURL());
        }
        String stamp = stampBuilder.toString();

        CachedDriverLoader existing = driverLoaderCache.get(cacheKey);
        if (existing != null && existing.stamp.equals(stamp)) {
            return existing.classLoader;
        }

        URLClassLoader newLoader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
        CachedDriverLoader previous = driverLoaderCache.put(cacheKey, new CachedDriverLoader(stamp, newLoader));
        if (previous != null) {
            closeClassLoaderQuietly(previous.classLoader);
        }
        return newLoader;
    }

    static List<Path> expandDriverJars(List<String> driverPaths) throws IOException {
        List<Path> resolvedJars = new ArrayList<>();
        Set<Path> seen = new LinkedHashSet<>();
        for (String raw : driverPaths) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Path path = PathUtils.expandUserHome(raw).toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                throw new IOException("Configured driver path does not exist: " + path);
            }
            if (Files.isDirectory(path)) {
                List<Path> dirJars;
                try (Stream<Path> stream = Files.list(path)) {
                    dirJars = stream.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                            .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
                }
                for (Path jar : dirJars) {
                    if (seen.add(jar)) {
                        resolvedJars.add(jar);
                    }
                }
            } else if (Files.isRegularFile(path)) {
                if (seen.add(path)) {
                    resolvedJars.add(path);
                }
            }
        }
        return List.copyOf(resolvedJars);
    }

    static String normalizeUrlForReadOnly(String rawUrl, DatabaseType dbType, List<String> driverPaths) {
        String url = rawUrl.trim();
        String lower = url.toLowerCase(Locale.ROOT);
        if (driverPaths.isEmpty() && lower.startsWith("jdbc:mysql:") && !lower.contains("permitmysqlscheme")) {
            if (url.contains("?")) {
                url = url + "&permitMysqlScheme=true";
            } else {
                url = url + "?permitMysqlScheme=true";
            }
        }
        if (dbType == DatabaseType.H2 && !lower.contains("access_mode_data=")) {
            url = url + ";ACCESS_MODE_DATA=r";
        }
        return url;
    }

    private static Properties buildConnectionProperties(DatabaseConnectionConfig config, DatabaseType dbType,
            @Nullable String password) {
        Properties props = new Properties();
        if (!config.username().isBlank()) {
            props.setProperty("user", config.username());
        }
        if (password != null && config.resolveCredentialSource() != CredentialSource.NONE) {
            props.setProperty("password", password);
        }
        int timeoutSec = config.queryTimeoutSeconds();
        String timeoutMs = String.valueOf(timeoutSec * 1000L);
        if (dbType == DatabaseType.SQLITE) {
            props.setProperty("open_mode", "1");
        } else if (dbType == DatabaseType.POSTGRESQL) {
            props.setProperty("readOnly", "true");
            props.setProperty("readOnlyMode", "always");
            props.setProperty("loginTimeout", String.valueOf(timeoutSec));
            props.setProperty("connectTimeout", String.valueOf(timeoutSec));
        } else if (dbType == DatabaseType.MYSQL || dbType == DatabaseType.MARIADB) {
            props.setProperty("connectTimeout", timeoutMs);
        } else if (dbType == DatabaseType.HSQLDB) {
            props.setProperty("readonly", "true");
            props.setProperty("ifexists", "true");
        } else if (dbType == DatabaseType.SQLSERVER) {
            props.setProperty("applicationIntent", "ReadOnly");
            props.setProperty("loginTimeout", String.valueOf(timeoutSec));
        }
        return props;
    }

    static String formatConnectionFailureMessage(Throwable error) {
        String message = ExceptionUtils.getRootCauseMessage(error);
        SQLException sqlEx = ExceptionUtils.findCause(error, SQLException.class);
        if (sqlEx != null) {
            String sqlState = sqlEx.getSQLState();
            if (sqlState != null && !sqlState.isBlank() && !message.contains(sqlState)) {
                message = message + " (SQLState: " + sqlState + ")";
            }
        }
        return message;
    }

    private static void configureReadOnlySession(Connection conn, DatabaseType dbType) throws SQLException {
        conn.setAutoCommit(false);
        try {
            conn.setReadOnly(true);
        } catch (SQLException e) {
            log.debug("Connection.setReadOnly(true) not supported by driver ({}): {}", dbType, e.getMessage());
        }
        if (dbType == DatabaseType.ORACLE || dbType == DatabaseType.POSTGRESQL || dbType == DatabaseType.MARIADB
                || dbType == DatabaseType.MYSQL || dbType == DatabaseType.HSQLDB) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET TRANSACTION READ ONLY");
            } catch (SQLException e) {
                log.debug("SET TRANSACTION READ ONLY failed/unsupported for {}: {}", dbType, e.getMessage());
            }
        }
        rollbackQuietly(conn);
    }

    private static void applyDefaultSchemaIfPossible(Connection conn, String effectiveDefaultSchema) {
        if (effectiveDefaultSchema == null || effectiveDefaultSchema.isBlank()) {
            return;
        }
        try {
            conn.setSchema(effectiveDefaultSchema);
        } catch (SQLException | AbstractMethodError e) {
            log.debug("Connection.setSchema('{}') ignored: {}", effectiveDefaultSchema, e.getMessage());
        }
    }

    private static boolean detectWritePrivileges(Connection conn, DatabaseConnectionConfig config) {
        DatabaseType dbType = config.resolveType();
        try {
            if (dbType == DatabaseType.POSTGRESQL) {
                try (Statement stmt = conn.createStatement();
                        ResultSet rs = stmt
                                .executeQuery("SELECT EXISTS (SELECT 1 FROM information_schema.table_privileges "
                                        + "WHERE grantee = CURRENT_USER AND privilege_type IN ('INSERT','UPDATE','DELETE'))")) {
                    if (rs.next()) {
                        return rs.getBoolean(1);
                    }
                }
            } else if (dbType == DatabaseType.MYSQL || dbType == DatabaseType.MARIADB) {
                try (Statement stmt = conn.createStatement();
                        ResultSet rs = stmt.executeQuery("SHOW GRANTS FOR CURRENT_USER()")) {
                    while (rs.next()) {
                        String grant = StringUtils.defaultString(rs.getString(1)).toUpperCase(Locale.ROOT);
                        if (grant.contains("ALL PRIVILEGES") || grant.contains("INSERT") || grant.contains("UPDATE")
                                || grant.contains("DELETE")) {
                            return true;
                        }
                    }
                }
            } else if (dbType == DatabaseType.SQLITE) {
                String url = config.url().trim();
                if (url.toLowerCase(Locale.ROOT).startsWith("jdbc:sqlite:")) {
                    String filePart = url.substring("jdbc:sqlite:".length());
                    int queryIdx = filePart.indexOf('?');
                    if (queryIdx >= 0) {
                        filePart = filePart.substring(0, queryIdx);
                    }
                    if (!filePart.isBlank() && !filePart.startsWith(":")) {
                        Path dbFile = Path.of(filePart);
                        return Files.exists(dbFile) && Files.isWritable(dbFile);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Write-privilege probe skipped for {}: {}", dbType, e.getMessage());
        }
        return false;
    }

    private static String buildExplainSql(DatabaseType dbType, String validatedSelect) {
        if (dbType == DatabaseType.SQLITE) {
            return "EXPLAIN QUERY PLAN " + validatedSelect;
        }
        return "EXPLAIN " + validatedSelect;
    }

    private static int resolveEffectiveMaxRows(int requested, int configuredMax) {
        if (requested <= 0) {
            return configuredMax;
        }
        return Math.min(requested, configuredMax);
    }

    private static @Nullable Object formatCellValue(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return "[BINARY " + bytes.length + " bytes]";
        }
        if (value instanceof Blob blob) {
            try {
                return "[BLOB " + blob.length() + " bytes]";
            } catch (SQLException e) {
                return "[BLOB]";
            }
        }
        if (value instanceof Clob clob) {
            return readClobTruncated(clob);
        }
        String text = String.valueOf(value);
        if (text.length() > MAX_CELL_CHARS) {
            return text.substring(0, MAX_CELL_CHARS) + "... [truncated]";
        }
        return text;
    }

    private static String readClobTruncated(Clob clob) {
        try (Reader reader = clob.getCharacterStream()) {
            char[] buffer = new char[MAX_CELL_CHARS + 1];
            int read = IOUtils.read(reader, buffer);
            if (read > MAX_CELL_CHARS) {
                return new String(buffer, 0, MAX_CELL_CHARS) + "... [truncated]";
            }
            return new String(buffer, 0, Math.max(0, read));
        } catch (Exception e) {
            return "[CLOB]";
        }
    }

    private static String buildConnectionFingerprint(DatabaseConnectionConfig cfg) {
        return cfg.type() + "|" + cfg.url() + "|" + cfg.username() + "|" + cfg.credentialSource() + "|"
                + cfg.passwordEnv() + "|" + String.join(",", cfg.driverPaths()) + "|" + cfg.driverClass();
    }

    private static boolean isConnectionUsable(Connection conn) {
        try {
            return !conn.isClosed() && conn.isValid(VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException e) {
            return false;
        }
    }

    private static boolean isConnectionBroken(Connection conn) {
        try {
            return conn.isClosed() || !conn.isValid(1);
        } catch (SQLException e) {
            return true;
        }
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void onConnectionRemoved(@Nullable String key, @Nullable CachedConnection value, RemovalCause cause) {
        if (value != null) {
            rollbackQuietly(value.connection);
            closeQuietly(value.connection);
        }
    }

    private static void rollbackQuietly(@Nullable Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            if (!conn.isClosed() && !conn.getAutoCommit()) {
                conn.rollback();
            }
        } catch (SQLException e) {
            log.debug("Rollback error ignored: {}", e.getMessage());
        }
    }

    private static void closeQuietly(@Nullable Connection conn) {
        if (conn == null) {
            return;
        }
        try {
            if (!conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            log.debug("Connection close error ignored: {}", e.getMessage());
        }
    }

    private static void closeClassLoaderQuietly(@Nullable URLClassLoader loader) {
        if (loader == null) {
            return;
        }
        try {
            loader.close();
        } catch (IOException e) {
            log.debug("Driver ClassLoader close error ignored: {}", e.getMessage());
        }
    }

    private static final class IndexAccumulator {
        private final String name;
        private final boolean unique;
        private final List<String> columns = new ArrayList<>();

        private IndexAccumulator(String name, boolean unique) {
            this.name = name;
            this.unique = unique;
        }
    }
}
