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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
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
import com.landawn.abacus.util.stream.Stream;
import com.landawn.abacus.util.stream.Stream.StreamEx;

/**
 * The UncheckedCrudDao interface provides comprehensive CRUD (Create, Read, Update, Delete) operations
 * with unchecked exceptions. It extends {@link UncheckedDao} and adds entity ID-based operations for more
 * convenient data access patterns.
 *
 * <p>This interface throws {@link UncheckedSQLException} instead of checked {@link java.sql.SQLException},
 * making it easier to work with in functional programming contexts and reducing boilerplate exception handling.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends UncheckedCrudDao<User, Long, SQLBuilder.PSC, UserDao> {
 *     // Custom query methods can be added here
 * }
 *
 * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
 * User user = new User("John", "Doe");
 * Long id = userDao.insert(user);
 *
 * Optional<User> found = userDao.get(id);
 * userDao.update("email", "john@example.com", id);
 * userDao.deleteById(id);
 * }</pre>
 *
 * @param <T> the entity type
 * @param <ID> the ID type (use {@code Void} if there is no id defined/annotated with {@code @Id} in target entity class {@code T})
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @param <TD> the self-type of the DAO for method chaining
 * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
 * @see Dao
 * @see com.landawn.abacus.query.condition.ConditionFactory
 * @see com.landawn.abacus.query.condition.ConditionFactory.CF
 */
