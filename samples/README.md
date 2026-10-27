# Executable user guide

These examples show how to use abacus-jdbc with SQL, entity mapping, DAOs,
transactions, and streams. Each database example creates its own in-memory H2
database from [schema.sql](schema.sql) and closes its connection pool afterward.
No database server, credentials, or existing database files are needed.

## Run the examples

Use JDK 21 or newer and Maven. From the **repository root**, run:

```sh
# Compile this checkout and run all sample tests.
mvn -Psamples test

# Run the smallest example, which checks the greeting "Hello, Ada!".
mvn -Psamples "-Dtest=com.landawn.abacus.samples.HelloTest" test

# Run one tutorial or one method.
mvn -Psamples "-Dtest=com.landawn.abacus.samples.NamedQueryTest" test
mvn -Psamples "-Dtest=com.landawn.abacus.samples.JdbcTest#commitOrRollBackAUnitOfWork" test
```

The `samples` profile adds this folder as a test source directory and runs only
the sample tests. Maven supplies H2, HikariCP, JUnit, Lombok, and the library's
dependencies using the versions in the root `pom.xml`. The first run may need
to download them. Reports are written to `target/surefire-reports`.
Use the fully qualified class names above: some library tests have the same
short class names as the samples. Plain `mvn test` runs the library test suite.

In an IDE, import the root Maven project, activate the `samples` Maven profile,
and enable annotation processing for Lombok. Run the JUnit examples, or run
[`Hello.main`](com/landawn/abacus/samples/Hello.java) with the test classpath.
Use the repository root as the working directory so `@SqlSource` can resolve
`samples/userSqlMapper.xml`.

## Suggested reading order

| Example | What to learn |
| --- | --- |
| [Hello](com/landawn/abacus/samples/Hello.java) | Open a pool, insert a row, bind a named parameter, read a scalar, and close resources |
| [JdbcTest](com/landawn/abacus/samples/JdbcTest.java) | Plain JDBC, PreparedQuery CRUD, a minimal DAO, an XML mapper, commit/rollback, and table overrides |
| [NamedQueryTest](com/landawn/abacus/samples/NamedQueryTest.java) | Bind a bean or map, reuse a parameter name, build SQL from entity properties, and handle no match |
| [PreparedQueryTest](com/landawn/abacus/samples/PreparedQueryTest.java) | Custom row mappers, aliases, typed values, and map results |
| [BatchAndStreamTest](com/landawn/abacus/samples/BatchAndStreamTest.java) | Batch inserts with generated keys, early stream closure, keyset pagination, and table copying |
| [DaoTest](com/landawn/abacus/samples/DaoTest.java) | More DAO recipes: conditions, projections, lists, joins, handlers, caching, and CSV export |
| [UncheckedDaoTest](com/landawn/abacus/samples/UncheckedDaoTest.java) | DAO operations with unchecked SQL exceptions |
| [DatasetTest](com/landawn/abacus/samples/DatasetTest.java) | Join two in-memory tabular results |
| [CodeGenerationUtilTest](com/landawn/abacus/samples/CodeGenerationUtilTest.java) | Generate entities, query projections, property constants, and SQL from metadata |

Assertions show the expected results. The broader DAO and mapping recipes also
print results for inspection. Each test starts with an empty schema and inserts
its own data; tests do not depend on execution order.

## Choose the API for the task

Start with `JdbcUtil.prepareQuery(dataSource, sql)` when you already have SQL
with `?` placeholders. Parameter indexes start at **1**. Use
`prepareNamedQuery` for `:name` placeholders; `setParameters(bean)` reads bean
properties and `setParameters(map)` matches map keys to parameter names.
Values belong in bindings, including strings containing quotes.

```java
try (var query = JdbcUtil.prepareNamedQuery(dataSource,
        "SELECT * FROM user1 WHERE first_name = :firstName")) {
    List<User> users = query.setString("firstName", "Ada").list(User.class);
}
```

