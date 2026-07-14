# abacus-jdbc

[![Maven Central](https://img.shields.io/maven-central/v/com.landawn.abacus/abacus-jdbc.svg)](https://central.sonatype.com/artifact/com.landawn.abacus/abacus-jdbc/4.8.3)
[![Javadocs](https://img.shields.io/badge/javadoc-4.8.3-brightgreen.svg)](https://www.javadoc.io/doc/com.landawn.abacus/abacus-jdbc/4.8.3/index.html)

Work with SQL and databases as naturally as you work with Collections.

`abacus-jdbc` is a lightweight layer on top of plain JDBC. It keeps SQL front and center — you write (or generate) real SQL — while removing the boilerplate of preparing statements, binding parameters, mapping result sets, managing transactions, and handling exceptions. There is no ORM magic to fight and no query DSL to learn before you can run your first statement.

## Features

The library is organized around three steps you already take with JDBC — write SQL, prepare a query, execute and map the result. Each step is optional and composable; use as much or as little as you need.

### 1. Write or generate SQL *(optional)*

Write SQL as a plain string, or build it type-safely with
[SQLBuilder](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/SQLBuilder_view.html) /
[DynamicSQLBuilder](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/DynamicSQLBuilder_view.html).

```java

// Hand-written SQL as a plain string.
String query = "SELECT id, first_name, last_name, email FROM user WHERE first_name = ?";

// Or build it from the entity class (compile-time safe, refactor-friendly).
String query = PSC.select("id", "firstName", "lastName", "email")
                  .from(User.class)
                  .where(Filters.eq("firstName"))
                  .sql();

// Or select every mapped column:
String query = PSC.selectFrom(User.class).where(Filters.eq("firstName")).sql();
```

SQL can also live in an external mapper file and be referenced by id from a DAO — see the `@SqlSource` example below.

### 2. Prepare a statement / query

[PreparedQuery](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/PreparedQuery_view.html),
[NamedQuery](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/NamedQuery_view.html), and
[CallableQuery](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/CallableQuery_view.html)
wrap JDBC statements with a fluent, null-safe, auto-closing API.

```java
PreparedQuery preparedQuery = JdbcUtil.prepareQuery(dataSource, query)
        //.prepareQuery(connection, query)               // reuse an existing connection
        //.prepareNamedQuery(dataSource, namedSql)        // ':param' named parameters
        //.prepareCallableQuery(dataSource, callSql)      // stored procedures
        .setString(1, firstName);                         // bind positional parameters
        //.setLong("id", id)                              // bind a named parameter
        //.setParameters(entity)                          // bind named params from an entity's getters
        //.setParameters(paramMap)                        // bind named params from a Map
        //.setParameters(param1, param2)                  // bind several positional params at once
        //.setParameters(parametersSetter)                // bind via a functional interface
```

### 3. Execute and retrieve results

Execute directly from a query, or let a
[Dao](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/Dao_view.html) /
[CrudDao](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/CrudDao_view.html) /
[JoinEntityHelper](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/JoinEntityHelper_view.html)
do it for you. Results map to entities, primitives, or a
[Dataset](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/Dataset_view.html)
via [Jdbc](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/Jdbc_view.html) mappers.

```java
// Execute directly from a PreparedQuery / NamedQuery / CallableQuery:
Optional<User> user = preparedQuery.findFirst(User.class);
        //.findOnlyOne(User.class) / .list(User.class) / .stream(User.class)
        //.query() / .queryForInt("id") / .queryForString("email") / .queryForSingleValue(...)
        //.exists() / .ifExists(rowConsumer)
        //.update() / .batchUpdate(...) / .execute()
```

A DAO turns your SQL into ready-to-call methods. Dozens of common operations
(`insert`, `update`, `deleteById`, `list`, `count`, `stream`, `batchInsert`, ...) are already
defined on the built-in interfaces — no implementation required — and you add your own with `@Query`:

```java
public interface UserDao extends CrudDao<User, Long, UserDao>, JoinEntityHelper<User, UserDao> {

    // Inline SQL with a positional parameter.
    @Query("SELECT id, first_name, last_name, email FROM user WHERE first_name = ?")
    List<User> selectUserByFirstName(String firstName) throws SQLException;

    // Or reference SQL by id (from the nested @SqlScript field below,
    // or from an external mapper declared with @SqlSource on the interface).
    @Query(id = "selectByLastName")
    List<User> selectByLastName(@Bind("lastName") String lastName) throws SQLException;

    // Run several statements in one transaction. The trailing String... parameter is
    // automatically filled with the SQL statements declared in @Query.
    @Transactional
    @Query({ "UPDATE user SET first_name = ? WHERE id = ?",
             "UPDATE user SET last_name = :lastName WHERE id = :id" })
    default void renameById(long id, String firstName, String lastName, String... sqls) throws SQLException {
        prepareQuery(sqls[0]).setString(1, firstName).setLong(2, id).update();
        prepareNamedQuery(sqls[1]).setString("lastName", lastName).setLong("id", id).update();
    }

    // SQL built with SQLBuilder and stored in a nested field, referenced above via @Query(id = ...).
    static final class SqlTable {
        @SqlScript
        static final String selectByLastName =
                PSC.select("id", "firstName", "lastName", "email").from(User.class).where(Filters.eq("lastName")).sql();
    }
}

// Instantiate the DAO — no boilerplate implementation to write.
UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);

List<User> users = userDao.selectUserByFirstName(firstName);
        //.findFirst(Filters.eq("email", email))
        //.list(Filters.gt("id", 100))
        //.stream(cond)
        //.update(user) / .deleteById(userId) / .batchInsert(userList)
```

To keep SQL out of your Java source entirely, put it in an XML mapper and point the DAO at it
with [`@SqlSource`](https://www.javadoc.io/doc/com.landawn.abacus/abacus-jdbc/latest/com/landawn/abacus/jdbc/annotation/SqlSource.html):

```java
@SqlSource("sql/UserDao.xml")
public interface UserDao extends CrudDao<User, Long, UserDao> {

    @Query(id = "selectUserByFirstName")
    List<User> selectUserByFirstName(@Bind("firstName") String firstName) throws SQLException;
}
```

`sql/UserDao.xml` (on the classpath):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<sqlMapper>
    <sql id="selectUserByFirstName">SELECT id, first_name, last_name, email FROM user WHERE first_name = :firstName</sql>
</sqlMapper>
```

See the full annotation set in package
[`com.landawn.abacus.jdbc.annotation`](https://www.javadoc.io/doc/com.landawn.abacus/abacus-jdbc/latest/com/landawn/abacus/jdbc/annotation/package-summary.html)
(`@Query`, `@Bind`, `@BindList`, `@Transactional`, `@Handler`, `@PerfLog`, `@CacheResult`, `@SqlLogEnabled`, ...).

### Bonus: code generation

Generate entity classes and DAO scaffolding straight from your database schema with
[CodeGenerationUtil](https://www.javadoc.io/doc/com.landawn.abacus/abacus-common/latest/com/landawn/abacus/util/CodeGenerationUtil.html)
and
[JdbcCodeGenerationUtil](https://www.javadoc.io/doc/com.landawn.abacus/abacus-jdbc/latest/com/landawn/abacus/jdbc/JdbcCodeGenerationUtil.html).

## Why abacus-jdbc?

The biggest difference between this library and other database-access frameworks is the simplicity,
consistency, and integrity of its API design. For a detailed comparison, see
[Abacus-JDBC vs Spring Data (JPA & JDBC), MyBatis, and Hibernate – A Comparative Analysis](https://github.com/landawn/abacus-jdbc/blob/master/docs/Abacus-JDBC%20vs%20Spring%20Data%20(JPA%20%26%20JDBC)%2C%20MyBatis%2C%20and%20Hibernate%20%E2%80%93%20A%20Comparative%20Analysis.pdf).

### Design considerations

* Embedding SQL where it is used is generally easier to review and maintain than storing it in separate files — but external mappers are fully supported when you prefer them.
* High-level abstractions such as JPA or Spring's `CrudRepository` improve productivity, but keeping the ability to write and run plain SQL preserves essential flexibility. SQL is simple, expressive, and powerful, and should be leveraged rather than avoided.
* Abstractions should simplify code and improve productivity **without** adding complexity or sacrificing performance.

## Download / Installation

Requires **JDK 17 or above**. See the [change log](https://github.com/landawn/abacus-jdbc/blob/master/CHANGES.md) for release notes.

`abacus-jdbc` declares `abacus-common` and `abacus-query` in `provided` scope, so add all three to your build:

### Maven

```xml
<dependency>
    <groupId>com.landawn.abacus</groupId>
    <artifactId>abacus-jdbc</artifactId>
    <version>4.8.3</version>
</dependency>
```

### Gradle

```gradle
implementation 'com.landawn.abacus:abacus-jdbc:4.8.3'
```

## User guide

* [Samples and answered questions](https://github.com/landawn/abacus-jdbc/tree/master/samples/com/landawn/abacus/samples)
* [API documentation (Javadoc)](https://www.javadoc.io/doc/com.landawn.abacus/abacus-jdbc/latest/index.html)
* [Introduction to JDBC](https://www.javacodegeeks.com/2015/02/jdbc-tutorial.html) (background reading)

## Also see

* [abacus-common](https://github.com/landawn/abacus-common) — core utilities, collections, and `Dataset`.
* [abacus-query](https://github.com/landawn/abacus-query) — `SQLBuilder`, `Filters`, and query conditions.

## Recommended libraries and tools

* Libraries: [Lombok](https://github.com/rzwitserloot/lombok), [HikariCP](https://github.com/brettwooldridge/HikariCP), [Apache ShardingSphere](https://github.com/apache/shardingsphere) ... [awesome-java](https://github.com/akullpp/awesome-java#database)
* Tools: [SpotBugs](https://github.com/spotbugs/spotbugs), [JaCoCo](https://www.eclemma.org/jacoco/)
