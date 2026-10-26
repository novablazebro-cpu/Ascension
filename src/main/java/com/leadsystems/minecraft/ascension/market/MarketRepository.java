package com.leadsystems.minecraft.ascension.market;

import com.leadsystems.minecraft.ascension.storage.Tx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** SQL-only fixed-price market. Item bytes are opaque serialized Bukkit inventories/items. */
public final class MarketRepository {
    private static final int MAX_LISTINGS = 10;

    public record Listing(String id, UUID seller, String sellerName, byte[] item, String itemName,
                          String currency, int price, long createdAt) {
        public Listing { item = copy(item); }
        @Override public byte[] item() { return copy(item); }
    }
    public record Delivery(String id, UUID owner, byte[] item, String description) {
        public Delivery { item = copy(item); }
        @Override public byte[] item() { return copy(item); }
    }
    public record Operation(String id, UUID actor, String kind, String reference, byte[] beforeInventory,
                            byte[] afterInventory, byte[] item, String itemName, String currency, int price,
                            String actorName, long createdAt) {
        public Operation { beforeInventory = copy(beforeInventory); afterInventory = copy(afterInventory); item = copy(item); }
        @Override public byte[] beforeInventory() { return copy(beforeInventory); }
        @Override public byte[] afterInventory() { return copy(afterInventory); }
        @Override public byte[] item() { return copy(item); }
    }

