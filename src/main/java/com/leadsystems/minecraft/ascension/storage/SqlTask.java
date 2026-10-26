package com.leadsystems.minecraft.ascension.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** A unit of database work executed on the storage thread. */
@FunctionalInterface
public interface SqlTask<T> {
    T run(Connection connection) throws SQLException;
}
