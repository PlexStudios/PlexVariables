package dev.plex.plexvariables.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

public final class SQLiteStorage {
    private static final int CURRENT_SCHEMA_VERSION = 1;

    private final Path databaseFile;
    private final Logger logger;
    private Connection connection;

    public SQLiteStorage(Path databaseFile, Logger logger) {
        this.databaseFile = Objects.requireNonNull(databaseFile, "databaseFile");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void init() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new StorageException("SQLite JDBC driver not found on classpath", e);
        }

        try {
            if (databaseFile.getParent() != null) {
                Files.createDirectories(databaseFile.getParent());
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
            
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL;");
                statement.execute("PRAGMA busy_timeout=5000;");
            }

            initTablesAndSchema();
        } catch (Exception e) {
            closeSilently();
            throw new StorageException("Failed to initialize SQLite database at " + databaseFile + ": " + e.getMessage(), e);
        }
    }

    private void initTablesAndSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE IF NOT EXISTS plexvariables_meta (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                );
                """);

            statement.execute("""
                CREATE TABLE IF NOT EXISTS player_variables (
                    uuid TEXT NOT NULL,
                    variable_id TEXT NOT NULL,
                    value TEXT NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(uuid, variable_id)
                );
                """);

            statement.execute("""
                CREATE TABLE IF NOT EXISTS global_variables (
                    variable_id TEXT NOT NULL PRIMARY KEY,
                    value TEXT NOT NULL,
                    updated_at INTEGER NOT NULL
                );
                """);
        }

        int version = 0;
        try (PreparedStatement ps = connection.prepareStatement("SELECT value FROM plexvariables_meta WHERE key = 'schema_version'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    version = Integer.parseInt(rs.getString("value"));
                }
            }
        }

        if (version == 0) {
            try (PreparedStatement ps = connection.prepareStatement("INSERT INTO plexvariables_meta (key, value) VALUES ('schema_version', ?)")) {
                ps.setString(1, String.valueOf(CURRENT_SCHEMA_VERSION));
                ps.executeUpdate();
            }
        } else if (version > CURRENT_SCHEMA_VERSION) {
            throw new StorageException("Database schema version " + version + " is newer than supported version " + CURRENT_SCHEMA_VERSION);
        }
    }

    public Map<String, String> loadGlobalVariables() {
        Map<String, String> result = new HashMap<>();
        String sql = "SELECT variable_id, value FROM global_variables";
        try (PreparedStatement ps = connection.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.put(rs.getString("variable_id"), rs.getString("value"));
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to load global variables", e);
        }
        return result;
    }

    public Map<String, String> loadPlayerVariables(UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        Map<String, String> result = new HashMap<>();
        String sql = "SELECT variable_id, value FROM player_variables WHERE uuid = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("variable_id"), rs.getString("value"));
                }
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to load player variables for UUID " + uuid, e);
        }
        return result;
    }

    public void savePlayerVariable(UUID uuid, String variableId, String value, long timestamp) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(variableId, "variableId");
        Objects.requireNonNull(value, "value");
        String sql = """
            INSERT INTO player_variables (uuid, variable_id, value, updated_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(uuid, variable_id) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, variableId);
            ps.setString(3, value);
            ps.setLong(4, timestamp);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Failed to save player variable '" + variableId + "' for UUID " + uuid, e);
        }
    }

    public void deletePlayerVariable(UUID uuid, String variableId) {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(variableId, "variableId");
        String sql = "DELETE FROM player_variables WHERE uuid = ? AND variable_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, variableId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Failed to delete player variable '" + variableId + "' for UUID " + uuid, e);
        }
    }

    public void saveGlobalVariable(String variableId, String value, long timestamp) {
        Objects.requireNonNull(variableId, "variableId");
        Objects.requireNonNull(value, "value");
        String sql = """
            INSERT INTO global_variables (variable_id, value, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(variable_id) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, variableId);
            ps.setString(2, value);
            ps.setLong(3, timestamp);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Failed to save global variable '" + variableId + "'", e);
        }
    }

    public void deleteGlobalVariable(String variableId) {
        Objects.requireNonNull(variableId, "variableId");
        String sql = "DELETE FROM global_variables WHERE variable_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, variableId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new StorageException("Failed to delete global variable '" + variableId + "'", e);
        }
    }

    public void close() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException e) {
                logger.warning("Error closing SQLite connection: " + e.getMessage());
            } finally {
                connection = null;
            }
        }
    }

    private void closeSilently() {
        try {
            close();
        } catch (Exception ignored) {
        }
    }
}
