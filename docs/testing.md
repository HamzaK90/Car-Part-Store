# Testing

210 test methods, 260 executions, 96% instruction and 90% branch coverage. Every test runs
against a real PostgreSQL 16 with the real migrations applied. There are no mocks of the
database and no H2.

```bash
./mvnw verify          # everything, including the coverage floor
./mvnw test            # tests only
./mvnw test -Dtest=QueryCountTest
```

The coverage report lands at `target/site/jacoco/index.html`.

---

## Why a real database

Half of what this application relies on does not exist outside PostgreSQL: native enum types,
deferred constraint triggers, `SELECT ... FOR UPDATE`, the way unique constraints treat NULLs,
and the six views the reports read. A test against a substitute database would pass while
proving nothing about the thing that ships.

`IntegrationTest` starts one embedded PostgreSQL for the whole run and hands Spring its URL.
Flyway applies `V1`–`V6`, so the schema under test is the schema in the repository, demo data
included. Hibernate then validates every mapping against it with `ddl-auto: validate` — the
same setting production uses, and the check that catches an entity drifting from its table.

Starting PostgreSQL costs a few seconds, so it happens once rather than per class. The
consequence is that **tests share a database**: they must not depend on each other's state.
Anything created carries a unique tag, and counts are made against explicit id ranges rather
than table totals.

---

## The suite

| Class | Covers |
|---|---|
| `SchemaAndSeedTest` | the schema Flyway builds, and the demo data by id range |
| `ConstraintTest` | every database rule, including the two that only refuse at commit |
| `ApiSecurityTest` | the role matrix, and both halves of the manager rule |
| `ApiJourneyTest` | the business journey end to end |
| `SessionHandlerTest` | the order handler coming from the token, not the request body |
| `ConcurrencyTest` | two orders for the last unit |
| `InvoiceContentTest` | the text extracted from the PDF, not just that bytes came back |
| `CrudEndpointsTest` | customers, suppliers, employees, departments |
| `CataloguePatchTest` | partial updates to a part |
| `RemainingEndpointsTest` | stock removal, order amendment, fitments, transfers |
| `ErrorResponseTest` | every error shape over HTTP |
| `ApiExceptionHandlerTest` | the error handler in isolation |
| `ConstraintMappingTest` | every friendly message against the real schema |
| `ServiceGuardTest` | the service guards that validation shields from HTTP |
| `DomainTest` | entity invariants, no Spring, runs in 0.2s |
| `EntityIdentityTest` | the identity contract shared by all eleven entities |
| `JwtPropertiesTest` | the start-up secret guard |
| `QueryCountTest` | what a page costs in queries |

---

## Tests are driven over HTTP, not through services

`AbstractWebTest` drives the API through the real filter chain and is **deliberately not
transactional**. A rolling-back test keeps a session open for the whole request, which is
exactly the condition this application does not run in — `open-in-view` is disabled.

Every lazy-loading bug this project has had would be invisible otherwise. `POST
/api/orders/{id}/fulfil` once returned a 500 *after the write had committed*: the caller was
told the server failed on an order that really was fulfilled. It passed every service-level
check and only appeared when something drove the endpoint.

---

## Counting queries

`QueryCountTest` asserts how many statements a page costs. This is the check that has found
more defects in this project than any other, and it used to be done by hand.

Dropping a `JOIN FETCH` on its own is already loud here: `open-in-view` is off and controllers
map their DTOs outside the transaction, so the lazy association throws and the endpoint
returns 500. Half the suite goes red.

The case that needs this test is the repair somebody reaches for next. Making the association
`EAGER` instead of restoring the fetch join removes the exception, keeps every response
byte-for-byte identical, and costs a query per row forever after.

That is not hypothetical. With `Part.supplier` set to `EAGER` and the fetch join removed:

- every other test still passes
- the parts listing goes from 2 queries to 14
- only `QueryCountTest` fails

**Fixture size is the test.** Three rows cannot tell two queries from N+1, which is how one
N+1 here survived its first review — there were three suppliers. Every fixture seeds twelve.

The counter sees only what Hibernate prepares. The reports and the order listing read
hand-written SQL through `JdbcClient` and are not counted, which is acceptable because one
hand-written statement cannot N+1 by construction.

---

## Three defects the tests found

All three were silent. Nothing failed, nothing was logged, and no response looked wrong.

**`WarehouseStock.increase()` had no guard at all.** `increase(-5)` removed five units.
`ck_warehouse_stock_quantity` only objects if the result crosses zero, so a negative delivery
against a full shelf left no trace anywhere.

**`CustomerOrder.addLine()` accepted a quantity of zero**, deferring the refusal to a
flush-time constraint name instead of a sentence.

**`ApiExceptionHandler` returned 500 for every unmapped constraint.** `Map.ofEntries` throws
on `get(null)` rather than answering null, so the explicit fallback below it — the entire
reason that branch exists — could never run.

---

## Two habits worth keeping

### An upper bound is satisfied by zero

Every assertion in `QueryCountTest` is `isLessThanOrEqualTo`, so with statistics turned off the
whole class would pass while measuring nothing at all. It carries one test asserting that the
counter itself counts. Apply the same suspicion anywhere a test can only fail in one
direction.

### Ask what the writer just wrote

Three assertions in this suite were first written as:

```java
assertThat(stocks.setQuantity(warehouse, part, 4).getQuantity()).isEqualTo(4);
```

That is true regardless of what the code under test did, because setting it to 4 is what the
call does. They now read the shelf back instead.

The same trap in another form: an assertion named "deleting an employee who handled orders"
once ran against an employee who had handled none. Assert the precondition inside the test, so
a drifting fixture fails loudly rather than passing vacuously.

### Prove a new test can fail

Both novel checks here were verified by deliberately breaking what they guard — the `EAGER`
regression for `QueryCountTest`, and a `getClass()`-based `equals` for the proxy test. A test
that has never been seen to fail is a hypothesis, not a test.

---

## The coverage floor

`verify` fails below 92% instructions, 85% branches, 98% classes.

Branch coverage is enforced as well as instruction coverage because they fail differently. A
test that calls a method and asserts nothing about which way it went still covers every
instruction in it. Instruction coverage alone can be satisfied by tests that check nothing,
and this project wrote two of those by accident.

The floor is a floor, not a target: set just below where the suite sits, so gutting the tests
fails the build while ordinary work that dips a point does not.

---

## Things that are not covered

- **The order listing and reports are not query-counted.** They use `JdbcClient`; see above.
- **No load or performance testing.** Query counts are the proxy.
- **No test that the application boots under the production profile.** Tests use the `test`
  profile with its own configuration.
- **`CarPartsApplication.main` is uncovered**, which is normal and not worth chasing.

---

## Continuous integration

`.github/workflows/ci.yml` runs `./mvnw verify` on every push and pull request: Ubuntu, JDK 21,
Maven cache, coverage summary in the job output. No secrets and no services block — the
embedded PostgreSQL is part of the test run. A full run takes about a minute.

---

## Related

- [api.md](api.md) — the endpoints under test
- [database.md](database.md) — the rules being verified
- [architecture.md](architecture.md) — the layers under test
