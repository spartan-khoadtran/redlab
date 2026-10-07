-- Lab schema. Runs once when the postgres volume is created.
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

CREATE TABLE products (
    id       int PRIMARY KEY,
    name     text NOT NULL,
    price    numeric(10, 2) NOT NULL,
    category int NOT NULL
);
-- 100k products; users mostly browse the first 500 (the ones with reviews).
INSERT INTO products
SELECT g, 'Product ' || g, round((5 + random() * 195)::numeric, 2), 1 + (g % 12)
FROM generate_series(1, 100000) g;

-- No index on reviews.product_id on purpose: product_detail is an expensive query
-- and the Redis cache is what keeps it cheap.
CREATE TABLE reviews (
    id         bigserial PRIMARY KEY,
    product_id int NOT NULL,
    rating     int NOT NULL,
    body       text
);
INSERT INTO reviews (product_id, rating, body)
SELECT 1 + (random() * 499)::int, 1 + (random() * 4)::int, md5(g::text)
FROM generate_series(1, 200000) g;

CREATE TABLE orders (
    id         bigserial PRIMARY KEY,
    tenant     text,
    total      numeric(12, 2),
    status     text,
    created_at timestamptz DEFAULT now()
);
CREATE TABLE order_items (order_id bigint, product_id int, qty int);

CREATE TABLE inventory (sku text PRIMARY KEY, stock bigint NOT NULL);
INSERT INTO inventory VALUES ('HOT-1', 1000000000), ('SKU-2', 1000);

ANALYZE;
