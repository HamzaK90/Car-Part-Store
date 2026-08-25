# Build Plan

REST API for a car parts retail business.

**Stack:** Java 21 · Spring Boot 3.3 · PostgreSQL 16 · Flyway · Spring Data JPA + `JdbcClient` · JWT

---

## Domain

- **Customers** place orders.
- **Departments** come in two kinds — **branches** (sales locations) and **warehouses** (storage,
  with a free-area figure). Each has a name, an address, and a manager.
- **Employees** belong to exactly one department. Sales staff work at branches, warehouse staff at
  warehouses.
- A **manager** is not a separate kind of person — it is the employee a department points at.
  A department is created before anyone is hired into it, so `manager_id` starts NULL and is
  filled once there is someone to promote; the database only insists that whoever is named
  works in that department. A manager who transfers away or leaves the company vacates the
  post rather than blocking the move — losing a manager is an ordinary event, having one who
  works elsewhere is not. "Every department ends up with a manager" is a service-layer rule,
  and `v_department_without_manager` is the alert an admin acts on.
- **Suppliers** supply **parts**. Each part has an SKU, price, weight, manufacturing place, and a
  set of cars it fits.
- **Orders** are placed by a customer at a branch, handled by a sales employee of that branch, and
  fulfilled from a warehouse. Line items record quantity and the price at the time of sale.

---

## Schema at a glance

| Table | Holds | Notes |
|---|---|---|
| `department` | shared identity for both kinds | `UNIQUE (department_id, type)` anchors the subtypes |
| `warehouse` / `branch` | subtypes | disjointness enforced by composite FK, no trigger |
| `employee` | staff | one `department_id`; the subtype determines the role |
| `customer` | buyers | `phone_number` unique |
| `supplier` | vendors | `name` unique |
| `part` | catalogue | `sku` unique; `reorder_level` drives the low-stock report |
| `car_fitment` | which cars a part fits | multi-valued, so its own table |
| `warehouse_stock` | inventory per warehouse | decremented inside the order transaction |
| `customer_order` | order header | `branch_id` where placed, `warehouse_id` where filled from |
| `order_item` | order lines | `unit_price` captured at sale time |
| `app_user` | login accounts | nullable `employee_id` names the person behind a login |

Derived values are views, never columns: `v_department_headcount`,
`v_department_without_manager`, `v_order_total`, `v_low_stock`, and `v_user_identity`
(in V5, since it reads `app_user`).

Manager is one of those derived values. There is no `MANAGER` role: `department.manager_id`
already records who manages what, so `v_user_identity.is_manager` computes it rather than
storing a second copy that could disagree.

---

## Steps

### 0 — Scaffold ✅
- [x] `git init`, `.gitignore`, `.env.example`
- [x] MIT `LICENSE`
- [x] `pom.xml` + Maven wrapper
- [x] Package skeleton `com.carparts.{config,domain,repository,service,web,security}`
- [x] `application.yml` — credentials from environment variables
- [x] `application-local.example.yml` — local dev secrets via the `local` profile.
      Spring Boot does not read `.env`, so a `.env` file alone configures nothing; the
      git-ignored `application-local.yml` is the mechanism instead. `.env.example`
      remains the reference for what CI and production must export.
- [ ] `README.md` — written in the documentation pass

**Gate:** ✅ `./mvnw -B -DskipTests compile` → BUILD SUCCESS.

### 1 — Diagrams
- [x] `docs/diagrams/erd.drawio` — entity relationship diagram
- [x] `docs/diagrams/uml-class.drawio` — domain model
- [x] `docs/diagrams/order-sequence.drawio` — order placement transaction
- [ ] Export each to `.svg` (draw.io: File → Export as → SVG)

**Gate:** ERD reviewed before any SQL is written.

### 2 — Schema (Flyway)
- [x] `V1__core_tables.sql` — enums, department + subtypes, employee, customer, supplier, part
- [x] `V2__orders_and_stock.sql` — customer_order, order_item, warehouse_stock, car_fitment
- [x] `V3__constraints_and_indexes.sql` — deferred manager FK, constraint triggers,
      index on every FK column
- [x] `V4__views.sql` — headcount, departments without a manager, order total, low stock
- [x] `V5__auth.sql` — app_user

Column CHECKs and UNIQUEs are declared inline on the tables in V1 and V2, not deferred to
V3, so no window exists in which a table accepts data it should reject. V3 carries only
what cannot be inline: the circular manager FK, the cross-table triggers, the indexes.

**Gate:** ✅ drop and re-migrate from empty — V1–V5 apply in order against PostgreSQL 16
with no errors, and 15 constraint assertions pass: both-subtype department, negative
salary, negative stock, warehouse staff on a branch order, a warehouse used as a sales
location, a branch holding stock, an outsider named as manager, a manager transferred out,
a plaintext password, a zero-quantity line, an inverted fitment year range. Repricing a
part left an existing order total unmoved.

Run with raw `psql` on a throwaway cluster, not through Flyway, so no `flyway_schema_history`
exists yet and **the files are still safe to edit in place.** The first `mvnw` run against a
real database is what checksums them; after that, every change becomes a new file.

### 3 — Seed data
- [x] `V6__seed_demo_data.sql` — deterministic demo dataset

V6 sits in `classpath:db/migration` with V1–V5, so it runs on every start-up. All six
migrations are committed; that is normal practice, and the schema is only reproducible if
they are.

Demo passwords are **not** in the repository. V6 takes two Flyway placeholders,
`seed_admin_password_hash` and `seed_staff_password_hash`, wired in `application.yml` to
`SEED_ADMIN_PASSWORD_HASH` and `SEED_STAFF_PASSWORD_HASH`. Neither has a default, so an
unset variable fails the migration on `ck_app_user_password_hashed` instead of creating an
account anyone could log into from a published digest. `.env.example` documents how to
generate one.