    /** Persists and, where needed, reserves an operation. A duplicate operation id is harmless. */
    public boolean prepare(Connection c, Operation operation) throws SQLException {
        Objects.requireNonNull(operation, "operation");
        validate(operation);
        return Tx.inTx(c, conn -> {
            String existing = operationStatus(conn, operation.id());
            if (existing != null) return !"ABORTED".equals(existing)
                    && sameOperation(operation(conn, operation.id()), operation);
            if (operation.beforeInventory() == null || operation.afterInventory() == null
                    || operation.beforeInventory().length == 0 || operation.afterInventory().length == 0) return false;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM market_operations WHERE actor_uuid=? AND status='PREPARED' LIMIT 1")) {
                ps.setString(1, operation.actor().toString());
                try (ResultSet rows = ps.executeQuery()) { if (rows.next()) return false; }
            }
            return switch (operation.kind()) {
                case "SELL" -> prepareSell(conn, operation);
                case "BUY" -> prepareBuy(conn, operation);
                case "CANCEL" -> prepareCancel(conn, operation);
                case "CLAIM" -> prepareClaim(conn, operation);
                case "EXCHANGE" -> {
                    if (!validMoney(operation) || operation.item() == null) yield false;
                    insertOperation(conn, operation); yield true;
                }
                case "SHOP" -> {
                    if (!DynamicShopRepository.prepare(conn, operation)) yield false;
                    insertOperation(conn, operation); yield true;
                }
                default -> false;
            };
        });
    }

    /** Applies an already-prepared journal operation. Repeating this call never creates duplicate mail. */
    public boolean commit(Connection c, String operationId) throws SQLException {
        return Tx.inTx(c, conn -> {
            Operation op = operation(conn, operationId);
            if (op == null) return false;
            String status = operationStatus(conn, operationId);
            if ("COMMITTED".equals(status)) return true;
            if (!"PREPARED".equals(status)) return false;
            boolean changed = switch (op.kind()) {
                case "SELL" -> commitSell(conn, op);
                case "BUY" -> commitBuy(conn, op);
                case "CANCEL" -> commitCancel(conn, op);
                case "CLAIM" -> commitClaim(conn, op);
                case "EXCHANGE" -> { addDelivery(conn, operationId + ":exchange", op.actor(), op.item(),
                        "Exchange: " + op.itemName(), op.createdAt()); yield true; }
                case "SHOP" -> {
                    if (!DynamicShopRepository.commit(conn, op)) yield false;
                    addDelivery(conn, operationId + ":shop", op.actor(), op.item(), op.itemName(), op.createdAt());
                    yield true;
                }
                default -> false;
            };
            if (!changed) return false;
            updateOperationStatus(conn, operationId, "COMMITTED");
            return true;
        });
    }

    /** Releases a reservation or cancels a staged operation. Safe to call repeatedly. */
    public boolean abort(Connection c, String operationId) throws SQLException {
        return Tx.inTx(c, conn -> {
            Operation op = operation(conn, operationId);
            if (op == null) return false;
            String status = operationStatus(conn, operationId);
            if ("ABORTED".equals(status)) return true;
            if (!"PREPARED".equals(status)) return false;
            switch (op.kind()) {
                case "BUY", "CANCEL" -> releaseListing(conn, op.reference(), operationId);
                case "CLAIM" -> releaseDelivery(conn, op.reference(), operationId);
                case "SHOP" -> DynamicShopRepository.abort(conn, op);
                default -> { }
            }
            updateOperationStatus(conn, operationId, "ABORTED");
            return true;
        });
    }

    public List<Operation> pending(Connection c, UUID actor) throws SQLException {
        return operations(c, "WHERE actor_uuid = ? AND status = 'PREPARED' ORDER BY created_at", actor.toString());
    }
    public Operation operation(Connection c, String id) throws SQLException {
        List<Operation> rows = operations(c, "WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public List<Operation> pending(Connection c) throws SQLException {
        return operations(c, "WHERE status = 'PREPARED' ORDER BY created_at");
    }

    public List<Listing> listings(Connection c, UUID sellerOrNull, int offset, int limit) throws SQLException {
        if (offset < 0 || limit < 1) return List.of();
        String sql = sellerOrNull == null
                ? "SELECT id,seller_uuid,seller_name,item,item_name,currency,price,created_at FROM market_listings WHERE status='ACTIVE' ORDER BY created_at DESC,id LIMIT ? OFFSET ?"
                : "SELECT id,seller_uuid,seller_name,item,item_name,currency,price,created_at FROM market_listings WHERE status='ACTIVE' AND seller_uuid=? ORDER BY created_at DESC,id LIMIT ? OFFSET ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            int n = 1; if (sellerOrNull != null) ps.setString(n++, sellerOrNull.toString()); ps.setInt(n++, limit); ps.setInt(n, offset);
            return readListings(ps);
        }
    }
    public Listing listing(Connection c, String id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id,seller_uuid,seller_name,item,item_name,currency,price,created_at FROM market_listings WHERE id=? AND status='ACTIVE'")) {
            ps.setString(1, id); List<Listing> rows = readListings(ps); return rows.isEmpty() ? null : rows.getFirst();
        }
    }
    public List<Delivery> deliveries(Connection c, UUID owner, int offset, int limit) throws SQLException {
        if (offset < 0 || limit < 1) return List.of();
        try (PreparedStatement ps = c.prepareStatement("SELECT id,owner_uuid,item,description FROM market_deliveries WHERE owner_uuid=? AND status='AVAILABLE' ORDER BY created_at DESC,id LIMIT ? OFFSET ?")) {
            ps.setString(1, owner.toString()); ps.setInt(2, limit); ps.setInt(3, offset); return readDeliveries(ps);
        }
    }
    public Delivery delivery(Connection c, String id, UUID owner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT id,owner_uuid,item,description FROM market_deliveries WHERE id=? AND owner_uuid=? AND status='AVAILABLE'")) {
            ps.setString(1, id); ps.setString(2, owner.toString()); List<Delivery> rows = readDeliveries(ps); return rows.isEmpty() ? null : rows.getFirst();
        }
    }

    private boolean prepareSell(Connection c, Operation op) throws SQLException {
        if (op.item() == null || blank(op.itemName()) || !validMoney(op) || blank(op.reference())) return false;
        try (PreparedStatement ps = c.prepareStatement("SELECT (SELECT COUNT(*) FROM market_listings WHERE seller_uuid=? AND status IN ('ACTIVE','RESERVED')) + (SELECT COUNT(*) FROM market_operations WHERE actor_uuid=? AND kind='SELL' AND status='PREPARED')")) {
            ps.setString(1, op.actor().toString()); ps.setString(2, op.actor().toString());
            try (ResultSet rs = ps.executeQuery()) { if (!rs.next() || rs.getInt(1) >= MAX_LISTINGS) return false; }
        }
        insertOperation(c, op); return true;
    }
    private boolean prepareBuy(Connection c, Operation op) throws SQLException {
        if (blank(op.reference()) || !validMoney(op) || op.item() == null) return false;
        try (PreparedStatement ps = c.prepareStatement("UPDATE market_listings SET status='RESERVED',reserved_by=? WHERE id=? AND status='ACTIVE' AND seller_uuid<>? AND currency=? AND price=?")) {
            ps.setString(1, op.id()); ps.setString(2, op.reference()); ps.setString(3, op.actor().toString()); ps.setString(4, op.currency()); ps.setInt(5, op.price());
            if (ps.executeUpdate() != 1) return false;
        }
        insertOperation(c, op); return true;
    }
    private boolean prepareCancel(Connection c, Operation op) throws SQLException {
        if (blank(op.reference())) return false;
        try (PreparedStatement ps = c.prepareStatement("UPDATE market_listings SET status='RESERVED',reserved_by=? WHERE id=? AND status='ACTIVE' AND seller_uuid=?")) {
            ps.setString(1, op.id()); ps.setString(2, op.reference()); ps.setString(3, op.actor().toString()); if (ps.executeUpdate() != 1) return false;
        }
        insertOperation(c, op); return true;
    }
    private boolean prepareClaim(Connection c, Operation op) throws SQLException {
        if (blank(op.reference())) return false;
        try (PreparedStatement ps = c.prepareStatement("UPDATE market_deliveries SET status='RESERVED',reserved_by=? WHERE id=? AND status='AVAILABLE' AND owner_uuid=?")) {
            ps.setString(1, op.id()); ps.setString(2, op.reference()); ps.setString(3, op.actor().toString()); if (ps.executeUpdate() != 1) return false;
        }
        insertOperation(c, op); return true;
    }
    private boolean commitSell(Connection c, Operation op) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO market_listings (id,seller_uuid,seller_name,item,item_name,currency,price,created_at,status) VALUES (?,?,?,?,?,?,?,?, 'ACTIVE') ON CONFLICT(id) DO NOTHING")) {
            ps.setString(1, op.reference()); ps.setString(2, op.actor().toString()); ps.setString(3, op.actorName()); ps.setBytes(4, op.item()); ps.setString(5, op.itemName()); ps.setString(6, op.currency()); ps.setInt(7, op.price()); ps.setLong(8, op.createdAt());
            return ps.executeUpdate() == 1;
        }
    }
    private boolean commitBuy(Connection c, Operation op) throws SQLException {
        ListingData listing = reservedListing(c, op.reference(), op.id()); if (listing == null) return false;
        try (PreparedStatement ps = c.prepareStatement("UPDATE market_listings SET status='SOLD',reserved_by=NULL WHERE id=? AND status='RESERVED' AND reserved_by=?")) { ps.setString(1, op.reference()); ps.setString(2, op.id()); if (ps.executeUpdate() != 1) return false; }
        addDelivery(c, op.id() + ":buyer", op.actor(), listing.item, listing.itemName, op.createdAt());
        addDelivery(c, op.id() + ":seller", listing.seller, op.item(), description(listing.itemName, listing.price, listing.currency), op.createdAt());
        return true;
    }
    private boolean commitCancel(Connection c, Operation op) throws SQLException {
        ListingData listing = reservedListing(c, op.reference(), op.id()); if (listing == null || !listing.seller.equals(op.actor())) return false;
        try (PreparedStatement ps = c.prepareStatement("UPDATE market_listings SET status='CANCELLED',reserved_by=NULL WHERE id=? AND status='RESERVED' AND reserved_by=?")) { ps.setString(1, op.reference()); ps.setString(2, op.id()); if (ps.executeUpdate() != 1) return false; }
        addDelivery(c, op.id() + ":cancel", op.actor(), listing.item, listing.itemName, op.createdAt()); return true;
    }
    private boolean commitClaim(Connection c, Operation op) throws SQLException {
        if (op.item() != null) {
            try (PreparedStatement ps = c.prepareStatement("UPDATE market_deliveries SET item=?,status='AVAILABLE',reserved_by=NULL WHERE id=? AND owner_uuid=? AND status='RESERVED' AND reserved_by=?")) {
                ps.setBytes(1, op.item()); ps.setString(2, op.reference()); ps.setString(3, op.actor().toString());
                ps.setString(4, op.id()); return ps.executeUpdate() == 1;
            }
        }
        try (PreparedStatement ps = c.prepareStatement("UPDATE market_deliveries SET status='CLAIMED',reserved_by=NULL WHERE id=? AND owner_uuid=? AND status='RESERVED' AND reserved_by=?")) { ps.setString(1, op.reference()); ps.setString(2, op.actor().toString()); ps.setString(3, op.id()); return ps.executeUpdate() == 1; }
    }
    private void insertOperation(Connection c, Operation op) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO market_operations (id,actor_uuid,kind,reference,before_inventory,after_inventory,item,item_name,currency,price,actor_name,created_at,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?, 'PREPARED')")) {
            ps.setString(1, op.id()); ps.setString(2, op.actor().toString()); ps.setString(3, op.kind()); ps.setString(4, op.reference()); ps.setBytes(5, op.beforeInventory()); ps.setBytes(6, op.afterInventory()); ps.setBytes(7, op.item()); ps.setString(8, op.itemName()); ps.setString(9, op.currency()); if (op.price() > 0) ps.setInt(10, op.price()); else ps.setNull(10, java.sql.Types.INTEGER); ps.setString(11, op.actorName()); ps.setLong(12, op.createdAt()); ps.executeUpdate();
        }
    }
    private static void addDelivery(Connection c, String id, UUID owner, byte[] item, String description, long at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO market_deliveries (id,owner_uuid,item,description,created_at,status) VALUES (?,?,?,?,?,'AVAILABLE') ON CONFLICT(id) DO NOTHING")) { ps.setString(1,id); ps.setString(2,owner.toString()); ps.setBytes(3,item); ps.setString(4,description); ps.setLong(5,at); ps.executeUpdate(); }
    }
    private static void releaseListing(Connection c, String id, String op) throws SQLException { try (PreparedStatement ps=c.prepareStatement("UPDATE market_listings SET status='ACTIVE',reserved_by=NULL WHERE id=? AND status='RESERVED' AND reserved_by=?")) { ps.setString(1,id);ps.setString(2,op);ps.executeUpdate(); } }
    private static void releaseDelivery(Connection c, String id, String op) throws SQLException { try (PreparedStatement ps=c.prepareStatement("UPDATE market_deliveries SET status='AVAILABLE',reserved_by=NULL WHERE id=? AND status='RESERVED' AND reserved_by=?")) { ps.setString(1,id);ps.setString(2,op);ps.executeUpdate(); } }
    private static void updateOperationStatus(Connection c,String id,String status)throws SQLException { try(PreparedStatement ps=c.prepareStatement("UPDATE market_operations SET status=? WHERE id=?")){ps.setString(1,status);ps.setString(2,id);ps.executeUpdate();} }
    private static String operationStatus(Connection c,String id)throws SQLException { try(PreparedStatement ps=c.prepareStatement("SELECT status FROM market_operations WHERE id=?")){ps.setString(1,id);try(ResultSet rs=ps.executeQuery()){return rs.next()?rs.getString(1):null;}} }
    private static List<Operation> operations(Connection c,String suffix,String... params)throws SQLException { String sql="SELECT id,actor_uuid,kind,reference,before_inventory,after_inventory,item,item_name,currency,price,actor_name,created_at FROM market_operations "+suffix; try(PreparedStatement ps=c.prepareStatement(sql)){for(int i=0;i<params.length;i++)ps.setString(i+1,params[i]);try(ResultSet rs=ps.executeQuery()){List<Operation> out=new ArrayList<>();while(rs.next())out.add(new Operation(rs.getString(1),UUID.fromString(rs.getString(2)),rs.getString(3),rs.getString(4),rs.getBytes(5),rs.getBytes(6),rs.getBytes(7),rs.getString(8),rs.getString(9),rs.getInt(10),rs.getString(11),rs.getLong(12)));return out;}} }
    private static List<Listing> readListings(PreparedStatement ps)throws SQLException { try(ps;ResultSet rs=ps.executeQuery()){List<Listing>out=new ArrayList<>();while(rs.next())out.add(new Listing(rs.getString(1),UUID.fromString(rs.getString(2)),rs.getString(3),rs.getBytes(4),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getLong(8)));return out;} }
    private static List<Delivery> readDeliveries(PreparedStatement ps)throws SQLException { try(ps;ResultSet rs=ps.executeQuery()){List<Delivery>out=new ArrayList<>();while(rs.next())out.add(new Delivery(rs.getString(1),UUID.fromString(rs.getString(2)),rs.getBytes(3),rs.getString(4)));return out;} }
    private static ListingData reservedListing(Connection c,String id,String op)throws SQLException { try(PreparedStatement ps=c.prepareStatement("SELECT seller_uuid,item,item_name,currency,price FROM market_listings WHERE id=? AND status='RESERVED' AND reserved_by=?")){ps.setString(1,id);ps.setString(2,op);try(ResultSet rs=ps.executeQuery()){return rs.next()?new ListingData(UUID.fromString(rs.getString(1)),rs.getBytes(2),rs.getString(3),rs.getString(4),rs.getInt(5)):null;}} }
    private record ListingData(UUID seller, byte[] item, String itemName, String currency, int price) {}
    private static boolean validMoney(Operation op){return op.price()>0&&op.price()<=4096&&("EMERALD_BLOCK".equals(op.currency())||"DIAMOND_BLOCK".equals(op.currency())||"NETHERITE_INGOT".equals(op.currency()));}
    private static boolean sameOperation(Operation a, Operation b) {
        return a != null && a.actor().equals(b.actor()) && a.kind().equals(b.kind())
                && Objects.equals(a.reference(), b.reference()) && Objects.equals(a.currency(), b.currency())
                && a.price() == b.price() && Arrays.equals(a.beforeInventory(), b.beforeInventory())
                && Arrays.equals(a.afterInventory(), b.afterInventory()) && Arrays.equals(a.item(), b.item());
    }
    private static void validate(Operation op){Objects.requireNonNull(op.id(),"id");Objects.requireNonNull(op.actor(),"actor");Objects.requireNonNull(op.kind(),"kind");Objects.requireNonNull(op.actorName(),"actorName");}
    private static boolean blank(String s){return s==null||s.isBlank();}
    private static byte[] copy(byte[] bytes){return bytes==null?null:Arrays.copyOf(bytes,bytes.length);}
    private static String description(String itemName,int price,String currency){return itemName+" payment: "+price+" "+currency;}
}
