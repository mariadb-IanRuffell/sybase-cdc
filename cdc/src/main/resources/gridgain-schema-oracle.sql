-- GridGain 9 tables for the ORACLE travel/reservations system (the Sybase tables are in gridgain-schema-sybase.sql).
-- bookings, segments and compensation are colocated on customer_id, exactly like the Sybase customer data, so a join
-- between the two systems' data (customer x booking x segment) runs on one node with no data movement.
-- booking_segment and compensation carry a denormalised customer_id (resolved by the reader via booking).
-- airport, flight, flight_inventory and flight_event are small reference-like tables kept on every node (zone c360_ref).

-- Distribution zones: c360 keeps 3 replicas of every partition on the 3 nodes (a Raft group survives losing one node); c360_ref holds the
-- small reference tables on every node.
CREATE ZONE IF NOT EXISTS c360 (PARTITIONS 25, REPLICAS 3) STORAGE PROFILES ['default'];
CREATE ZONE IF NOT EXISTS c360_ref (PARTITIONS 25, REPLICAS ALL) STORAGE PROFILES ['default'];

CREATE TABLE IF NOT EXISTS airport (
  iata VARCHAR(3) PRIMARY KEY,
  name VARCHAR(80), city VARCHAR(60), country VARCHAR(2), tz VARCHAR(40)
) ZONE c360_ref;

CREATE TABLE IF NOT EXISTS flight (
  flight_id INT PRIMARY KEY,
  flight_no VARCHAR(8), origin VARCHAR(3), destination VARCHAR(3), dep_time TIMESTAMP, arr_time TIMESTAMP,
  distance_km INT, aircraft VARCHAR(10), status VARCHAR(10), delay_minutes INT
) ZONE c360_ref;

CREATE TABLE IF NOT EXISTS flight_inventory (
  flight_id INT, cabin VARCHAR(10),
  seats_total INT, seats_available INT, next_seat INT, base_fare DECIMAL(10,2),
  PRIMARY KEY (flight_id, cabin)
) ZONE c360_ref;

CREATE TABLE IF NOT EXISTS booking (
  booking_id INT, customer_id INT,
  booking_ref VARCHAR(6), status VARCHAR(10), booked_at TIMESTAMP, channel VARCHAR(10),
  total_fare DECIMAL(12,2), refunded DECIMAL(12,2), currency VARCHAR(3),
  PRIMARY KEY (booking_id, customer_id)
) COLOCATE BY (customer_id) ZONE c360;

CREATE TABLE IF NOT EXISTS booking_segment (
  segment_id INT, customer_id INT,
  booking_id INT, flight_id INT, cabin VARCHAR(10), seat_no VARCHAR(4), passenger_name VARCHAR(80),
  fare DECIMAL(10,2), status VARCHAR(10), miles_earned INT,
  PRIMARY KEY (segment_id, customer_id)
) COLOCATE BY (customer_id) ZONE c360;

CREATE TABLE IF NOT EXISTS flight_event (
  event_id INT PRIMARY KEY,
  flight_id INT, event_type VARCHAR(12), detail VARCHAR(200), event_time TIMESTAMP
) ZONE c360_ref;

CREATE TABLE IF NOT EXISTS compensation (
  segment_id INT, customer_id INT,
  amount DECIMAL(10,2), reason VARCHAR(20), created_at TIMESTAMP,
  PRIMARY KEY (segment_id, customer_id)
) COLOCATE BY (customer_id) ZONE c360;
