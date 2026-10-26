package com.leadsystems.minecraft.ascension.storage;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Runs work inside a single transaction: commit on success, rollback on any exception.
 * Not re-entrant: callers must not nest {@code inTx} calls on the same connection.
 */
public final class Tx {

    private Tx() {}

    public static <T> T inTx(Connection c, SqlTask<T> work) throws SQLException {
        boolean previous = c.getAutoCommit();
        c.setAutoCommit(false);
        try {
            T result = work.run(c);
            c.commit();
            return result;
        } catch (SQLException | RuntimeException | Error e) {
            try {
                c.rollback();
            } catch (SQLException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        } finally {
            c.setAutoCommit(previous);
        }
    }
}
