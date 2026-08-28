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
`v_department_without_manager`, `v_order_total`, `v_customer_revenue`, `v_low_stock`, and
`v_user_identity` (in V5, since it reads `app_user`).

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
- [x] `README.md` — an interim one covering what actually works. The full version, with the
      endpoint table and Swagger screenshot, waits for step 10 and an API that exists

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
- [x] `V3__constraints_and_indexes.sql` — deferred manager FK, four cross-table rules as
      constraint triggers, index on every FK column
- [x] `V4__views.sql` — headcount, departments without a manager, order total, low stock
- [x] `V5__auth.sql` — app_user

Column CHECKs and UNIQUEs are declared inline on the tables in V1 and V2, not deferred to
V3, so no window exists in which a table accepts data it should reject. V3 carries only
what cannot be inline: the circular manager FK, the cross-table triggers, the indexes.

The four rules in V3, each beyond what a CHECK can see:

| Rule | Enforces | Timing |
|---|---|---|
| `ct_order_employee_at_branch` | the handler works at the branch that took the order | immediate |
| `ct_department_manager_membership` | a manager belongs to the department they manage | deferred |
| `ct_order_status_transition` | only a PLACED order may become FULFILLED or CANCELLED | immediate |
| `ct_order_has_lines` | an order holds at least one line, the UML's `1..*` | deferred |

`tg_employee_transfer_vacates_post` is an ordinary trigger rather than a constraint one: it
changes data instead of rejecting it, clearing `manager_id` when a manager transfers away.

`ct_order_has_lines` must be deferred, and that is inherent rather than incidental — a
header necessarily exists before the lines referencing it, so the question can only be
asked once the transaction has finished speaking. The consequence is that **V6 must run
inside a transaction**, which Flyway always provides; replaying it by hand needs
`psql --single-transaction`.

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

### 4 — Domain + repositories ✅
- [x] Entities, `@Embeddable Address`, enums, `@Inheritance(JOINED)` on `Department`
- [x] **Every enum field needs two annotations**, or `ddl-auto: validate` refuses to start:

      @Enumerated(EnumType.STRING)
      @JdbcTypeCode(SqlTypes.NAMED_ENUM)
      private OrderStatus status;

      The schema uses real PostgreSQL enum types, not VARCHAR. That was a deliberate
      choice for database-level typing, and the price is paid later: `ALTER TYPE … ADD
      VALUE` cannot be used in the transaction that adds it, so introducing a new status
      takes two migrations, and removing one means recreating the type. Miss the
      annotation on a single field and start-up fails — the failure is loud, not subtle.
- [x] `JpaRepository` per aggregate — 8 interfaces
- [x] `ReportingRepository` — `JdbcClient` over the views, rows mapped onto records

**Gate:** ✅ the application starts against a real PostgreSQL 16 with `ddl-auto: validate`,
so every mapping was checked against the actual schema and every Spring Data query parsed
at bootstrap.

Decisions worth keeping:

- **No discriminator column on the `Department` hierarchy.** The obvious candidate,
  `department.type`, is a native enum and `@DiscriminatorColumn` accepts only string, char
  or integer. `JOINED` already tells subtypes apart by which child table holds the row —
  the same fact the composite foreign key enforces — so the mapping omits it entirely.
- **Defaults are set in Java as well as SQL** for `hired_on`, `order_date`, `status`,
  `created_at`, `reorder_level`. Hibernate sends every mapped column on insert, so a null
  field writes NULL and trips NOT NULL rather than falling back to the column DEFAULT.
- **`lockForUpdate` orders by part id.** Two transactions taking the same rows in different
  orders deadlock; a fixed order means one simply waits. Needed for step 5.
- **`Warehouse` has no `stock` collection.** Mapping one would let `getStock()` pull every
  part in the warehouse with no filter or paging, and a serializer could trigger it by
  accident. `WarehouseStockRepository.findByWarehouseId()` and `v_low_stock` serve it
  properly. Bounded collections — `Part.fitments`, `Department.employees`,
  `CustomerOrder.items` — are kept.
- **Domain methods carry the rules a query cannot**: `fulfil()` / `cancel()` guard the
  status sequence, `addEmployee()` sets both ends of the association so `headcount()`
  cannot go stale, and `addLine()` merges a repeated part rather than failing at flush.
- Derived values exist in Java *and* SQL on purpose. Java acts on objects already loaded —
  an invoice already holds its lines — while views answer across rows nothing has loaded.
  The cost is that changing a formula means changing both.

