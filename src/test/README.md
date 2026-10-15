# Test organization and maintenance

Run commands from the repository root with JDK 21 or newer and Maven. The default
suite runs local unit tests and self-contained integration tests. An explicitly
enabled MySQL profile runs the external database tests.

```sh
# Full local suite; failures fail the Maven build. Generate coverage explicitly.
mvn test jacoco:report

# Unit tests only, or self-contained integration tests only.
mvn "-Dtest=*Test" "-DexcludedGroups=integration,mysql" test
mvn "-Dtest=*Test" "-Dgroups=integration" test

# A focused family, including its behavior-specific classes.
mvn "-Dtest=PreparedQuery*Test" test

# Read-only ownership/discovery audit and scanner regression tests (Node.js required).
node scripts/test-maintenance.mjs --check --json target/test-inventory.json
node --test scripts/test-maintenance/test-maintenance.test.mjs
```

After moving or deleting test classes, run `mvn clean test jacoco:report` once to
remove obsolete compiled classes. Coverage is written to `target/site/jacoco`.
Use `-Djacoco.skip=true` when coverage is not needed.

## Ownership and grouping

Keep tests beside the production package and name each class for its production
owner and behavior: `PreparedQueryParameterBindingTest`, for example. Retain a
small `OwnerTest` for core construction and configuration. Split large suites by
behavior instead of creating numbered, dated, or `Review` classes.

| Production owner | Test groups |
| --- | --- |
| `AbstractQuery`, `PreparedQuery`, `CallableQuery` | Core, parameter binding, execution, results, streams, and lifecycle as applicable |
| `NamedQuery` | Core, named parameters, scalar, temporal, stream, and object binding; shared parameter-layout contracts |
| `JdbcUtil` | Connections, parameters, batches, result mapping, streams, transactions, metadata, resource cleanup, logging, and DAO caches |
| `Jdbc` | Column access, row callbacks/mapping, result extraction, parameter/out-parameter binding, handler factories, and DAO caches |
| `ResultSetProxy` | Core/wrappers, column access, object mapping, cursor behavior, and updates |
| `JdbcCodeGenerationUtil` | Entities, SQL, SQL conversion, and identifier contracts |
| `DataTransferUtil` | Imports, copies, CSV import/export, argument validation, and batch failures |

The read-only scanner maps these names to production sources. Explicit ownership
for cross-cutting tests lives in
[`ownership.json`](../../scripts/test-maintenance/ownership.json). Ownership is
many-to-one: several focused test classes can cover one production class.

Within each class, put fields first, lifecycle methods next, related test cases
together, then helpers and fixture types. Preserve field initialization order.
Group tests by the API they exercise, then normal, boundary, invalid-input, and
failure/cleanup behavior. This is source organization, not an execution dependency:
do not add ordered execution to make shared mutable fixtures work. Prefer `operation_condition_expectedBehavior` for new
methods; descriptive existing regression names can remain. Use `@Nested` when
independent fixture setup makes a behavior group clearer.

Share fixtures through package-private, abstract `OwnerTestSupport` classes only
within that owner's family. Keep behavior-specific helpers local. Open Mockito
annotation sessions in setup and close them in teardown; close connections,
statements, streams, pools, and temporary resources that the fixture owns.

## Discovery and test tiers

`AbacusJdbcTestSuite` includes the recursive JDBC packages using the inherited
`base-test` tag from `TestBase` or the existing `2025` tag. Preserve this discovery
contract when adding or moving a test. Root-package test infrastructure remains
separate from the JDBC test families.

- Unit tests exercise behavior in memory or through controlled mocks.
- `@Tag("integration")` identifies embedded-database, generated-code compilation,
  and subprocess compatibility tests. Prefer `*IntegrationTest` for a dedicated
  class. Existing mixed classes use method tags for the integration cases.
- `@Tag("mysql")` identifies external MySQL tests. They are disabled by default.

Configure `ABACUS_JDBC_MYSQL_URL`, `ABACUS_JDBC_MYSQL_USER`, and
`ABACUS_JDBC_MYSQL_PASSWORD` for an isolated test database, then run:

```sh
mvn -Pmysql-tests -Dtest=JdbcMySQLTest test
```

Equivalent properties are `jdbc.mysql.url`, `jdbc.mysql.user`, and
`jdbc.mysql.password`. The fixtures create and drop their tables and procedures.
Once enabled, missing configuration, connection failures, and fixture failures
fail the build. Connection pools are closed after use.

## Consolidation and assertions

Treat duplicate reports as review candidates. Two equal bodies can use different
fixtures or parameter sources. Before removing a case, identify the surviving
test and confirm it preserves the same input, branch, assertion, and cleanup
contract. Preserve distinct failure stages and different driver behavior.

Use parameterized tests for the same contract over related inputs or overloads.
Give each invocation a useful display name. Avoid loops that hide later variants
after the first failure. Assert returned values, persisted state, exception
identity/cause/suppressed failures, and resource ownership where relevant;
successful execution alone is often insufficient.

Recent consolidation examples:

- NamedQuery binding families share eight parameter layouts through
  `NamedQueryBindingTestSupport`. Each overload verifies exact JDBC positions and
  call order, fluent return values, and missing-name cleanup. Repeated streams and
  readers are read back to check the payload at every occurrence.
- Keep direct collection/array overloads separate from values passed as `Object`.
  They dispatch differently and have different failure cleanup contracts; names
  and assertions should make that distinction clear.
- ResultSetProxy delegates use one mock fixture and separate column access, object
  mapping, cursor, and update classes. Corresponding index/label cases stay adjacent.
- Constants use individually named parameterized cases. Known field names remain
  explicit so removed constants fail; newly declared constants join the same
  deduplicated parameter source.
- Five matching integer/large-batch pairs now use five parameterized tests with
  both overloads. Distinct execution-versus-commit self-suppression cases remain.
- JDBC-typed and object-bound stream replay variants now run as individually
  reported parameterized cases, with row-presence and row-count assertions.
- Identifier validation belongs to `SqlIdentifierUtilTest`; its existing escaping
  and empty-identifier cases replace the duplicate assertions in transfer tests.
- Quoting and metadata regression files are grouped under their production owner
  with nested fixtures. Query and result-set null validation lives with the
  corresponding binding/proxy tests.
- `DBLockIntegrationTest` observes an actual scheduled refresh with a bounded
  latch, then exercises eviction directly; it does not rely on a fixed sleep.
- `JdbcUtilTestSupport` saves and restores global logging switches, handlers,
  extractors, and per-thread configuration so logging tests cannot change the
  behavior of tests that run later.

For structural changes, compare the test inventory and executed cases before and
after, run the full suite from a fresh build, inspect line/branch coverage for the
affected production classes, and exercise changed families in random order when
shared state is involved. Method counts and invocation counts are different for
parameterized and dynamic tests.

The [2026-10-15 follow-up cleanup plan and results](../../scripts/test-maintenance/cleanup-2026-10-15.md)
record the latest consolidation and validation.
