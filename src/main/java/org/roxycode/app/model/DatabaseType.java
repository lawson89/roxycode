package org.roxycode.app.model;

import java.util.Locale;

/**
 * Supported database dialects for read-only JDBC connections.
 */
public enum DatabaseType {
    POSTGRESQL("postgresql", "PostgreSQL", "jdbc:postgresql://localhost:5432/mydb", "org.postgresql.Driver", true),
    MARIADB("mariadb", "MariaDB", "jdbc:mariadb://localhost:3306/mydb", "org.mariadb.jdbc.Driver", true),
    MYSQL("mysql", "MySQL", "jdbc:mysql://localhost:3306/mydb", "org.mariadb.jdbc.Driver", true),
    SQLSERVER("sqlserver", "SQL Server",
            "jdbc:sqlserver://localhost:1433;databaseName=mydb;encrypt=true;trustServerCertificate=true",
            "com.microsoft.sqlserver.jdbc.SQLServerDriver", true),
    SQLITE("sqlite", "SQLite", "jdbc:sqlite:/path/to/database.db", "org.sqlite.JDBC", true),
    H2("h2", "H2", "jdbc:h2:file:/path/to/mydb", "org.h2.Driver", true),
    HSQLDB("hsqldb", "HSQLDB", "jdbc:hsqldb:file:/path/to/mydb", "org.hsqldb.jdbc.JDBCDriver", true),
    ORACLE("oracle", "Oracle", "jdbc:oracle:thin:@//localhost:1521/ORCLPDB1", "oracle.jdbc.OracleDriver", false),
    DB2("db2", "IBM DB2", "jdbc:db2://localhost:50000/MYDB", "com.ibm.db2.jcc.DB2Driver", false),
    CUSTOM("custom", "Custom JDBC", "jdbc:custom://host:port/db", "", false);

    private final String id;
    private final String displayName;
    private final String sampleUrl;
    private final String defaultDriverClass;
    private final boolean bundledDriver;

    DatabaseType(String id, String displayName, String sampleUrl, String defaultDriverClass, boolean bundledDriver) {
        this.id = id;
        this.displayName = displayName;
        this.sampleUrl = sampleUrl;
        this.defaultDriverClass = defaultDriverClass;
        this.bundledDriver = bundledDriver;
    }

    /**
     * Returns the stable TOML identifier for this database type.
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the human-readable dialect name.
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns an example JDBC URL for UI placeholders.
     */
    public String getSampleUrl() {
        return sampleUrl;
    }

    /**
     * Returns the conventional JDBC driver class name, or empty string for custom drivers.
     */
    public String getDefaultDriverClass() {
        return defaultDriverClass;
    }

    /**
     * Returns {@code true} if RoxyCode bundles an open-source JDBC driver for this dialect.
     */
    public boolean isBundledDriver() {
        return bundledDriver;
    }

    /**
     * Parses a database type identifier or display name, defaulting to {@link #CUSTOM} when unrecognized.
     */
    public static DatabaseType fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return CUSTOM;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (DatabaseType type : values()) {
            if (type.id.equals(normalized) || type.displayName.toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        return CUSTOM;
    }

    /**
     * Infers the most likely {@link DatabaseType} from a JDBC URL prefix.
     */
    public static DatabaseType detectFromUrl(String url) {
        if (url == null || url.isBlank()) {
            return CUSTOM;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:postgresql:")) {
            return POSTGRESQL;
        }
        if (lower.startsWith("jdbc:mariadb:")) {
            return MARIADB;
        }
        if (lower.startsWith("jdbc:mysql:")) {
            return MYSQL;
        }
        if (lower.startsWith("jdbc:sqlserver:")) {
            return SQLSERVER;
        }
        if (lower.startsWith("jdbc:sqlite:")) {
            return SQLITE;
        }
        if (lower.startsWith("jdbc:h2:")) {
            return H2;
        }
        if (lower.startsWith("jdbc:hsqldb:")) {
            return HSQLDB;
        }
        if (lower.startsWith("jdbc:oracle:")) {
            return ORACLE;
        }
        if (lower.startsWith("jdbc:db2:")) {
            return DB2;
        }
        return CUSTOM;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
