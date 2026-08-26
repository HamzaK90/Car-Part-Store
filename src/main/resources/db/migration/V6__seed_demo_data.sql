-- V6 — demo data
--
-- A deterministic dataset: fixed ids and fixed dates. Running this on an empty database
-- twice gives identical business rows, so tests and screenshots do not drift.
--
-- ---------------------------------------------------------------------------
-- THIS FILE RUNS EVERYWHERE
--
-- It sits in classpath:db/migration alongside V1-V5, so every start-up applies it —
-- development, test, and production alike. There is no profile guarding it.
--
-- That has two consequences worth being deliberate about:
--
--   1. No credentials in this file. The password hashes are Flyway placeholders resolved
--      from the environment at migration time:
--
--          ${seed_admin_password_hash}     <- SEED_ADMIN_PASSWORD_HASH
--          ${seed_staff_password_hash}     <- SEED_STAFF_PASSWORD_HASH
--
--      Both must be BCrypt digests at cost 12. Neither has a default, so an unset
--      variable fails this migration on ck_app_user_password_hashed (V5) rather than
--      seeding an account with a password somebody could read off the repository.
--      See .env.example for how to generate one; .env is git-ignored.
--
--   2. This seeds ADMIN accounts and fake business data into whatever database it is
--      pointed at. A deployment that does not want demo data must exclude this file
--      deliberately — by narrowing spring.flyway.locations, or by not shipping it.
--
-- The dataset is therefore not byte-identical across environments: the five hash values
-- differ wherever the secrets differ. That is the security property, not a defect. Every
-- other row is fixed.
-- ---------------------------------------------------------------------------
--
-- What this dataset deliberately demonstrates:
--
--   * Irbid Warehouse has no manager, so it appears in v_department_without_manager with
--     two employees eligible for promotion.
--   * Several Irbid stock rows sit below their part's reorder_level, so v_low_stock is
--     not empty.
--   * Order 1004 billed the alternator at 205.00 while the catalogue now says 210.00,
--     so v_order_total shows a price snapshot that a later reprice did not move.
--   * Order 1005 has no employee_id — an order taken without a named salesperson.
--   * Customer 3 has no email, exercising the nullable half of uq_customer_email.

-- ---------------------------------------------------------------------------
-- Suppliers and the catalogue
-- ---------------------------------------------------------------------------

INSERT INTO supplier (supplier_id, name, city, street, phone_number) VALUES
    (1, 'Bosch', 'Stuttgart', 'Robert-Bosch-Platz 1', '+49-711-400040'),
    (2, 'Denso', 'Kariya',    'Showa-cho 1-1',        '+81-566-255511'),
    (3, 'NGK',   'Nagoya',    'Suda-cho 14-18',       '+81-52-8725555');

INSERT INTO part (part_id, sku, name, price, weight_kg, description, manufacturing_place, reorder_level, supplier_id) VALUES
    ( 1, 'BRK-1001', 'Brake Disc, Front',   45.00,  6.50, 'Vented cast iron, 280mm',        'Germany',  10, 1),
    ( 2, 'BRK-1002', 'Brake Pad Set',       28.50,  1.80, 'Ceramic, front axle, set of 4',  'Germany',  20, 1),
    ( 3, 'FLT-2001', 'Oil Filter',           9.75,  0.35, 'Spin-on, 3/4-16 UNF',            'Japan',    40, 2),
    ( 4, 'FLT-2002', 'Air Filter',          14.20,  0.50, 'Panel, paper element',           'Japan',    30, 2),
    ( 5, 'SPK-3001', 'Spark Plug, Copper',   6.40,  0.08, 'Gap 0.8mm',                      'Japan',   100, 3),
    ( 6, 'SPK-3002', 'Spark Plug, Iridium', 18.90,  0.09, 'Long life, gap 0.7mm',           'Japan',    50, 3),
    ( 7, 'ALT-4001', 'Alternator, 12V',    210.00,  5.20, '90A, with pulley',               'Germany',   4, 1),
    ( 8, 'BAT-5001', 'Battery, 70Ah',       95.00, 17.50, 'Maintenance free, 640A',         'Japan',     6, 2),
    ( 9, 'WIP-6001', 'Wiper Blade, 22in',   12.50,  0.40, 'Beam type, universal fit',       'Japan',    25, 3),
    (10, 'RAD-7001', 'Radiator',           165.00,  8.00, 'Aluminium core, plastic tanks',  'Germany',   3, 1);

