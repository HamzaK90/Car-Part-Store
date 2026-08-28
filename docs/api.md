# API reference

48 endpoints. Every one except `POST /api/auth/login` needs `Authorization: Bearer <token>`.
The filter chain requires authentication for everything else; `/swagger-ui` and `/v3/api-docs`
are open.

**Who** in the tables below:

| Who | Means |
|---|---|
| staff | any authenticated account — every account belongs to a member of staff |
| ADMIN | the `ADMIN` role |
| manager | ADMIN, or the manager of that specific department |

Browsable at `/swagger-ui` with an **Authorize** button. Machine-readable at `/v3/api-docs`.

---

## Authentication

| Verb | Path | Who | Notes |
|---|---|---|---|
| POST | `/api/auth/login` | open | Exchange username and password for a JWT |

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"...","password":"..."}'
```

The token carries the account, the person behind it, their role, their department and whether
they manage it, read from `v_user_identity` in one query. It is a snapshot: a promotion or a
disabled account only takes effect at the next login.

---

## Parts

| Verb | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/parts` | staff | Search and filter; paged |
| GET | `/api/parts/{id}` | staff | |
| POST | `/api/parts` | ADMIN | |
| PATCH | `/api/parts/{id}` | ADMIN | Partial; an absent field is left alone |
| DELETE | `/api/parts/{id}` | ADMIN | 409 if any order names the part |
| GET | `/api/parts/{id}/fitments` | staff | Which cars the part fits |
| POST | `/api/parts/{id}/fitments` | ADMIN | |
| PATCH | `/api/parts/{id}/fitments` | ADMIN | Only the last model year can change |
| DELETE | `/api/parts/{id}/fitments` | ADMIN | Identified by query parameters |
| GET | `/api/parts/{id}/stock` | staff | Which warehouses hold it |

`GET /api/parts` accepts `search`, `supplierId`, `minPrice`, `maxPrice`, `make`, `model`,
`year`, `sort`, `page`, `size`, where `sort` is `SKU`, `NAME`, `CHEAPEST`, `DEAREST` or
`HEAVIEST`. The catalogue search answers the question the business is actually asked:

```
GET /api/parts?make=Toyota&model=Corolla&year=2017
```

A fitment is keyed by make, model and first model year, so those three identify it on
`PATCH` and `DELETE`:

```
DELETE /api/parts/12/fitments?make=Toyota&model=Corolla&yearFrom=2015
```

Only `yearTo` can be corrected. The other three are the fitment's identity — changing one is
recording a different fitment, so it is an add and a remove.

---

## Orders

| Verb | Path | Who | Notes |
|---|---|---|---|
| POST | `/api/orders` | staff | The handler comes from the token |
| GET | `/api/orders` | staff | Paged; see the filters below |
| GET | `/api/orders/{id}` | staff | With its lines |
| PATCH | `/api/orders/{id}/lines` | staff | The complete desired set, not a delta |
| POST | `/api/orders/{id}/fulfil` | staff | `PLACED` only |
| POST | `/api/orders/{id}/cancel` | staff | Returns the stock |
| GET | `/api/orders/{id}/invoice.pdf` | staff | Served inline, with a filename |

`GET /api/orders` accepts `status` (repeatable), `branchId`, `warehouseId`, `customerId`,
`employeeId`, `partId`, `from`, `to`, `minTotal`, `maxTotal`, `sort`, `page`, `size`. Combining
them is how the operational views are built rather than by adding an endpoint each:

```
GET /api/orders?status=PLACED&warehouseId=3&sort=OLDEST     a warehouse's picking queue
GET /api/orders?status=PLACED&branchId=1                    a branch's outstanding work
GET /api/orders?partId=12                                   every order naming a part
```

`sort` is `NEWEST` (default), `OLDEST`, `LARGEST` or `SMALLEST`. Each closes its
`ORDER BY` on the order id, so pages stay stable when two orders tie.

`POST /api/orders` has **no employee field**. The handler is whoever is authenticated, so a
salesperson cannot record an order as handled by a colleague — there is nowhere in the request
to say it. An account with nobody on the payroll behind it still places orders and records no
handler.

Ordering more than is available is a `409` carrying every shortage, not just the first:

```json
{
  "status": 409,
  "detail": "...",
  "shortages": [
    { "partId": 12, "sku": "BRK-001", "requested": 10, "available": 3 }
  ]
}
```

Amending sends the lines the order should end up with. A part left out is removed and its
stock returned; a part added is billed at today's price, while a line already on the order
keeps the price it was sold at. An order cannot be amended down to nothing — that is
cancelling it, which has its own endpoint.

---

## Stock

| Verb | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/warehouses/{id}/stock` | staff | What a warehouse holds; `lowOnly=true` narrows to shelves below their reorder level |
| POST | `/api/warehouses/{id}/stock` | staff | Receive a delivery |
| POST | `/api/warehouses/{id}/stock/transfer` | staff | Move units to another warehouse |
| PUT | `/api/warehouses/{id}/stock/{partId}` | staff | Stock-take: set the count outright |
| DELETE | `/api/warehouses/{id}/stock/{partId}` | staff | Stop carrying; 409 unless empty |

Receiving and a stock-take are deliberately separate. "We received 20 more" and "we counted
and there are 20" are different statements, and one endpoint doing both makes the caller's
intent ambiguous at the moment it matters. The stock-take is the one `PUT` in this API,
because it genuinely is a replacement.

A transfer moves both ends in one transaction. Two calls with nothing binding them would lose
the units if the second failed.

---

## Customers

| Verb | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/customers` | staff | `search` matches name or phone; paged |
| GET | `/api/customers/{id}` | staff | |
| POST | `/api/customers` | ADMIN | |
| PATCH | `/api/customers/{id}` | ADMIN | |
| DELETE | `/api/customers/{id}` | ADMIN | |

