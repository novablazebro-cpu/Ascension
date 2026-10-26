-- Fixed-price player market, item mailbox, and durable inventory-operation journal.
CREATE TABLE market_operations (
    id TEXT PRIMARY KEY,
    actor_uuid TEXT NOT NULL,
    kind TEXT NOT NULL CHECK (kind IN ('SELL', 'BUY', 'CANCEL', 'CLAIM', 'EXCHANGE', 'SHOP')),
    reference TEXT,
    before_inventory BLOB,
    after_inventory BLOB,
    item BLOB,
    item_name TEXT,
    currency TEXT CHECK (currency IN ('EMERALD_BLOCK', 'DIAMOND_BLOCK', 'NETHERITE_INGOT')),
    price INTEGER CHECK (price > 0 AND price <= 4096),
    actor_name TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('PREPARED', 'COMMITTED', 'ABORTED'))
);

CREATE TABLE market_listings (
    id TEXT PRIMARY KEY,
    seller_uuid TEXT NOT NULL,
    seller_name TEXT NOT NULL,
    item BLOB NOT NULL,
    item_name TEXT NOT NULL,
    currency TEXT NOT NULL CHECK (currency IN ('EMERALD_BLOCK', 'DIAMOND_BLOCK', 'NETHERITE_INGOT')),
    price INTEGER NOT NULL CHECK (price > 0 AND price <= 4096),
    created_at INTEGER NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'RESERVED', 'SOLD', 'CANCELLED')),
    reserved_by TEXT
);

CREATE INDEX idx_market_listings_active ON market_listings (status, created_at DESC);
CREATE INDEX idx_market_listings_seller ON market_listings (seller_uuid, status, created_at DESC);

CREATE TABLE market_deliveries (
    id TEXT PRIMARY KEY,
    owner_uuid TEXT NOT NULL,
    item BLOB NOT NULL,
    description TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('AVAILABLE', 'RESERVED', 'CLAIMED')),
    reserved_by TEXT
);

CREATE INDEX idx_market_deliveries_owner ON market_deliveries (owner_uuid, status, created_at DESC);