Consequences that come with running everywhere:

- A deployment that does not want demo rows must exclude V6 deliberately — narrow
  `spring.flyway.locations`, or do not ship the file. There is no profile guard.
- **Tests (step 9)** run the real migrations, so V6 runs too. `application-test.yml` must
  supply a fixed test-only digest, or `ck_app_user_password_hashed` rejects the empty
  placeholder and every test fails. A hardcoded hash is acceptable *there* and nowhere
  else: it guards an ephemeral database destroyed at the end of the run.
- **CI (step 10)** needs no stored secret — generate a throwaway digest at job start.
- Business rows stay deterministic; only the five hash values differ per environment,
  which is the security property rather than a defect.

### 4 — Domain + repositories
- [ ] Entities, `@Embeddable Address`, enums, `@Inheritance(JOINED)` on `Department`
- [ ] **Every enum field needs two annotations**, or `ddl-auto: validate` refuses to start:

      @Enumerated(EnumType.STRING)
      @JdbcTypeCode(SqlTypes.NAMED_ENUM)
      private OrderStatus status;

      The schema uses real PostgreSQL enum types, not VARCHAR. That was a deliberate
      choice for database-level typing, and the price is paid later: `ALTER TYPE … ADD
      VALUE` cannot be used in the transaction that adds it, so introducing a new status
      takes two migrations, and removing one means recreating the type. Miss the
      annotation on a single field and start-up fails — the failure is loud, not subtle.
- [ ] `JpaRepository` per aggregate
- [ ] `ReportingRepository` — hand-written SQL via `JdbcClient`

### 5 — Services
- [ ] `OrderService.placeOrder` as one `@Transactional` unit:
      validate → `SELECT … FOR UPDATE` stock → reject if short → capture `unit_price` →
      insert order + items → decrement stock

### 6 — REST layer
- [ ] Controllers returning DTO records
- [ ] `@ControllerAdvice` → RFC 7807 `ProblemDetail`
- [ ] Bean Validation on request bodies
- [ ] springdoc-openapi at `/swagger-ui`
- [ ] After `POST /api/employees`, if the department has no manager, the response says so
      and offers the new employee as a candidate — read from `v_department_without_manager`,
      whose `eligible_employees` distinguishes "nobody to promote" from "here are four".
      Accepting is a separate `PATCH /api/departments/{id}` with `managerId`.
- [ ] `GET /api/reports/departments-without-manager` — the standing vacancy alert an `ADMIN`
      works through. A manager who transfers or leaves vacates the post silently, so this is
      the only thing that surfaces it.

### 7 — Security
- [ ] `SecurityFilterChain`, stateless, `JwtAuthFilter`, BCrypt cost 12
- [ ] `@PreAuthorize` — writes and `/api/employees` require `ADMIN`
- [ ] Login reads `v_user_identity`: the JWT carries `userId`, `employeeId`, `role` and
      `isManager`, so a request knows exactly who made it
- [ ] A manager may edit their own department — `isManager` plus a `departmentId` match,
      not a role check, since managing is per-department rather than global
- [ ] `customer_order.employee_id` is filled from the session, not from the request body,
      so a salesperson cannot record an order as handled by a colleague

### 8 — Invoice PDF
- [ ] `GET /api/orders/{id}/invoice.pdf` — Thymeleaf → openhtmltopdf

### 9 — Tests
- [ ] Integration tests on embedded PostgreSQL running the real migrations
- [ ] Constraint tests — the database rejects negative salary, a both-subtype department,
      oversold stock, a warehouse employee on a branch order, a branch holding stock, a
      warehouse used as a sales location, an outsider named as manager, a plaintext
      password, a zero-quantity line, the same part twice on one order, and an inverted
      fitment year range. Port the 14 assertions already proven by hand.
- [ ] Behaviour tests — transferring or deleting a manager vacates the post and the
      department surfaces in `v_department_without_manager`; repricing a part leaves an
      existing order total unmoved
- [ ] Concurrency test — two orders for the last unit, exactly one succeeds
- [ ] `MockMvc` auth tests — 401 / 403 / 200
- [ ] JaCoCo report

### 10 — Documentation
- [ ] GitHub Actions: `mvnw verify` on push/PR
- [ ] README: overview, ERD, quickstart, endpoint table, Swagger screenshot, design notes
- [ ] `docs/normalization.md`

---

## API surface

```
POST   /api/auth/login
GET    /api/parts                    ?search=&supplierId=&page=
GET    /api/parts/{id}/fitments
POST   /api/orders
GET    /api/orders/{id}
GET    /api/orders/{id}/invoice.pdf
GET    /api/warehouses/{id}/stock
GET    /api/reports/revenue-by-customer
GET    /api/reports/low-stock
GET    /api/reports/department-headcount
GET    /api/reports/departments-without-manager
PATCH  /api/departments/{id}          set managerId
CRUD   /api/{employees,customers,suppliers,departments}
```

`revenue-by-customer` has no view behind it — it is hand-written SQL in
`ReportingRepository`. A `v_customer_balance` view was dropped: with no payments table it
could only ever report total ordered, and calling that a balance invites misreading.

## Acceptance

1. Login returns a JWT. No token → 401. Wrong role → 403.
2. Placing an order decrements warehouse stock.
3. Ordering more than available → 409 with stock unchanged.
4. An invoice total is unaffected by a later change to `part.price`.
5. Naming a warehouse employee as the handler of a branch order → rejected.
6. `INSERT INTO employee (salary) VALUES (-5)` → rejected by `CHECK`.