A blank email is stored as nothing rather than an empty string, so two customers who supplied
no address do not collide on `uq_customer_email`.

---

## Suppliers

| Verb | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/suppliers` | staff | `search` matches the name; paged |
| GET | `/api/suppliers/{id}` | staff | |
| POST | `/api/suppliers` | ADMIN | |
| PATCH | `/api/suppliers/{id}` | ADMIN | Address fields merge, they do not replace |
| DELETE | `/api/suppliers/{id}` | ADMIN | 409 while any part comes from them |

Patching a supplier's city does not erase their street. The address is one value made of
parts, and a partial update merges into it.

---

## Departments

| Verb | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/departments` | staff | `type=BRANCH` or `type=WAREHOUSE`; paged |
| GET | `/api/departments/{id}` | staff | |
| POST | `/api/departments` | ADMIN | A branch or a warehouse, never both |
| PATCH | `/api/departments/{id}` | manager | Appoint or vacate the manager |
| DELETE | `/api/departments/{id}` | ADMIN | 409 while anyone works there |

A warehouse must give `freeAreaSqm` and a branch must not. A manager must already work in the
department they are appointed to; a null `managerId` vacates the post, which is a legitimate
state rather than a refusal.

`PATCH` is the one endpoint where a plain role check is not enough: a manager may appoint or
vacate the manager of *their own* department. That is checked as `isManager` **and** a
matching department id, never as a role.

---

## Employees

Every endpoint here needs `ADMIN`, including reads — these rows carry salaries and birthdates.

| Verb | Path | Notes |
|---|---|---|
| GET | `/api/employees` | Filter by `departmentId`; paged |
| GET | `/api/employees/{id}` | |
| POST | `/api/employees` | Hiring; reports a manager vacancy if the department has none |
| PATCH | `/api/employees/{id}` | Their own details; not their department |
| PATCH | `/api/employees/{id}/department/{departmentId}` | Transfer |
| DELETE | `/api/employees/{id}` | |

A transfer is its own endpoint because it does more than change a column: it vacates any
manager's post the person held. A `departmentId` in a `PATCH` body is ignored rather than
honoured, so a transfer cannot happen by accident during an ordinary edit.

---

## Reports

All read-only, all paged, all backed by views.

| Verb | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/reports/revenue-by-customer` | staff | From `v_customer_revenue` |
| GET | `/api/reports/low-stock` | staff | Below reorder level, from `v_low_stock` |
| GET | `/api/reports/department-headcount` | staff | From `v_department_headcount` |
| GET | `/api/reports/departments-without-manager` | staff | From `v_department_without_manager` |

`revenue-by-customer` returns customer names and phone numbers to any authenticated user.
Left open because every account belongs to staff, but the personal data is the reason to
revisit it, not the revenue figure.

---

## Paging

Every listing is paged. `page` is zero-based, `size` is clamped to `1..100`, and a value
outside that range is adjusted rather than refused.

```json
{
  "content": [],
  "totalElements": 0,
  "totalPages": 0,
  "number": 0,
  "size": 20
}
```

Each listing sorts on something meaningful and then on a unique column. Without the
tiebreaker, tied rows shift between pages: some come back twice and others never, while the
reported total still counts both.

---

## Errors

Every failure is an RFC 7807 `ProblemDetail`:

```json
{
  "type": "https://github.com/HamzaK90/Car-Part-Store/problems/constraint-violation",
  "title": "Constraint violation",
  "status": 409,
  "detail": "that SKU is already in the catalogue",
  "constraint": "uq_part_sku"
}
```

| Status | `type` suffix | Meaning |
|---|---|---|
| 400 | `validation-failed` | Bean Validation; an `errors` object names every bad field at once |
| 400 | `invalid-request` | The domain refuses it |
| 400 | `invalid-parameter` | A path or query value of the wrong type |
| 400 | `malformed-request` | The body could not be read as JSON |
| 401 | `unauthenticated` | No token, or one that could not be read |
| 403 | `forbidden` | A valid token belonging to somebody who may not do that |
| 404 | `not-found` | Something the request named does not exist |
| 405 | `method-not-allowed` | Right path, wrong verb; the detail lists what is supported |
| 409 | `constraint-violation` | A database rule refused it; `constraint` names which |
| 409 | `insufficient-stock` | Carries a `shortages` array |
| 500 | `internal-error` | Unforeseen; logged in full, reported as nothing |

`401` and `403` answer different questions — "who are you" against "you, specifically, may
not". Collapsing them makes a client retry a login that was never the problem.

The `500` body deliberately says nothing useful. An exception message can carry a query, a
file path or part of a row, and none of that belongs in a response to somebody who has just
tripped over a bug.

Unknown fields in a request body are ignored rather than refused, so a client can send a field
this version does not know yet. The cost is that a misspelled optional field is accepted and
silently does nothing.

---

## Related

- [architecture.md](architecture.md) — how the API is put together
- [database.md](database.md) — the rules underneath it
- [testing.md](testing.md) — how it is verified
