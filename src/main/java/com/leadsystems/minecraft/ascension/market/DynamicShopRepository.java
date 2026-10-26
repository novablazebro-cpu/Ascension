package com.leadsystems.minecraft.ascension.market;

import com.leadsystems.minecraft.ascension.storage.Tx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;

/** SQL-only shop state. Purchase helpers participate in the market journal's transaction. */
public final class DynamicShopRepository {
    public record Product(String id, String material, int quantity, String label, int basePrice,
                          int minPrice, int maxPrice, int priceStep, int restockDrop, int maxStock,
                          int restockAmount, int minRestockSeconds, int maxRestockSeconds) {
        public Product {
            if (id == null || !id.matches("[a-z0-9_]{1,48}") || material == null || material.isBlank()
                    || label == null || label.isBlank() || quantity < 1 || quantity > 64
                    || minPrice < 1 || maxPrice > 4096 || minPrice > basePrice || basePrice > maxPrice
                    || priceStep < 1 || priceStep > 4096 || restockDrop < 1 || restockDrop > 4096
                    || maxStock < 1 || maxStock > 4096 || restockAmount < 1 || restockAmount > maxStock
                    || minRestockSeconds < 1 || maxRestockSeconds > 86400
                    || minRestockSeconds > maxRestockSeconds) {
                throw new IllegalArgumentException("Invalid shop product: " + id);
            }
        }
    }
    public record Offer(Product product, int stock, int currentPrice, long nextRestock, boolean reserved) {}

