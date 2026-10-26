package com.leadsystems.minecraft.ascension.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Owns the single JDBC connection. For SQLite the connection must only be used from the storage
 * thread ({@link StorageExecutor}); SQLite serializes writers anyway, so one connection plus one
 * thread gives a strict total order of all writes.
 */
public final class SqlDatabase implements AutoCloseable {

    private final String url;
    private final Properties properties;
    private final Dialect dialect;
    private final Path file;
    private Connection connection;

    private SqlDatabase(String url, Properties properties, Dialect dialect, Path file) {
        this.url = url;
        this.properties = properties;
        this.dialect = dialect;
        this.file = file;
    }

    /**
     * Opens (creating if needed) a SQLite database file in WAL mode with synchronous=FULL, so a
     * committed title claim or rank reward survives a crash immediately after commit.
     */
    public static SqlDatabase sqlite(Path file) throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found (Paper normally ships it in libraries/)", e);
        }
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Properties p = new Properties();
        p.setProperty("journal_mode", "WAL");
        p.setProperty("synchronous", "FULL");
        p.setProperty("busy_timeout", "5000");
        p.setProperty("foreign_keys", "true");
        p.setProperty("transaction_mode", "IMMEDIATE");
        SqlDatabase db = new SqlDatabase("jdbc:sqlite:" + file.toAbsolutePath(), p, new SqliteDialect(), file);
        db.connection(); // fail fast on a bad path or locked file
        return db;
    }

    /** The shared connection, reopened if it was closed. Storage thread only. */
    public synchronized Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(url, properties);
        }
        return connection;
    }

    public Dialect dialect() {
        return dialect;
    }

    /** Database file for SQLite (used for pre-migration backups); null for server databases. */
    public Path file() {
        return file;
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // closing on shutdown; nothing useful to do
            }
            connection = null;
        }
    }
}
