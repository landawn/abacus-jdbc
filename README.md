# abacus-jdbc

[![Maven Central](https://img.shields.io/maven-central/v/com.landawn/abacus-jdbc.svg)](https://central.sonatype.com/artifact/com.landawn/abacus-jdbc)
[![Javadocs](https://img.shields.io/badge/javadoc-io-blue.svg)](https://javadoc.io/doc/com.landawn/abacus-jdbc)

**abacus-jdbc** is a SQL-first Java DAL that makes JDBC-style data access feel like coding with Collections:
- write SQL directly **or** generate SQL with builders
- execute with fluent prepared/named/callable query APIs
- optionally define DAO interfaces with built-in CRUD and annotation-mapped SQL

> Goal: **simplicity + consistency + predictability** (no ORM session magic; SQL stays visible).

---

## Why abacus-jdbc

Most data access tools sit at one extreme:
- **ORM-first**: high-level object graphs, but SQL/behavior can be non-obvious
- **SQL-first**: full control, but repetitive boilerplate around JDBC

abacus-jdbc aims for a pragmatic middle ground:
- **SQL stays explicit**
- **boilerplate drops**
- **APIs stay uniform**

---

## Quickstart

### 1) Execute a plain SQL query (PreparedQuery)

```java
String sql = "SELECT id, first_name, last_name, email FROM user WHERE first_name = ?";

try (PreparedQuery q = JdbcUtil.prepareQuery(dataSource, sql)) {
    User u = q.setString(1, firstName)
              .findFirst(User.class)
              .orElse(null);
}
````

### 2) Generate SQL with SQLBuilder

```java
String sql = PSC.select("id", "firstName", "lastName", "email")
                .from(User.class)
                .where(Filters.eq("firstName"))
                .sql();

List<User> users = JdbcUtil.prepareQuery(dataSource, sql)
                           .setString(1, firstName)
                           .list(User.class);
```

> Tip: if you want “SELECT * FROM user …”, use:

```java
String sql = PSC.selectFrom(User.class)
                .where(Filters.eq("firstName"))
                .sql();
```

### 3) Use a DAO interface (annotation-mapped SQL)

```java
public interface UserDao extends CrudDao<User, Long, SQLBuilder.PSC, UserDao>,
                                 JoinEntityHelper<User, SQLBuilder.PSC, UserDao> {

    @Query("SELECT id, first_name, last_name, email FROM user WHERE first_name = ?")
    List<User> selectByFirstName(String firstName) throws SQLException;

    // Example: multiple statements in a transaction
    @Transactional
    @Query({
        "UPDATE user SET first_name = ? WHERE id = ?",
        "UPDATE user SET last_name  = ? WHERE id = :id"
    })
    default void updateNames(long idForFirst, long idForLast, String firstName, String lastName, String... sqls)
            throws SQLException {
        prepareQuery(sqls[0]).setString(1, firstName).setLong(2, idForFirst).update();
        prepareNamedQuery(sqls[1]).setString("id", String.valueOf(idForLast)).update();
    }
}

// Create a DAO
UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource /*, optional sqlMapper */);
```

---

## Key features

### A) SQL authoring options

* Write SQL as strings
* Generate SQL via builders: `SQLBuilder`, `DynamicSQLBuilder`

### B) Statement / query execution APIs

* `PreparedQuery` — positional parameters (`?`)
* `NamedQuery` — named parameters (`:name`)
* `CallableQuery` — stored procedures / functions

Parameter binding options include:

* set by index/name
* set by entity getters/setters
* set by `Map`
* set by varargs/collection
* set by functional setter

### C) DAO interfaces with built-in CRUD

Define DAO interfaces extending `Dao`, `CrudDao`, and optional helpers like `JoinEntityHelper`,
and reuse pre-defined methods for common patterns:

* `findFirst(Condition)`
* `findOnlyOne(Condition)`
* `list(Condition)`
* `stream(Condition)`
* `insert/update/delete`
* `batchInsert/batchUpdate`

### D) SQL mappers (XML) + DAO binding

You can place SQL scripts in an XML mapper file and reference them by id.

Example `userSqlMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<sqlMapper>
  <sql id="selectUserByFirstName">
    SELECT id, first_name, last_name, email
    FROM user
    WHERE first_name = ?
  </sql>
</sqlMapper>
```

Then:

```java
UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource, sqlMapper);
```

And in DAO:

```java
@Query(id = "selectUserByFirstName")
List<User> selectByFirstName(String firstName) throws SQLException;
```

### E) Code generation

Helpers exist for generating code artifacts from schemas/utilities:

* `CodeGenerationUtil`
* `JdbcCodeGenerationUtil`

---

## Installation

### Maven

```xml
<dependency>
  <groupId>com.landawn</groupId>
  <artifactId>abacus-jdbc</artifactId>
  <version>4.1.0</version>
</dependency>
```

### Gradle (JDK 17+)

```gradle
dependencies {
  implementation "com.landawn:abacus-jdbc:4.1.0"
}
```

> Versions and release notes: see `CHANGES.md` and GitHub Releases.

---

## Spring Boot integration notes

abacus-jdbc works well with Spring Boot:

* use Spring-managed `DataSource`
* let Spring manage transactions (`@Transactional`)
* prefer externalized SQL and logging for production debugging

---

## Documentation

* Javadocs: [https://javadoc.io/doc/com.landawn/abacus-jdbc](https://javadoc.io/doc/com.landawn/abacus-jdbc)
* See also: abacus-common, abacus-entity-manager

