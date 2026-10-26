package com.leadsystems.minecraft.ascension.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies versioned schema migrations from {@code db/<dialect>/index.txt} in order. Each migration
 * runs in its own transaction together with its {@code schema_version} row, so a migration is
 * either fully applied or not at all. Before migrating an existing SQLite database, a copy is
 * written with {@code VACUUM INTO} to the backup directory.
 */
public final class MigrationRunner {

    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.+\\.sql$");

    private final SqlDatabase db;
    private final Path backupDir;
    private final Logger log;

    public MigrationRunner(SqlDatabase db, Path backupDir, Logger log) {
        this.db = db;
        this.backupDir = backupDir;
        this.log = log;
    }

    /** Applies pending migrations; returns how many were applied. */
    public int migrate() throws SQLException {
        Connection c = db.connection();
        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS schema_version ("
                    + "version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
        }
        int current = currentVersion(c);
        String base = "db/" + db.dialect().name() + "/";
        int applied = 0;
        for (String fileName : readLines(base + "index.txt")) {
            Matcher m = VERSION.matcher(fileName);
            if (!m.matches()) {
                throw new SQLException("Bad migration file name in index.txt: " + fileName);
            }
            int version = Integer.parseInt(m.group(1));
            if (version <= current) {
                continue;
            }
            if (current > 0) {
                backup(c, version);
            }
            List<String> statements = splitStatements(String.join("\n", readLines(base + fileName)));
            final int v = version;
            Tx.inTx(c, conn -> {
                try (Statement st = conn.createStatement()) {
                    for (String sql : statements) {
                        st.executeUpdate(sql);
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO schema_version (version, applied_at) VALUES (?, ?)")) {
                    ps.setInt(1, v);
                    ps.setLong(2, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                return null;
            });
            log.info("Applied database migration " + fileName);
            current = version;
            applied++;
        }
        return applied;
    }

    /** Highest applied schema version (0 for a new database). */
    public static int currentVersion(Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void backup(Connection c, int toVersion) throws SQLException {
        if (db.file() == null) {
            return;
        }
        try {
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            throw new SQLException("Cannot create backup directory " + backupDir, e);
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
        Path target = backupDir.resolve("ascension-pre-v" + toVersion + "-" + stamp + ".db").toAbsolutePath();
        for (int suffix = 1; Files.exists(target); suffix++) {
            target = backupDir.resolve("ascension-pre-v" + toVersion + "-" + stamp + "-" + suffix + ".db")
                    .toAbsolutePath();
        }
        try (Statement st = c.createStatement()) {
            st.executeUpdate("VACUUM INTO '" + target.toString().replace("'", "''") + "'");
        }
        log.info("Backed up database to " + target + " before migrating to v" + toVersion);
    }

    static List<String> splitStatements(String script) {
        StringBuilder cleaned = new StringBuilder();
        for (String line : script.split("\n")) {
            String t = line.trim();
            if (t.startsWith("--") || t.isEmpty()) {
                continue;
            }
            cleaned.append(line).append('\n');
        }
        List<String> out = new ArrayList<>();
        for (String part : cleaned.toString().split(";")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static List<String> readLines(String resource) throws SQLException {
        InputStream in = MigrationRunner.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new SQLException("Missing migration resource " + resource);
        }
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (resource.endsWith("index.txt")) {
                    String t = line.trim();
                    if (!t.isEmpty() && !t.startsWith("#")) {
                        lines.add(t);
                    }
                } else {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            throw new SQLException("Cannot read " + resource, e);
        }
        return lines;
    }
}