For repeated entity operations, declare an interface extending
`CrudDao<User, Long, YourDao>` and create it with `JdbcUtil.createDao`.
`JdbcTest.BasicUserDao` is the minimal version. The larger
[`UserDao`](com/landawn/abacus/samples/dao/UserDao.java) shows optional annotations
and custom queries; those annotations are not prerequisites for a basic DAO.
Checked DAOs declare `SQLException`. Unchecked DAOs expose SQL failures as
`com.landawn.abacus.exception.UncheckedSQLException`.

SQL builders are optional. `NSC.selectFrom(User.class)` uses the entity's table
and column mappings. A condition such as `Filters.eq("nickName")` refers to the
Java property; the SQL column is `prop1`. When you write SQL yourself, use the
actual database column names.

## Understand the sample mapping

[`User`](com/landawn/abacus/samples/entity/User.java) maps to `user1`:

| Java member | Database mapping |
| --- | --- |
| `id` | `@Id`, an identity primary key |
| `firstName` | `FIRST_NAME` via `@Column` |
| `lastName` | `last_name` through the default snake-case mapping |
| `nickName` | `prop1` via `@Column` |
| `createTime` | `create_time`, supplied by the database and marked `@ReadOnly` |
| `devices`, `address` | Related entities loaded explicitly through `@JoinedBy` helpers |

For generated IDs, leave `id` unset and use the value returned by `dao.insert`.
For a direct prepared insert that needs generated keys, prepare the query with
the `autoGeneratedKeys` argument set to `true`, as in `BatchAndStreamTest`.
The custom `insertWithId` methods in the larger DAO examples deliberately insert
an explicit ID instead.

`Employee`, `Project`, and `EmployeeProject` demonstrate a many-to-many
relationship. Loading an entity does not automatically load its joined entities:
call `loadJoinEntities` or `loadAllJoinEntities` when needed.

## Results and resource lifetime

- `findFirst(User.class)` returns the first match; add `ORDER BY` when the choice matters.
- `findOnlyOne(User.class)` expresses a query that should match at most one row.
  An empty result is represented by `com.landawn.abacus.util.u.Optional`.
- `list(User.class)` materializes the rows. `query()` returns a `Dataset`.
- `queryForString()` returns a nullable scalar wrapper; check presence/nullability
  before reading values that may be missing or SQL `NULL`.
- `stream(User.class)` is lazy and retains JDBC resources. Use try-with-resources,
  especially with `findFirst`, `limit`, or other operations that stop early.

Queries normally close their statements after terminal execution. The introductory
examples still use try-with-resources so exceptions during parameter binding
also release resources. When using a caller-supplied `Connection`, the caller
owns that connection. A pool should normally live for the application's lifetime;
these tests create and close a small pool per example for isolation.

## Transactions and custom SQL

`JdbcTest.commitOrRollBackAUnitOfWork` updates a row, deliberately violates a
`NOT NULL` constraint, and verifies that closing the uncommitted transaction
restores the original value. It then commits a successful update. Use the same
`DataSource` on the same thread for the operations in a transaction.

[`userSqlMapper.xml`](userSqlMapper.xml) and `UserDao.selectUserById` form a complete
external SQL example: `@SqlSource` loads the file, `@Query(id = "selectUserById")`
selects the statement, and `@Bind("id")` supplies its parameter. `@SqlSource`
resolves a filesystem location; do not assume it can read a mapper inside a JAR.

`@BindList` expands a collection into value placeholders. `@SqlFragment` inserts
SQL structure such as a table or sort expression. Use application-controlled
fragments or an allowlist for identifiers; bind user values as parameters.
Overriding a DAO's `targetTableName` changes its generated CRUD statements, while
literal SQL in `@Query` retains the table names written in the annotation.

## Code generation and supporting files

The code-generation examples use JUnit's `@TempDir`, which is removed after the
test. They never overwrite the checked-in examples. When adopting generation
in your own build, choose a generated-sources directory and review the generated
IDs, read-only fields, and column mappings before using the classes.

[`SampleDatabase`](com/landawn/abacus/samples/SampleDatabase.java) owns the H2
schema and pool. [`SampleTestSupport`](com/landawn/abacus/samples/SampleTestSupport.java)
creates DAOs for the larger recipes. The `entity/`, `dao/`, and `codes/entity/`
folders contain shared mappings, DAO declarations, and illustrative generated
classes. Use the introductory tests as the starting point for application code.
