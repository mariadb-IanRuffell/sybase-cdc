-- GridGain 9 target schema for the Sybase Customer 360 data.
-- Everything customer-owned is colocated on customer_id (COLOCATE BY) so that the compute jobs (sp_customer_360, sp_score_customers,
-- the order flow) run on the node that holds the customer's partition.
-- order_item and payment carry a denormalised customer_id (resolved by the CDC reader via orders).
-- product and loyalty_tier_rule are small reference tables kept on every node (zone c360_ref).
-- Tables are persistent: the default storage profile uses native persistence.

-- Distribution zones: c360 keeps 3 replicas of every partition on the 3 nodes (a Raft group survives losing one node); c360_ref holds the
-- small reference tables on every node.
CREATE ZONE IF NOT EXISTS c360 (PARTITIONS 25, REPLICAS 3) STORAGE PROFILES ['default'];
CREATE ZONE IF NOT EXISTS c360_ref (PARTITIONS 25, REPLICAS ALL) STORAGE PROFILES ['default'];

CREATE TABLE IF NOT EXISTS customer (
  customer_id INT PRIMARY KEY,
  first_name VARCHAR(60), last_name VARCHAR(60), email VARCHAR(120), phone VARCHAR(30),
  date_of_birth DATE, nationality VARCHAR(2), segment VARCHAR(20), loyalty_tier VARCHAR(10),
  loyalty_points INT, marketing_opt_in BOOLEAN, status VARCHAR(10),
  created_at TIMESTAMP, updated_at TIMESTAMP
) ZONE c360;

CREATE TABLE IF NOT EXISTS address (
  address_id INT, customer_id INT,
  addr_type VARCHAR(10), line1 VARCHAR(120), city VARCHAR(60), postcode VARCHAR(20),
  country VARCHAR(2), is_primary BOOLEAN,
  PRIMARY KEY (address_id, customer_id)
) COLOCATE BY (customer_id) ZONE c360;

CREATE TABLE IF NOT EXISTS orders (
  order_id INT, customer_id INT,
  order_date TIMESTAMP, status VARCHAR(10), channel VARCHAR(10),
  total_amount DECIMAL(12,2), currency VARCHAR(3),
  PRIMARY KEY (order_id, customer_id)
) COLOCATE BY (customer_id) ZONE c360;

CREATE TABLE IF NOT EXISTS order_item (
  order_item_id INT, customer_id INT,
  order_id INT, product_id INT, qty INT, unit_price DECIMAL(10,2), line_total DECIMAL(12,2),
  PRIMARY KEY (order_item_id, customer_id)
) COLOCATE BY (customer_id) ZONE c360;

CREATE TABLE IF NOT EXISTS payment (
  payment_id INT, customer_id INT,
  order_id INT, method VARCHAR(10), amount DECIMAL(12,2), status VARCHAR(10), paid_at TIMESTAMP,
  PRIMARY KEY (payment_id, customer_id)
) COLOCATE BY (customer_id) ZONE c360;

CREATE TABLE IF NOT EXISTS interaction (
  interaction_id INT, customer_id INT,
  channel VARCHAR(10), type VARCHAR(20), subject VARCHAR(200), sentiment SMALLINT,
  status VARCHAR(10), opened_at TIMESTAMP, closed_at TIMESTAMP,
  PRIMARY KEY (interaction_id, customer_id)
) COLOCATE BY (customer_id) ZONE c360;

CREATE TABLE IF NOT EXISTS loyalty_txn (
  txn_id INT, customer_id INT,
  order_id INT, points INT, reason VARCHAR(20), txn_date TIMESTAMP,
  PRIMARY KEY (txn_id, customer_id)
) COLOCATE BY (customer_id) ZONE c360;

CREATE TABLE IF NOT EXISTS customer_score (
  customer_id INT PRIMARY KEY,
  churn_score INT, risk_band VARCHAR(10), scored_at TIMESTAMP
) COLOCATE BY (customer_id) ZONE c360;

CREATE TABLE IF NOT EXISTS product (
  product_id INT PRIMARY KEY,
  sku VARCHAR(20), name VARCHAR(100), category VARCHAR(20),
  unit_price DECIMAL(10,2), stock_qty INT, active BOOLEAN
) ZONE c360_ref;

CREATE TABLE IF NOT EXISTS loyalty_tier_rule (
  tier VARCHAR(10) PRIMARY KEY,
  min_spend DECIMAL(14,2), points_multiplier DECIMAL(4,2)
) ZONE c360_ref;
