package com.leadsystems.minecraft.ascension.market;

import com.leadsystems.minecraft.ascension.storage.MigrationRunner;
import com.leadsystems.minecraft.ascension.storage.SqlDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class MarketRepositoryTest {
    @TempDir Path dir;
    SqlDatabase db;
    Connection c;
    MarketRepository repo = new MarketRepository();
    UUID seller = UUID.randomUUID(), buyer = UUID.randomUUID();
    @BeforeEach void setup() throws Exception {
        db = SqlDatabase.sqlite(dir.resolve("market.db"));
        new MigrationRunner(db, dir.resolve("backups"), Logger.getAnonymousLogger()).migrate();
        c = db.connection();
    }
    @AfterEach void close() { if (db != null) db.close(); }
    MarketRepository.Operation op(UUID actor, String kind, String ref) {
        return new MarketRepository.Operation(UUID.randomUUID().toString(), actor, kind, ref,
                new byte[]{1}, new byte[]{2}, kind.equals("CLAIM") ? null : new byte[]{3}, "Sword", "EMERALD_BLOCK", 4, "Player", 10);
    }
    String listing() throws Exception {
        String id = UUID.randomUUID().toString();
        var sell = op(seller, "SELL", id);
        assertTrue(repo.prepare(c, sell)); assertTrue(repo.commit(c, sell.id()));
        return id;
    }
    @Test void tenListingsIncludeReservedListingsAndPendingSell() throws Exception {
        for (int i = 0; i < 9; i++) listing();
        var tenth = op(seller, "SELL", UUID.randomUUID().toString());
        assertTrue(repo.prepare(c, tenth));
        assertFalse(repo.prepare(c, op(seller, "SELL", UUID.randomUUID().toString())));
        assertTrue(repo.commit(c, tenth.id()));
        assertFalse(repo.prepare(c, op(seller, "SELL", UUID.randomUUID().toString())));
        assertEquals(10, repo.listings(c, seller, 0, 20).size());
        var buy = op(buyer, "BUY", repo.listings(c, seller, 0, 1).getFirst().id());
        assertTrue(repo.prepare(c, buy));
        assertFalse(repo.prepare(c, op(seller, "SELL", UUID.randomUUID().toString())));
        assertTrue(repo.commit(c, buy.id()));
        assertTrue(repo.prepare(c, op(seller, "SELL", UUID.randomUUID().toString())));
    }
    @Test void competingBuyersGetOneItemAndOfflineSellerGetsPaymentOnce() throws Exception {
        String id = listing(); var buy = op(buyer, "BUY", id);
        assertTrue(repo.prepare(c, buy));
        assertFalse(repo.prepare(c, op(UUID.randomUUID(), "BUY", id)));
        assertTrue(repo.commit(c, buy.id())); assertTrue(repo.commit(c, buy.id()));
        assertNull(repo.listing(c, id));
        assertEquals(1, repo.deliveries(c, buyer, 0, 20).size());
        assertEquals(1, repo.deliveries(c, seller, 0, 20).size());
        assertFalse(repo.abort(c, buy.id()));
    }
    @Test void selfBuyingAndIncorrectQuoteAreRejectedWithoutReservation() throws Exception {
        String id = listing(); assertFalse(repo.prepare(c, op(seller, "BUY", id)));
        var p = op(buyer, "BUY", id);
        var wrong = new MarketRepository.Operation(p.id(), p.actor(), p.kind(), p.reference(),
                p.beforeInventory(), p.afterInventory(), p.item(), p.itemName(), "DIAMOND_BLOCK", p.price(), p.actorName(), p.createdAt());
        assertFalse(repo.prepare(c, wrong)); assertNotNull(repo.listing(c, id));
    }
    @Test void abortedPurchaseReleasesListingAndNoMailIsMinted() throws Exception {
        String id = listing(); var buy = op(buyer, "BUY", id);
        assertTrue(repo.prepare(c, buy)); assertTrue(repo.abort(c, buy.id())); assertTrue(repo.abort(c, buy.id()));
        assertNotNull(repo.listing(c, id)); assertFalse(repo.commit(c, buy.id()));
        assertTrue(repo.deliveries(c, seller, 0, 10).isEmpty());
    }
    @Test void onlyOwnerCancelsAndCancellationReturnsExactBytes() throws Exception {
        String id = listing(); assertFalse(repo.prepare(c, op(buyer, "CANCEL", id)));
        var cancel = op(seller, "CANCEL", id);
        assertTrue(repo.prepare(c, cancel)); assertTrue(repo.commit(c, cancel.id()));
        assertTrue(repo.commit(c, cancel.id()));
        assertNull(repo.listing(c, id));
        assertArrayEquals(new byte[]{3}, repo.deliveries(c, seller, 0, 10).getFirst().item());
    }
    @Test void deliveryReservationAndClaimRequireOwner() throws Exception {
        var exchange = op(buyer, "EXCHANGE", "emerald_to_diamond");
        assertTrue(repo.prepare(c, exchange)); assertTrue(repo.commit(c, exchange.id()));
        String id = repo.deliveries(c, buyer, 0, 10).getFirst().id();
        assertNull(repo.delivery(c, id, seller));
        assertFalse(repo.prepare(c, op(seller, "CLAIM", id)));
        var claim = op(buyer, "CLAIM", id); assertTrue(repo.prepare(c, claim));
        assertNull(repo.delivery(c, id, buyer));
        assertTrue(repo.abort(c, claim.id())); assertNotNull(repo.delivery(c, id, buyer));
        var second = op(buyer, "CLAIM", id); assertTrue(repo.prepare(c, second));
        assertTrue(repo.commit(c, second.id())); assertTrue(repo.commit(c, second.id()));
        assertTrue(repo.deliveries(c, buyer, 0, 10).isEmpty());
    }
    @Test void preparedJournalSurvivesDatabaseReopen() throws Exception {
        var sell = op(seller, "SELL", UUID.randomUUID().toString()); assertTrue(repo.prepare(c, sell));
        db.close(); db = SqlDatabase.sqlite(dir.resolve("market.db")); c = db.connection();
        assertEquals(sell.id(), repo.pending(c, seller).getFirst().id());
        assertTrue(repo.commit(c, sell.id())); assertTrue(repo.pending(c, seller).isEmpty());
    }
    @Test void duplicateOperationCannotBeReboundToAnotherPlayer() throws Exception {
        var sell = op(seller, "SELL", UUID.randomUUID().toString()); assertTrue(repo.prepare(c, sell));
        assertTrue(repo.prepare(c, sell));
        var forged = new MarketRepository.Operation(sell.id(), buyer, sell.kind(), sell.reference(), sell.beforeInventory(),
                sell.afterInventory(), sell.item(), sell.itemName(), sell.currency(), sell.price(), "Other", sell.createdAt());
        assertFalse(repo.prepare(c, forged)); assertEquals(seller, repo.operation(c, sell.id()).actor());
    }
    @Test void invalidCurrencyAndPriceDoNotCreateListings() throws Exception {
        var sell = op(seller, "SELL", UUID.randomUUID().toString());
        for (int price : new int[]{0, -1, 4097}) {
            var invalid = new MarketRepository.Operation(UUID.randomUUID().toString(), seller, "SELL", sell.reference(),
                    sell.beforeInventory(), sell.afterInventory(), sell.item(), sell.itemName(), "EMERALD_BLOCK", price, "Player", 10);
            assertFalse(repo.prepare(c, invalid));
        }
        assertTrue(repo.listings(c, null, 0, 10).isEmpty());
    }
}
