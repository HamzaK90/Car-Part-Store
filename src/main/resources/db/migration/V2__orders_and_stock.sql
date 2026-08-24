-- V2 — orders and stock
--
-- The four tables that depend on V1's core entities: which cars a part fits, how much of
-- each part a warehouse holds, and the order header and its lines.
--
-- The deferred manager FK, the constraint trigger that keeps a warehouse employee off a
-- branch order, and the index on every FK column all arrive in V3.

-- ---------------------------------------------------------------------------
-- car_fitment
--
-- A part fits many cars, so the fitments are their own table rather than a column.
-- A fitment is identified by the part plus the make, model and first model year; year_to
-- is the only non-key attribute, which is why it sits outside the primary key.
-- ---------------------------------------------------------------------------

CREATE TABLE car_fitment (
    part_id   BIGINT      NOT NULL,
    make      VARCHAR(50) NOT NULL,
    model     VARCHAR(50) NOT NULL,
    year_from SMALLINT    NOT NULL,
    year_to   SMALLINT    NOT NULL,

    CONSTRAINT pk_car_fitment PRIMARY KEY (part_id, make, model, year_from),
    CONSTRAINT ck_car_fitment_year_range CHECK (year_to >= year_from),
    CONSTRAINT fk_car_fitment_part
        FOREIGN KEY (part_id) REFERENCES part (part_id)
        ON DELETE CASCADE
);


-- ---------------------------------------------------------------------------
-- warehouse_stock
--
-- Inventory, one row per (warehouse, part). quantity is decremented inside the order
-- transaction after a SELECT ... FOR UPDATE on these rows; ck_warehouse_stock_quantity is
-- the backstop that makes overselling impossible even if the service logic is wrong.
--
-- warehouse_id references the warehouse subtype, not department, so a branch can never
-- be given stock.
-- ---------------------------------------------------------------------------

CREATE TABLE warehouse_stock (
    warehouse_id BIGINT NOT NULL,
    part_id      BIGINT NOT NULL,
    quantity     INT    NOT NULL DEFAULT 0,

    CONSTRAINT pk_warehouse_stock PRIMARY KEY (warehouse_id, part_id),
    CONSTRAINT ck_warehouse_stock_quantity CHECK (quantity >= 0),
    CONSTRAINT fk_warehouse_stock_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouse (department_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_warehouse_stock_part
        FOREIGN KEY (part_id) REFERENCES part (part_id)
);


-- ---------------------------------------------------------------------------
-- customer_order
--
-- Two departments are involved and they mean different things: branch_id is the sales
-- location where the order was taken, warehouse_id is the warehouse whose stock fills it.
-- Both reference their subtype table, so neither can be pointed at a department of the
-- wrong kind.
--
-- employee_id is nullable — an order may outlive the salesperson's record, and the UML
-- gives it 0..1. That the employee actually works at branch_id is a cross-table rule a
-- column constraint cannot express; V3 adds it as a constraint trigger.
-- ---------------------------------------------------------------------------

CREATE TABLE customer_order (
    order_id     BIGSERIAL    PRIMARY KEY,
    customer_id  BIGINT       NOT NULL,
    employee_id  BIGINT,
    branch_id    BIGINT       NOT NULL,
    warehouse_id BIGINT       NOT NULL,
    order_date   DATE         NOT NULL DEFAULT CURRENT_DATE,
    status       order_status NOT NULL DEFAULT 'PLACED',

    CONSTRAINT fk_customer_order_customer
        FOREIGN KEY (customer_id) REFERENCES customer (customer_id),
    CONSTRAINT fk_customer_order_employee
        FOREIGN KEY (employee_id) REFERENCES employee (employee_id)
        ON DELETE SET NULL,
    CONSTRAINT fk_customer_order_branch
        FOREIGN KEY (branch_id) REFERENCES branch (department_id),
    CONSTRAINT fk_customer_order_warehouse
        FOREIGN KEY (warehouse_id) REFERENCES warehouse (department_id)
);


-- ---------------------------------------------------------------------------
-- order_item
--
-- The order lines. unit_price is copied from part.price when the order is placed and is
-- never read back from the catalogue afterwards, so repricing a part leaves the total of
-- an existing invoice untouched.
--
-- Lines belong to their order and are deleted with it. The part reference is not
-- cascading: a part that has ever been sold cannot be deleted out from under an invoice.
-- ---------------------------------------------------------------------------

CREATE TABLE order_item (
    order_id   BIGINT         NOT NULL,
    part_id    BIGINT         NOT NULL,
    quantity   INT            NOT NULL,
    unit_price NUMERIC(10, 2) NOT NULL,

    CONSTRAINT pk_order_item PRIMARY KEY (order_id, part_id),
    CONSTRAINT ck_order_item_quantity   CHECK (quantity > 0),
    CONSTRAINT ck_order_item_unit_price CHECK (unit_price >= 0),
    CONSTRAINT fk_order_item_order
        FOREIGN KEY (order_id) REFERENCES customer_order (order_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_order_item_part
        FOREIGN KEY (part_id) REFERENCES part (part_id)
);
