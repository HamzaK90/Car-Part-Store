-- V3 — constraints and indexes
--
-- Everything that could not be declared inline on the tables in V1 and V2: the circular
-- manager FK, the two cross-table rules that need triggers, and an index behind every
-- foreign key that does not already have one.
--
-- Column-level CHECKs and UNIQUEs are not here; they live on the tables themselves so
-- that no window ever exists in which a table accepts data it should reject.

-- ---------------------------------------------------------------------------
-- department.manager_id — the circular foreign key
--
-- department.manager_id references employee, and employee.department_id references
-- department, so neither table can be populated first under an immediate constraint.
-- DEFERRABLE INITIALLY DEFERRED holds the check until COMMIT, which lets a department and
-- its manager be inserted in either order inside one transaction.
--
-- ON DELETE SET NULL: dismissing a manager leaves the department, headless, in place.
-- ---------------------------------------------------------------------------

ALTER TABLE department
    ADD CONSTRAINT fk_department_manager
    FOREIGN KEY (manager_id) REFERENCES employee (employee_id)
    ON DELETE SET NULL
    DEFERRABLE INITIALLY DEFERRED;


-- ---------------------------------------------------------------------------
-- Rule 1 — an order's handler must work at the branch that took the order
--
-- Neither a CHECK nor a foreign key can express this: it reads employee.department_id
-- while validating a row of customer_order. A constraint trigger is the narrowest tool
-- that can.
--
-- INITIALLY IMMEDIATE, because unlike the manager FK there is no ordering problem —
-- the employee and the branch both exist before an order can reference them.
-- ---------------------------------------------------------------------------

CREATE FUNCTION fn_order_employee_at_branch() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.employee_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM employee e
        WHERE e.employee_id   = NEW.employee_id
          AND e.department_id = NEW.branch_id
    ) THEN
        RAISE EXCEPTION
            'employee % does not work at branch % (order %)',
            NEW.employee_id, NEW.branch_id, NEW.order_id
            USING ERRCODE   = 'integrity_constraint_violation',
                  CONSTRAINT = 'ct_order_employee_at_branch';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ct_order_employee_at_branch
    AFTER INSERT OR UPDATE OF employee_id, branch_id ON customer_order
    DEFERRABLE INITIALLY IMMEDIATE
    FOR EACH ROW EXECUTE FUNCTION fn_order_employee_at_branch();

-- Deliberately not re-validated when an employee transfers departments. An order records
-- who handled it at the time; a later transfer does not make that history false, and
-- re-checking here would block legitimate transfers.


-- ---------------------------------------------------------------------------
-- Rule 2 — a department's manager must be one of its own employees
--
-- Handled from both sides, because both sides can break it, but they are handled
-- differently: naming an outsider as manager is REFUSED, while a sitting manager leaving
-- is ALLOWED and simply vacates the post.
--
-- That asymmetry is the point. Losing a manager is a normal event — people transfer, people
-- leave — and refusing the transfer would make the schema fight ordinary staff movement.
-- What must never happen is a department whose manager works somewhere else. So the
-- department is left headless, which is a legitimate state, and v_department_without_manager
-- reports it so an admin can fill the post.
--
-- The department-side trigger is INITIALLY DEFERRED to match fk_department_manager —
-- the manager's employee row may not exist yet when the department row is written.
-- ---------------------------------------------------------------------------

CREATE FUNCTION fn_department_manager_membership() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.manager_id IS NOT NULL AND NOT EXISTS (
        SELECT 1
        FROM employee e
        WHERE e.employee_id   = NEW.manager_id
          AND e.department_id = NEW.department_id
    ) THEN
        RAISE EXCEPTION
            'employee % cannot manage department % without working in it',
            NEW.manager_id, NEW.department_id
            USING ERRCODE   = 'integrity_constraint_violation',
                  CONSTRAINT = 'ct_department_manager_membership';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ct_department_manager_membership
    AFTER INSERT OR UPDATE OF manager_id ON department
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION fn_department_manager_membership();

-- Transferring a manager to another department vacates the post they leave behind. The
-- alternative — refusing the transfer until somebody reassigns the department first — puts
-- a schema constraint in the way of an ordinary HR action, and the department would end up
-- headless anyway once the transfer finally went through.
--
-- This is an ordinary trigger, not a CONSTRAINT TRIGGER: it changes data rather than
-- rejecting it, and constraint triggers are for validation. It mirrors what
-- fk_department_manager already does on ON DELETE SET NULL, so a manager who leaves the
-- company and a manager who moves departments both leave the same vacancy behind.
--
-- No recursion: the UPDATE below fires ct_department_manager_membership, which returns
-- immediately because the new manager_id is NULL.

CREATE FUNCTION fn_employee_transfer_vacates_post() RETURNS TRIGGER AS $$
BEGIN
    UPDATE department
       SET manager_id = NULL
     WHERE department_id = OLD.department_id
       AND manager_id    = NEW.employee_id;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tg_employee_transfer_vacates_post
    AFTER UPDATE OF department_id ON employee
    FOR EACH ROW
    WHEN (OLD.department_id IS DISTINCT FROM NEW.department_id)
    EXECUTE FUNCTION fn_employee_transfer_vacates_post();


-- ---------------------------------------------------------------------------
-- Indexes behind the foreign keys
--
-- Postgres indexes primary and unique keys automatically but never foreign keys, so an
-- unindexed FK column means a sequential scan on the child table for every parent delete
-- and for every join in that direction.
--
-- Only the columns not already covered are listed here. A composite PK's leading column
-- is already usable on its own, so car_fitment.part_id, warehouse_stock.warehouse_id and
-- order_item.order_id need nothing. warehouse and branch reference department through
-- (department_id, type), whose leading column is their own primary key. The trailing
-- columns of a composite PK are not covered, so those do get an index.
-- ---------------------------------------------------------------------------

CREATE INDEX idx_department_manager        ON department      (manager_id);
CREATE INDEX idx_employee_department       ON employee        (department_id);
CREATE INDEX idx_part_supplier             ON part            (supplier_id);
CREATE INDEX idx_warehouse_stock_part      ON warehouse_stock (part_id);
CREATE INDEX idx_customer_order_customer   ON customer_order  (customer_id);
CREATE INDEX idx_customer_order_employee   ON customer_order  (employee_id);
CREATE INDEX idx_customer_order_branch     ON customer_order  (branch_id);
CREATE INDEX idx_customer_order_warehouse  ON customer_order  (warehouse_id);
CREATE INDEX idx_order_item_part           ON order_item      (part_id);
