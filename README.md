# abacus-jdbc

[![Maven Central](https://img.shields.io/maven-central/v/com.landawn/abacus-jdbc.svg)](https://maven-badges.herokuapp.com/maven-central/com.landawn/abacus-jdbc/)
[![Javadocs](https://img.shields.io/badge/javadoc-3.10.9-brightgreen.svg)](https://www.javadoc.io/doc/com.landawn/abacus-jdbc/3.10.9/index.html)

Experience the simplicity of coding with SQL/DB as if you're working with Collections.

## Features:

This library focuses on three main aspects:

*  Writing/Generating `SQL Scripts`(if needed): [SQLBuilder](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/SQLBuilder_view.html), 
[DynamicSQLBuilder](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/DynamicSQLBuilder_view.html).

```java
// Manually write the sql in plain string.
String query = "SELECT id, first_name, last_name, email FROM user WHERE first_Name = ?";

// Or by SQLBuilder
String query = PSC.select("id", "firstName, "lastName", "email").from(User.class).where(CF.eq("firstName")).sql();
// Or if select all columns from user:
String query = PSC.selectFrom(User.class).where(CF.eq("firstName")).sql();

// Sql scripts can also be placed in sql mapper xml file and then associated with a DAO object.
UserDao userDao =  JdbcUtil.createDao(UserDao.class, dataSource, sqlMapper);
```
`userSqlMapper.xml`
```xml
<?xml version="1.0" encoding="UTF-8"?>
<sqlMapper>
	<sql id="selectUserByFirstName">SELECT id, first_name, last_name, email FROM user WHERE first_Name = ?</sql>
</sqlMapper>
```

<br />

*  Preparing `Statements` [PreparedQuery](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/PreparedQuery_view.html), 
[NamedQuery](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/NamedQuery_view.html), 
[CallableQuery](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/CallableQuery_view.html) with a `sql` or `Dao` mapped with `sqls`.

```java
// sql can be used to create PreparedQuery/NamedQuery/CallableQuery
PreparedQuery preparedQuery = JdbcUtil.prepareQuery(dataSource, query...); 
			            //.prepareQuery(connection, query...)		
			            //.prepareNamedQuery(dataSource, namedQuery...)									   
			            //.prepareCallableQuery(dataSource, query...)									   
			            //....	
				      .setString(1, fistName) // Firstly set query parameters, if needed.
				    //.setLong(paramName, paramValue) // set named parameters for NamedQuery/CallableQuery.
				    //.setParameters(entity) // set named parameters by entity with getter/setter methods
				    //.setParameters(Map<String, ?>) // set named parameters by Map
				    //.setParameters(param1, param2...) // set several parameters in one line.
				    //.setParameters(Collection<?> parameters) // set parameters with a Collection.
				    //.setParameters(ParametersSetter parametersSetter) // set parameters by functional interface. 
				    //....  									   
																		   

// Sql can also be associated to a self-defined DAO method. (There are tens of most used predefined methods in DAO interfaces which be used without write single line of code).
public interface UserDao extends CrudDao<User, Long, SQLBuilder.PSC, UserDao>, JoinEntityHelper<User, SQLBuilder.PSC, UserDao> {
    // This is just a sample. Normally there are pre-defined methods available for this query: userDao.list(Condition cond).
    // Methods defined in Dao interface don't require implementation. Of course, Customized implemnetation is also supported by default method.
    @Select(sql = "SELECT id, first_name, last_name, email FROM user WHERE first_Name = ?")
    List<User> selectUserByFirstName(String firstName) throws SQLException;
    
    // Or id of the sql script defined in xml mapper file.
    @Select(id = "selectUserByFirstName")
    List<User> selectUserByFirstName(String firstName) throws SQLException;

    // Or id of the sql script defined in below nested static class.
    // Instead of writing sql scripts manually, you can also use SQLBuilder/DynamicSQLBuilder to write sql scripts.
    @Select(id = "selectUserByFirstName")
    List<User> selectUserByFirstName(String firstName) throws SQLException;
    
    // Multiple updates within transaction.
    @Transactional
    @Sqls({ "update user set first_name = ? where id = ?", 
            "update user set last_name = ? where id = :id" })
    default void updateFirstNameLastNameByIds(long idForUpdateFirstName, long idForUpdateLastName, String... sqls) throws SQLException { // Last parameter must be String[]. It will be automatically filled with sqls in @Sql.
        prepareQuery(sqls[0]).setLong(1, idForUpdateFirstName).update();
        prepareNamedQuery(sqls[1]).setLong(1, idForUpdateLastName).update();
    }

    // Refer classes in package com.landawn.abacus.jdbc.annotation for more supported annations
    @Select(sql = "SELECT * FROM {tableName} where id = :id ORDER BY {{orderBy}}")
    User selectByIdWithDefine(@Define("tableName") String tableName, @Define("{{orderBy}}") String orderBy, @Bind("id") long id);

    static final class SqlTable {
        @SqlField
        static final String selectUserByFirstName = PSC.select("id", "firstName, "lastName", "email").from(User.class).where(CF.eq("first")).sql();
    }
}

UserDao userDao =  JdbcUtil.createDao(UserDao.class, dataSource, ...);
```
<br />

* Calling methods in the prepared `statement/query` or `Dao` and retrieve the result(If needed):
[Dao](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/Dao_view.html)/[CrudDao](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/CrudDao_view.html)/[JoinEntityHelper](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/JoinEntityHelper_view.html), 
[Jdbc](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/Jdbc_view.html),
[Dataset](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/Dataset_view.html), 
[ConditionFactory(CF)](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/ConditionFactory_view.html), 
[JdbcUtil](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/JdbcUtil_view.html),
[JdbcUtils](https://htmlpreview.github.io/?https://github.com/landawn/abacus-jdbc/blob/master/docs/JdbcUtils_view.html).

```java
// Execute the sql by a PreparedQuery/NamedQuery/CallableQuery
preparedQuery.findFirst()
           //.findFirst(User.class)/findFirst(rowMapper)/...
           //.findOnlyOne()/findOnlyOne(User.class)/findOnlyOne(rowMapper)/...
           //.list()/list(User.class)/list(rowMapper)/...
           //.stream()/stream(User.class)/stream(rowMapper)/...
	   //.query()/qurey(resultExtractor)/queryForInt/queryForString/queryForSingleResult/...
           //.exists()/ifExists(rowConsumer)
	   //.update/batchUpdate/execute/...
																		   

// Sql can also be executed by directly calling DAO methods.
userDao.selectUserByFirstName(firstName)
     //.updateFirstNameLastNameByIds(100, 101)
     //.findFirst(Condition)
     //.findOnlyOne(Condition)
     //.list(Condition)
     //.stream(Condition)
     //.update(user)/deleteById(userId)/batchInsert(Collection<User>)/...

```
<br />


* Code Generation: [CodeGenerationUtil](https://www.javadoc.io/doc/com.landawn/abacus-common/latest/com/landawn/abacus/util/CodeGenerationUtil.html), [JdbcCodeGenerationUtil](https://www.javadoc.io/doc/com.landawn/abacus-jdbc/latest/com/landawn/abacus/jdbc/JdbcCodeGenerationUtil.html).

## Why abacus-jdbc?

The biggest difference between this library and other data(database) access frameworks is the simplicity/consistency/integrity in the APIs design.

<br />

## Download/Installation & [Changes](https://github.com/landawn/abacus-jdbc/blob/master/CHANGES.md):

* [Maven](https://central.sonatype.com/artifact/com.landawn/abacus-jdbc)

```xml
<dependency>
	<groupId>com.landawn</groupId>
	<artifactId>abacus-jdbc</artifactId>
	<version>3.10.9</version> 
<dependency>
```

* Gradle:

```gradle
// JDK 17 or above:
compile 'com.landawn:abacus-jdbc:3.10.9'
```

## User Guide:
* [Introduction to JDBC](https://www.javacodegeeks.com/2015/02/jdbc-tutorial.html)
* [More samples/questions(?)](https://github.com/landawn/abacus-jdbc/tree/master/samples/com/landawn/abacus/samples)

## Also See: [abacus-common](https://github.com/landawn/abacus-common), [abacus-entity-manager](https://github.com/landawn/abacus-entity-manager).

## Recommended Java programming libraries/frameworks:
[lombok](https://github.com/rzwitserloot/lombok), 
[Apache Druid](https://github.com/apache/druid), 
[Sharding-JDBC](https://github.com/apache/incubator-shardingsphere)
... [awesome-java](https://github.com/akullpp/awesome-java#database)


## Recommended Java programming tools:
[Spotbugs](https://github.com/spotbugs/spotbugs), [JaCoCo](https://www.eclemma.org/jacoco/)...