INSERT INTO car_fitment (part_id, make, model, year_from, year_to) VALUES
    ( 1, 'Toyota', 'Corolla', 2014, 2019), ( 1, 'Toyota', 'Camry',   2015, 2020),
    ( 2, 'Toyota', 'Corolla', 2014, 2019), ( 2, 'Honda',  'Civic',   2016, 2021),
    ( 3, 'Toyota', 'Corolla', 2014, 2019), ( 3, 'Toyota', 'Camry',   2015, 2020),
    ( 3, 'Honda',  'Civic',   2016, 2021),
    ( 4, 'Honda',  'Civic',   2016, 2021),
    ( 5, 'Toyota', 'Corolla', 2014, 2019),
    ( 6, 'Honda',  'Civic',   2016, 2021), ( 6, 'Nissan', 'Altima',  2013, 2018),
    ( 7, 'Nissan', 'Altima',  2013, 2018),
    ( 8, 'Toyota', 'Camry',   2015, 2020), ( 8, 'Nissan', 'Altima',  2013, 2018),
    ( 9, 'Toyota', 'Corolla', 2014, 2019), ( 9, 'Honda',  'Civic',   2016, 2021),
    (10, 'Nissan', 'Altima',  2013, 2018);


-- ---------------------------------------------------------------------------
-- Departments, then staff, then managers
--
-- Written in the order the business actually works: the department opens, people are
-- hired into it, and one of them is promoted. manager_id is therefore left NULL on insert
-- and set by the UPDATE at the end of this block.
--
-- That ordering means the department block never depends on fk_department_manager being
-- deferred.
--
-- The order block below does depend on deferral, and unavoidably so: ct_order_has_lines
-- requires every order to have at least one line, and a header must exist before its lines
-- can reference it. This whole file therefore has to run in one transaction, which Flyway
-- always provides. Replaying it by hand needs psql --single-transaction.
-- ---------------------------------------------------------------------------

INSERT INTO department (department_id, name, type, city, street) VALUES
    (1, 'Downtown Branch',     'BRANCH',    'Amman', 'King Hussein St'),
    (2, 'Airport Road Branch', 'BRANCH',    'Amman', 'Airport Rd'),
    (3, 'Zarqa Warehouse',     'WAREHOUSE', 'Zarqa', 'Industrial Rd'),
    (4, 'Irbid Warehouse',     'WAREHOUSE', 'Irbid', 'North Ring Rd');

INSERT INTO branch (department_id) VALUES (1), (2);

INSERT INTO warehouse (department_id, free_area_sqm) VALUES
    (3, 1200.00),
    (4,  800.00);

INSERT INTO employee (employee_id, full_name, salary, birthdate, city, street, work_shift, department_id, hired_on) VALUES
    (1, 'Layla Haddad',  1450.00, '1988-03-12', 'Amman', 'Rainbow St',    'MORNING', 1, '2019-05-01'),
    (2, 'Omar Nasser',    980.00, '1995-07-22', 'Amman', 'Jabal Amman',   'EVENING', 1, '2022-02-14'),
    (3, 'Rana Khalil',   1380.00, '1990-11-05', 'Amman', 'Sweifieh',      'MORNING', 2, '2020-09-01'),
    (4, 'Tariq Aziz',    1020.00, '1993-01-30', 'Amman', 'Airport Rd',    'EVENING', 2, '2023-03-20'),
    (5, 'Hana Saleh',    1500.00, '1986-06-18', 'Zarqa', 'Al-Hashmi St',  'MORNING', 3, '2018-01-15'),
    (6, 'Yousef Odeh',    900.00, '1997-09-09', 'Zarqa', 'Industrial Rd', 'NIGHT',   3, '2023-11-06'),
    (7, 'Dina Farah',    1120.00, '1992-04-25', 'Irbid', 'University St', 'MORNING', 4, '2021-07-11'),
    (8, 'Sami Barakat',   870.00, '1999-12-01', 'Irbid', 'North Ring Rd', 'NIGHT',   4, '2024-02-19');

-- Irbid Warehouse is left without a manager on purpose — it is the row that makes
-- v_department_without_manager return something, with Dina and Sami as candidates.
UPDATE department SET manager_id = 1 WHERE department_id = 1;
UPDATE department SET manager_id = 3 WHERE department_id = 2;
UPDATE department SET manager_id = 5 WHERE department_id = 3;


-- ---------------------------------------------------------------------------
-- Customers
-- ---------------------------------------------------------------------------

INSERT INTO customer (customer_id, name, phone_number, email) VALUES
    (1, 'Ahmad Sweidan', '0790000001', 'ahmad.sweidan@example.com'),
    (2, 'Nour Qasem',    '0790000002', 'nour.qasem@example.com'),
    (3, 'Firas Deeb',    '0790000003', NULL),
    (4, 'Maha Zaid',     '0790000004', 'maha.zaid@example.com');


-- ---------------------------------------------------------------------------
-- Stock
--
-- Zarqa is the main warehouse and is comfortably stocked. Irbid is the small one, and six
-- of its rows sit below the part's reorder_level so that v_low_stock has something to say.
--
-- These are current levels, not a figure recomputed from the order history below. Seed
-- orders do not decrement stock; the decrement is OrderService's job at runtime.
-- ---------------------------------------------------------------------------

