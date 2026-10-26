package com.leadsystems.minecraft.ascension.market;

import com.leadsystems.minecraft.ascension.storage.MigrationRunner;
import com.leadsystems.minecraft.ascension.storage.SqlDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class DynamicShopRepositoryTest {
    @TempDir Path directory;
    SqlDatabase database;
    Connection connection;
    final DynamicShopRepository shop = new DynamicShopRepository();
    final MarketRepository market = new MarketRepository();
    final UUID buyer = UUID.randomUUID();
    final DynamicShopRepository.Product product = new DynamicShopRepository.Product(
            "obsidian", "OBSIDIAN", 16, "Obsidian", 4, 2, 6, 1, 1, 8, 4, 300, 900);

    @BeforeEach void start() throws Exception {
        database = SqlDatabase.sqlite(directory.resolve("shop.db"));
        new MigrationRunner(database, directory.resolve("backups"), Logger.getLogger("shop-test")).migrate();
        connection = database.connection();
        shop.seed(connection, List.of(product), 1_000, (min, max) -> min);
    }
    @AfterEach void stop() { database.close(); }

    MarketRepository.Operation order(int price) {
        return order(buyer, price);
    }
    MarketRepository.Operation order(UUID actor, int price) {
        return new MarketRepository.Operation(UUID.randomUUID().toString(), actor, "SHOP", "obsidian|" + price,
                new byte[]{1}, new byte[]{2}, new byte[]{3}, "16 x Obsidian", "EMERALD_BLOCK", price, "Buyer", 2_000);
    }
    DynamicShopRepository.Offer offer() throws SQLException { return shop.find(connection, "obsidian"); }
    void buy(int price) throws SQLException {
        var op = order(price);
        assertTrue(market.prepare(connection, op));
        assertTrue(market.commit(connection, op.id()));
    }

    @Test void purchaseUpdatesDemandWithinBoundsAndCreatesExactlyOneDelivery() throws Exception {
        var op = order(4);
        assertTrue(market.prepare(connection, op));
        assertEquals(7, offer().stock());
        assertEquals(4, offer().currentPrice(), "reserve must not raise demand before payment");
        assertTrue(market.commit(connection, op.id()));
        assertTrue(market.commit(connection, op.id()));
        assertEquals(5, offer().currentPrice());
        assertEquals(7, offer().stock());
        assertEquals(1, market.deliveries(connection, buyer, 0, 10).size());
        buy(5); buy(6); buy(6);
        assertEquals(6, offer().currentPrice());
        assertEquals(4, offer().stock());
    }

    @Test void concurrentBuyersAndStalePriceCannotReserveTheSameOffer() throws Exception {
        var first = order(4);
        assertTrue(market.prepare(connection, first));
        assertFalse(market.prepare(connection, order(UUID.randomUUID(), 4)), "another buyer cannot claim the price reservation");
        assertTrue(market.commit(connection, first.id()));
        assertFalse(market.prepare(connection, order(4)), "old quoted price must be rejected");
        assertEquals(7, offer().stock());
        assertTrue(market.prepare(connection, order(5)));
    }

    @Test void abortRestoresReservedStockExactlyOnceAndDoesNotRaisePrice() throws Exception {
        var op = order(4);
        assertTrue(market.prepare(connection, op));
        assertTrue(market.prepare(connection, op));
        assertEquals(7, offer().stock());
        assertTrue(market.abort(connection, op.id()));
        assertTrue(market.abort(connection, op.id()));
        assertEquals(8, offer().stock());
        assertEquals(4, offer().currentPrice());
        assertTrue(market.deliveries(connection, buyer, 0, 10).isEmpty());
        assertFalse(market.commit(connection, op.id()));
    }

    @Test void dueRestockIsBoundedLowersPriceAndSchedulesOneFutureDeadline() throws Exception {
        buy(4); buy(5);
        assertEquals(0, shop.restock(connection, 300_999, (min, max) -> max));
        assertEquals(1, shop.restock(connection, 301_000, (min, max) -> max));
        assertEquals(8, offer().stock());
        assertEquals(5, offer().currentPrice());
        assertEquals(1_201_000, offer().nextRestock());
        assertEquals(0, shop.restock(connection, 301_000, (min, max) -> max));
        for (int i = 0; i < 5; i++) shop.restock(connection, offer().nextRestock(), (min, max) -> min);
        assertEquals(2, offer().currentPrice());
        assertEquals(8, offer().stock());
    }

    @Test void restockWaitsForReservationBeforeReturningItsStock() throws Exception {
        var op = order(4);
        assertTrue(market.prepare(connection, op));
        assertEquals(0, shop.restock(connection, 1_000_000, (min, max) -> min));
        assertEquals(7, offer().stock());
        assertTrue(market.abort(connection, op.id()));
        assertEquals(1, shop.restock(connection, 1_000_000, (min, max) -> min));
        assertEquals(8, offer().stock());
        assertEquals(3, offer().currentPrice());
    }

    @Test void restartAndReseedKeepDemandStockDeadlineAndPendingRecovery() throws Exception {
        buy(4);
        var op = order(5);
        assertTrue(market.prepare(connection, op));
        var before = offer();
        database.close();
        database = SqlDatabase.sqlite(directory.resolve("shop.db"));
        connection = database.connection();
        shop.seed(connection, List.of(product), 900_000, (min, max) -> max);
        assertEquals(before, offer());
        assertTrue(market.commit(connection, op.id()));
        assertEquals(6, offer().currentPrice());
        assertEquals(6, offer().stock());
        assertEquals(before.nextRestock(), offer().nextRestock());
        assertEquals(2, market.deliveries(connection, buyer, 0, 10).size());
    }

    @Test void exhaustedStockAndWrongCurrencyCannotProduceOrders() throws Exception {
        for (int i = 0; i < 8; i++) buy(offer().currentPrice());
        assertEquals(0, offer().stock());
        assertFalse(market.prepare(connection, order(6)));
        shop.restock(connection, 301_000, (min, max) -> min);
        var valid = order(5);
        var wrongMoney = new MarketRepository.Operation(valid.id(), valid.actor(), "SHOP", valid.reference(),
                valid.beforeInventory(), valid.afterInventory(), valid.item(), valid.itemName(), "DIAMOND_BLOCK",
                valid.price(), valid.actorName(), valid.createdAt());
        assertFalse(market.prepare(connection, wrongMoney));
        assertEquals(4, offer().stock());
    }

    @Test void journalInsertionFailureRollsBackStockReservation() throws Exception {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER fail_shop_journal BEFORE INSERT ON market_operations "
                    + "WHEN NEW.kind='SHOP' BEGIN SELECT RAISE(ABORT,'injected journal failure'); END");
        }
        assertThrows(SQLException.class, () -> market.prepare(connection, order(4)));
        assertEquals(8, offer().stock());
        assertFalse(offer().reserved());
    }

    @Test void removedProductDisallowsNewOrdersButKeepsRecoveryOfReservedPurchase() throws Exception {
        var op = order(4);
        assertTrue(market.prepare(connection, op));
        shop.seed(connection, List.of(), 9_000, (min, max) -> min);
        assertNull(offer());
        assertFalse(market.prepare(connection, order(4)));
        assertTrue(market.commit(connection, op.id()));
        assertEquals(1, market.deliveries(connection, buyer, 0, 10).size());
    }

    @Test void longerIntervalReschedulesOldDeadlineWithoutLosingDemandStockOrPendingJournal() throws Exception {
        buy(4);
        var op = order(5);
        assertTrue(market.prepare(connection, op));
        var before = offer();
        var hours = new DynamicShopRepository.Product("obsidian", "OBSIDIAN", 16, "Obsidian",
                4, 2, 6, 1, 1, 8, 4, 7200, 14400);
        shop.seed(connection, List.of(hours), 100_000, (min, max) -> min);
        assertEquals(7_300_000, offer().nextRestock());
        assertEquals(before.stock(), offer().stock());
        assertEquals(before.currentPrice(), offer().currentPrice());
        assertTrue(offer().reserved());
        assertEquals(1, market.pending(connection, buyer).size());
        assertEquals(0, shop.restock(connection, 9_000_000, (min, max) -> max));
        assertTrue(market.commit(connection, op.id()));
        assertEquals(2, market.deliveries(connection, buyer, 0, 10).size());
        assertEquals(1, shop.restock(connection, 9_000_000, (min, max) -> max));
        assertEquals(23_400_000, offer().nextRestock());
    }

    @Test void overdueQuoteCannotReserveBeforeItsReplenishment() throws Exception {
        var valid = order(4);
        var overdue = new MarketRepository.Operation(valid.id(), valid.actor(), valid.kind(), valid.reference(),
                valid.beforeInventory(), valid.afterInventory(), valid.item(), valid.itemName(), valid.currency(),
                valid.price(), valid.actorName(), 301_000);
        assertFalse(market.prepare(connection, overdue));
        assertEquals(8, offer().stock());
        assertFalse(offer().reserved());
        assertEquals(1, shop.restock(connection, 301_000, (min, max) -> min));
        assertTrue(market.prepare(connection, order(3)));
    }
}
