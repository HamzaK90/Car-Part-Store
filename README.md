# Car Parts Store

A REST API for a car parts retail business — customers, branches, warehouses, staff,
suppliers, a parts catalogue, stock, and orders.

**Work in progress.** The database schema, domain model and order-placement logic are
built and verified. The HTTP layer is not written yet, so there is nothing to call over the
network — see [Status](#status). The roadmap lives in [PLAN.md](PLAN.md).

---

## Stack

Java 21 · Spring Boot 3.3 · PostgreSQL 16 · Flyway · Spring Data JPA + `JdbcClient` ·
JWT (planned) · Maven

---

## Status

| | |
|---|---|
| ✅ Schema — 12 tables, 6 views, 6 triggers, Flyway V1–V6 | done |
| ✅ Deterministic demo dataset | done |
| ✅ JPA domain model — 20 classes, 9 repositories | done |
| ✅ `OrderService.placeOrder` — transactional, concurrency-safe | done |
| ⬜ REST controllers, DTOs, RFC 7807 error responses | next |
| ⬜ JWT security | |
| ⬜ Invoice PDF | |
| ⬜ Test suite + CI | |
| ⬜ Full documentation | |

Every finished step was verified against a real PostgreSQL 16 instance, not a mock.

---

## Design notes

The interesting part of this project is the schema. A few decisions worth calling out:

**Branches and warehouses are disjoint subtypes, enforced without a trigger.**
`department` declares `UNIQUE (department_id, type)`; `warehouse` and `branch` each pin
their own `type` with a `CHECK` and reference that composite key. A department is therefore
exactly one of the two, guaranteed by a plain foreign key.

**An invoice total cannot drift.** `order_item.unit_price` captures the price at the moment
of sale and is never read back from the catalogue, so repricing a part leaves settled
orders untouched.

**Overselling is impossible, including under concurrency.** Placing an order locks the
stock rows with `SELECT … FOR UPDATE` and holds them until commit. Two simultaneous orders
for the last unit end with exactly one order and stock at zero — verified with a
concurrency test, not assumed. A `CHECK (quantity >= 0)` is the backstop if the service
logic is ever wrong.

**Derived values are views, never columns.** Headcounts, order totals, customer revenue and
low stock are computed on read. There is no stored copy to fall out of step.

**There is no `MANAGER` role.** `department.manager_id` already records who manages what,
so a second copy on the login account would drift the moment somebody is promoted. It is
derived at login instead.

**Business rules the database enforces itself**, because a `CHECK` cannot see other rows:

| Rule |
|---|
| an order's handler works at the branch that took it |
| a manager belongs to the department they manage |
| only a `PLACED` order may become `FULFILLED` or `CANCELLED` |
| an order holds at least one line |

---

## Diagrams

In [`docs/diagrams/`](docs/diagrams/), as editable draw.io files:

- `erd.drawio` — entity relationship diagram, with a legend explaining the design choices
- `uml-class.drawio` — the JPA domain model
- `order-sequence.drawio` — the order-placement transaction

---

## Running it

You need **JDK 21** and **PostgreSQL 16**. Maven comes with the wrapper.

**1. Create the database and a role**

```sql
CREATE DATABASE carparts;
CREATE USER carparts_app WITH PASSWORD 'choose-something';
GRANT ALL PRIVILEGES ON DATABASE carparts TO carparts_app;
```

**2. Supply local configuration**

Copy the template and fill it in — the real file is git-ignored:

```bash
cp src/main/resources/application-local.example.yml src/main/resources/application-local.yml
```

The demo accounts need BCrypt digests at cost 12. Generate one per password:

```bash
python -c "import bcrypt;print(bcrypt.hashpw(b'YOUR-PASSWORD', bcrypt.gensalt(12, prefix=b'2a')).decode())"
```

Deployments set the environment variables named in [`.env.example`](.env.example) instead.
Spring Boot does not read `.env` files itself — the `local` profile is the mechanism for
development.

**3. Start it**

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Flyway applies `V1`–`V6` on start-up, creating the schema and the demo dataset. Hibernate
then validates every entity mapping against it, so a mismatch fails the start rather than
surfacing later.

**There are no HTTP endpoints yet.** A successful start means the schema is provisioned and
the mappings are sound. Endpoints arrive with the REST layer.

---

## Layout

```
src/main/java/com/carparts/
  domain/          entities, embeddables, enums
  repository/      Spring Data interfaces + JdbcClient reporting
  service/         OrderService and its exceptions

src/main/resources/db/migration/
  V1__core_tables.sql              enums, department + subtypes, staff, catalogue
  V2__orders_and_stock.sql         orders, lines, stock, fitments
  V3__constraints_and_indexes.sql  deferred FK, cross-table triggers, FK indexes
  V4__views.sql                    the derived values
  V5__auth.sql                     login accounts
  V6__seed_demo_data.sql           demo dataset
```

---

## Notes

Demo credentials are **not** in this repository. `V6` takes its password hashes from Flyway
placeholders resolved from the environment, and neither has a default — an unset variable
fails the migration rather than seeding an account whose password could be read here.

`V6` also seeds ADMIN accounts and fake business data into whatever database it is pointed
at. A deployment that does not want demo data must exclude it deliberately.

---

## Licence

[MIT](LICENSE)
