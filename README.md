# Car Parts Store

A REST API for a car parts retail business — customers, branches, warehouses, staff,
suppliers, a parts catalogue, stock, and orders.

**Work in progress.** The schema, domain model, REST API and JWT security are built and
verified; the invoice PDF, the automated test suite and CI are not — see
[Status](#status). The roadmap lives in [PLAN.md](PLAN.md).

---

## Stack

Java 21 · Spring Boot 3.3 · PostgreSQL 16 · Flyway · Spring Data JPA + `JdbcClient` ·
Spring Security with JWT · springdoc-openapi · Maven

---

## Status

| | |
|---|---|
| ✅ Schema — 12 tables, 6 views, 6 triggers, Flyway V1–V6 | done |
| ✅ Deterministic demo dataset | done |
| ✅ JPA domain model — 20 classes, 9 repositories | done |
| ✅ `OrderService.placeOrder` — transactional, concurrency-safe | done |
| ✅ REST API — 47 endpoints, DTOs, RFC 7807 error responses, OpenAPI | done |
| ✅ JWT security — login, roles, per-department manager rule | done |
| ⬜ Invoice PDF | next |
| ⬜ Test suite + CI | |
| ⬜ Full documentation | |

Every finished step was verified against a real PostgreSQL 16 instance, not a mock — the
API by driving it over HTTP, including a full business journey and all six acceptance
criteria in [PLAN.md](PLAN.md#acceptance).

**There is no automated test suite yet.** That verification was done with throwaway scripts
against a scratch database and deleted afterwards; rebuilding it as real tests is step 9.
Treat the ✅ rows as "proven by hand", not "covered by CI".

---

## The API

47 endpoints. Everything but `POST /api/auth/login` needs a bearer token, and the browsable
description at `/swagger-ui` has an **Authorize** button for pasting one in.

```
POST   /api/auth/login                       exchange credentials for a JWT
```

| | Endpoints | Who |
|---|---|---|
| **Parts** | search · read · fitments (list/add/correct/remove) · create · update · delete | read: staff · write: ADMIN |
| **Orders** | place · list · read · amend lines · fulfil · cancel | staff |
| **Stock** | a warehouse's stock · where a part is · receive · transfer · stock-take · stop carrying | staff |
| **Customers** | list/search · read · create · update · delete | read: staff · write: ADMIN |
| **Suppliers** | list/search · read · create · update · delete | read: staff · write: ADMIN |
| **Departments** | list · read · open · appoint manager · close | ADMIN, except the manager rule below |
| **Employees** | list · read · hire · update · transfer · remove | ADMIN throughout |
| **Reports** | revenue per customer · low stock · headcount · departments without a manager | staff |

Searching the catalogue answers the question the business is actually asked:

```
GET /api/parts?make=Toyota&model=Corolla&year=2017
```

### Logging in

```bash
curl -X POST http://localhost:8080/api/auth/login      -H 'Content-Type: application/json'      -d '{"username":"...","password":"..."}'
```

The token carries the account, the person behind it, their role, their department and whether
they manage it — read from `v_user_identity` in one query — so a request answers "who is this
and what may they do" without a lookup per call. Send it as `Authorization: Bearer <token>`.

**A token is a snapshot, not a session.** Claims are read once at login, so a promotion, a
demotion or a disabled account only takes effect at the next login. There is nothing to log
out of; to revoke access sooner, disable the account and wait out `JWT_EXPIRY_MINUTES`.

### Who may do what

Reads are open to any authenticated member of staff, because every account in this system
belongs to one. Administrative writes — the catalogue, customers, suppliers, departments —
need `ADMIN`. Operational writes do not: a salesperson has to be able to place an order and
warehouse staff to receive stock.

`/api/employees` needs `ADMIN` even to read, since those rows carry salaries and birthdates.

**There is no `MANAGER` role.** Managing is per-department, and `department.manager_id`
already records who manages what — a second copy on the login account would drift the moment
somebody is promoted. So a manager may appoint or vacate the manager of *their own*
department, and that is checked as `isManager` **and** a matching `departmentId`, not as a
role. The flag alone would let any manager edit any department.

### The order handler comes from the token

`POST /api/orders` has no employee field in its body. The handler is taken from whoever is
authenticated, so a salesperson cannot record an order as handled by a colleague — there is
nowhere in the request to say it. The database agrees independently:
`ct_order_employee_at_branch` refuses an order whose handler does not work at the branch that
took it, which is why warehouse staff cannot place branch orders.

An account with nobody on the payroll behind it — an administrator or an integration — still
places orders, and records no handler. Both columns are nullable for exactly that.

### Errors

Every failure is an RFC 7807 `ProblemDetail`, and database constraints are translated into
something a person can act on rather than surfacing as a constraint name.

| | |
|---|---|
| `400` | malformed, or something the domain refuses |
| `401` | no token, or one that could not be read |
| `403` | a valid token belonging to somebody who may not do that |
| `404` | something the request named does not exist |
| `409` | understood, but conflicts with the current state — an oversell carries a `shortages` array naming every part |

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

You also need a **signing secret** for the JWTs, at least 32 bytes:

```bash
openssl rand -base64 48
```

It has no default on purpose. An unset, too-short or unresolved `JWT_SECRET` stops the
application at start-up with a message naming the property — a fallback secret is a published
secret.

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

Then open **<http://localhost:8080/swagger-ui>**, log in through `POST /api/auth/login` with
one of the demo accounts, and paste the token into **Authorize**. The machine-readable
description is at `/v3/api-docs`.

---

## Layout

```
src/main/java/com/carparts/
  domain/          entities, embeddables, enums
  repository/      Spring Data interfaces + JdbcClient reporting
  service/         business rules and the exceptions they raise
  web/             controllers, RFC 7807 handler, request/response DTOs
  security/        JWT issue and parse, the filter chain, the manager rule
  support/         small shared helpers
  config/          OpenAPI description

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
