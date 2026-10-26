package com.leadsystems.minecraft.ascension.audit;

import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;

import java.util.logging.Logger;

/**
 * Records administrative mutations and suspicious events (dupe attempts, forged books) to the
 * {@code admin_audit} table and the server log. Fire-and-forget: a failed audit write never
 * blocks gameplay, it only shows up in the log.
 */
public final class AuditLog {

    private final Logger log;
    private final StorageExecutor storage;
    private final PlayerRepository repo;

    public AuditLog(Logger log, StorageExecutor storage, PlayerRepository repo) {
        this.log = log;
        this.storage = storage;
        this.repo = repo;
    }

    /**
     * @param actor   who did it ({@code console}, a player name, or {@code system})
     * @param action  dotted action key, e.g. {@code rank.set}, {@code book.dupe}
     * @param target  affected player or title
     * @param details free text
     */
    public void record(String actor, String action, String target, String details) {
        long now = System.currentTimeMillis();
        log.info("[audit] " + actor + " " + action + " " + target + (details.isEmpty() ? "" : " - " + details));
        storage.submit("audit", c -> {
            repo.audit(c, actor, action, target, details, now);
            return null;
        });
    }
}
