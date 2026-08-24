-- V4 — views
--
-- Every derived value in this schema is a view, never a stored column. A headcount column
-- on department, or a total column on customer_order, would be a second copy of a fact the
-- base tables already hold, and the two copies would eventually disagree. Computing them
-- on read costs a join and can never be stale.

-- ---------------------------------------------------------------------------
-- v_department_headcount
--
-- LEFT JOIN, not JOIN: a department that has just been created and has nobody in it yet
-- must still appear, with a headcount of 0. An inner join would hide exactly the rows a
-- staffing report most needs to show.
--
-- GROUP BY the primary key alone. Postgres knows the other department columns are
-- functionally dependent on it, so listing them would add noise without adding meaning.
--
-- manager_id is carried through because v_department_without_manager filters on it. That
-- is the whole reason this view exposes it — see the note there.
-- ---------------------------------------------------------------------------

CREATE VIEW v_department_headcount AS
SELECT d.department_id,
       d.name,
       d.type,
       d.manager_id,
       COUNT(e.employee_id) AS headcount
FROM department d
LEFT JOIN employee e ON e.department_id = d.department_id
GROUP BY d.department_id;


-- ---------------------------------------------------------------------------
-- v_department_without_manager
--
-- The departments currently running headless. "Every department has a manager" cannot be
-- a NOT NULL column, because a department is created before anyone is hired into it — so
-- the rule lives in the service layer, and this view is what makes a breach visible
-- instead of silent.
--
-- Built ON TOP OF v_department_headcount rather than repeating its join. The count of
-- people who could be promoted is the department's headcount — the same number, not a
-- second one that happens to agree. Re-deriving it here would create two places to change
-- and two answers to reconcile.
--
-- eligible_employees is that headcount under the name the API needs: after an employee is
-- added to a department with no manager, it tells the caller whether there is anybody to
-- offer for promotion, or nobody at all.
-- ---------------------------------------------------------------------------

CREATE VIEW v_department_without_manager AS
SELECT department_id,
       name,
       type,
       headcount AS eligible_employees
FROM v_department_headcount
WHERE manager_id IS NULL;


-- ---------------------------------------------------------------------------
-- v_order_total
--
-- The money on an order, summed from the line prices captured at the time of sale. This
-- is why repricing a part cannot move the total of an order that already exists.
--
-- COALESCE keeps the total at 0 rather than NULL for an order with no lines. An order
-- should always have at least one line, but a view that reports NULL for a malformed row
-- is harder to reason about than one that reports zero.
-- ---------------------------------------------------------------------------

CREATE VIEW v_order_total AS
SELECT o.order_id,
       o.customer_id,
       o.branch_id,
       o.warehouse_id,
       o.order_date,
       o.status,
       COUNT(oi.part_id)                                    AS line_count,
       COALESCE(SUM(oi.quantity), 0)                        AS unit_count,
       COALESCE(SUM(oi.quantity * oi.unit_price), 0.00)     AS total_amount
FROM customer_order o
LEFT JOIN order_item oi ON oi.order_id = o.order_id
GROUP BY o.order_id;


-- ---------------------------------------------------------------------------
-- v_low_stock
--
-- Inventory that needs reordering, joined out to the names and the supplier a human needs
-- in order to act on it.
--
-- The threshold is per part, read from part.reorder_level, not a constant: a brake disc
-- and a wiper blade run low at different counts. shortfall is how many units short of the
-- level the warehouse is, which is the number worth ordering.
--
-- Parts left at the default reorder_level of 0 never appear here, since quantity is
-- constrained to be >= 0 and so can never fall below it.
-- ---------------------------------------------------------------------------

CREATE VIEW v_low_stock AS
SELECT ws.warehouse_id,
       d.name      AS warehouse_name,
       ws.part_id,
       p.sku,
       p.name      AS part_name,
       p.price,
       s.name      AS supplier_name,
       ws.quantity,
       p.reorder_level,
       p.reorder_level - ws.quantity AS shortfall
FROM warehouse_stock ws
JOIN part       p ON p.part_id       = ws.part_id
JOIN supplier   s ON s.supplier_id   = p.supplier_id
JOIN department d ON d.department_id = ws.warehouse_id
WHERE ws.quantity < p.reorder_level;
