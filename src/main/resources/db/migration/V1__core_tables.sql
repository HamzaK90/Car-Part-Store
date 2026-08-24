-- V1 — core tables
--
-- Enums and the seven tables that carry no order or stock data: department and its two
-- subtypes, employee, customer, supplier, part.
--
-- Orders, order lines, stock and car fitments arrive in V2. The deferred manager FK,
-- the cross-table constraint triggers and the FK indexes arrive in V3.

-- ---------------------------------------------------------------------------
-- Enum types
--
-- All four are declared here even though V1 only uses two; V2 needs order_status and
-- V5 needs user_role, and keeping the vocabulary in one place beats scattering it.
-- ---------------------------------------------------------------------------

CREATE TYPE department_type AS ENUM ('WAREHOUSE', 'BRANCH');
CREATE TYPE shift_type      AS ENUM ('MORNING', 'EVENING', 'NIGHT');
CREATE TYPE order_status    AS ENUM ('PLACED', 'FULFILLED', 'CANCELLED');
CREATE TYPE user_role       AS ENUM ('ADMIN', 'EMPLOYEE');


-- ---------------------------------------------------------------------------
-- department
--
-- The shared identity of both branches and warehouses.
--
-- uq_department_id_type looks redundant next to the primary key, but it is what the two
-- subtype tables reference. Pairing the id with the type in a unique key lets warehouse
-- and branch each pin their own type with a CHECK, so a department cannot appear in both.
--
-- manager_id is a bare column here. Its FK to employee is circular with
-- employee.department_id, so V3 adds it DEFERRABLE INITIALLY DEFERRED.
-- ---------------------------------------------------------------------------

CREATE TABLE department (
    department_id BIGSERIAL       PRIMARY KEY,
    name          VARCHAR(100)    NOT NULL,
    type          department_type NOT NULL,
    city          VARCHAR(50)     NOT NULL,
    street        VARCHAR(100)    NOT NULL,
    manager_id    BIGINT,

    CONSTRAINT uq_department_name    UNIQUE (name),
    CONSTRAINT uq_department_id_type UNIQUE (department_id, type)
);

COMMENT ON CONSTRAINT uq_department_id_type ON department IS
    'Referenced by warehouse and branch to make the two subtypes mutually exclusive.';


-- ---------------------------------------------------------------------------
-- warehouse / branch — the disjoint subtypes of department
--
-- Each table fixes its own type with a CHECK and then references the composite unique
-- key. A department row of type BRANCH therefore cannot be referenced by a warehouse row,
-- because the (department_id, 'WAREHOUSE') pair does not exist. No trigger involved.
-- ---------------------------------------------------------------------------

CREATE TABLE warehouse (
    department_id BIGINT          PRIMARY KEY,
    type          department_type NOT NULL DEFAULT 'WAREHOUSE',
    free_area_sqm NUMERIC(10, 2)  NOT NULL,

    CONSTRAINT ck_warehouse_type      CHECK (type = 'WAREHOUSE'),
    CONSTRAINT ck_warehouse_free_area CHECK (free_area_sqm >= 0),
    CONSTRAINT fk_warehouse_department
        FOREIGN KEY (department_id, type)
        REFERENCES department (department_id, type)
        ON DELETE CASCADE
);

CREATE TABLE branch (
    department_id BIGINT          PRIMARY KEY,
    type          department_type NOT NULL DEFAULT 'BRANCH',

    CONSTRAINT ck_branch_type CHECK (type = 'BRANCH'),
    CONSTRAINT fk_branch_department
        FOREIGN KEY (department_id, type)
        REFERENCES department (department_id, type)
        ON DELETE CASCADE
);


-- ---------------------------------------------------------------------------
-- employee
--
-- Every employee belongs to exactly one department; the department's subtype decides
-- whether they are sales staff or warehouse staff. Deleting a department that still has
-- staff is refused (the FK defaults to NO ACTION).
-- ---------------------------------------------------------------------------

CREATE TABLE employee (
    employee_id   BIGSERIAL      PRIMARY KEY,
    full_name     VARCHAR(100)   NOT NULL,
    salary        NUMERIC(10, 2) NOT NULL,
    birthdate     DATE,
    city          VARCHAR(50),
    street        VARCHAR(100),
    work_shift    shift_type     NOT NULL,
    department_id BIGINT         NOT NULL,
    hired_on      DATE           NOT NULL DEFAULT CURRENT_DATE,

    CONSTRAINT ck_employee_salary CHECK (salary > 0),
    CONSTRAINT fk_employee_department
        FOREIGN KEY (department_id) REFERENCES department (department_id)
);


-- ---------------------------------------------------------------------------
-- customer
-- ---------------------------------------------------------------------------

CREATE TABLE customer (
    customer_id  BIGSERIAL    PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    phone_number VARCHAR(20)  NOT NULL,
    email        VARCHAR(255),

    CONSTRAINT uq_customer_phone UNIQUE (phone_number),
    CONSTRAINT uq_customer_email UNIQUE (email)
);


-- ---------------------------------------------------------------------------
-- supplier
-- ---------------------------------------------------------------------------

CREATE TABLE supplier (
    supplier_id  BIGSERIAL    PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    city         VARCHAR(50),
    street       VARCHAR(100),
    phone_number VARCHAR(20),

    CONSTRAINT uq_supplier_name UNIQUE (name)
);


-- ---------------------------------------------------------------------------
-- part
--
-- price is the current catalogue price. It is deliberately not the price an order is
-- billed at — order_item captures unit_price at the time of sale, so editing this column
-- never moves the total of an invoice that already exists.
--
-- reorder_level is the stock figure below which this part counts as running low. It sits
-- on the part rather than on warehouse_stock because the level is a property of the part
-- itself — a brake disc and a wiper blade run low at different counts regardless of which
-- warehouse holds them. v_low_stock compares against it.
--
-- It defaults to 0, which reads as "never flag this part": a part imported without a
-- considered reorder level should stay quiet rather than fill the report with noise.
-- ---------------------------------------------------------------------------

CREATE TABLE part (
    part_id             BIGSERIAL      PRIMARY KEY,
    sku                 VARCHAR(32)    NOT NULL,
    name                VARCHAR(100)   NOT NULL,
    price               NUMERIC(10, 2) NOT NULL,
    weight_kg           NUMERIC(10, 2) NOT NULL,
    description         TEXT,
    manufacturing_place VARCHAR(100),
    reorder_level       INT            NOT NULL DEFAULT 0,
    supplier_id         BIGINT         NOT NULL,

    CONSTRAINT uq_part_sku           UNIQUE (sku),
    CONSTRAINT ck_part_price         CHECK (price >= 0),
    CONSTRAINT ck_part_weight        CHECK (weight_kg > 0),
    CONSTRAINT ck_part_reorder_level CHECK (reorder_level >= 0),
    CONSTRAINT fk_part_supplier
        FOREIGN KEY (supplier_id) REFERENCES supplier (supplier_id)
);