    /** Config refresh preserves demand and journals. A changed interval gets a fresh deadline,
     * including reserved rows: their reservation still blocks replenishment until commit/abort. */
    public void seed(Connection c, List<Product> products, long now, IntBinaryOperator randomSeconds)
            throws SQLException {
        Tx.inTx(c, conn -> {
            try (var statement = conn.createStatement()) {
                statement.executeUpdate("UPDATE dynamic_shop_products SET enabled=0");
            }
            for (Product p : products) {
                String sql = "INSERT INTO dynamic_shop_products (id,material,quantity,label,base_price,min_price,max_price,"
                        + "price_step,restock_drop,max_stock,restock_amount,min_restock_seconds,max_restock_seconds,"
                        + "stock,current_price,next_restock,enabled) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1) "
                        + "ON CONFLICT(id) DO UPDATE SET material=excluded.material,quantity=excluded.quantity,"
                        + "label=excluded.label,base_price=excluded.base_price,min_price=excluded.min_price,"
                        + "max_price=excluded.max_price,price_step=excluded.price_step,restock_drop=excluded.restock_drop,"
                        + "max_stock=excluded.max_stock,restock_amount=excluded.restock_amount,"
                        + "next_restock=CASE WHEN min_restock_seconds<>excluded.min_restock_seconds "
                        + "OR max_restock_seconds<>excluded.max_restock_seconds THEN excluded.next_restock ELSE next_restock END,"
                        + "min_restock_seconds=excluded.min_restock_seconds,max_restock_seconds=excluded.max_restock_seconds,"
                        + "stock=MIN(stock,excluded.max_stock-CASE WHEN pending_operation IS NULL THEN 0 ELSE 1 END),"
                        + "current_price=MAX(excluded.min_price,MIN(current_price,excluded.max_price)),enabled=1";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    int n = 1;
                    ps.setString(n++, p.id()); ps.setString(n++, p.material()); ps.setInt(n++, p.quantity());
                    ps.setString(n++, p.label()); ps.setInt(n++, p.basePrice()); ps.setInt(n++, p.minPrice());
                    ps.setInt(n++, p.maxPrice()); ps.setInt(n++, p.priceStep()); ps.setInt(n++, p.restockDrop());
                    ps.setInt(n++, p.maxStock()); ps.setInt(n++, p.restockAmount());
                    ps.setInt(n++, p.minRestockSeconds()); ps.setInt(n++, p.maxRestockSeconds());
                    ps.setInt(n++, p.maxStock()); ps.setInt(n++, p.basePrice());
                    ps.setLong(n, deadline(p, now, randomSeconds)); ps.executeUpdate();
                }
            }
            return null;
        });
    }

    public List<Offer> offers(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM dynamic_shop_products WHERE enabled=1 ORDER BY id")) {
            return read(ps);
        }
    }
    public Offer find(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM dynamic_shop_products WHERE id=? AND enabled=1")) {
            ps.setString(1, id);
            var rows = read(ps);
            return rows.isEmpty() ? null : rows.getFirst();
        }
    }

    /** One replenishment per overdue row: restarting never accumulates unlimited offline stock. */
    public int restock(Connection c, long now, IntBinaryOperator randomSeconds) throws SQLException {
        return Tx.inTx(c, conn -> {
            List<Offer> due;
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM dynamic_shop_products "
                    + "WHERE enabled=1 AND pending_operation IS NULL AND next_restock<=? ORDER BY next_restock LIMIT 64")) {
                ps.setLong(1, now); due = read(ps);
            }
            int changed = 0;
            for (Offer offer : due) {
                Product p = offer.product();
                try (PreparedStatement ps = conn.prepareStatement("UPDATE dynamic_shop_products SET "
                        + "stock=MIN(max_stock,stock+restock_amount),current_price=MAX(min_price,current_price-restock_drop),"
                        + "next_restock=? WHERE id=? AND pending_operation IS NULL AND next_restock<=? AND enabled=1")) {
                    ps.setLong(1, deadline(p, now, randomSeconds)); ps.setString(2, p.id()); ps.setLong(3, now);
                    changed += ps.executeUpdate();
                }
            }
            return changed;
        });
    }

    /** Called only inside MarketRepository.prepare's transaction, before inserting its journal row. */
    public static boolean prepare(Connection c, MarketRepository.Operation op) throws SQLException {
        String id = quoteId(op);
        if (id == null || op.item() == null || !"EMERALD_BLOCK".equals(op.currency())) return false;
        try (PreparedStatement ps = c.prepareStatement("UPDATE dynamic_shop_products SET stock=stock-1,pending_operation=? "
                + "WHERE id=? AND enabled=1 AND stock>0 AND current_price=? AND pending_operation IS NULL AND next_restock>?")) {
            ps.setString(1, op.id()); ps.setString(2, id); ps.setInt(3, op.price());
            ps.setLong(4, op.createdAt());
            return ps.executeUpdate() == 1;
        }
    }
    /** Called only inside MarketRepository.commit's transaction, before creating purchase delivery. */
    public static boolean commit(Connection c, MarketRepository.Operation op) throws SQLException {
        String id = quoteId(op);
        if (id == null) return false;
        try (PreparedStatement ps = c.prepareStatement("UPDATE dynamic_shop_products SET "
                + "current_price=MIN(max_price,current_price+price_step),pending_operation=NULL WHERE id=? AND pending_operation=?")) {
            ps.setString(1, id); ps.setString(2, op.id()); return ps.executeUpdate() == 1;
        }
    }
    /** Called only inside MarketRepository.abort's transaction; it never changes demand price. */
    public static boolean abort(Connection c, MarketRepository.Operation op) throws SQLException {
        String id = quoteId(op);
        if (id == null) return false;
        try (PreparedStatement ps = c.prepareStatement("UPDATE dynamic_shop_products SET "
                + "stock=MIN(max_stock,stock+1),pending_operation=NULL WHERE id=? AND pending_operation=?")) {
            ps.setString(1, id); ps.setString(2, op.id()); return ps.executeUpdate() == 1;
        }
    }
    private static String quoteId(MarketRepository.Operation op) {
        if (!"SHOP".equals(op.kind()) || op.reference() == null || op.price() < 1 || op.price() > 4096) return null;
        String[] parts = op.reference().split("\\|", -1);
        if (parts.length != 2 || !parts[0].matches("[a-z0-9_]{1,48}")) return null;
        try { return Integer.parseInt(parts[1]) == op.price() ? parts[0] : null; }
        catch (NumberFormatException e) { return null; }
    }
    private static long deadline(Product product, long now, IntBinaryOperator randomSeconds) {
        int seconds = randomSeconds.applyAsInt(product.minRestockSeconds(), product.maxRestockSeconds());
        if (seconds < product.minRestockSeconds() || seconds > product.maxRestockSeconds())
            throw new IllegalArgumentException("Restock random source outside configured bounds");
        return Math.addExact(now, seconds * 1000L);
    }
    private static List<Offer> read(PreparedStatement ps) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            List<Offer> rows = new ArrayList<>();
            while (rs.next()) {
                Product p = new Product(rs.getString("id"), rs.getString("material"), rs.getInt("quantity"),
                        rs.getString("label"), rs.getInt("base_price"), rs.getInt("min_price"), rs.getInt("max_price"),
                        rs.getInt("price_step"), rs.getInt("restock_drop"), rs.getInt("max_stock"),
                        rs.getInt("restock_amount"), rs.getInt("min_restock_seconds"), rs.getInt("max_restock_seconds"));
                rows.add(new Offer(p, rs.getInt("stock"), rs.getInt("current_price"), rs.getLong("next_restock"),
                        rs.getString("pending_operation") != null));
            }
            return rows;
        }
    }
}