A bug this gate caught: `PartRepository.search()` passed a null search term into
`LOWER(?)`, and PostgreSQL types an untyped null parameter as `bytea`, so the very first
unfiltered `GET /api/parts` would have failed with *function lower(bytea) does not exist*.
An absent term now becomes `%`.

### 5 — Services ✅
- [x] `OrderService.placeOrder` as one `@Transactional` unit:
      validate → `SELECT … FOR UPDATE` stock → reject if short → capture `unit_price` →
      insert order + items → decrement stock

**Gate:** ✅ ten assertions against a real PostgreSQL 16, including two concurrent orders
for the last unit where exactly one succeeds.

- **The handler is a method parameter, not a field of `PlaceOrderCommand`.** A request body
  therefore cannot supply it — step 7 passes it from the session. Structural rather than a
  rule somebody has to remember.
- **Duplicate lines are summed before the stock check.** Two lines of three would otherwise
  be checked as three and then three again, selling six units of a part that had four.
- **Every shortage is reported, not the first.** One response tells the caller everything
  they must fix instead of three round trips.
- **A part with no row in that warehouse reads as zero available**, not "not found" — from
  the caller's side there is no difference between run out and never stocked. A part id
  that matches nothing at all is still a 404, checked before the stock comparison.
- `findBranch` / `findWarehouse` type the query to the subtype, so a warehouse id handed in
  as the branch simply finds nothing. Same guarantee as `fk_customer_order_branch`, one
  layer earlier where the error can still be readable.

Exceptions for step 6 to map: `NotFoundException` → 404, `InvalidOrderException` → 400,
`InsufficientStockException` → 409 carrying every shortage.

### 6 — REST layer

Delivered one API category at a time, each reviewed and measured before it was committed —
43 endpoints across eight PRs. Splitting it this way is what made the defects findable: almost
every one was invisible on demo data and only appeared when that category was looked at on its
own with realistic cardinality.

| Category | Endpoints | State |
|---|---|---|
| foundation — error handling, DTOs, OpenAPI, interim security | — | ✅ |
| orders | 5 | ✅ |
| parts | 7 | ✅ |
| warehouses + stock | 6 | ✅ |
| departments | 5 | ✅ |
| employees | 6 | ✅ |
| customers + suppliers | 10 | ✅ |
| reports | 4 | ✅ |
| `docs/api.md`, `docs/api-roadmap.md` | | held for the final PR, when they describe endpoints that exist |

**Carried out of step 6, deliberately.** Each item spans categories already merged, so fixing it
inside any one of them would have made that PR a cross-cutting change nobody asked for. One pass
now that every category has shipped:

| | Where | Why it waited |
|---|---|---|
| `MAX_PAGE_SIZE` and the page/size clamp, written out identically | 7 controllers | a cap only protects the server if every copy has it, and the one endpoint that shipped without one returned every stock row a warehouse held |
| The `LIKE`-pattern widening that avoids the `lower(bytea)` trap | 3 repositories | three copies of one subtle workaround is three chances to drift |
| The partial-`Address` merge | 2 services | `Address` is one value; the four-way null dance belongs on it |
| A unique tiebreaker on paged sorts | `/api/parts`, `/api/customers`, `/api/employees` | same defect as `low-stock`; `name`, `fullName` and `price` are not unique. `/api/suppliers` and `/api/departments` are safe on unique constraints, and the stock listing is filtered to one warehouse |

- [x] Controllers returning DTO records
- [x] `@ControllerAdvice` → RFC 7807 `ProblemDetail`
- [x] Bean Validation on request bodies
- [x] springdoc-openapi at `/swagger-ui`
- [x] After `POST /api/employees`, if the department has no manager, the response says so
      and offers the new employee as a candidate — read from `v_department_without_manager`,
      whose `eligible_employees` distinguishes "nobody to promote" from "here are four".
      Accepting is a separate `PATCH /api/departments/{id}` with `managerId`.
      Read by department id rather than by listing every vacancy and filtering, which would
      fetch the whole company's headless departments to answer a question about one.
- [x] `GET /api/reports/departments-without-manager` — the standing vacancy alert an `ADMIN`
      works through. A manager who transfers or leaves vacates the post silently, so this is
      the only thing that surfaces it.

Decisions worth keeping:

- **No `@Transactional` on a controller.** `open-in-view` is disabled, and putting a transaction
  on a controller reinstates open-session-in-view by hand: it hides an N+1 behind serialization,
  holds a connection for the duration of the network write, and turns a failure into a broken
  body behind an already-sent `200`. The controller is handed complete data instead — a fetch
  join, or flat rows from `JdbcClient`.
