-- Persistent bundle stock, demand price and randomized replenishment deadlines.
CREATE TABLE dynamic_shop_products (
    id TEXT PRIMARY KEY,
    material TEXT NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity BETWEEN 1 AND 64),
    label TEXT NOT NULL,
    base_price INTEGER NOT NULL CHECK (base_price BETWEEN 1 AND 4096),
    min_price INTEGER NOT NULL CHECK (min_price BETWEEN 1 AND 4096),
    max_price INTEGER NOT NULL CHECK (max_price BETWEEN min_price AND 4096),
    price_step INTEGER NOT NULL CHECK (price_step BETWEEN 1 AND 4096),
    restock_drop INTEGER NOT NULL CHECK (restock_drop BETWEEN 1 AND 4096),
    max_stock INTEGER NOT NULL CHECK (max_stock BETWEEN 1 AND 4096),
    restock_amount INTEGER NOT NULL CHECK (restock_amount BETWEEN 1 AND 4096),
    min_restock_seconds INTEGER NOT NULL CHECK (min_restock_seconds BETWEEN 1 AND 86400),
    max_restock_seconds INTEGER NOT NULL CHECK (max_restock_seconds BETWEEN min_restock_seconds AND 86400),
    stock INTEGER NOT NULL CHECK (stock >= 0 AND stock <= max_stock),
    current_price INTEGER NOT NULL CHECK (current_price BETWEEN min_price AND max_price),
    next_restock INTEGER NOT NULL,
    pending_operation TEXT,
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0,1))
);
CREATE INDEX idx_dynamic_shop_due ON dynamic_shop_products(enabled,next_restock);
