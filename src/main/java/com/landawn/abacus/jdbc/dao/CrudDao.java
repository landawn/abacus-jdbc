/*
 * Copyright (c) 2021, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.landawn.abacus.jdbc.dao;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.jdbc.AbstractQuery;
import com.landawn.abacus.jdbc.IsolationLevel;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.SQLTransaction;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.query.QueryUtil;
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.query.condition.ConditionFactory;
import com.landawn.abacus.query.condition.ConditionFactory.CF;
import com.landawn.abacus.util.Beans;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Seid;
import com.landawn.abacus.util.Seq;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.u.OptionalBoolean;
import com.landawn.abacus.util.u.OptionalByte;
import com.landawn.abacus.util.u.OptionalChar;
import com.landawn.abacus.util.u.OptionalDouble;
import com.landawn.abacus.util.u.OptionalFloat;
import com.landawn.abacus.util.u.OptionalInt;
import com.landawn.abacus.util.u.OptionalLong;
import com.landawn.abacus.util.u.OptionalShort;
import com.landawn.abacus.util.stream.Stream.StreamEx;

/**
 * The CrudDao interface provides comprehensive CRUD (Create, Read, Update, Delete) operations for entity management.
 * This interface is designed to work with entity classes that have an ID field annotated with {@code @Id}.
 * 
 * <p>The interface supports batch operations, unique result queries, and various data type conversions.
 * It extends the base {@link Dao} interface and adds entity-specific CRUD functionality.</p>
 * 
 * <p>Usage example:</p>
 * <pre>{@code
 * public interface UserDao extends CrudDao<User, Long, SQLBuilder.PSC, UserDao> {
 *     // Custom query methods can be added here
 * }
 * 
 * // Usage
 * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
 * User user = new User("John", "Doe");
 * Long id = userDao.insert(user);
 * Optional<User> retrieved = userDao.get(id);
 * }</pre>
 *
 * @param <T> The entity type this DAO manages
 * @param <ID> The ID type of the entity. Use {@code Void} if there is no id defined/annotated with {@code @Id} in target entity class {@code T}
 * @param <SB> The SQLBuilder type used to generate SQL scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @param <TD> The self-type of the DAO for fluent interface support
 * 
 * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
 * @see Dao
 * @see com.landawn.abacus.annotation.JoinedBy
 * @see com.landawn.abacus.query.condition.ConditionFactory
 * @see com.landawn.abacus.query.condition.ConditionFactory.CF
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
public interface CrudDao<T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> extends Dao<T, SB, TD> {

    /**
     * Returns the functional interface of {@code Jdbc.BiRowMapper} that extracts the ID from a database row.
     * This mapper is used internally to extract ID values from query results.
     * 
     * <p>Override this method to provide a custom ID extractor if the default behavior doesn't suit your needs.</p>
     * 
     * <p>Example implementation:</p>
     * <pre>{@code
     * @Override
     * public Jdbc.BiRowMapper<Long> idExtractor() {
     *     return (rs, columnNames) -> rs.getLong("id");
     * }
     * }</pre>
     *
     * @return a BiRowMapper that extracts the ID from a row, or {@code null} to use default extraction
     */
    @SuppressWarnings("SameReturnValue")
    @NonDBOperation
    default Jdbc.BiRowMapper<ID> idExtractor() {
        return null;
    }

    /**
     * Generates a new ID for entity insertion.
     * 
     * <p>This method should be overridden by implementations that support ID generation.
     * Common use cases include generating UUIDs, using sequences, or other ID generation strategies.</p>
     * 
     * <p>Example implementation:</p>
     * <pre>{@code
     * @Override
     * public Long generateId() throws SQLException {
     *     return System.currentTimeMillis(); // Simple timestamp-based ID
     * }
     * }</pre>
     *
     * @return the generated ID
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the operation is not supported (default behavior)
     * @deprecated This operation is deprecated as ID generation should typically be handled by the database
     */
    @Deprecated
    @NonDBOperation
    default ID generateId() throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Inserts the specified entity into the database and returns the generated ID.
     * All non-null properties of the entity will be included in the INSERT statement.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User("John", "Doe", "john@example.com");
     * Long userId = userDao.insert(user);
     * System.out.println("Created user with ID: " + userId);
     * }</pre>
     *
     * @param entityToInsert the entity to insert (must not be null)
     * @return the ID of the inserted entity (either database-generated or entity-provided)
     * @throws SQLException if a database access error occurs or the entity is null
     */
    ID insert(final T entityToInsert) throws SQLException;

    /**
     * Inserts the specified entity with only the specified properties.
     * This is useful when you want to insert an entity with only certain fields populated.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setEmail("john@example.com");
     * user.setCreatedDate(new Date());
     * Long userId = userDao.insert(user, Arrays.asList("email", "createdDate"));
     * }</pre>
     *
     * @param entityToInsert the entity to insert (must not be null)
     * @param propNamesToInsert the property names to include in the INSERT statement.
     *                          If {@code null} or empty, all properties will be inserted
     * @return the ID of the inserted entity (either database-generated or entity-provided)
     * @throws SQLException if a database access error occurs
     */
    ID insert(final T entityToInsert, final Collection<String> propNamesToInsert) throws SQLException;

    /**
     * Inserts an entity using a custom named SQL insert statement.
     * The SQL should use named parameters that match the entity's property names.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "INSERT INTO users (name, email, status) VALUES (:name, :email, 'ACTIVE')";
     * User user = new User("John", "john@example.com");
     * Long userId = userDao.insert(sql, user);
     * }</pre>
     *
     * @param namedInsertSQL the named parameter SQL insert statement
     * @param entityToInsert the entity whose properties will be bound to the named parameters
     * @return the ID of the inserted entity (either database-generated or entity-provided)
     * @throws SQLException if a database access error occurs
     */
    ID insert(final String namedInsertSQL, final T entityToInsert) throws SQLException;

    /**
     * Performs batch insert of multiple entities using the default batch size.
     * This method is more efficient than inserting entities one by one.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = Arrays.asList(
     *     new User("John", "john@example.com"),
     *     new User("Jane", "jane@example.com")
     * );
     * List<Long> ids = userDao.batchInsert(users);
     * }</pre>
     *
     * @param entities the collection of entities to insert
     * @return a list of generated IDs in the same order as the input entities
     * @throws SQLException if a database access error occurs
     */
    default List<ID> batchInsert(final Collection<? extends T> entities) throws SQLException {
        return batchInsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch insert of multiple entities with a specified batch size.
     * Large collections will be processed in batches of the specified size.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> largeUserList = loadUsers(); // 10000 users
     * List<Long> ids = userDao.batchInsert(largeUserList, 1000); // Process in batches of 1000
     * }</pre>
     *
     * @param entities the collection of entities to insert
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of generated IDs in the same order as the input entities
     * @throws SQLException if a database access error occurs
     */
    List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws SQLException;

    /**
     * Performs batch insert with only specified properties for all entities.
     * Uses the default batch size.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = loadUsers();
     * // Only insert email and createdDate for all users
     * List<Long> ids = userDao.batchInsert(users, Arrays.asList("email", "createdDate"));
     * }</pre>
     *
     * @param entities the collection of entities to insert
     * @param propNamesToInsert the property names to include in the INSERT statement
     * @return a list of generated IDs in the same order as the input entities
     * @throws SQLException if a database access error occurs
     */
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert) throws SQLException {
        return batchInsert(entities, propNamesToInsert, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch insert with only specified properties and custom batch size.
     * This provides fine-grained control over both what fields are inserted and how the batch is processed.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> largeUserList = loadUsers(); // 20000 users
     * // Insert only name and email fields in batches of 1000
     * List<Long> ids = userDao.batchInsert(largeUserList, Arrays.asList("name", "email"), 1000);
     * }</pre>
     *
     * @param entities the collection of entities to insert
     * @param propNamesToInsert the property names to include in the INSERT statement
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of generated IDs in the same order as the input entities
     * @throws SQLException if a database access error occurs
     */
    List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize) throws SQLException;

    /**
     * Performs batch insert using a custom named SQL statement with default batch size.
     * This is useful for complex insert scenarios that require custom SQL.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "INSERT INTO users (name, email, status) VALUES (:name, :email, 'PENDING')";
     * List<User> users = loadPendingUsers();
     * List<Long> ids = userDao.batchInsert(sql, users);
     * }</pre>
     *
     * @param namedInsertSQL the named parameter SQL insert statement
     * @param entities the collection of entities whose properties will be bound to the named parameters
     * @return a list of generated IDs in the same order as the input entities
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities) throws SQLException {
        return batchInsert(namedInsertSQL, entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch insert using a custom named SQL statement with specified batch size.
     * Combines custom SQL flexibility with batch processing efficiency.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "INSERT INTO users (name, email, status, created_date) " +
     *              "VALUES (:name, :email, 'ACTIVE', CURRENT_TIMESTAMP)";
     * List<User> largeUserList = loadNewUsers(); // 15000 users
     * List<Long> ids = userDao.batchInsert(sql, largeUserList, 1000);
     * }</pre>
     *
     * @param namedInsertSQL the named parameter SQL insert statement
     * @param entities the collection of entities whose properties will be bound to the named parameters
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of generated IDs in the same order as the input entities
     * @throws SQLException if a database access error occurs
     */
    @Beta
    List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities, final int batchSize) throws SQLException;

    /**
     * Queries for a boolean value from a single property of the entity with the specified ID.
     * Returns an empty OptionalBoolean if no record is found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalBoolean isActive = userDao.queryForBoolean("isActive", userId);
     * if (isActive.isPresent() && isActive.getAsBoolean()) {
     *     System.out.println("User is active");
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an OptionalBoolean containing the value if found, otherwise empty
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForBoolean()
     */
    OptionalBoolean queryForBoolean(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a char value from a single property of the entity with the specified ID.
     * Returns an empty OptionalChar if no record is found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalChar grade = studentDao.queryForChar("grade", studentId);
     * grade.ifPresent(g -> System.out.println("Student grade: " + g));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an OptionalChar containing the value if found, otherwise empty
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForChar()
     */
    OptionalChar queryForChar(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a byte value from a single property of the entity with the specified ID.
     * Returns an empty OptionalByte if no record is found.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalByte flags = entityDao.queryForByte("flags", entityId);
     * byte flagValue = flags.orElse((byte) 0);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an OptionalByte containing the value if found, otherwise empty
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForByte()
     */
    OptionalByte queryForByte(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a short value from a single property of the entity with the specified ID.
     * Returns an empty OptionalShort if no record is found.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalShort port = serverDao.queryForShort("port", serverId);
     * short portNumber = port.orElse((short) 8080);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an OptionalShort containing the value if found, otherwise empty
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForShort()
     */
    OptionalShort queryForShort(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for an integer value from a single property of the entity with the specified ID.
     * Returns an empty OptionalInt if no record is found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalInt age = userDao.queryForInt("age", userId);
     * int userAge = age.orElse(0);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an OptionalInt containing the value if found, otherwise empty
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForInt()
     */
    OptionalInt queryForInt(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a long value from a single property of the entity with the specified ID.
     * Returns an empty OptionalLong if no record is found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalLong lastLoginTime = userDao.queryForLong("lastLoginTimestamp", userId);
     * lastLoginTime.ifPresent(time -> System.out.println("Last login: " + new Date(time)));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an OptionalLong containing the value if found, otherwise empty
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForLong()
     */
    OptionalLong queryForLong(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a float value from a single property of the entity with the specified ID.
     * Returns an empty OptionalFloat if no record is found.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalFloat rating = productDao.queryForFloat("rating", productId);
     * float productRating = rating.orElse(0.0f);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an OptionalFloat containing the value if found, otherwise empty
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForFloat()
     */
    OptionalFloat queryForFloat(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a double value from a single property of the entity with the specified ID.
     * Returns an empty OptionalDouble if no record is found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalDouble balance = accountDao.queryForDouble("balance", accountId);
     * double amount = balance.orElse(0.0);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an OptionalDouble containing the value if found, otherwise empty
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForDouble()
     */
    OptionalDouble queryForDouble(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a String value from a single property of the entity with the specified ID.
     * Returns a Nullable containing the value, which can be {@code null} if the database value is {@code null}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<String> email = userDao.queryForString("email", userId);
     * String userEmail = email.orElse("no-email@example.com");
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return a Nullable containing the String value if found, or Nullable.empty() if no record exists
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForString()
     */
    Nullable<String> queryForString(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a Date value from a single property of the entity with the specified ID.
     * Returns a Nullable containing the value, which can be {@code null} if the database value is {@code null}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Date> birthDate = userDao.queryForDate("birthDate", userId);
     * birthDate.ifPresent(date -> System.out.println("Birth date: " + date));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return a Nullable containing the Date value if found, or Nullable.empty() if no record exists
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForDate()
     */
    Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a Time value from a single property of the entity with the specified ID.
     * Returns a Nullable containing the value, which can be {@code null} if the database value is {@code null}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Time> startTime = scheduleDao.queryForTime("startTime", scheduleId);
     * startTime.ifPresent(time -> System.out.println("Start time: " + time));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return a Nullable containing the Time value if found, or Nullable.empty() if no record exists
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForTime()
     */
    Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a Timestamp value from a single property of the entity with the specified ID.
     * Returns a Nullable containing the value, which can be {@code null} if the database value is {@code null}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<Timestamp> lastModified = userDao.queryForTimestamp("lastModified", userId);
     * lastModified.ifPresent(ts -> System.out.println("Last modified: " + ts));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return a Nullable containing the Timestamp value if found, or Nullable.empty() if no record exists
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForTimestamp()
     */
    Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a byte array value from a single property of the entity with the specified ID.
     * Returns a Nullable containing the value, which can be {@code null} if the database value is {@code null}.
     * This is typically used for BLOB data.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<byte[]> avatar = userDao.queryForBytes("avatarImage", userId);
     * avatar.ifPresent(bytes -> saveImageToFile(bytes));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return a Nullable containing the byte array value if found, or Nullable.empty() if no record exists
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForBytes()
     */
    Nullable<byte[]> queryForBytes(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a single value of the specified type from a property of the entity with the specified ID.
     * This is a generic method that can handle any type conversion supported by the underlying JDBC driver.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<BigDecimal> salary = userDao.queryForSingleResult("salary", userId, BigDecimal.class);
     * Nullable<UserStatus> status = userDao.queryForSingleResult("status", userId, UserStatus.class);
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @param targetValueType the class of the value type to convert to
     * @return a Nullable containing the value if found, or Nullable.empty() if no record exists
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForSingleResult(Class)
     */
    <V> Nullable<V> queryForSingleResult(final String singleSelectPropName, final ID id, final Class<? extends V> targetValueType) throws SQLException;

    /**
     * Queries for a single non-null value of the specified type from a property of the entity.
     * Returns an empty Optional if no record is found or if the value is {@code null}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<String> username = userDao.queryForSingleNonNull("username", userId, String.class);
     * username.ifPresent(name -> System.out.println("Username: " + name));
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @param targetValueType the class of the value type to convert to
     * @return an Optional containing the non-null value if found, otherwise empty
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForSingleNonNull(Class)
     */
    <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final ID id, final Class<? extends V> targetValueType) throws SQLException;

    /**
     * Queries for a single non-null value using a custom row mapper.
     * This allows for complex transformations of the result.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<String> fullName = userDao.queryForSingleNonNull("firstName", userId, 
     *     (rs, columnNames) -> rs.getString(1).toUpperCase());
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @param rowMapper the custom mapper to transform the result
     * @return an Optional containing the mapped non-null value if found, otherwise empty
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForSingleNonNull(Class)
     */
    @Beta
    <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final ID id, final Jdbc.RowMapper<? extends V> rowMapper) throws SQLException;

    /**
     * Queries for a unique single result of the specified type.
     * Throws DuplicatedResultException if more than one record is found.
     * 
     * <p>This method ensures that at most one record matches the query.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<String> email = userDao.queryForUniqueResult("email", userId, String.class);
     * // Throws DuplicatedResultException if multiple records found
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @param targetValueType the class of the value type to convert to
     * @return a Nullable containing the unique value if found, or Nullable.empty() if no record exists
     * @throws DuplicatedResultException if more than one record found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForUniqueResult(Class)
     */
    <V> Nullable<V> queryForUniqueResult(final String singleSelectPropName, final ID id, final Class<? extends V> targetValueType)
            throws DuplicatedResultException, SQLException;

    /**
     * Queries for a unique non-null result of the specified type.
     * Throws DuplicatedResultException if more than one record is found.
     * Returns empty Optional if no record found or value is {@code null}.
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @param targetValueType the class of the value type to convert to
     * @return an Optional containing the unique non-null value if found, otherwise empty
     * @throws DuplicatedResultException if more than one record found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForUniqueNonNull(Class)
     */
    <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final ID id, final Class<? extends V> targetValueType)
            throws DuplicatedResultException, SQLException;

    /**
     * Queries for a unique non-null result using a custom row mapper.
     * Throws DuplicatedResultException if more than one record is found.
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @param rowMapper the custom mapper to transform the result
     * @return an Optional containing the mapped unique non-null value if found, otherwise empty
     * @throws DuplicatedResultException if more than one record found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForUniqueNonNull(Class)
     */
    @Beta
    <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final ID id, final Jdbc.RowMapper<? extends V> rowMapper)
            throws DuplicatedResultException, SQLException;

    /**
     * Retrieves an entity by its ID.
     * Returns an Optional containing the entity if found, otherwise empty.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<User> user = userDao.get(userId);
     * user.ifPresent(u -> System.out.println("Found user: " + u.getName()));
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @return an Optional containing the entity if found, otherwise empty
     * @throws DuplicatedResultException if more than one record found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    default Optional<T> get(final ID id) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id));
    }

    /**
     * Retrieves an entity by its ID with only selected properties populated.
     * Properties not in the select list will have their default values.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Only load id, name, and email fields
     * Optional<User> user = userDao.get(userId, Arrays.asList("id", "name", "email"));
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @param selectPropNames the properties to select, excluding properties of joining entities. 
     *                        All properties will be selected if null
     * @return an Optional containing the entity if found, otherwise empty
     * @throws DuplicatedResultException if more than one record found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    default Optional<T> get(final ID id, final Collection<String> selectPropNames) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, selectPropNames));
    }

    /**
     * Retrieves an entity by its ID, returning {@code null} if not found.
     * This is a convenience method that returns the entity directly instead of wrapped in Optional.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * if (user != null) {
     *     System.out.println("Found user: " + user.getName());
     * }
     * }</pre>
     *
     * @param id the entity ID to retrieve
     * @return the entity if found, otherwise null
     * @throws DuplicatedResultException if more than one record found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    T gett(final ID id) throws DuplicatedResultException, SQLException;

    /**
     * Retrieves an entity by its ID with only selected properties populated, returning {@code null} if not found.
     * This is useful for performance optimization when you only need specific fields.
     *
     * @param id the entity ID to retrieve
     * @param selectPropNames the properties to select, excluding properties of joining entities. 
     *                        All properties will be selected if null
     * @return the entity if found, otherwise null
     * @throws DuplicatedResultException if more than one record found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    T gett(final ID id, final Collection<String> selectPropNames) throws DuplicatedResultException, SQLException;

    /**
     * Retrieves multiple entities by their IDs using the default batch size.
     * The returned list may be smaller than the input ID collection if some entities are not found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> userIds = Arrays.asList(1L, 2L, 3L);
     * List<User> users = userDao.batchGet(userIds);
     * }</pre>
     *
     * @param ids the collection of IDs to retrieve
     * @return a list of found entities
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    default List<T> batchGet(final Collection<? extends ID> ids) throws DuplicatedResultException, SQLException {
        return batchGet(ids, (Collection<String>) null);
    }

    /**
     * Retrieves multiple entities by their IDs with a specified batch size.
     * Large ID collections will be processed in batches to avoid database query size limits.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> largeIdList = getIdsFromReport(); // 50000 IDs
     * // Process in batches of 1000 to avoid SQL query size limits
     * List<User> users = userDao.batchGet(largeIdList, 1000);
     * }</pre>
     *
     * @param ids the collection of IDs to retrieve
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of found entities
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    default List<T> batchGet(final Collection<? extends ID> ids, final int batchSize) throws DuplicatedResultException, SQLException {
        return batchGet(ids, (Collection<String>) null, batchSize);
    }

    /**
     * Retrieves multiple entities by their IDs with only selected properties populated.
     * Uses the default batch size.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> userIds = Arrays.asList(1L, 2L, 3L);
     * List<User> users = userDao.batchGet(userIds, Arrays.asList("id", "name", "email"));
     * }</pre>
     *
     * @param ids the collection of IDs to retrieve
     * @param selectPropNames the properties to select, excluding properties of joining entities. 
     *                        All properties will be selected if null
     * @return a list of found entities
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames) throws DuplicatedResultException, SQLException {
        return batchGet(ids, selectPropNames, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Retrieves multiple entities by their IDs with only selected properties populated and custom batch size.
     * This provides the most control over batch retrieval operations.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> largeIdList = getAllUserIds(); // 30000 IDs
     * // Only fetch id, name, email in batches of 2000
     * List<User> users = userDao.batchGet(largeIdList,
     *                                     Arrays.asList("id", "name", "email"),
     *                                     2000);
     * }</pre>
     *
     * @param ids the collection of IDs to retrieve
     * @param selectPropNames the properties to select, excluding properties of joining entities.
     *                        All properties will be selected if null
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of found entities
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize)
            throws DuplicatedResultException, SQLException;

    /**
     * Checks if an entity with the specified ID exists in the database.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (userDao.exists(userId)) {
     *     System.out.println("User exists");
     * } else {
     *     System.out.println("User not found");
     * }
     * }</pre>
     *
     * @param id the entity ID to check for existence
     * @return {@code true} if an entity with the given ID exists, {@code false} otherwise
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#exists()
     */
    boolean exists(final ID id) throws SQLException;

    /**
     * Checks if an entity with the specified ID does not exist in the database.
     * This is a convenience method that negates the result of exists().
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (userDao.notExists(userId)) {
     *     // Create new user
     * }
     * }</pre>
     *
     * @param id the entity ID to check for non-existence
     * @return {@code true} if no entity with the given ID exists, {@code false} otherwise
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#notExists()
     */
    @Beta
    default boolean notExists(final ID id) throws SQLException {
        return !exists(id);
    }

    /**
     * Counts how many of the specified IDs exist in the database.
     * This is useful for validating bulk operations.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> userIds = Arrays.asList(1L, 2L, 3L, 4L, 5L);
     * int existingCount = userDao.count(userIds);
     * System.out.println(existingCount + " users exist out of " + userIds.size());
     * }</pre>
     *
     * @param ids the collection of IDs to count
     * @return the number of existing entities with the given IDs
     * @throws SQLException if a database access error occurs
     */
    @Beta
    int count(final Collection<? extends ID> ids) throws SQLException;

    /**
     * Updates an existing entity in the database.
     * All non-null properties of the entity will be updated.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * user.setEmail("newemail@example.com");
     * user.setLastModified(new Date());
     * int updatedRows = userDao.update(user);
     * }</pre>
     *
     * @param entityToUpdate the entity with updated values
     * @return the number of rows updated (typically 1 if successful, 0 if not found)
     * @throws SQLException if a database access error occurs
     */
    int update(final T entityToUpdate) throws SQLException;

    /**
     * Updates only specified properties of an existing entity.
     * This is useful when you want to update only certain fields.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setId(userId);
     * user.setEmail("newemail@example.com");
     * user.setLastModified(new Date());
     * // Only update email and lastModified fields
     * int rows = userDao.update(user, Arrays.asList("email", "lastModified"));
     * }</pre>
     *
     * @param entityToUpdate the entity containing the values to update
     * @param propNamesToUpdate the property names to update. If {@code null} or empty, all properties will be updated
     * @return the number of rows updated
     * @throws SQLException if a database access error occurs
     */
    int update(final T entityToUpdate, final Collection<String> propNamesToUpdate) throws SQLException;

    /**
     * Updates a single property of an entity identified by ID.
     * This is a convenience method for updating one field.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * userDao.update("lastLoginTime", new Date(), userId);
     * userDao.update("failedLoginAttempts", 0, userId);
     * }</pre>
     *
     * @param propName the property name to update
     * @param propValue the new value for the property
     * @param id the entity ID to update
     * @return the number of rows updated
     * @throws SQLException if a database access error occurs
     */
    default int update(final String propName, final Object propValue, final ID id) throws SQLException {
        final Map<String, Object> updateProps = new HashMap<>();
        updateProps.put(propName, propValue);

        return update(updateProps, id);
    }

    /**
     * Updates multiple properties of an entity identified by ID.
     * This allows updating multiple fields without loading the entire entity.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Map<String, Object> updates = new HashMap<>();
     * updates.put("status", "ACTIVE");
     * updates.put("lastModified", new Date());
     * updates.put("modifiedBy", currentUserId);
     * userDao.update(updates, userId);
     * }</pre>
     *
     * @param updateProps a map of property names to their new values
     * @param id the entity ID to update
     * @return the number of rows updated
     * @throws SQLException if a database access error occurs
     */
    int update(final Map<String, Object> updateProps, final ID id) throws SQLException;

    /**
     * Performs batch update of multiple entities using the default batch size.
     * All non-null properties of each entity will be updated.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = loadUsersToUpdate();
     * users.forEach(u -> u.setLastModified(new Date()));
     * int totalUpdated = userDao.batchUpdate(users);
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @return the total number of rows updated
     * @throws SQLException if a database access error occurs
     */
    default int batchUpdate(final Collection<? extends T> entities) throws SQLException {
        return batchUpdate(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch update of multiple entities with a specified batch size.
     * Large collections will be processed in batches of the specified size.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> largeUserList = loadUsers(); // 5000 users
     * largeUserList.forEach(u -> u.setLastModified(new Date()));
     * // Process in batches of 500
     * int totalUpdated = userDao.batchUpdate(largeUserList, 500);
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return the total number of rows updated
     * @throws SQLException if a database access error occurs
     */
    int batchUpdate(final Collection<? extends T> entities, final int batchSize) throws SQLException;

    /**
     * Performs batch update of multiple entities updating only specified properties.
     * Uses the default batch size.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = loadUsers();
     * users.forEach(u -> {
     *     u.setStatus("INACTIVE");
     *     u.setDeactivatedDate(new Date());
     * });
     * int rows = userDao.batchUpdate(users, Arrays.asList("status", "deactivatedDate"));
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @param propNamesToUpdate the property names to update for all entities
     * @return the total number of rows updated
     * @throws SQLException if a database access error occurs
     */
    default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate) throws SQLException {
        return batchUpdate(entities, propNamesToUpdate, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch update of multiple entities updating only specified properties with custom batch size.
     * This provides the most control over batch update operations.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = loadLargeUserList(); // 10000 users
     * users.forEach(u -> {
     *     u.setStatus("VERIFIED");
     *     u.setVerifiedDate(new Date());
     * });
     * // Update only status and verifiedDate in batches of 500
     * int rows = userDao.batchUpdate(users, Arrays.asList("status", "verifiedDate"), 500);
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @param propNamesToUpdate the property names to update for all entities
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return the total number of rows updated
     * @throws SQLException if a database access error occurs
     */
    int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize) throws SQLException;

    /**
     * Performs an upsert operation: inserts the entity if it doesn't exist based on ID fields, otherwise updates the existing entity.
     * If an entity with the same ID exists, it will be updated; otherwise, a new entity will be inserted.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setId(userId);
     * user.setName("John Doe");
     * user.setEmail("john@example.com");
     * User savedUser = userDao.upsert(user); // Insert if new, update if exists
     * }</pre>
     *
     * @param entity the entity to insert or update
     * @return the saved entity (either newly inserted or updated)
     * @throws SQLException if a database access error occurs
     */
    default T upsert(final T entity) throws SQLException {
        N.checkArgNotNull(entity, cs.entity);

        final Class<?> cls = entity.getClass();
        @SuppressWarnings("deprecation")
        final List<String> idPropNameList = QueryUtil.getIdFieldNames(cls); // must not empty.

        return upsert(entity, idPropNameList);
    }

    /**
     * Performs an upsert operation: inserts the entity if it doesn't exist based on the specified condition, otherwise updates the existing entity.
     * This allows for upsert logic based on any criteria, not just ID fields.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User("john@example.com", "John Doe");
     * // Upsert based on email instead of ID
     * User saved = userDao.upsert(user, CF.eq("email", user.getEmail()));
     * }</pre>
     *
     * @param entity the entity to insert or update
     * @param cond the condition to check if the entity exists
     * @return the saved entity (either newly inserted or updated)
     * @throws SQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @Override
    default T upsert(final T entity, final Condition cond) throws SQLException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotNull(cond, cs.cond);

        final T dbEntity = findOnlyOne(cond).orElseNull();

        if (dbEntity == null) {
            insert(entity);
            return entity;
        } else {
            final Class<?> cls = entity.getClass();
            @SuppressWarnings("deprecation")
            final List<String> idPropNameList = QueryUtil.getIdFieldNames(cls);
            Beans.merge(entity, dbEntity, false, N.newHashSet(idPropNameList));
            update(dbEntity);
            return dbEntity;
        }
    }

    /**
     * Performs batch upsert of multiple entities using the default batch size.
     * Each entity will be inserted if new or updated if it already exists based on ID fields.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = loadUsersFromImport();
     * List<User> savedUsers = userDao.batchUpsert(users);
     * }</pre>
     *
     * @param entities the collection of entities to upsert
     * @return a list of saved entities (both inserted and updated)
     * @throws SQLException if a database access error occurs
     */
    default List<T> batchUpsert(final Collection<? extends T> entities) throws SQLException {
        return batchUpsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch upsert of multiple entities with a specified batch size.
     * Large collections will be processed in batches of the specified size.
     *
     * @param entities the collection of entities to upsert
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of saved entities (both inserted and updated)
     * @throws SQLException if a database access error occurs
     */
    default List<T> batchUpsert(final Collection<? extends T> entities, final int batchSize) throws SQLException {
        N.checkArgPositive(batchSize, cs.batchSize);

        if (N.isEmpty(entities)) {
            return new ArrayList<>();
        }

        final T entity = N.firstOrNullIfEmpty(entities);
        final Class<?> cls = entity.getClass();
        @SuppressWarnings("deprecation")
        final List<String> idPropNameList = QueryUtil.getIdFieldNames(cls); // must not empty.

        return batchUpsert(entities, idPropNameList, batchSize);
    }

    /**
     * Performs batch upsert based on specified unique properties for matching.
     * This allows upsert logic based on properties other than the ID fields.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = loadUsers();
     * // Upsert based on email uniqueness
     * List<User> saved = userDao.batchUpsert(users, Arrays.asList("email"));
     * }</pre>
     *
     * @param entities the collection of entities to upsert
     * @param uniquePropNamesForQuery the property names that uniquely identify each entity
     * @return a list of saved entities (both inserted and updated)
     * @throws SQLException if a database access error occurs
     */
    default List<T> batchUpsert(final Collection<? extends T> entities, final List<String> uniquePropNamesForQuery) throws SQLException {
        return batchUpsert(entities, uniquePropNamesForQuery, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch upsert based on specified unique properties with custom batch size.
     * This provides the most flexibility for batch upsert operations.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> largeUserList = importUsers(); // 25000 users from external system
     * // Upsert based on email uniqueness in batches of 2000
     * List<User> saved = userDao.batchUpsert(largeUserList, Arrays.asList("email"), 2000);
     * }</pre>
     *
     * @param entities the collection of entities to upsert
     * @param uniquePropNamesForQuery the property names that uniquely identify each entity
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of saved entities (both inserted and updated)
     * @throws SQLException if a database access error occurs
     */
    default List<T> batchUpsert(final Collection<? extends T> entities, final List<String> uniquePropNamesForQuery, final int batchSize) throws SQLException {
        N.checkArgPositive(batchSize, cs.batchSize);
        N.checkArgNotEmpty(uniquePropNamesForQuery, cs.uniquePropNamesForQuery);

        if (N.isEmpty(entities)) {
            return new ArrayList<>();
        }

        @SuppressWarnings("UnnecessaryLocalVariable")
        final List<String> propNameListForQuery = uniquePropNamesForQuery;
        final T first = N.firstOrNullIfEmpty(entities);
        final Class<?> cls = first.getClass();
        final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);

        final PropInfo uniquePropInfo = entityInfo.getPropInfo(propNameListForQuery.get(0));
        final List<PropInfo> uniquePropInfos = N.map(propNameListForQuery, entityInfo::getPropInfo);

        final com.landawn.abacus.util.function.Function<T, Object> singleKeyExtractor = uniquePropInfo::getPropValue;

        @SuppressWarnings("deprecation")
        final com.landawn.abacus.util.function.Function<T, EntityId> entityIdExtractor = it -> {
            final Seid entityId = Seid.of(entityInfo.simpleClassName);

            for (final PropInfo propInfo : uniquePropInfos) {
                entityId.set(propInfo.name, propInfo.getPropValue(it));
            }

            return entityId;
        };

        final com.landawn.abacus.util.function.Function<T, ?> keysExtractor = propNameListForQuery.size() == 1 ? singleKeyExtractor : entityIdExtractor;

        final List<T> dbEntities = propNameListForQuery.size() == 1
                ? Seq.of(entities, SQLException.class)
                        .split(batchSize)
                        .flatmap(it -> list(CF.in(propNameListForQuery.get(0), N.map(it, singleKeyExtractor))))
                        .toList()
                : Seq.of(entities, SQLException.class) //
                        .split(batchSize)
                        .flatmap(it -> list(CF.id2Cond(N.map(it, entityIdExtractor))))
                        .toList();

        final Map<Object, T> dbIdEntityMap = StreamEx.of(dbEntities).toMap(keysExtractor, Fn.identity(), Fn.ignoringMerger());
        final Map<Boolean, List<T>> map = StreamEx.of(entities).groupTo(it -> dbIdEntityMap.containsKey(keysExtractor.apply(it)), Fn.identity());
        final List<T> entitiesToUpdate = map.get(true);
        final List<T> entitiesToInsert = map.get(false);

        final List<T> result = new ArrayList<>(entities.size());
        final SQLTransaction tran = N.notEmpty(entitiesToInsert) && N.notEmpty(entitiesToUpdate) ? JdbcUtil.beginTransaction(dataSource()) : null;

        try {
            if (N.notEmpty(entitiesToInsert)) {
                batchInsert(entitiesToInsert, batchSize);
                result.addAll(entitiesToInsert);
            }

            if (N.notEmpty(entitiesToUpdate)) {
                final Set<String> ignoredPropNames = N.newHashSet(propNameListForQuery);

                @SuppressWarnings("deprecation")
                final List<String> idPropNameList = QueryUtil.getIdFieldNames(cls);

                if (N.notEmpty(idPropNameList)) {
                    ignoredPropNames.addAll(idPropNameList);
                }

                final List<T> dbEntitiesToUpdate = StreamEx.of(entitiesToUpdate)
                        .map(it -> Beans.merge(it, dbIdEntityMap.get(keysExtractor.apply(it)), false, ignoredPropNames))
                        .toList();

                batchUpdate(dbEntitiesToUpdate, batchSize);

                result.addAll(dbEntitiesToUpdate);
            }

            if (tran != null) {
                tran.commit();
            }
        } finally {
            if (tran != null) {
                tran.rollbackIfNotCommitted();
            }
        }

        return result;
    }

    /**
     * Refreshes an entity by reloading all its properties from the database.
     * This is useful when you want to ensure an entity has the latest values from the database.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getCachedUser();
     * if (userDao.refresh(user)) {
     *     System.out.println("User refreshed with latest data");
     * } else {
     *     System.out.println("User no longer exists in database");
     * }
     * }</pre>
     *
     * @param entity the entity to refresh (must have ID populated)
     * @return {@code true} if the entity was successfully refreshed, {@code false} if not found
     * @throws SQLException if a database access error occurs
     */
    default boolean refresh(final T entity) throws SQLException {
        N.checkArgNotNull(entity, cs.entity);

        final Class<?> cls = entity.getClass();
        final Collection<String> propNamesToRefresh = JdbcUtil.getSelectPropNames(cls);

        return refresh(entity, propNamesToRefresh);
    }

    /**
     * Refreshes specific properties of an entity from the database.
     * Only the specified properties will be updated with database values.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getUser();
     * // Only refresh balance and status fields
     * userDao.refresh(user, Arrays.asList("balance", "status"));
     * }</pre>
     *
     * @param entity the entity to refresh (must have ID populated)
     * @param propNamesToRefresh the properties to refresh from the database
     * @return {@code true} if the entity was successfully refreshed, {@code false} if not found
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    default boolean refresh(final T entity, final Collection<String> propNamesToRefresh) throws SQLException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotEmpty(propNamesToRefresh, cs.propNamesToRefresh);

        final Class<?> cls = entity.getClass();
        final List<String> idPropNameList = QueryUtil.getIdFieldNames(cls); // must not empty.
        final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);

        final ID id = DaoUtil.extractId(entity, idPropNameList, entityInfo);
        final Collection<String> selectPropNames = DaoUtil.getRefreshSelectPropNames(propNamesToRefresh, idPropNameList);

        final T dbEntity = gett(id, selectPropNames);

        if (dbEntity == null) {
            return false;
        } else {
            Beans.merge(dbEntity, entity, propNamesToRefresh);

            return true;
        }
    }

    /**
     * Refreshes multiple entities from the database using the default batch size.
     * Returns the count of entities that were successfully refreshed.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getCachedUsers();
     * int refreshedCount = userDao.batchRefresh(users);
     * System.out.println(refreshedCount + " users refreshed");
     * }</pre>
     *
     * @param entities the collection of entities to refresh
     * @return the number of entities successfully refreshed
     * @throws SQLException if a database access error occurs
     */
    default int batchRefresh(final Collection<? extends T> entities) throws SQLException {
        return batchRefresh(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Refreshes multiple entities from the database with a specified batch size.
     * Large collections will be processed in batches of the specified size.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> cachedUsers = getCachedUsers(); // 10000 cached entities
     * int refreshedCount = userDao.batchRefresh(cachedUsers, 1000);
     * System.out.println(refreshedCount + " users refreshed from database");
     * }</pre>
     *
     * @param entities the collection of entities to refresh
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return the number of entities successfully refreshed
     * @throws SQLException if a database access error occurs
     */
    default int batchRefresh(final Collection<? extends T> entities, final int batchSize) throws SQLException {
        if (N.isEmpty(entities)) {
            return 0;
        }

        final T first = N.firstOrNullIfEmpty(entities);
        final Class<?> cls = first.getClass();
        final Collection<String> propNamesToRefresh = JdbcUtil.getSelectPropNames(cls);

        return batchRefresh(entities, propNamesToRefresh, batchSize);
    }

    /**
     * Refreshes specific properties of multiple entities using the default batch size.
     * Only the specified properties will be updated with database values.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getCachedUsers();
     * // Only refresh balance and lastModified for all users
     * int count = userDao.batchRefresh(users, Arrays.asList("balance", "lastModified"));
     * }</pre>
     *
     * @param entities the collection of entities to refresh
     * @param propNamesToRefresh the properties to refresh from the database
     * @return the number of entities successfully refreshed
     * @throws SQLException if a database access error occurs
     */
    default int batchRefresh(final Collection<? extends T> entities, final Collection<String> propNamesToRefresh) throws SQLException {
        return batchRefresh(entities, propNamesToRefresh, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Refreshes specific properties of multiple entities with a custom batch size.
     * This provides the most control over batch refresh operations.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> cachedUsers = getCachedUsers(); // 8000 cached entities
     * // Refresh only balance and status fields in batches of 800
     * int count = userDao.batchRefresh(cachedUsers,
     *                                  Arrays.asList("balance", "status"),
     *                                  800);
     * }</pre>
     *
     * @param entities the collection of entities to refresh
     * @param propNamesToRefresh the properties to refresh from the database
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return the number of entities successfully refreshed
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    default int batchRefresh(final Collection<? extends T> entities, final Collection<String> propNamesToRefresh, final int batchSize) throws SQLException {
        N.checkArgNotEmpty(propNamesToRefresh, cs.propNamesToRefresh);
        N.checkArgPositive(batchSize, cs.batchSize);

        if (N.isEmpty(entities)) {
            return 0;
        }

        final T first = N.firstOrNullIfEmpty(entities);
        final Class<?> cls = first.getClass();
        final List<String> idPropNameList = QueryUtil.getIdFieldNames(cls); // must not empty.
        final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);

        final com.landawn.abacus.util.function.Function<T, ID> idExtractorFunc = DaoUtil.createIdExtractor(idPropNameList, entityInfo);
        final Map<ID, List<T>> idEntityMap = StreamEx.of(entities).groupTo(idExtractorFunc, Fn.identity());
        final Collection<String> selectPropNames = DaoUtil.getRefreshSelectPropNames(propNamesToRefresh, idPropNameList);

        final List<T> dbEntities = batchGet(idEntityMap.keySet(), selectPropNames, batchSize);

        if (N.isEmpty(dbEntities)) {
            return 0;
        } else {
            return dbEntities.stream().mapToInt(dbEntity -> {
                final ID id = idExtractorFunc.apply(dbEntity);
                final List<T> tmp = idEntityMap.get(id);

                if (N.notEmpty(tmp)) {
                    for (final T entity : tmp) {
                        Beans.merge(dbEntity, entity, propNamesToRefresh);
                    }
                }

                return N.size(tmp);
            }).sum();
        }
    }

    /**
     * Deletes an entity from the database.
     * The entity must have its ID field(s) populated.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * int deletedRows = userDao.delete(user);
     * if (deletedRows > 0) {
     *     System.out.println("User deleted successfully");
     * }
     * }</pre>
     *
     * @param entity the entity to delete (must have ID populated)
     * @return the number of rows deleted (typically 1 if successful, 0 if not found)
     * @throws SQLException if a database access error occurs
     */
    int delete(final T entity) throws SQLException;

    /**
     * Deletes an entity by its ID.
     * This is more efficient than loading the entity first and then deleting it.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * int deletedRows = userDao.deleteById(userId);
     * if (deletedRows == 0) {
     *     System.out.println("User not found");
     * }
     * }</pre>
     *
     * @param id the entity ID to delete
     * @return the number of rows deleted (typically 1 if successful, 0 if not found)
     * @throws SQLException if a database access error occurs
     */
    int deleteById(final ID id) throws SQLException;

    /**
     * Performs batch delete of multiple entities using the default batch size.
     * Each entity must have its ID field(s) populated.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> usersToDelete = getInactiveUsers();
     * int totalDeleted = userDao.batchDelete(usersToDelete);
     * System.out.println(totalDeleted + " users deleted");
     * }</pre>
     *
     * @param entities the collection of entities to delete
     * @return the total number of rows deleted
     * @throws SQLException if a database access error occurs
     */
    default int batchDelete(final Collection<? extends T> entities) throws SQLException {
        return batchDelete(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch delete of multiple entities with a specified batch size.
     * Large collections will be processed in batches of the specified size.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> largeDeleteList = getInactiveUsers(); // 20000 inactive users
     * int totalDeleted = userDao.batchDelete(largeDeleteList, 1000);
     * System.out.println(totalDeleted + " users deleted");
     * }</pre>
     *
     * @param entities the collection of entities to delete
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return the total number of rows deleted
     * @throws SQLException if a database access error occurs
     */
    int batchDelete(final Collection<? extends T> entities, final int batchSize) throws SQLException;

    //    /**
    //     *
    //     * @param entities
    //     * @param onDeleteAction It should be defined and done in DB server side.
    //     * @return
    //     * @throws SQLException
    //     */
    //    @Beta
    //    default int batchDelete(final Collection<? extends T> entities, final OnDeleteAction onDeleteAction) throws SQLException {
    //        return batchDelete(entities, onDeleteAction, DEFAULT_BATCH_SIZE);
    //    }
    //
    //    /**
    //     *
    //     * @param entities
    //     * @param onDeleteAction It should be defined and done in DB server side.
    //     * @param batchSize
    //     * @return
    //     * @throws SQLException
    //     */
    //    @Beta
    //    int batchDelete(final Collection<? extends T> entities, final OnDeleteAction onDeleteAction, final int batchSize) throws SQLException;

    /**
     * Deletes multiple entities by their IDs using the default batch size.
     * This is more efficient than deleting entities one by one.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> userIds = Arrays.asList(1L, 2L, 3L, 4L, 5L);
     * int deletedCount = userDao.batchDeleteByIds(userIds);
     * System.out.println(deletedCount + " users deleted");
     * }</pre>
     *
     * @param ids the collection of IDs to delete
     * @return the total number of rows deleted
     * @throws SQLException if a database access error occurs
     */
    default int batchDeleteByIds(final Collection<? extends ID> ids) throws SQLException {
        return batchDeleteByIds(ids, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Deletes multiple entities by their IDs with a specified batch size.
     * Large ID collections will be processed in batches to avoid database query size limits.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> largeIdList = getExpiredUserIds(); // 40000 IDs
     * // Delete in batches of 2000 to avoid SQL query size limits
     * int deletedCount = userDao.batchDeleteByIds(largeIdList, 2000);
     * }</pre>
     *
     * @param ids the collection of IDs to delete
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return the total number of rows deleted
     * @throws SQLException if a database access error occurs
     */
    int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws SQLException;
}