@SuppressWarnings("resource")
@Beta
public interface UncheckedCrudDao<T, ID, SB extends SQLBuilder, TD extends UncheckedCrudDao<T, ID, SB, TD>>
        extends UncheckedDao<T, SB, TD>, CrudDao<T, ID, SB, TD> {

    /**
     * Generates a new ID for entity insertion.
     *
     * <p>This method should be overridden by implementations that support ID generation.
     * Common use cases include generating UUIDs, using sequences, or other ID generation strategies.</p>
     *
     * @return the generated ID
     * @throws UncheckedSQLException if a database access error occurs
     * @throws UnsupportedOperationException if the operation is not supported (default behavior)
     * @deprecated This operation is deprecated as ID generation should typically be handled by the database
     */
    @Deprecated
    @NonDBOperation
    @Override
    default ID generateId() throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Inserts the specified entity into the database and returns the generated ID.
     * All non-null properties of the entity will be included in the INSERT statement.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User("John", "Doe");
     * user.setEmail("john@example.com");
     * Long id = userDao.insert(user);
     * System.out.println("Created user with ID: " + id);
     * }</pre>
     *
     * @param entityToInsert the entity to insert (must not be null)
     * @return the generated ID of the inserted entity
     * @throws UncheckedSQLException if a database access error occurs or the entity is null
     */
    @Override
    ID insert(final T entityToInsert) throws UncheckedSQLException;

    /**
     * Inserts the specified entity with only the specified properties.
     * This is useful when you want to insert an entity with only certain fields populated.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setFirstName("John");
     * user.setLastName("Doe");
     * user.setEmail("john@example.com");
     * // Only insert firstName and email, skip lastName
     * Long id = userDao.insert(user, Arrays.asList("firstName", "email"));
     * }</pre>
     *
     * @param entityToInsert the entity to insert (must not be null)
     * @param propNamesToInsert the property names to include in the INSERT statement.
     *                          If null or empty, all properties will be inserted
     * @return the generated ID of the inserted entity
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    ID insert(final T entityToInsert, final Collection<String> propNamesToInsert) throws UncheckedSQLException;

    /**
     * Inserts an entity using a custom named SQL insert statement.
     * The SQL should use named parameters that match the entity's property names.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String sql = "INSERT INTO users (first_name, last_name, created_date) " +
     *              "VALUES (:firstName, :lastName, CURRENT_TIMESTAMP)";
     * User user = new User("John", "Doe");
     * Long id = userDao.insert(sql, user);
     * }</pre>
     *
     * @param namedInsertSQL the named parameter SQL insert statement
     * @param entityToInsert the entity whose properties will be bound to the named parameters
     * @return the generated ID of the inserted entity
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    ID insert(final String namedInsertSQL, final T entityToInsert) throws UncheckedSQLException;

    /**
     * Performs batch insert of multiple entities using the default batch size.
     * This method is more efficient than inserting entities one by one.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = Arrays.asList(
     *     new User("John", "Doe"),
     *     new User("Jane", "Smith"),
     *     new User("Bob", "Johnson")
     * );
     * List<Long> ids = userDao.batchInsert(users);
     * System.out.println("Created " + ids.size() + " users");
     * }</pre>
     *
     * @param entities the collection of entities to insert
     * @return a list of generated IDs in the same order as the input entities
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities) throws UncheckedSQLException {
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
     * @param batchSize the number of entities to process in each batch
     * @return a list of generated IDs in the same order as the input entities
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws UncheckedSQLException;

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
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert) throws UncheckedSQLException {
        return batchInsert(entities, propNamesToInsert, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch insert with only specified properties and custom batch size.
     * This provides fine-grained control over both what fields are inserted and how the batch is processed.
     *
     * @param entities the collection of entities to insert
     * @param propNamesToInsert the property names to include in the INSERT statement
     * @param batchSize the number of entities to process in each batch
     * @return a list of generated IDs in the same order as the input entities
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize) throws UncheckedSQLException;

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
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities) throws UncheckedSQLException {
        return batchInsert(namedInsertSQL, entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Performs batch insert using a custom named SQL statement with specified batch size.
     * Combines custom SQL flexibility with batch processing efficiency.
     *
     * @param namedInsertSQL the named parameter SQL insert statement
     * @param entities the collection of entities whose properties will be bound to the named parameters
     * @param batchSize the number of entities to process in each batch
     * @return a list of generated IDs in the same order as the input entities
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities, final int batchSize) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalBoolean} describing the value of a single property for the entity with the specified ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalBoolean isActive = userDao.queryForBoolean("isActive", userId);
     * if (isActive.isPresent() && isActive.getAsBoolean()) {
     *     // User is active
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an OptionalBoolean containing the value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForBoolean()
     */
    @Override
    OptionalBoolean queryForBoolean(final String singleSelectPropName, final ID id) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalChar} describing the value of a single property for the entity with the specified ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalChar grade = userDao.queryForChar("grade", studentId);
     * if (grade.isPresent()) {
     *     System.out.println("Student grade: " + grade.getAsChar());
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an OptionalChar containing the value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForChar()
     */
    @Override
    OptionalChar queryForChar(final String singleSelectPropName, final ID id) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalByte} describing the value of a single property for the entity with the specified ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalByte level = userDao.queryForByte("accessLevel", userId);
     * if (level.isPresent() && level.getAsByte() > 5) {
     *     // User has admin access
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an OptionalByte containing the value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForByte()
     */
    @Override
    OptionalByte queryForByte(final String singleSelectPropName, final ID id) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalShort} describing the value of a single property for the entity with the specified ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalShort age = userDao.queryForShort("age", userId);
     * if (age.isPresent()) {
     *     System.out.println("User age: " + age.getAsShort());
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an OptionalShort containing the value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForShort()
     */
    @Override
    OptionalShort queryForShort(final String singleSelectPropName, final ID id) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalInt} describing the value of a single property for the entity with the specified ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalInt loginCount = userDao.queryForInt("loginCount", userId);
     * if (loginCount.isPresent() && loginCount.getAsInt() > 100) {
     *     // Frequent user
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an OptionalInt containing the value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForInt()
     */
    @Override
    OptionalInt queryForInt(final String singleSelectPropName, final ID id) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalLong} describing the value of a single property for the entity with the specified ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalLong totalBytes = userDao.queryForLong("storageUsed", userId);
     * if (totalBytes.isPresent()) {
     *     System.out.println("Storage used: " + totalBytes.getAsLong() + " bytes");
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an OptionalLong containing the value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForLong()
     */
    @Override
    OptionalLong queryForLong(final String singleSelectPropName, final ID id) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalFloat} describing the value of a single property for the entity with the specified ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalFloat rating = userDao.queryForFloat("averageRating", productId);
     * if (rating.isPresent() && rating.getAsFloat() >= 4.5f) {
     *     // Highly rated product
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an OptionalFloat containing the value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForFloat()
     */
    @Override
    OptionalFloat queryForFloat(final String singleSelectPropName, final ID id) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalDouble} describing the value of a single property for the entity with the specified ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalDouble balance = userDao.queryForDouble("accountBalance", accountId);
     * if (balance.isPresent()) {
     *     processPayment(balance.getAsDouble());
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an OptionalDouble containing the value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForDouble()
     */
    @Override
    OptionalDouble queryForDouble(final String singleSelectPropName, final ID id) throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable<String>} describing the value of a single property for the entity with the specified ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<String> email = userDao.queryForString("email", userId);
     * if (email.isPresent()) {
     *     sendNotification(email.get());
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return a Nullable containing the String value, or Nullable.empty() if no record exists
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForString()
     */
    @Override
    Nullable<String> queryForString(final String singleSelectPropName, final ID id) throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable<java.sql.Date>} describing the value of a single property for the entity with the specified ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Date> birthDate = userDao.queryForDate("birthDate", userId);
     * if (birthDate.isPresent()) {
     *     calculateAge(birthDate.get());
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return a Nullable containing the Date value, or Nullable.empty() if no record exists
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForDate()
     */
    @Override
    Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final ID id) throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable<java.sql.Time>} describing the value of a single property for the entity with the specified ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Time> startTime = userDao.queryForTime("workStartTime", employeeId);
     * if (startTime.isPresent()) {
     *     scheduleShift(startTime.get());
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return a Nullable containing the Time value, or Nullable.empty() if no record exists
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForTime()
     */
    @Override
    Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final ID id) throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable<java.sql.Timestamp>} describing the value of a single property for the entity with the specified ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Timestamp> lastLogin = userDao.queryForTimestamp("lastLoginTime", userId);
     * if (lastLogin.isPresent()) {
     *     updateActivity(lastLogin.get());
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return a Nullable containing the Timestamp value, or Nullable.empty() if no record exists
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForTimestamp()
     */
    @Override
    Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final ID id) throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable<byte[]>} describing the value of a single property for the entity with the specified ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<byte[]> avatar = userDao.queryForBytes("profileImage", userId);
     * if (avatar.isPresent()) {
     *     displayImage(avatar.get());
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return a Nullable containing the byte array value, or Nullable.empty() if no record exists
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForBytes()
     */
    @Override
    Nullable<byte[]> queryForBytes(final String singleSelectPropName, final ID id) throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable<V>} describing the value of a single property for the entity with the specified ID,
     * converted to the specified target type.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<BigDecimal> price = userDao.queryForSingleResult("price", productId, BigDecimal.class);
     * if (price.isPresent()) {
     *     applyDiscount(price.get());
     * }
     * }</pre>
     *
     * @param <V> the target value type
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @param targetValueClass the class of the target value type
     * @return a Nullable containing the converted value, or Nullable.empty() if no record exists
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForSingleResult(Class)
     */
    @Override
    <V> Nullable<V> queryForSingleResult(final String singleSelectPropName, final ID id, final Class<? extends V> targetValueClass)
            throws UncheckedSQLException;

    /**
     * Returns an {@code Optional} describing the non-null value of a single property for the entity with the specified ID.
     * Unlike queryForSingleResult, this method returns empty Optional for null values.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<String> nickname = userDao.queryForSingleNonNull("nickname", userId, String.class);
     * nickname.ifPresent(name -> updateDisplayName(name));
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @param targetValueClass the class of the target value type
     * @return an Optional containing the non-null value, or empty if no entity found or value is null
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForSingleNonNull(Class)
     */
    @Override
    <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final ID id, final Class<? extends V> targetValueClass)
            throws UncheckedSQLException;

    /**
     * Returns an {@code Optional} describing the non-null value mapped by the row mapper for the entity with the specified ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<UserStatus> status = userDao.queryForSingleNonNull(
     *     "statusCode",
     *     userId,
     *     rs -> UserStatus.fromCode(rs.getString(1))
     * );
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @param rowMapper the function to map the result set row
     * @return an Optional containing the non-null mapped value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForSingleNonNull(Class)
     */
    @Override
    <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final ID id, final Jdbc.RowMapper<? extends V> rowMapper)
            throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable} describing the value of a single property for the entity with the specified ID.
     * Throws {@code DuplicatedResultException} if more than one record is found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Assuming email should be unique per user
     * Nullable<String> email = userDao.queryForUniqueResult("email", userId, String.class);
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @param targetValueClass the class of the target value type
     * @return a Nullable containing the unique result value, or empty if no entity found
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForUniqueResult(Class)
     */
    @Override
    <V> Nullable<V> queryForUniqueResult(final String singleSelectPropName, final ID id, final Class<? extends V> targetValueClass)
            throws DuplicatedResultException, UncheckedSQLException;

    /**
     * Returns an {@code Optional} describing the unique non-null value of a single property for the entity with the specified ID.
     * Throws {@code DuplicatedResultException} if more than one record is found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<Integer> accessLevel = userDao.queryForUniqueNonNull(
     *     "accessLevel",
     *     userId,
     *     Integer.class
     * );
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @param targetValueClass the class of the target value type
     * @return an Optional containing the unique non-null value, or empty if no entity found or value is null
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForUniqueNonNull(Class)
     */
    @Override
    <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final ID id, final Class<? extends V> targetValueClass)
            throws DuplicatedResultException, UncheckedSQLException;

    /**
     * Returns an {@code Optional} describing the unique non-null value mapped by the row mapper for the entity with the specified ID.
     * Throws {@code DuplicatedResultException} if more than one record is found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<Permission> permission = userDao.queryForUniqueNonNull(
     *     "permissionData",
     *     roleId,
     *     rs -> Permission.parse(rs.getString(1))
     * );
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @param rowMapper the function to map the result set row
     * @return an Optional containing the unique non-null mapped value, or empty if no entity found
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForUniqueNonNull(Class)
     */
    @Override
    <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final ID id, final Jdbc.RowMapper<? extends V> rowMapper)
            throws DuplicatedResultException, UncheckedSQLException;

    /**
     * Retrieves the entity with the specified ID.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<User> user = userDao.get(userId);
     * user.ifPresent(u -> System.out.println("Found user: " + u.getName()));
     * }</pre>
     *
     * @param id the entity ID
     * @return an Optional containing the entity if found, otherwise empty
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Optional<T> get(final ID id) throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id));
    }

    /**
     * Retrieves the entity with the specified ID, selecting only the specified properties.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<User> user = userDao.get(userId, Arrays.asList("id", "firstName", "email"));
     * // Only id, firstName, and email will be populated in the returned user
     * }</pre>
     *
     * @param id the entity ID
     * @param selectPropNames the properties to select, or null to select all
     * @return an Optional containing the entity with selected properties if found, otherwise empty
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Optional<T> get(final ID id, final Collection<String> selectPropNames) throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, selectPropNames));
    }

    /**
     * Retrieves the entity with the specified ID. Returns the entity directly or null if not found.
     * The 'gett' naming convention indicates this method returns T directly.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * if (user != null) {
     *     processUser(user);
     * }
     * }</pre>
     *
     * @param id the entity ID
     * @return the entity if found, otherwise null
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    T gett(final ID id) throws DuplicatedResultException, UncheckedSQLException;

    /**
     * Retrieves the entity with the specified ID, selecting only the specified properties.
     * Returns the entity directly or null if not found.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId, Arrays.asList("id", "email", "status"));
     * if (user != null && "ACTIVE".equals(user.getStatus())) {
     *     sendEmail(user.getEmail());
     * }
     * }</pre>
     *
     * @param id the entity ID
     * @param selectPropNames the properties to select, or null to select all
     * @return the entity with selected properties if found, otherwise null
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    T gett(final ID id, final Collection<String> selectPropNames) throws DuplicatedResultException, UncheckedSQLException;

    /**
     * Gets multiple entities by their IDs in batch using the default batch size.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> userIds = Arrays.asList(1L, 2L, 3L, 4L, 5L);
     * List<User> users = userDao.batchGet(userIds);
     * }</pre>
     *
     * @param ids the collection of entity IDs
     * @return a list of found entities
     * @throws DuplicatedResultException if the size of result is bigger than the size of input IDs
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids) throws DuplicatedResultException, UncheckedSQLException {
        return batchGet(ids, (Collection<String>) null);
    }

    /**
     * Gets multiple entities by their IDs in batch using the specified batch size.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Set<Long> userIds = getLargeUserIdSet();
     * // Fetch in batches of 1000 to avoid query size limits
     * List<User> users = userDao.batchGet(userIds, 1000);
     * }</pre>
     *
     * @param ids the collection of entity IDs
     * @param batchSize the size of each batch
     * @return a list of found entities
     * @throws DuplicatedResultException if the size of result is bigger than the size of input IDs
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final int batchSize) throws DuplicatedResultException, UncheckedSQLException {
        return batchGet(ids, (Collection<String>) null, batchSize);
    }

    /**
     * Gets multiple entities by their IDs with only the specified properties selected.
     * Uses the default batch size.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> userIds = Arrays.asList(1L, 2L, 3L);
     * List<User> users = userDao.batchGet(userIds, Arrays.asList("id", "email", "firstName"));
     * // Only id, email, and firstName will be populated
     * }</pre>
     *
     * @param ids the collection of entity IDs
     * @param selectPropNames the properties to select, or null to select all
     * @return a list of found entities with selected properties
     * @throws DuplicatedResultException if the size of result is bigger than the size of input IDs
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames)
            throws DuplicatedResultException, UncheckedSQLException {
        return batchGet(ids, selectPropNames, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Gets multiple entities by their IDs with only the specified properties selected,
     * using the specified batch size for efficient querying.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Set<Long> userIds = getThousandsOfUserIds();
     * List<String> minimalProps = Arrays.asList("id", "email");
     * // Fetch minimal data in batches of 500
     * List<User> users = userDao.batchGet(userIds, minimalProps, 500);
     * }</pre>
     *
     * @param ids the collection of entity IDs
     * @param selectPropNames the properties to select, or null to select all
     * @param batchSize the size of each batch
     * @return a list of found entities with selected properties
     * @throws DuplicatedResultException if the size of result is bigger than the size of input IDs
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize)
            throws DuplicatedResultException, UncheckedSQLException;

    /**
     * Checks if an entity with the specified ID exists in the database.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (userDao.exists(userId)) {
     *     // User exists, proceed with update
     *     userDao.update("lastAccess", new Date(), userId);
     * } else {
     *     // User doesn't exist, create new
     *     userDao.insert(new User(userId));
     * }
     * }</pre>
     *
     * @param id the entity ID to check
     * @return {@code true} if the entity exists, {@code false} otherwise
     * @throws UncheckedSQLException if a database access error occurs
     * @see AbstractQuery#exists()
     */
    @Override
    boolean exists(final ID id) throws UncheckedSQLException;

    /**
     * Checks if an entity with the specified ID does not exist in the database.
     * This is the logical opposite of {@link #exists(ID)}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (userDao.notExists(userId)) {
     *     // User doesn't exist, safe to create
     *     userDao.insert(new User(userId));
     * }
     * }</pre>
     *
     * @param id the entity ID to check
     * @return {@code true} if the entity does not exist, {@code false} if it exists
     * @throws UncheckedSQLException if a database access error occurs
     * @see AbstractQuery#notExists()
     */
    @Beta
    @Override
    default boolean notExists(final ID id) throws UncheckedSQLException {
        return !exists(id);
    }

    /**
     * Counts the number of entities with the specified IDs.
     * This is a beta API that can be used to check how many of the given IDs actually exist.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> requestedIds = Arrays.asList(1L, 2L, 3L, 4L, 5L);
     * int existingCount = userDao.count(requestedIds);
     * if (existingCount < requestedIds.size()) {
     *     // Some users don't exist
     * }
     * }</pre>
     *
     * @param ids the collection of entity IDs to count
     * @return the count of existing entities
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    int count(final Collection<? extends ID> ids) throws UncheckedSQLException;

    /**
     * Updates the specified entity in the database. The entity must have its ID set.
     * All non-null properties will be updated.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * user.setEmail("newemail@example.com");
     * user.setLastModified(new Date());
     * int updatedRows = userDao.update(user);
     * }</pre>
     *
     * @param entityToUpdate the entity to update
     * @return the number of rows updated (typically 1 if successful, 0 if not found)
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int update(final T entityToUpdate) throws UncheckedSQLException;

    /**
     * Updates only the specified properties of the entity in the database.
     * Properties not included in {@code propNamesToUpdate} will not be modified.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setId(userId);
     * user.setEmail("newemail@example.com");
     * user.setPhone("555-1234");
     * user.setAddress("123 Main St");  // This won't be updated
     * 
     * // Only update email and phone
     * int updated = userDao.update(user, Arrays.asList("email", "phone"));
     * }</pre>
     *
     * @param entityToUpdate the entity containing the values to update
     * @param propNamesToUpdate the properties to update
     * @return the number of rows updated
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int update(final T entityToUpdate, final Collection<String> propNamesToUpdate) throws UncheckedSQLException;

    /**
     * Updates a single property value for the entity with the specified ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Update user's last login time
     * userDao.update("lastLoginTime", new Date(), userId);
     * 
     * // Deactivate user
     * userDao.update("status", "INACTIVE", userId);
     * }</pre>
     *
     * @param propName the property name to update
     * @param propValue the new value for the property
     * @param id the entity ID
     * @return the number of rows updated
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int update(final String propName, final Object propValue, final ID id) throws UncheckedSQLException {
        final Map<String, Object> updateProps = new HashMap<>();
        updateProps.put(propName, propValue);

        return update(updateProps, id);
    }

    /**
     * Updates multiple properties for the entity with the specified ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Map<String, Object> updates = new HashMap<>();
     * updates.put("email", "newemail@example.com");
     * updates.put("phone", "555-9999");
     * updates.put("lastModified", new Date());
     * 
     * int updated = userDao.update(updates, userId);
     * }</pre>
     *
     * @param updateProps a map of property names to their new values
     * @param id the entity ID
     * @return the number of rows updated
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int update(final Map<String, Object> updateProps, final ID id) throws UncheckedSQLException;

    /**
     * Batch updates multiple entities using the default batch size.
     * All non-null properties in each entity will be updated.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(CF.eq("needsUpdate", true));
     * users.forEach(user -> {
     *     user.setProcessed(true);
     *     user.setProcessedDate(new Date());
     * });
     * int totalUpdated = userDao.batchUpdate(users);
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @return the total number of rows updated
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int batchUpdate(final Collection<? extends T> entities) throws UncheckedSQLException {
        return batchUpdate(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch updates multiple entities using the specified batch size.
     * All non-null properties in each entity will be updated.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> largeUserList = getThousandsOfUsers();
     * // Update in batches of 500
     * int totalUpdated = userDao.batchUpdate(largeUserList, 500);
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @param batchSize the size of each batch
     * @return the total number of rows updated
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int batchUpdate(final Collection<? extends T> entities, final int batchSize) throws UncheckedSQLException;

    /**
     * Batch updates only the specified properties of multiple entities using the default batch size.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getUsersToProcess();
     * users.forEach(user -> {
     *     user.setStatus("PROCESSED");
     *     user.setScore(calculateScore(user));
     * });
     * // Only update status and score fields
     * int updated = userDao.batchUpdate(users, Arrays.asList("status", "score"));
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @param propNamesToUpdate the properties to update for each entity
     * @return the total number of rows updated
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate) throws UncheckedSQLException {
        return batchUpdate(entities, propNamesToUpdate, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch updates only the specified properties of multiple entities using the specified batch size.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getLargeUserList();
     * // Prepare updates
     * users.forEach(u -> u.setMigrated(true));
     * 
     * // Update only the 'migrated' field in batches of 1000
     * int updated = userDao.batchUpdate(users, Arrays.asList("migrated"), 1000);
     * }</pre>
     *
     * @param entities the collection of entities to update
     * @param propNamesToUpdate the properties to update for each entity
     * @param batchSize the size of each batch
     * @return the total number of rows updated
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize) throws UncheckedSQLException;

    /**
     * Performs an upsert operation: inserts the entity if it doesn't exist based on ID fields, otherwise updates the existing entity.
     * The entity must have ID field(s) defined.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setId(123L);
     * user.setEmail("john@example.com");
     * user.setLastSeen(new Date());
     *
     * User result = userDao.upsert(user);
     * // Result will be either the newly inserted or updated user
     * }</pre>
     *
     * @param entity the entity to insert or update
     * @return the inserted or updated entity
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default T upsert(final T entity) throws UncheckedSQLException {
        N.checkArgNotNull(entity, cs.entity);

        final Class<?> cls = entity.getClass();
        @SuppressWarnings("deprecation")
        final List<String> idPropNameList = QueryUtil.getIdFieldNames(cls); // must not empty.

        return upsert(entity, idPropNameList);
    }

    /**
     * Performs an upsert operation: inserts the entity if it doesn't exist based on the specified unique properties, otherwise updates the existing entity.
     * If no record matches the unique properties, inserts the entity.
     * Otherwise, updates the existing record.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setEmail("john@example.com");
     * user.setFirstName("John");
     * user.setLastName("Doe");
     * user.setScore(100);
     *
     * // Upsert based on email being unique
     * User result = userDao.upsert(user, Arrays.asList("email"));
     * }</pre>
     *
     * @param entity the entity to insert or update
     * @param uniquePropNamesForQuery the property names that uniquely identify the record
     * @return the inserted or updated entity
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default T upsert(final T entity, final List<String> uniquePropNamesForQuery) throws UncheckedSQLException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotEmpty(uniquePropNamesForQuery, cs.uniquePropNamesForQuery);

        final Condition cond = CF.eqAnd(entity, uniquePropNamesForQuery);

        return upsert(entity, cond);
    }

    /**
     * Executes an upsert operation based on the specified condition.
     * If no record matches the condition, inserts the entity.
     * Otherwise, updates the existing record merging non-null values.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setEmail("john@example.com");
     * user.setDepartment("IT");
     * user.setLastUpdated(new Date());
     * 
     * // Custom condition for upsert
     * Condition cond = CF.and(
     *     CF.eq("email", user.getEmail()),
     *     CF.eq("department", user.getDepartment())
     * );
     * 
     * User result = userDao.upsert(user, cond);
     * }</pre>
     *
     * @param entity the entity to insert or update
     * @param cond the condition to check for existing record
     * @return the inserted or updated entity
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default T upsert(final T entity, final Condition cond) throws UncheckedSQLException {
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
     * Batch upserts multiple entities using the default batch size.
     * Entities are inserted if they don't exist (based on ID), otherwise updated.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = Arrays.asList(
     *     new User(1L, "John", "john@example.com"),
     *     new User(2L, "Jane", "jane@example.com"),
     *     new User(3L, "Bob", "bob@example.com")
     * );
     * 
     * List<User> results = userDao.batchUpsert(users);
     * }</pre>
     *
     * @param entities the collection of entities to upsert
     * @return a list of upserted entities
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default List<T> batchUpsert(final Collection<? extends T> entities) throws UncheckedSQLException {
        return batchUpsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch upserts multiple entities using the specified batch size.
     * Entities are inserted if they don't exist (based on ID), otherwise updated.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> largeUserList = getThousandsOfUsers();
     * // Upsert in batches of 500
     * List<User> results = userDao.batchUpsert(largeUserList, 500);
     * }</pre>
     *
     * @param entities the collection of entities to upsert
     * @param batchSize the size of each batch
     * @return a list of upserted entities
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default List<T> batchUpsert(final Collection<? extends T> entities, final int batchSize) throws UncheckedSQLException {
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
     * Batch upserts multiple entities based on the specified unique properties.
     * Uses the default batch size.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getUsersFromImport();
     * // Upsert based on email being unique
     * List<User> results = userDao.batchUpsert(users, Arrays.asList("email"));
     * }</pre>
     *
     * @param entities the collection of entities to upsert
     * @param uniquePropNamesForQuery the property names that uniquely identify each record
     * @return a list of upserted entities
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default List<T> batchUpsert(final Collection<? extends T> entities, final List<String> uniquePropNamesForQuery) throws UncheckedSQLException {
        return batchUpsert(entities, uniquePropNamesForQuery, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch upserts multiple entities based on the specified unique properties using the specified batch size.
     * This method efficiently handles large collections by:
     * 1. Querying existing records in batches
     * 2. Separating entities into insert and update groups
     * 3. Performing batch insert and batch update operations
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> importedUsers = parseCSVFile();
     * // Upsert based on email, in batches of 1000
     * List<User> results = userDao.batchUpsert(
     *     importedUsers, 
     *     Arrays.asList("email"), 
     *     1000
     * );
     * }</pre>
     *
     * @param entities the collection of entities to upsert
     * @param uniquePropNamesForQuery the property names that uniquely identify each record
     * @param batchSize the size of each batch
     * @return a list of upserted entities
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default List<T> batchUpsert(final Collection<? extends T> entities, final List<String> uniquePropNamesForQuery, final int batchSize)
            throws UncheckedSQLException {
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
                ? Stream.of(entities).split(batchSize).flatmap(it -> list(CF.in(propNameListForQuery.get(0), N.map(it, singleKeyExtractor)))).toList()
                : Stream.of(entities).split(batchSize).flatmap(it -> list(CF.id2Cond(N.map(it, entityIdExtractor)))).toList();

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
     * Refreshes the specified entity by reloading all its properties from the database.
     * The entity must have its ID set. After refresh, the entity will contain the
     * current values from the database.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setId(123L);
     * boolean found = userDao.refresh(user);
     * if (found) {
     *     // User now contains all current values from database
     *     System.out.println("User email: " + user.getEmail());
     * }
     * }</pre>
     *
     * @param entity the entity to refresh (must have ID set)
     * @return {@code true} if the entity was found and refreshed, {@code false} if not found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default boolean refresh(final T entity) throws UncheckedSQLException {
        N.checkArgNotNull(entity, cs.entity);

        final Class<?> cls = entity.getClass();
        final Collection<String> propNamesToRefresh = JdbcUtil.getSelectPropNames(cls);

        return refresh(entity, propNamesToRefresh);
    }

    /**
     * Refreshes only the specified properties of the entity from the database.
     * The entity must have its ID set. Properties not in {@code propNamesToRefresh}
     * will retain their current values.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getCachedUser();
     * // Only refresh email and status fields
     * boolean found = userDao.refresh(user, Arrays.asList("email", "status"));
     * }</pre>
     *
     * @param entity the entity to refresh (must have ID set)
     * @param propNamesToRefresh the properties to refresh from the database
     * @return {@code false} if no record found by the ID in the specified entity, {@code true} otherwise
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    @SuppressWarnings("deprecation")
    default boolean refresh(final T entity, final Collection<String> propNamesToRefresh) throws UncheckedSQLException {
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
     * Batch refreshes multiple entities from the database using the default batch size.
     * Each entity must have its ID set.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> cachedUsers = getCachedUsers();
     * int refreshedCount = userDao.batchRefresh(cachedUsers);
     * // All users now have current values from database
     * }</pre>
     *
     * @param entities the collection of entities to refresh
     * @return the count of successfully refreshed entities
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int batchRefresh(final Collection<? extends T> entities) throws UncheckedSQLException {
        return batchRefresh(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch refreshes multiple entities from the database using the specified batch size.
     * Each entity must have its ID set.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> thousandsOfUsers = getLargeUserCache();
     * // Refresh in batches of 500
     * int refreshedCount = userDao.batchRefresh(thousandsOfUsers, 500);
     * }</pre>
     *
     * @param entities the collection of entities to refresh
     * @param batchSize the size of each batch
     * @return the count of successfully refreshed entities
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int batchRefresh(final Collection<? extends T> entities, final int batchSize) throws UncheckedSQLException {
        if (N.isEmpty(entities)) {
            return 0;
        }

        final T first = N.firstOrNullIfEmpty(entities);
        final Class<?> cls = first.getClass();
        final Collection<String> propNamesToRefresh = JdbcUtil.getSelectPropNames(cls);

        return batchRefresh(entities, propNamesToRefresh, batchSize);
    }

    /**
     * Batch refreshes only the specified properties of multiple entities using the default batch size.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getCachedUsers();
     * // Only refresh status and lastLogin fields
     * int count = userDao.batchRefresh(users, Arrays.asList("status", "lastLogin"));
     * }</pre>
     *
     * @param entities the collection of entities to refresh
     * @param propNamesToRefresh the properties to refresh for each entity
     * @return the count of successfully refreshed entities
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int batchRefresh(final Collection<? extends T> entities, final Collection<String> propNamesToRefresh) throws UncheckedSQLException {
        return batchRefresh(entities, propNamesToRefresh, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch refreshes only the specified properties of multiple entities using the specified batch size.
     * This method efficiently refreshes large collections by:
     * 1. Extracting IDs from all entities
     * 2. Fetching current values from database in batches
     * 3. Merging the specified properties back into the original entities
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> cachedUsers = getThousandsOfCachedUsers();
     * // Refresh only critical fields in batches of 1000
     * int count = userDao.batchRefresh(
     *     cachedUsers,
     *     Arrays.asList("balance", "status", "verified"),
     *     1000
     * );
     * }</pre>
     *
     * @param entities the collection of entities to refresh
     * @param propNamesToRefresh the properties to refresh for each entity
     * @param batchSize the size of each batch
     * @return the count of successfully refreshed entities
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    @SuppressWarnings("deprecation")
    default int batchRefresh(final Collection<? extends T> entities, final Collection<String> propNamesToRefresh, final int batchSize)
            throws UncheckedSQLException {
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
     * Deletes the specified entity from the database. The entity must have its ID set.
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
     * @param entity the entity to delete (must have ID set)
     * @return the number of rows deleted (typically 1 or 0)
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int delete(final T entity) throws UncheckedSQLException;

    /**
     * Deletes the entity with the specified ID from the database.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * int deletedRows = userDao.deleteById(userId);
     * if (deletedRows == 0) {
     *     System.out.println("User not found");
     * }
     * }</pre>
     *
     * @param id the ID of the entity to delete
     * @return the number of rows deleted (typically 1 or 0)
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int deleteById(final ID id) throws UncheckedSQLException;

    /**
     * Batch deletes multiple entities from the database using the default batch size.
     * Each entity must have its ID set.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> usersToDelete = getInactiveUsers();
     * int totalDeleted = userDao.batchDelete(usersToDelete);
     * System.out.println("Deleted " + totalDeleted + " users");
     * }</pre>
     *
     * @param entities the collection of entities to delete
     * @return the total number of rows deleted
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int batchDelete(final Collection<? extends T> entities) throws UncheckedSQLException {
        return batchDelete(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch deletes multiple entities from the database using the specified batch size.
     * Each entity must have its ID set.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> thousandsToDelete = getObsoleteUsers();
     * // Delete in batches of 500
     * int totalDeleted = userDao.batchDelete(thousandsToDelete, 500);
     * }</pre>
     *
     * @param entities the collection of entities to delete
     * @param batchSize the size of each batch
     * @return the total number of rows deleted
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int batchDelete(final Collection<? extends T> entities, final int batchSize) throws UncheckedSQLException;

    /**
     * Batch deletes entities by their IDs using the default batch size.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> userIdsToDelete = Arrays.asList(1L, 2L, 3L, 4L, 5L);
     * int totalDeleted = userDao.batchDeleteByIds(userIdsToDelete);
     * }</pre>
     *
     * @param ids the collection of entity IDs to delete
     * @return the total number of rows deleted
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int batchDeleteByIds(final Collection<? extends ID> ids) throws UncheckedSQLException {
        return batchDeleteByIds(ids, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch deletes entities by their IDs using the specified batch size.
     * This is more efficient than deleting entities one by one, especially for large collections.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Set<Long> thousandsOfIds = getExpiredUserIds();
     * // Delete in batches of 1000 to avoid query size limits
     * int totalDeleted = userDao.batchDeleteByIds(thousandsOfIds, 1000);
     * }</pre>
     *
     * @param ids the collection of entity IDs to delete
     * @param batchSize the size of each batch
     * @return the total number of rows deleted
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws UncheckedSQLException;
}