- **Paging is a claim about cardinality, not a habit.** Two of the four reports are pages and
  two are plain lists, deliberately. Headcount and vacancies have a row per *department* — a
  company's physical locations, counted in dozens. Revenue has a row per *customer* and low
  stock a row per warehouse-and-part; both grow with trade and never shrink, so both are paged.
  `v_customer_revenue` reads `FROM customer LEFT JOIN`, so it would have returned every customer
  on the books in one array — the same defect as the original stock listing.
- **An offset-paged query must close its `ORDER BY` on a unique column.** `LIMIT/OFFSET` across
  a tie has no defined order between pages, so a tied row can appear twice and another never at
  all — while the total still reports both. Measured on `low-stock`, whose rows are a warehouse
  *and* a part: the same SKU appears once per warehouse holding it short, so
  `ORDER BY shortfall DESC, sku` ties. Ten rows paged two at a time returned nine distinct.
  `OrderSort` had appended `order_id` to every ordering from the start for this reason.
- **Every listing is paged and capped.** A bare `List` return type is the tell. Each category has
  had exactly one N+1 in its listing and each was invisible on demo data, so a list query is
  counted at the database against realistic cardinality before the category is called done.
- **Entities are never serialised.** DTO records only, or a lazy association resolves while the
  response is being written — which without a session is a `LazyInitializationException`, and
  with one is dozens of queries.
- **PATCH, not PUT.** A full-object update means two people editing different fields each send a
  complete object built from a stale read, and the second silently overwrites the first.
- **A service exists to own the transaction, not to forward calls.** Customers and suppliers are
  plain CRUD with no business rules, which argues against a service layer — but with
  `open-in-view` disabled and no `@Transactional` on controllers, a read-modify-write driven
  from a controller loads a *detached* entity and the mutation is silently discarded, with no
  error and no row changed. The boundary has to live somewhere, and owning it is real behaviour
  rather than indirection.
- **A change with a side effect gets its own endpoint.** Moving an employee vacates any manager
  post they held, so `departmentId` is deliberately absent from `PATCH /api/employees/{id}` and
  transferring is `PATCH /api/employees/{id}/department/{departmentId}`. Allowing it as a field
  would let a transfer happen as a side effect of correcting a name, bypassing
  `Employee.transferTo` and the vacancy it is responsible for. For the same reason an address is
  patched as one embedded value: a request naming only the city must not erase the street.
- **A foreign key fires in two directions and only one reaches the error handler.** Naming a
  parent that does not exist is caught in Java first — every service resolves its parent with
  `findById().orElseThrow(NotFoundException)` — so what reaches `ApiExceptionHandler` is always
  the delete direction. `fk_employee_department` had been worded for the other one, and closing a
  department that still had staff returned a `409` reading *"that department does not exist"*.
  Each constraint message describes the direction it can actually be seen in.

### 7 — Security
- [x] `SecurityFilterChain`, stateless, `JwtAuthFilter`, BCrypt cost 12
- [ ] `@PreAuthorize` — writes and `/api/employees` require `ADMIN`
- [x] Login reads `v_user_identity`: the JWT carries `userId`, `employeeId`, `role` and
      `isManager`, so a request knows exactly who made it. Every login failure — unknown
      username, wrong password, disabled account — is the same 401 with the same wording, or
      the endpoint becomes a way of discovering which accounts exist. An unknown username is
      still verified against a dummy digest so the two paths take the same time.
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
- [ ] Concurrency test — two orders for the last unit, exactly one succeeds. Proven by hand
      at step 5; port it. It must **not** be `@Transactional`: a test-level transaction makes
      both calls share one, so the row lock is never contended and the test passes while
      proving nothing.
- [ ] Port the step-5 order assertions: oversell leaves stock untouched, every shortage is
      reported, duplicate lines are summed, warehouse staff cannot handle a branch order, a
      warehouse id given as the branch is a 404
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

`revenue-by-customer` reads `v_customer_revenue`. It reports revenue the shop earned from a
customer, not money they hold or owe — the schema has no payments table and cannot know
what anyone owes. It was briefly named `v_customer_balance`, which invited exactly that
misreading; "balance" becomes honest only once a payment table exists and the figure
becomes ordered minus paid.

## Acceptance

1. Login returns a JWT. No token → 401. Wrong role → 403.
2. Placing an order decrements warehouse stock.
3. Ordering more than available → 409 with stock unchanged.
4. An invoice total is unaffected by a later change to `part.price`.
5. Naming a warehouse employee as the handler of a branch order → rejected.
6. `INSERT INTO employee (salary) VALUES (-5)` → rejected by `CHECK`.
