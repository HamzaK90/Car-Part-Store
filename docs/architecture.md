# The system

A REST API for a car parts retail business: customers, branches, warehouses, staff, suppliers,
a parts catalogue, stock and orders. 48 endpoints behind JWT authentication.

Java 21, Spring Boot 3.3, PostgreSQL 16, Flyway, Spring Data JPA with `JdbcClient` for
reporting, Spring Security, springdoc-openapi, Maven.

---

## Layers

```
web/         controllers, DTOs, the RFC 7807 error handler      11 classes
service/     business rules and the exceptions they raise       14 classes
repository/  Spring Data interfaces, plus JdbcClient reporting  10 classes
domain/      entities, embeddables, enums                       20 classes
security/    JWT issue and parse, filter chain, manager rule      6 classes
support/     shared helpers
config/      OpenAPI description
```

The direction is one-way: web depends on service, service on repository, repository on domain.
Nothing points back up.

### A request

```
HTTP  ->  JwtAuthFilter        reads the token, sets the authentication
      ->  Controller           validates the body, no transaction here
      ->  Service              @Transactional, the business rules
      ->  Repository           JPA or JdbcClient
      ->  PostgreSQL           the rules that must hold regardless
      <-  DTO                  mapped in the controller, never an entity
```

---

## Rules the code keeps

These are the decisions that shaped the codebase. Undoing one usually breaks something quietly
rather than loudly.

### Entities are never serialised

Controllers map to DTOs. An entity on the wire couples the API to the schema, exposes columns
nobody meant to publish, and drags lazy associations into the serialiser.

### No `@Transactional` on a controller

`open-in-view` is disabled. A transaction on a controller reinstates open-session-in-view by
hand: it hides N+1 behind serialisation, holds a database connection during network I/O, and
turns a serialisation failure into a broken response body behind an already-sent `200`.

The controller is handed complete data instead. Every listing query fetch-joins what its
response needs, which is asserted by `QueryCountTest`.

### PATCH, not PUT

Updates are partial. A `PUT` that means "replace" makes every client send every field, and the
first one that forgets a field silently erases it. The one exception is a stock-take, which
genuinely is a replacement.

### Every listing is paged and capped

`Paging.MAX_SIZE` is 100, and a requested size outside `1..100` is clamped rather than
refused. An unpaged list works on demo data and falls over in a year; a bare `List` return
type on a listing is the tell.

### Offset paging closes its ORDER BY on something unique

Paging by a non-unique sort is unstable: rows shift between pages, so some are returned twice
and others never. Proven here, not assumed — ten low-stock rows paged two at a time returned
nine distinct rows, one of them twice, and the reported total still counted both.

`Paging.of(page, size, sort, uniqueTiebreaker)` takes the tiebreaker as a required argument
and appends it to the sort, so the mistake cannot be made by omission.

### Reports use hand-written SQL

The reporting endpoints read the views through `JdbcClient` and project straight into records.
Loading entity graphs to compute a total that a view already computes cost about six queries
per row.

---

## Security

**A token is a snapshot, not a session.** Claims are read once at login from `v_user_identity`
in a single query — the account, the person behind it, their role, their department, and
whether they manage it. A request therefore answers "who is this and what may they do" without
a lookup per call.

The cost is that a promotion, demotion or disabled account only takes effect at the next
login. A disabled account keeps read and write access until its token expires. There is
nothing server-side to revoke; to cut access sooner, disable the account and wait out
`JWT_EXPIRY_MINUTES`.

**The signing secret has no default.** An unset, too-short or unresolved `JWT_SECRET` stops
the application at start-up with a message naming the property. A fallback secret is a
published secret. Below 32 bytes, HMAC signing fails outright, so a short secret is not a
weaker token but no token at all — better to never start.

**There is no MANAGER role.** Managing is per-department. `@access.managesDepartment(#id)`
compares the `isManager` flag **and** the department id; the flag alone would let any manager
edit any department, and the department alone would let every employee edit the one they work
in.

**The order handler comes from the token.** `PlaceOrderRequest` has no employee field at all.
A salesperson cannot record an order as handled by a colleague because there is nowhere in the
request to say it — the absence is the mechanism, not a rule somewhere that has to remember to
refuse. The database agrees independently through `ct_order_employee_at_branch`.

### Who may do what

Reads are open to any authenticated member of staff, because every account belongs to one.
Administrative writes — the catalogue, customers, suppliers, departments — need `ADMIN`.
Operational writes do not: a salesperson must be able to place an order and warehouse staff to
receive stock. `/api/employees` needs `ADMIN` even to read, because those rows carry salaries
and birthdates.

---

## Errors

Every failure is an RFC 7807 `ProblemDetail`. One place decides what a failure looks like on
the wire; without it every controller invents its own shape and a client has to discover each
variation.

| Status | Meaning |
|---|---|
| `400` | malformed, or something the domain refuses |
| `401` | no token, or one that could not be read |
| `403` | a valid token belonging to somebody who may not do that |
| `404` | something the request named does not exist |
| `405` | right path, wrong verb |
| `409` | understood, but conflicts with current state |
| `500` | unforeseen; logged in full, reported as nothing |

A `409` from an oversell carries a `shortages` array naming every part that was short, with
requested and available counts. All shortages are collected rather than throwing on the first,
so one response tells the caller everything they need to fix the order.

Database constraint names are translated into sentences a person can act on. The `500` body
deliberately says nothing: an exception message can carry a query, a file path or part of a
row, and none of that belongs in a response to somebody who has just tripped over a bug.

---

## The API

48 endpoints. Everything except `POST /api/auth/login` needs a bearer token.

Every endpoint, its query parameters and who may call it are in
[api.md](api.md). The browsable description is at `/swagger-ui`, with an **Authorize** button
for a token; the machine-readable version is at `/v3/api-docs`.

---

## Known gaps

Deliberate, and worth knowing before extending the system:

- **No audit trail.** Nothing records who changed what, or when.
- **No idempotency on `POST /api/orders`.** A retried request places a second order.
- **No optimistic locking.** There is no `@Version`; concurrent edits to the same row are
  last-write-wins. Stock is the exception and is protected by row locks.
- **`revenue-by-customer` is readable by any authenticated user** and returns customer names
  and phone numbers. Left open because every account belongs to staff, but the personal data
  is the reason to revisit it, not the revenue figure.

---

## Related

- [database.md](database.md) — the schema and the rules it enforces
- [testing.md](testing.md) — how all of this is verified
- [diagrams/](diagrams/) — ERD, class diagram, order sequence