INSERT INTO warehouse_stock (warehouse_id, part_id, quantity) VALUES
    (3,  1,  40), (3,  2,  60), (3,  3, 120), (3,  4,  90), (3,  5, 300),
    (3,  6, 140), (3,  7,  12), (3,  8,  25), (3,  9,  80), (3, 10,   9),
    (4,  1,   6),   -- below reorder_level 10
    (4,  2,  18),   -- below 20
    (4,  3,  55),
    (4,  5,  40),   -- below 100
    (4,  7,   2),   -- below 4
    (4,  8,   4),   -- below 6
    (4,  9,  30),
    (4, 10,   1);   -- below 3


-- ---------------------------------------------------------------------------
-- Orders
--
-- Every handler works at the branch that took the order, as ct_order_employee_at_branch
-- requires. Order 1005 has none at all, which the schema allows.
--
-- Order 1004 is the interesting one: the alternator was billed at 205.00, and the
-- catalogue now says 210.00. The order total stays at 205.00 because order_item carries
-- the price of the day rather than reading part.price.
-- ---------------------------------------------------------------------------

INSERT INTO customer_order (order_id, customer_id, employee_id, branch_id, warehouse_id, order_date, status) VALUES
    (1001, 1,    2, 1, 3, '2026-01-12', 'FULFILLED'),
    (1002, 2,    4, 2, 3, '2026-02-03', 'FULFILLED'),
    (1003, 3,    2, 1, 4, '2026-02-20', 'PLACED'),
    (1004, 1,    4, 2, 3, '2026-03-05', 'CANCELLED'),
    (1005, 4, NULL, 1, 3, '2026-03-18', 'PLACED');

INSERT INTO order_item (order_id, part_id, quantity, unit_price) VALUES
    (1001,  1, 2,  45.00),
    (1001,  2, 1,  28.50),
    (1001,  3, 4,   9.75),
    (1002,  8, 1,  95.00),
    (1002,  5, 8,   6.40),
    (1003,  9, 2,  12.50),
    (1003,  3, 1,   9.75),
    (1004,  7, 1, 205.00),   -- catalogue is 210.00 today; this total does not move
    (1005,  6, 4,  18.90);


-- ---------------------------------------------------------------------------
-- Login accounts
--
-- layla and rana each manage a department, so v_user_identity reports is_manager = true
-- for them without any of it being stored on the account. omar manages nothing.
--
-- admin has no employee_id: an account that is not a person.
-- svc-reporting is disabled, showing an account kept for its history rather than deleted.
--
-- The three staff accounts share one hash and the two non-staff accounts share another.
-- That is a demo convenience, not a pattern to copy: real accounts get individual
-- passwords, and sharing a digest means one leak opens three doors.
-- ---------------------------------------------------------------------------

INSERT INTO app_user (user_id, username, password_hash, role, enabled, created_at, employee_id) VALUES
    (1, 'layla',         '${seed_staff_password_hash}', 'ADMIN',    TRUE,  '2026-01-05 09:00:00+03', 1),
    (2, 'omar',          '${seed_staff_password_hash}', 'EMPLOYEE', TRUE,  '2026-01-05 09:05:00+03', 2),
    (3, 'rana',          '${seed_staff_password_hash}', 'EMPLOYEE', TRUE,  '2026-01-05 09:10:00+03', 3),
    (4, 'admin',         '${seed_admin_password_hash}', 'ADMIN',    TRUE,  '2026-01-05 08:00:00+03', NULL),
    (5, 'svc-reporting', '${seed_admin_password_hash}', 'EMPLOYEE', FALSE, '2026-01-05 08:30:00+03', NULL);


-- ---------------------------------------------------------------------------
-- Re-align the sequences
--
-- Every id above was given explicitly, which leaves each BIGSERIAL's sequence sitting at
-- 1. Without this block the first row the application inserts would claim id 1 and collide
-- with seed data. This is the step that is easy to forget and painful to diagnose.
--
-- pg_get_serial_sequence looks the name up rather than hard-coding it, so renaming a table
-- cannot silently leave a sequence behind.
-- ---------------------------------------------------------------------------

SELECT setval(pg_get_serial_sequence('department',     'department_id'), (SELECT MAX(department_id) FROM department));
SELECT setval(pg_get_serial_sequence('employee',       'employee_id'),   (SELECT MAX(employee_id)   FROM employee));
SELECT setval(pg_get_serial_sequence('customer',       'customer_id'),   (SELECT MAX(customer_id)   FROM customer));
SELECT setval(pg_get_serial_sequence('supplier',       'supplier_id'),   (SELECT MAX(supplier_id)   FROM supplier));
SELECT setval(pg_get_serial_sequence('part',           'part_id'),       (SELECT MAX(part_id)       FROM part));
SELECT setval(pg_get_serial_sequence('customer_order', 'order_id'),      (SELECT MAX(order_id)      FROM customer_order));
SELECT setval(pg_get_serial_sequence('app_user',       'user_id'),       (SELECT MAX(user_id)       FROM app_user));
