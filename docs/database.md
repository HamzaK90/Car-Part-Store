# The database

PostgreSQL 16, built by Flyway migrations `V1`–`V6`. 12 tables, 6 views, 6 triggers,
39 named constraints, 9 indexes.

The schema is the centre of this project. Most of the rules live here rather than in Java,
because a rule in the application holds only for code that goes through the application, and
a rule in the database holds for everything — including the next service, a migration script,
and somebody at a `psql` prompt.

---

## The migrations

| File | What it adds |
|---|---|
| `V1__core_tables.sql` | Enum types, `department` and its two subtypes, staff, customers, suppliers, the parts catalogue |
| `V2__orders_and_stock.sql` | Orders, order lines, warehouse stock, car fitments |
| `V3__constraints_and_indexes.sql` | The cross-table rules, as triggers; foreign-key indexes |
| `V4__views.sql` | Every derived value |
| `V5__auth.sql` | Login accounts and the identity view |
| `V6__seed_demo_data.sql` | A deterministic demo dataset |

Migrations are never edited after they run anywhere. Flyway records a checksum, and changing
an applied file makes the next start fail.

---

## Tables

```
department ─┬─ warehouse            a department is exactly one of the two
            └─ branch

employee ──── department            who works where
department ── employee              manager_id, nullable

supplier ──── part ──── car_fitment       what a part fits
part ──────── warehouse_stock ─ warehouse what is on which shelf

customer ─┬── customer_order ─┬─ branch     who sold it
          │                   ├─ warehouse  where it shipped from
          │                   └─ employee   who handled it, nullable
          └── order_item ───── part         the lines

app_user ──── employee              a login, optionally tied to a person
```

---

## Design decisions

### Branches and warehouses are disjoint subtypes, without a trigger

A department must be exactly one of the two, never both and never neither-but-referenced-as-one.
The usual answer is a trigger. This schema uses a plain foreign key instead:

```sql
-- department
CONSTRAINT uq_department_id_type UNIQUE (department_id, type)

-- warehouse
CONSTRAINT ck_warehouse_type CHECK (type = 'WAREHOUSE')
FOREIGN KEY (department_id, type) REFERENCES department (department_id, type)
```

Each subtype pins its own `type` with a `CHECK` and then references the composite key. A
warehouse row can only point at a department whose type is already `WAREHOUSE`. The database
enforces it with no procedural code, and it cannot be bypassed.

### Derived values are views, never columns

Headcounts, order totals, customer revenue and low stock are computed when read:

| View | Answers |
|---|---|
| `v_order_total` | what an order came to |
| `v_customer_revenue` | what a customer has spent |
| `v_department_headcount` | how many people work where |
| `v_department_without_manager` | which departments have no manager |
| `v_low_stock` | which shelves are below their reorder level |
| `v_user_identity` | who a login belongs to, and what they manage |

A stored total is a total that can disagree with its lines. There is no stored copy here, so
there is nothing to fall out of step and nothing to recalculate after a correction.

### An invoice total cannot drift

`order_item.unit_price` records the price at the moment of sale. Nothing reads the price back
from the catalogue, so repricing a part leaves every settled order exactly as it was. This is
the single most common way a retail system produces a wrong invoice, and the fix is one
column.

### Overselling is impossible, including under concurrency

Placing an order takes `SELECT ... FOR UPDATE` on the stock rows and holds them until commit,
so two simultaneous orders for the last unit end with one order and stock at zero rather than
two orders and stock at minus one. The rows are locked in a fixed order (by part id), so two
orders touching the same parts queue instead of deadlocking.

`ck_warehouse_stock_quantity CHECK (quantity >= 0)` is the backstop for the case where the
service logic is wrong.

### There is no MANAGER role

Managing is per-department, and `department.manager_id` already records who manages what. A
second copy on the login account would drift the moment somebody is promoted. `v_user_identity`
derives the fact at login instead.

---

## Rules a CHECK cannot express

A `CHECK` sees one row. Five rules here span rows or tables, so they are constraint
triggers — which still means the database enforces them, not the application.

| Trigger | Refuses |
|---|---|
| `ct_order_employee_at_branch` | an order whose handler does not work at the branch that took it |
| `ct_department_manager_membership` | a manager who does not work in the department they manage |
| `ct_order_status_transition` | any status change other than `PLACED` to `FULFILLED` or `CANCELLED` |
| `ct_order_has_lines` | an order with no lines |
| `ct_order_keeps_a_line` | removing the last line from an order |

`tg_employee_transfer_vacates_post` is not a refusal but a repair: transferring somebody
empties any manager's post they held. Losing a manager is an ordinary event; a manager who
works somewhere else is a broken state, so the transfer is allowed and the post is vacated.

### Two of these are deferred

`ct_department_manager_membership` and `fk_department_manager` are
`DEFERRABLE INITIALLY DEFERRED`. A department and its manager reference each other, so
whichever is inserted first would violate the other. Deferring the check to `COMMIT` lets both
land in one transaction.

The consequence matters when testing: the statement succeeds and nothing is refused until
commit, so a test that rolls back never sees the refusal at all. `ConstraintTest` uses
`SET CONSTRAINTS ALL IMMEDIATE` to force the check where it needs to observe one.

---

## Every constraint is named

39 of them, all named deliberately. PostgreSQL reports the name it was given, so a violation
can be turned into a sentence a person can act on instead of surfacing as a stack trace:

```
uq_part_sku          ->  "that SKU is already in the catalogue"
ck_employee_salary   ->  "salary must be greater than zero"
fk_part_supplier     ->  "parts in the catalogue still come from this supplier"
```

`ApiExceptionHandler` holds that mapping, and `ConstraintMappingTest` checks every name in it
against the schema Flyway just built. Renaming a constraint in a migration would otherwise
orphan its message silently: nothing fails, the API just stops explaining that failure.

### Foreign keys fire in two directions

A foreign key can be violated by inserting a child that names no parent, or by deleting a
parent that still has children. Only the delete direction reaches the error handler here,
because services check the parent exists first and raise a 404. So `fk_part_supplier` is
worded for the delete: "parts in the catalogue still come from this supplier", not "that
supplier does not exist".

---

## Indexes

Nine, all on foreign keys. PostgreSQL indexes primary keys and unique constraints
automatically but not the referencing side, which is where joins and cascade checks actually
look. Everything else is left to be added when a real query needs it, rather than guessed at
now.

---

## The demo dataset

`V6` seeds a deterministic dataset with explicit ids, so tests can rely on it — customer 1,
branch 1, warehouse 3. It includes departments both with and without managers, an order taken
without a named salesperson, and stock deliberately below its reorder level, because each is a
case something has to handle.

Two things to know:

- **No passwords are in this repository.** `V6` takes its BCrypt digests from Flyway
  placeholders with no defaults, so an unset variable fails the migration rather than seeding
  an account whose password could be read here.
- **`V6` seeds ADMIN accounts and fake data into whatever database it is pointed at.** A
  deployment that does not want demo data must exclude it deliberately.

---

## Related

- [api.md](api.md) — the endpoints built on this schema
- [testing.md](testing.md) — how these rules are verified
- [architecture.md](architecture.md) — how the application sits on top of this
- [diagrams/](diagrams/) — the ERD, as an editable draw.io file
