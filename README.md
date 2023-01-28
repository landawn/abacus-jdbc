# abacus-jdbc

[![Maven Central](https://img.shields.io/maven-central/v/com.landawn/abacus-jdbc.svg)](https://maven-badges.herokuapp.com/maven-central/com.landawn/abacus-jdbc/)
[![Javadocs](https://img.shields.io/badge/javadoc-3.2.19-brightgreen.svg)](https://www.javadoc.io/doc/com.landawn/abacus-jdbc/3.2.19/index.html)

Hope it will bring you the programming experiences: coding with SQL/DB is just like coding with Collections.

## Features:



*  [PreparedQuery](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/PreparedQuery_view.html), 
[NamedQuery](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/NamedQuery_view.html), 
[PreparedCallableQuery](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/PreparedCallableQuery_view.html), 
[Dao](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/Dao_view.html)/[CrudDao](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/CrudDao_view.html)/[JoinEntityHelper](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/JoinEntityHelper_view.html), 
[Jdbc](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/Jdbc_view.html),
[JdbcUtil](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/JdbcUtil_view.html),
[JdbcUtils](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/JdbcUtils_view.html),
[DataSet](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/DataSet_view.html), 
[ConditionFactory(CF)](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/ConditionFactory_view.html), 
[SQLBuilder](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/SQLBuilder_view.html), 
[DynamicSQLBuilder](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/DynamicSQLBuilder_view.html)...


* Looking for: 
[SQLExecutor](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/SQLExecutor_view.html) and [Mapper](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/Mapper_view.html)? Please refer to branch: [sql_executor_mapper](https://github.com/landawn/abacus-jdbc/tree/sql_executor_mapper)


## Why abacus-jdbc?

Abacus-jdbc provides the best APIs, which you won't find in other libraries, for preparing query/setting parameters/extracting result. A lot of DB operations can be done through Dao/CrudDao without writing a single data access method.

* Work with sql statements
```java
String query = "SELECT first_name, last_name FROM account WHERE first_Name = ?";

Optional<Account> account = JdbcUtil.prepareQuery(query)
  .setString(1, "Tom") // setInt/setString/setDate/...
  // OR .setParameters(entity/map) for named query.
  // OR .setParameters(stmt -> {}) by functional interface.
  .findFirst(Account.class); // findOnlyOne(Account.class/RowMapper)/list/...
  // OR ./query(ResultExtractor).../queryForInt/String/...
  // Or .stream(Account.class/RowMapper).filter/map/collect/...
  // More query/update/delete/batchInsert/batchUpdate...
```

* Work with Dao:
```java
public interface UserDao extends JdbcUtil.CrudDao<User, Long, SQLBuilder.PSC, UserDao> {
   // ...
    @NamedInsert("INSERT INTO user (id, first_name, last_name, email) VALUES (:id, :firstName, :lastName, :email)")
    void insertWithId(User user) throws SQLException;
}

User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
userDao.insertWithId(user);

User userFromDB = userDao.gett(100L);
System.out.println(userFromDB);

userDao.stream(CF.eq("firstName", "Forrest")).filter(it -> it.getLastName().equals("Gump"));
userDao.deleteById(100L);

```

## Samples & FQA
* How to write/generate sql scripts:

```java
    String query = "select first_name, last_name from account where id = ?"; // write by yourself.
    
    String query = PSC.select("firstName, "lastName").from(Account.class).where(CF.eq("id")).sql(); // use SQLBuilder
    
    // To select all fields:
    String query = PSC.selectFrom(Account.class).where(CF.eq("id")).sql();
```

* Where to put sql scripts:

```java
    // define it as constant or local variable
    static final String query = "select ....";
    String query = "select ....";
    
    // annotated on method in Dao interface
    @NamedUpdate("UPDATE user SET first_name = :firstName, last_name = :lastName WHERE id = :id")
    int updateFirstAndLastName(@Bind("firstName") String newFirstName, @Bind("lastName") String newLastName, @Bind("id") long id) throws SQLException;
    
    // Or define it in nested class and then annotated by field name
    public interface UserDao extends JdbcUtil.CrudDao<User, Long, SQLBuilder.PSC, UserDao>, JdbcUtil.JoinEntityHelper<User, SQLBuilder.PSC, UserDao> {
        ...
        @Select(id = "sql_listToSet")
        Set<User> listToSet(int id) throws SQLException;

        static final class SqlTable {
            @SqlField
            static final String sql_listToSet = PSC.selectFrom(User.class).where(CF.gt("id")).sql();
        }
    }

    // Or define it in xml file and then annotated by id. Refer to : ./schema/SQLMapper.xsd
    <sqlMapper>
        <sql id="sql_listToSet", fetchSize = 10>select first_name, last_name from user where id = ?</sql>
    </sqlMapper>
    
    static final UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource, sqlMapper);
    
    // Here I would suggest putting the sql scripts in the closest place where it will be executed.
```

* How to execute sql scripts:

```java
    String query = "select first_name, last_name from account where id = ?";
    
    1) By Prepared query.
    JdbcUtil.prepareQuery(query).setLong(1, id).findOnlyOne(Account.class); // or .findFirst/list/stream...
    
    2) By Dao method
    @Select("select first_name, last_name from account where id = ?")
    Optional<Account> selectNameById(int id) throws SQLException;
    
    accountDao.selectNameById(id);
```

* How about dynamic sql scripts:

```java
    // Dao interfaces provides tens of methods for most used daily query.
    accountDao.get(id, N.asList("firstName", "lastName"));
    accountDao.deleteById(id);
    ...
    
    // you can also use SQLBuilder and DynamicSQLBuilder to composite sql scripts.
```

* How to set parameters by Entity or map:

```java
    // By default, the built-in methods in Dao interfaces already support entity/Map parameters. 
    accountDao.update(account);
    accountDao.update(updatePropMap, id);
    ...
    
    // Use NamedQurey
    String sql = NSC.update(Account.class).set(N.asList("firstName, "lastName")).where(CF.eq("id")).sql();
    JdbcUtil.prepareNamedQuery(sql).setParameters(account).update();
```

* What's the best way to extract query result:

```java
    // To extract single result(single column)
    JdbcUtil.prepareQuery(sql).setParameters(...).queryForInt/Long/String/SingleResult/...  
    
    // To extract one row.
    JdbcUtil.prepareQuery(sql).setParameters(...).findFirst/firstOnlyOne(targetEntityClass/rowMapper/biRowMapper...);
    
    // To list/stream
    JdbcUtil.prepareQuery(sql).setParameters(...).list/stream(targetEntityClass/rowMapper/biRowMapper...);
    
    // general query by DataSet
    JdbcUtil.prepareQuery(sql).setParameters(...).query();
    
    // Merge result.
    String sql = "select user.id, first_name, last_name, device.id \"devices.id\", device.model \"devices.model\" from user left join device on user.id = device.user_id;
    List<User> userWithDevices = JdbcUtil.prepareQuery(sql).setParameters(...).query().toMergedEntities(User.class);
    
    @Data
    public class User {
    	private int id;
    	private String firstName;
    	private String lastName;
    	List<Device> devices;
    }
    
    @Data
    public class Device {
    	private int id;
    	private String model;
    }
```

* More samples/questions? 
    take a look at the samples ./samples/com.landawn.abacus.samples/...


## Download/Installation & [Changes](https://github.com/landawn/abacus-jdbc/blob/master/CHANGES.md):

* [Maven](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.landawn%22)

* Gradle:
```gradle
// JDK 1.8 or above:
compile 'com.landawn:abacus-jdbc:3.2.19'
```

## User Guide:
* [Introduction to JDBC](https://www.javacodegeeks.com/2015/02/jdbc-tutorial.html)
* [Programming in JDBC/DB with JdbcUtil/PreparedQuery/SQLExecutor/Mapper/Dao](https://github.com/landawn/abacus-jdbc/wiki/Programming-in-RDBMS-with-Jdbc,-PreparedQuery,-SQLExecutor,-Mapper-and-Dao).
* [More samples](https://github.com/landawn/abacus-jdbc/tree/master/samples/com/landawn/abacus/samples)

## Also See: [abacus-common](https://github.com/landawn/abacus-common), [abacus-entity-manager](https://github.com/landawn/abacus-entity-manager).

## Recommended Java programming libraries/frameworks:
[lombok](https://github.com/rzwitserloot/lombok), 
[Jinq](https://github.com/my2iu/Jinq), 
[jdbi](https://github.com/jdbi/jdbi), 
[Mybatis](https://github.com/mybatis/mybatis-3), 
[Sharding-JDBC](https://github.com/apache/incubator-shardingsphere),
[mapstruct](https://github.com/mapstruct/mapstruct)...[awesome-java](https://github.com/akullpp/awesome-java#database)


## Recommended Java programming tools:
[Spotbugs](https://github.com/spotbugs/spotbugs), [JaCoCo](https://www.eclemma.org/jacoco/)...
