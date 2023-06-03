# abacus-jdbc

[![Maven Central](https://img.shields.io/maven-central/v/com.landawn/abacus-jdbc.svg)](https://maven-badges.herokuapp.com/maven-central/com.landawn/abacus-jdbc/)
[![Javadocs](https://img.shields.io/badge/javadoc-3.3.6-brightgreen.svg)](https://www.javadoc.io/doc/com.landawn/abacus-jdbc/3.3.6/index.html)

Hope it will bring you the programming experiences: coding with SQL/DB is just like coding with Collections.

## Features:

This library is just about three things:

*  How to write/generate a `sql script`(if needed): [SQLBuilder](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/SQLBuilder_view.html), 
[DynamicSQLBuilder](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/DynamicSQLBuilder_view.html).

```java
// Manually write the sql in plain string.
String query = "SELECT id, first_name, last_name, email FROM user WHERE first_Name = ?";

// Or by SQLBuilder
String query = PSC.select("id", "firstName, "lastName", "email").from(User.class).where(CF.eq("firstName")).sql();
// Or if select all columns from user:
String query = PSC.selectFrom(User.class).where(CF.eq("firstName")).sql();

// Sql scripts can also placed in sql mapper xml file and then associated with a DAO object. See JdbcUtil.createDao(...) 
```
<br />

*  How to create a [PreparedQuery](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/PreparedQuery_view.html), 
[NamedQuery](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/NamedQuery_view.html), 
[PreparedCallableQuery](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/PreparedCallableQuery_view.html) with a `sql`.

```java
// sql can be used to create PreparedQuery/NamedQuery/PreparedCallableQuery
PreparedQuery preparedQuery = JdbcUtil.prepareQuery(dataSource, query...); 
			            //.prepareQuery(connection, query...)		
			            //.prepareNamedQuery(dataSource, namedQuery...)									   
			            //.prepareCallableQuery(dataSource, query...)									   
			            //....										   
																		   

// It can also associated to a self-defined DAO method. (There are tens of most used predefined methods in DAO interfaces which be used without write single line of code).
public interface UserDao extends JdbcUtil.CrudDao<User, Long, SQLBuilder.PSC, UserDao>, JdbcUtil.JoinEntityHelper<User, SQLBuilder.PSC, UserDao> {
    // This is just a sample. Normally there are pre-defined methods available for this query: userDao.list(Condition cond).
    @Select(sql = "SELECT id, first_name, last_name, email FROM user WHERE first_Name = ?")
    List<User> selectUserByFirstName(String firstName) throws SQLException;
    
    // Or id of the sql script defined in xml mapper file.
    @Select(id = "selectUserByFirstName")
    List<User> selectUserByFirstName(String firstName) throws SQLException;

    // Or id of the sql script defined in below nested static class.
    // Instead of writing sql scripts manually, you can also use SQLBuilder/DynamicSQLBuilder to write sql scripts.
    @Select(id = "selectUserByFirstName")
    List<User> selectUserByFirstName(String firstName) throws SQLException;

    static final class SqlTable {
        @SqlField
        static final String selectUserByFirstName = PSC.select("id", "firstName, "lastName", "email").from(User.class).where(CF.eq("first")).sql();
    }
}

```
<br />

*  How to execute a sql and retrieve the result(If needed):
[Dao](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/Dao_view.html)/[CrudDao](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/CrudDao_view.html)/[JoinEntityHelper](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/JoinEntityHelper_view.html), 
[Jdbc](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/Jdbc_view.html),
[DataSet](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/DataSet_view.html), 
[ConditionFactory(CF)](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/ConditionFactory_view.html), 
[JdbcUtil](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/JdbcUtil_view.html),
[JdbcUtils](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/JdbcUtils_view.html).

```java
// Execute the sql script by a PreparedQuery/NamedQuery/PreparedCallableQuery
preparedQuery.setString(1, fistName) // Firstly set query parameters, if needed.
               //.setLong(paramName, paramValue) // set named parameters for NamedQuery or PreparedCallableQuery.
               //.setParameters(entity) // set named parameters by entity with getter/setter methods
               //.setParameters(Map<String, ?>) // set named parameters by Map
               //.setParameters(param1, param2...) // set several parameters in one line.
               //.setParameters(Collection<?> parameters)
               //.setParameters(ParametersSetter parametersSetter) // set parameters by functional interface. 
               //....  
               findFirst()
               //.findFirst(rowMapper)
               //.findOnlyOne()
               //.list()
               //.stream()
               //.exists()/ifExists(rowConsumer)/query/update/batchUpdate/execute/...
																		   
 

// Sql script can also be executed by directly calling DAO methods.
userDao.selectUserByFirstName(firstName)
         //.findFirst(Condition)
         //.findOnlyOne(Condition)
         //.list(Condition)
         //.stream(Condition)
         //.update(user)/deleteById(userId)/batchInsert(Collection<User>)/...

```
<br />


* Questions? Please take a look at the samples in: `./samples/com.landawn.abacus.samples/...`


## Download/Installation & [Changes](https://github.com/landawn/abacus-jdbc/blob/master/CHANGES.md):

* [Maven](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22com.landawn%22)

* Gradle:
```gradle
// JDK 1.8 or above:
compile 'com.landawn:abacus-jdbc:3.3.6'
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
