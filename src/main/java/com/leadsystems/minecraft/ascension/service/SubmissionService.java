package com.leadsystems.minecraft.ascension.service;

import com.leadsystems.minecraft.ascension.domain.quest.ActiveQuest;
import com.leadsystems.minecraft.ascension.domain.quest.ObjectiveDefinition;
import com.leadsystems.minecraft.ascension.domain.quest.ObjectiveType;
import com.leadsystems.minecraft.ascension.domain.quest.QuestState;
import com.leadsystems.minecraft.ascension.scheduler.MainThread;
import com.leadsystems.minecraft.ascension.session.PlayerSession;
import com.leadsystems.minecraft.ascension.session.SessionHook;
import com.leadsystems.minecraft.ascension.session.SessionManager;
import com.leadsystems.minecraft.ascension.storage.PlayerRepository;
import com.leadsystems.minecraft.ascension.storage.StorageExecutor;
import com.leadsystems.minecraft.ascension.storage.StorageHealth;
import com.leadsystems.minecraft.ascension.storage.SubmissionRecord;
import com.leadsystems.minecraft.ascension.text.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/** Journaled quest item submission and crash recovery. Inventory calls are main-thread only. */
public final class SubmissionService implements SessionHook {

    private static final String OPERATION = "quest.submit";

    private final SessionManager sessions;
    private final StorageExecutor storage;
    private final PlayerRepository repo;
    private final StorageHealth health;
    private final MainThread main;
    private final Messages messages;

    public SubmissionService(SessionManager sessions, StorageExecutor storage, PlayerRepository repo,
                             StorageHealth health, MainThread main, Messages messages) {
        this.sessions = sessions;
        this.storage = storage;
        this.repo = repo;
        this.health = health;
        this.main = main;
        this.messages = messages;
    }

    public void submit(Player player, String objectiveId, Runnable after) {
        PlayerSession session = sessions.get(player);
        ActiveQuest quest = session == null ? null : session.quest();
        ObjectiveDefinition objective = quest == null ? null : quest.definition().objective(objectiveId);
        if (session == null || !session.usable() || !session.rankUsable(System.currentTimeMillis())
                || quest == null || quest.state() != QuestState.ACTIVE
                || objective == null || objective.type() != ObjectiveType.SUBMIT_ITEMS
                || objective.targets().size() != 1) {
            messages.send(player, "submission-invalid");
            return;
        }
        if (!health.isHealthy()) {
            messages.send(player, "storage-degraded");
            return;
        }
        long remaining = objective.amount() - quest.amount(objective.id());
        if (remaining <= 0 || remaining > Integer.MAX_VALUE) {
            messages.send(player, "submission-invalid");
            return;
        }
        Material material = Material.matchMaterial(objective.targets().iterator().next());
        int before = count(player, material);
        if (material == null || before < remaining) {
            messages.send(player, "submission-missing", Messages.ph("amount", remaining),
                    Messages.ph("item", objective.description()));
            return;
        }
        if (!session.tryBegin(OPERATION)) {
            messages.send(player, "quest-busy");
            return;
        }
        int amount = (int) remaining;
        String operationId = UUID.randomUUID().toString();
        SubmissionRecord op = new SubmissionRecord(operationId, session.uuid(), session.rankIndex(),
                objective.id(), material.name(), amount, before);
        long now = System.currentTimeMillis();
        main.onComplete(storage.submit("submission.prepare", c -> repo.beginSubmission(c, op, now)),
                (prepared, error) -> {
                    if (!player.isOnline() || sessions.get(player) != session) {
                        session.end(OPERATION);
                        return;
                    }
                    if (error != null || !Boolean.TRUE.equals(prepared)) {
                        session.end(OPERATION);
                        messages.send(player, error == null ? "submission-invalid" : "storage-degraded");
                        return;
                    }
                    if (!remove(player, material, amount)) {
                        session.end(OPERATION);
                        restoreJournal(op);
                        messages.send(player, "submission-missing", Messages.ph("amount", amount),
                                Messages.ph("item", objective.description()));
                        return;
                    }
                    sessions.mutate(session.uuid(), "submission.complete",
                            c -> repo.completeSubmission(c, operationId, objective.amount(), System.currentTimeMillis()),
                            (completed, failure) -> {
                                session.end(OPERATION);
                                if (failure != null || !Boolean.TRUE.equals(completed)) {
                                    if (player.isOnline()) {
                                        returnItems(player, material, amount);
                                        restoreJournal(op);
                                        messages.send(player, "submission-restored");
                                    }
                                    return;
                                }
                                messages.send(player, "submission-complete", Messages.ph("amount", amount),
                                        Messages.ph("item", material.name()));
                                after.run();
                            });
                });
    }

    @Override
    public void onLoaded(Player player, PlayerSession session, boolean reload) {
        main.onComplete(storage.submit("submission.recover", c -> repo.pendingSubmissions(c, session.uuid())),
                (pending, error) -> {
                    if (error != null || pending == null) {
                        return;
                    }
                    for (SubmissionRecord op : pending) {
                        recover(player, op);
                    }
                });
    }

    private void recover(Player player, SubmissionRecord op) {
        Material material = Material.matchMaterial(op.material());
        if (material == null) {
            return;
        }
        int missing = Math.min(op.amount(), Math.max(0, op.beforeCount() - count(player, material)));
        if (missing > 0) {
            returnItems(player, material, missing);
        }
        restoreJournal(op);
        if (missing > 0) {
            messages.send(player, "submission-restored");
        }
    }

    private void restoreJournal(SubmissionRecord op) {
        storage.submit("submission.restore", c -> {
            repo.markSubmissionRestored(c, op.operationId(), System.currentTimeMillis());
            return null;
        });
    }

    private static void returnItems(Player player, Material material, int amount) {
        for (ItemStack leftover : player.getInventory().addItem(new ItemStack(material, amount)).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private static int count(Player player, Material material) {
        if (material == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private static boolean remove(Player player, Material material, int amount) {
        if (count(player, material) < amount) {
            return false;
        }
        int left = amount;
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int take = Math.min(left, stack.getAmount());
            stack.setAmount(stack.getAmount() - take);
            contents[i] = stack.getAmount() == 0 ? null : stack;
            left -= take;
        }
        player.getInventory().setStorageContents(contents);
        return left == 0;
    }
}
