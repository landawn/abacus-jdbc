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

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.AbstractQuery;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.Jdbc.Columns.ColumnOne;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.query.QueryUtil;
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.query.condition.ConditionFactory;
import com.landawn.abacus.query.condition.ConditionFactory.CF;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.DataSet;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
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

/**
 * Interface for an unchecked Data Access Object (DAO) that extends the base DAO interface.
 * Its methods throw {@code UncheckedSQLException} instead of {@code SQLException}, providing a more convenient
 * API for developers who prefer unchecked exceptions.
 * 
 * <p>This interface provides basic CRUD operations and query methods without the need to handle checked exceptions.
 * All operations that would normally throw {@code SQLException} will throw {@code UncheckedSQLException} instead.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * UncheckedDao<User, SQLBuilder.PSC, UserDao> userDao = ...;
 * User user = new User("John", "Doe");
 * userDao.save(user);
 * 
 * Optional<User> foundUser = userDao.findFirst(CF.eq("firstName", "John"));
 * }</pre>
 *
 * @param <T> the entity type
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @param <TD> the self-type of the DAO for method chaining
 * @see com.landawn.abacus.jdbc.dao.Dao
 * @see com.landawn.abacus.query.condition.ConditionFactory
 * @see com.landawn.abacus.query.condition.ConditionFactory.CF
 */
@Beta
public interface UncheckedDao<T, SB extends SQLBuilder, TD extends UncheckedDao<T, SB, TD>> extends Dao<T, SB, TD> {

    /**
     * Saves the specified entity to the database. This is typically an insert operation
     * for new entities or an update operation for existing entities.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * User user = new User("John", "Doe");
     * userDao.save(user);
     * }</pre>
     *
     * @param entityToSave the entity to save
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    void save(final T entityToSave) throws UncheckedSQLException;

    /**
     * Saves the specified entity with only the specified properties.
     * Properties not included in {@code propNamesToSave} will not be persisted.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * User user = new User("John", "Doe");
     * userDao.save(user, Arrays.asList("firstName", "email"));
     * }</pre>
     *
     * @param entityToSave the entity to save
     * @param propNamesToSave the properties to save, or null to save all properties
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    void save(final T entityToSave, final Collection<String> propNamesToSave) throws UncheckedSQLException;

    /**
     * Saves the entity using a named insert SQL statement. The SQL statement should contain
     * named parameters that will be populated from the entity properties.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * String sql = "INSERT INTO users (first_name, last_name) VALUES (:firstName, :lastName)";
     * User user = new User("John", "Doe");
     * userDao.save(sql, user);
     * }</pre>
     *
     * @param namedInsertSQL the named insert SQL statement
     * @param entityToSave the entity to save
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    void save(final String namedInsertSQL, final T entityToSave) throws UncheckedSQLException;

    /**
     * Batch saves the specified entities to the database using the default batch size.
     * This method is more efficient than saving entities one by one.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<User> users = Arrays.asList(
     *     new User("John", "Doe"),
     *     new User("Jane", "Smith")
     * );
     * userDao.batchSave(users);
     * }</pre>
     *
     * @param entitiesToSave the collection of entities to save
     * @throws UncheckedSQLException if a database access error occurs
     * @see CrudDao#batchInsert(Collection)
     */
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave) throws UncheckedSQLException {
        batchSave(entitiesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch saves the specified entities to the database using the specified batch size.
     * The entities will be saved in batches to improve performance.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<User> users = getLargeUserList();
     * userDao.batchSave(users, 1000);
     * }</pre>
     *
     * @param entitiesToSave the collection of entities to save
     * @param batchSize the size of each batch
     * @throws UncheckedSQLException if a database access error occurs
     * @see CrudDao#batchInsert(Collection)
     */
    @Override
    void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws UncheckedSQLException;

    /**
     * Batch saves the specified entities with only the specified properties using the default batch size.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<User> users = getUserList();
     * userDao.batchSave(users, Arrays.asList("firstName", "email"));
     * }</pre>
     *
     * @param entitiesToSave the collection of entities to save
     * @param propNamesToSave the properties to save for each entity
     * @throws UncheckedSQLException if a database access error occurs
     * @see CrudDao#batchInsert(Collection)
     */
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave) throws UncheckedSQLException {
        batchSave(entitiesToSave, propNamesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch saves the specified entities with only the specified properties using the specified batch size.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<User> users = getLargeUserList();
     * userDao.batchSave(users, Arrays.asList("firstName", "email"), 500);
     * }</pre>
     *
     * @param entitiesToSave the collection of entities to save
     * @param propNamesToSave the properties to save for each entity
     * @param batchSize the size of each batch
     * @throws UncheckedSQLException if a database access error occurs
     * @see CrudDao#batchInsert(Collection)
     */
    @Override
    void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize) throws UncheckedSQLException;

    /**
     * Batch saves entities using a named insert SQL statement with the default batch size.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * String sql = "INSERT INTO users (first_name, last_name) VALUES (:firstName, :lastName)";
     * List<User> users = getUserList();
     * userDao.batchSave(sql, users);
     * }</pre>
     *
     * @param namedInsertSQL the named insert SQL statement
     * @param entitiesToSave the collection of entities to save
     * @throws UncheckedSQLException if a database access error occurs
     * @see CrudDao#batchInsert(Collection)
     */
    @Beta
    @Override
    default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave) throws UncheckedSQLException {
        batchSave(namedInsertSQL, entitiesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Batch saves entities using a named insert SQL statement with the specified batch size.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * String sql = "INSERT INTO users (first_name, last_name) VALUES (:firstName, :lastName)";
     * List<User> users = getLargeUserList();
     * userDao.batchSave(sql, users, 1000);
     * }</pre>
     *
     * @param namedInsertSQL the named insert SQL statement
     * @param entitiesToSave the collection of entities to save
     * @param batchSize the size of each batch
     * @throws UncheckedSQLException if a database access error occurs
     * @see CrudDao#batchInsert(Collection)
     */
    @Beta
    @Override
    void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize) throws UncheckedSQLException;

    /**
     * Checks if any records exist that match the specified condition.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * boolean hasActiveUsers = userDao.exists(CF.eq("status", "ACTIVE"));
     * }</pre>
     *
     * @param cond the condition to match
     * @return {@code true} if at least one record is found, {@code false} otherwise
     * @throws UncheckedSQLException if a database access error occurs
     * @see AbstractQuery#exists()
     */
    @Override
    boolean exists(final Condition cond) throws UncheckedSQLException;

    /**
     * Checks if no records exist that match the specified condition.
     * This is the logical opposite of {@link #exists(Condition)}.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * boolean noInactiveUsers = userDao.notExists(CF.eq("status", "INACTIVE"));
     * }</pre>
     *
     * @param cond the condition to match
     * @return {@code true} if no records are found, {@code false} if at least one record exists
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#notExists()
     */
    @Beta
    @Override
    default boolean notExists(final Condition cond) throws UncheckedSQLException {
        return !exists(cond);
    }

    /**
     * Counts the number of records that match the specified condition.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * int activeUserCount = userDao.count(CF.eq("status", "ACTIVE"));
     * }</pre>
     *
     * @param cond the condition to match
     * @return the count of matching records
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int count(final Condition cond) throws UncheckedSQLException;

    /**
     * Finds and returns the first record that matches the specified condition.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<User> user = userDao.findFirst(CF.eq("email", "john@example.com"));
     * }</pre>
     *
     * @param cond the condition to match
     * @return an Optional containing the first matching record, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    Optional<T> findFirst(final Condition cond) throws UncheckedSQLException;

    /**
     * Finds the first record matching the condition and maps it using the provided row mapper.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<String> name = userDao.findFirst(
     *     CF.eq("id", 1), 
     *     rs -> rs.getString("firstName") + " " + rs.getString("lastName")
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param cond the condition to match
     * @param rowMapper the function to map the result set row to the desired type
     * @return an Optional containing the mapped result, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> Optional<R> findFirst(final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws UncheckedSQLException;

    /**
     * Finds the first record matching the condition and maps it using the provided bi-row mapper.
     * The bi-row mapper receives both the result set and a list of column labels.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<Map<String, Object>> result = userDao.findFirst(
     *     CF.eq("id", 1),
     *     (rs, columnLabels) -> {
     *         Map<String, Object> map = new HashMap<>();
     *         for (String col : columnLabels) {
     *             map.put(col, rs.getObject(col));
     *         }
     *         return map;
     *     }
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param cond the condition to match
     * @param rowMapper the function to map the result set row with column labels
     * @return an Optional containing the mapped result, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> Optional<R> findFirst(final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper) throws UncheckedSQLException;

    /**
     * Finds the first record matching the condition, selecting only the specified properties.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<User> user = userDao.findFirst(
     *     Arrays.asList("id", "firstName", "email"),
     *     CF.eq("status", "ACTIVE")
     * );
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected, or null to select all
     * @param cond the condition to match
     * @return an Optional containing the first matching record, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    Optional<T> findFirst(final Collection<String> selectPropNames, final Condition cond) throws UncheckedSQLException;

    /**
     * Finds the first record matching the condition with selected properties and maps it using the row mapper.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<String> email = userDao.findFirst(
     *     Arrays.asList("email", "firstName"),
     *     CF.eq("id", 1),
     *     rs -> rs.getString("email")
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param selectPropNames the properties (columns) to be selected, or null to select all
     * @param cond the condition to match
     * @param rowMapper the function to map the result set row
     * @return an Optional containing the mapped result, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper)
            throws UncheckedSQLException;

    /**
     * Finds the first record matching the condition with selected properties and maps it using the bi-row mapper.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<UserInfo> info = userDao.findFirst(
     *     Arrays.asList("id", "firstName", "lastName"),
     *     CF.eq("email", "john@example.com"),
     *     (rs, cols) -> new UserInfo(rs.getLong("id"), rs.getString("firstName"))
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param selectPropNames the properties (columns) to be selected, or null to select all
     * @param cond the condition to match
     * @param rowMapper the function to map the result set row with column labels
     * @return an Optional containing the mapped result, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper)
            throws UncheckedSQLException;

    /**
     * Finds exactly one record matching the condition. Throws an exception if multiple records are found.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<User> user = userDao.findOnlyOne(CF.eq("email", "unique@example.com"));
     * }</pre>
     *
     * @param cond the condition to match
     * @return an Optional containing the single matching record, or empty if no match found
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    Optional<T> findOnlyOne(final Condition cond) throws DuplicatedResultException, UncheckedSQLException;

    /**
     * Finds exactly one record matching the condition and maps it using the row mapper.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<String> name = userDao.findOnlyOne(
     *     CF.eq("email", "unique@example.com"),
     *     rs -> rs.getString("firstName")
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param cond the condition to match
     * @param rowMapper the function to map the result set row
     * @return an Optional containing the mapped result, or empty if no match found
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> Optional<R> findOnlyOne(final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws DuplicatedResultException, UncheckedSQLException;

    /**
     * Finds exactly one record matching the condition and maps it using the bi-row mapper.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<UserDTO> user = userDao.findOnlyOne(
     *     CF.eq("id", 1),
     *     (rs, cols) -> UserDTO.from(rs)
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param cond the condition to match
     * @param rowMapper the function to map the result set row with column labels
     * @return an Optional containing the mapped result, or empty if no match found
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> Optional<R> findOnlyOne(final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper) throws DuplicatedResultException, UncheckedSQLException;

    /**
     * Finds exactly one record matching the condition, selecting only the specified properties.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<User> user = userDao.findOnlyOne(
     *     Arrays.asList("id", "email"),
     *     CF.eq("username", "john_doe")
     * );
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected, or null to select all
     * @param cond the condition to match
     * @return an Optional containing the single matching record, or empty if no match found
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    Optional<T> findOnlyOne(final Collection<String> selectPropNames, final Condition cond) throws DuplicatedResultException, UncheckedSQLException;

    /**
     * Finds exactly one record with selected properties and maps it using the row mapper.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<Long> userId = userDao.findOnlyOne(
     *     Arrays.asList("id"),
     *     CF.eq("email", "unique@example.com"),
     *     rs -> rs.getLong("id")
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param selectPropNames the properties (columns) to be selected, or null to select all
     * @param cond the condition to match
     * @param rowMapper the function to map the result set row
     * @return an Optional containing the mapped result, or empty if no match found
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> Optional<R> findOnlyOne(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper)
            throws DuplicatedResultException, UncheckedSQLException;

    /**
     * Finds exactly one record with selected properties and maps it using the bi-row mapper.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<UserSummary> summary = userDao.findOnlyOne(
     *     Arrays.asList("id", "firstName", "lastName", "email"),
     *     CF.eq("username", "john_doe"),
     *     (rs, cols) -> new UserSummary(rs)
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param selectPropNames the properties (columns) to be selected, or null to select all
     * @param cond the condition to match
     * @param rowMapper the function to map the result set row with column labels
     * @return an Optional containing the mapped result, or empty if no match found
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> Optional<R> findOnlyOne(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper)
            throws DuplicatedResultException, UncheckedSQLException;

    /**
     * Returns an {@code OptionalBoolean} describing the value in the first row/column if it exists.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * OptionalBoolean isActive = userDao.queryForBoolean("isActive", CF.eq("id", 1));
     * if (isActive.isPresent() && isActive.getAsBoolean()) {
     *     // User is active
     * }
     * }</pre>
     *
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @return an OptionalBoolean containing the value, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForBoolean()
     */
    @Override
    OptionalBoolean queryForBoolean(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalChar} describing the value in the first row/column if it exists.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * OptionalChar grade = userDao.queryForChar("grade", CF.eq("studentId", 12345));
     * }</pre>
     *
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @return an OptionalChar containing the value, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForChar()
     */
    @Override
    OptionalChar queryForChar(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalByte} describing the value in the first row/column if it exists.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * OptionalByte level = userDao.queryForByte("userLevel", CF.eq("id", 1));
     * }</pre>
     *
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @return an OptionalByte containing the value, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForByte()
     */
    @Override
    OptionalByte queryForByte(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalShort} describing the value in the first row/column if it exists.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * OptionalShort age = userDao.queryForShort("age", CF.eq("username", "john_doe"));
     * }</pre>
     *
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @return an OptionalShort containing the value, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForShort()
     */
    @Override
    OptionalShort queryForShort(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalInt} describing the value in the first row/column if it exists.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * OptionalInt count = userDao.queryForInt("loginCount", CF.eq("email", "user@example.com"));
     * }</pre>
     *
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @return an OptionalInt containing the value, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForInt()
     */
    @Override
    OptionalInt queryForInt(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalLong} describing the value in the first row/column if it exists.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * OptionalLong totalBytes = userDao.queryForLong("totalStorageUsed", CF.eq("id", 1));
     * }</pre>
     *
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @return an OptionalLong containing the value, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForLong()
     */
    @Override
    OptionalLong queryForLong(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalFloat} describing the value in the first row/column if it exists.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * OptionalFloat rating = userDao.queryForFloat("averageRating", CF.eq("productId", 100));
     * }</pre>
     *
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @return an OptionalFloat containing the value, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForFloat()
     */
    @Override
    OptionalFloat queryForFloat(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalDouble} describing the value in the first row/column if it exists.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * OptionalDouble balance = userDao.queryForDouble("accountBalance", CF.eq("accountId", 12345));
     * }</pre>
     *
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @return an OptionalDouble containing the value, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForDouble()
     */
    @Override
    OptionalDouble queryForDouble(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable<String>} describing the value in the first row/column if it exists.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Nullable<String> email = userDao.queryForString("email", CF.eq("username", "john_doe"));
     * if (email.isPresent()) {
     *     sendEmail(email.get());
     * }
     * }</pre>
     *
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @return a Nullable containing the String value, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForString()
     */
    @Override
    Nullable<String> queryForString(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable<java.sql.Date>} describing the value in the first row/column if it exists.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Nullable<java.sql.Date> birthDate = userDao.queryForDate("birthDate", CF.eq("id", 1));
     * }</pre>
     *
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @return a Nullable containing the Date value, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForDate()
     */
    @Override
    Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable<java.sql.Time>} describing the value in the first row/column if it exists.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Nullable<java.sql.Time> startTime = userDao.queryForTime("workStartTime", CF.eq("employeeId", 100));
     * }</pre>
     *
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @return a Nullable containing the Time value, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForTime()
     */
    @Override
    Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable<java.sql.Timestamp>} describing the value in the first row/column if it exists.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Nullable<java.sql.Timestamp> lastLogin = userDao.queryForTimestamp("lastLoginTime", CF.eq("username", "john_doe"));
     * }</pre>
     *
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @return a Nullable containing the Timestamp value, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForTimestamp()
     */
    @Override
    Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable<byte[]>} describing the value in the first row/column if it exists.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Nullable<byte[]> avatar = userDao.queryForBytes("avatarImage", CF.eq("userId", 1));
     * }</pre>
     *
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @return a Nullable containing the byte array value, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForBytes()
     */
    @Override
    Nullable<byte[]> queryForBytes(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable<V>} describing the value in the first row/column if it exists,
     * converted to the specified target type.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Nullable<BigDecimal> price = userDao.queryForSingleResult("price", CF.eq("productId", 100), BigDecimal.class);
     * }</pre>
     *
     * @param <V> the target value type
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @param targetValueType the class of the target value type
     * @return a Nullable containing the converted value, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForSingleResult(Class)
     */
    @Override
    <V> Nullable<V> queryForSingleResult(final String singleSelectPropName, final Condition cond, final Class<? extends V> targetValueType)
            throws UncheckedSQLException;

    /**
     * Returns an {@code Optional} describing the non-null value in the first row/column if it exists.
     * Unlike queryForSingleResult, this method returns empty Optional for null values.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<String> nickname = userDao.queryForSingleNonNull("nickname", CF.eq("id", 1), String.class);
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @param targetValueType the class of the target value type
     * @return an Optional containing the non-null value, or empty if no match found or value is null
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForSingleNonNull(Class)
     */
    @Override
    <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final Condition cond, final Class<? extends V> targetValueType)
            throws UncheckedSQLException;

    /**
     * Returns an {@code Optional} describing the non-null value mapped by the row mapper.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<UserStatus> status = userDao.queryForSingleNonNull(
     *     "status", 
     *     CF.eq("id", 1),
     *     rs -> UserStatus.valueOf(rs.getString(1))
     * );
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @param rowMapper the function to map the result set row
     * @return an Optional containing the non-null mapped value, or empty if no match found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForSingleNonNull(Class)
     */
    @Override
    <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final Condition cond, final Jdbc.RowMapper<? extends V> rowMapper)
            throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable} describing the value in the first row/column if it exists.
     * Throws {@code DuplicatedResultException} if more than one record is found.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Nullable<String> uniqueCode = userDao.queryForUniqueResult("code", CF.eq("type", "ADMIN"), String.class);
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @param targetValueType the class of the target value type
     * @return a Nullable containing the unique result value, or empty if no match found
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForUniqueResult(Class)
     */
    @Override
    <V> Nullable<V> queryForUniqueResult(final String singleSelectPropName, final Condition cond, final Class<? extends V> targetValueType)
            throws DuplicatedResultException, UncheckedSQLException;

    /**
     * Returns an {@code Optional} describing the unique non-null value in the first row/column.
     * Throws {@code DuplicatedResultException} if more than one record is found.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<Integer> uniqueLevel = userDao.queryForUniqueNonNull(
     *     "level", 
     *     CF.eq("badge", "GOLD"), 
     *     Integer.class
     * );
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @param targetValueType the class of the target value type
     * @return an Optional containing the unique non-null value, or empty if no match found or value is null
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForUniqueNonNull(Class)
     */
    @Override
    <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final Condition cond, final Class<? extends V> targetValueType)
            throws DuplicatedResultException, UncheckedSQLException;

    /**
     * Returns an {@code Optional} describing the unique non-null value mapped by the row mapper.
     * Throws {@code DuplicatedResultException} if more than one record is found.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<Permission> permission = userDao.queryForUniqueNonNull(
     *     "permissionData",
     *     CF.eq("roleId", 1),
     *     rs -> Permission.parse(rs.getString(1))
     * );
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @param rowMapper the function to map the result set row
     * @return an Optional containing the unique non-null mapped value, or empty if no match found
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForUniqueNonNull(Class)
     */
    @Override
    <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final Condition cond, final Jdbc.RowMapper<? extends V> rowMapper)
            throws DuplicatedResultException, UncheckedSQLException;

    /**
     * Executes a query and returns the results as a DataSet containing all matching records.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * DataSet activeUsers = userDao.query(CF.eq("status", "ACTIVE"));
     * activeUsers.forEach(row -> System.out.println(row.getString("email")));
     * }</pre>
     *
     * @param cond the condition to match
     * @return a DataSet containing all matching records
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    DataSet query(final Condition cond) throws UncheckedSQLException;

    /**
     * Executes a query selecting only specified properties and returns the results as a DataSet.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * DataSet userEmails = userDao.query(
     *     Arrays.asList("id", "email", "firstName"),
     *     CF.like("email", "%@company.com")
     * );
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected, or null to select all
     * @param cond the condition to match
     * @return a DataSet containing the selected properties of matching records
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    DataSet query(final Collection<String> selectPropNames, final Condition cond) throws UncheckedSQLException;

    /**
     * Executes a query and processes the result set using the provided result extractor.
     * The ResultSet will be closed after this call, so don't save or return it.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Map<Long, String> idToEmail = userDao.query(
     *     CF.eq("status", "ACTIVE"),
     *     rs -> {
     *         Map<Long, String> map = new HashMap<>();
     *         while (rs.next()) {
     *             map.put(rs.getLong("id"), rs.getString("email"));
     *         }
     *         return map;
     *     }
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param cond the condition to match
     * @param resultExtractor the function to extract results from the ResultSet
     * @return the extracted result
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> R query(final Condition cond, final Jdbc.ResultExtractor<? extends R> resultExtractor) throws UncheckedSQLException;

    /**
     * Executes a query with selected properties and processes the result set using the result extractor.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<String> names = userDao.query(
     *     Arrays.asList("firstName", "lastName"),
     *     CF.eq("department", "IT"),
     *     rs -> {
     *         List<String> list = new ArrayList<>();
     *         while (rs.next()) {
     *             list.add(rs.getString("firstName") + " " + rs.getString("lastName"));
     *         }
     *         return list;
     *     }
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param selectPropNames the properties (columns) to be selected, or null to select all
     * @param cond the condition to match
     * @param resultExtractor the function to extract results from the ResultSet
     * @return the extracted result
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> R query(final Collection<String> selectPropNames, final Condition cond, final Jdbc.ResultExtractor<? extends R> resultExtractor)
            throws UncheckedSQLException;

    /**
     * Executes a query and processes the result set using the bi-result extractor which receives column labels.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<Map<String, Object>> results = userDao.query(
     *     CF.gt("createdDate", lastWeek),
     *     (rs, columnLabels) -> {
     *         List<Map<String, Object>> list = new ArrayList<>();
     *         while (rs.next()) {
     *             Map<String, Object> row = new HashMap<>();
     *             for (String col : columnLabels) {
     *                 row.put(col, rs.getObject(col));
     *             }
     *             list.add(row);
     *         }
     *         return list;
     *     }
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param cond the condition to match
     * @param resultExtractor the function to extract results with column labels
     * @return the extracted result
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> R query(final Condition cond, final Jdbc.BiResultExtractor<? extends R> resultExtractor) throws UncheckedSQLException;

    /**
     * Executes a query with selected properties and processes using the bi-result extractor.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * double avgAge = userDao.query(
     *     Arrays.asList("age"),
     *     CF.eq("status", "ACTIVE"),
     *     (rs, cols) -> {
     *         double sum = 0;
     *         int count = 0;
     *         while (rs.next()) {
     *             sum += rs.getDouble("age");
     *             count++;
     *         }
     *         return count > 0 ? sum / count : 0;
     *     }
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param selectPropNames the properties (columns) to be selected, or null to select all
     * @param cond the condition to match
     * @param resultExtractor the function to extract results with column labels
     * @return the extracted result
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> R query(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiResultExtractor<? extends R> resultExtractor)
            throws UncheckedSQLException;

    /**
     * Returns a list of all entities matching the specified condition.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<User> activeUsers = userDao.list(CF.eq("status", "ACTIVE"));
     * }</pre>
     *
     * @param cond the condition to match
     * @return a list of matching entities
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    List<T> list(final Condition cond) throws UncheckedSQLException;

    /**
     * Returns a list of results mapped by the provided row mapper for records matching the condition.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<String> emails = userDao.list(
     *     CF.eq("newsletter", true),
     *     rs -> rs.getString("email")
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param cond the condition to match
     * @param rowMapper the function to map each result set row
     * @return a list of mapped results
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> List<R> list(final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws UncheckedSQLException;

    /**
     * Returns a list of results mapped by the bi-row mapper for records matching the condition.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<UserDTO> users = userDao.list(
     *     CF.gt("score", 100),
     *     (rs, cols) -> new UserDTO(rs.getLong("id"), rs.getString("name"))
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param cond the condition to match
     * @param rowMapper the function to map each result set row with column labels
     * @return a list of mapped results
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> List<R> list(final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper) throws UncheckedSQLException;

    /**
     * Returns a filtered list of results mapped by the row mapper for records matching the condition.
     * Only rows that pass the row filter will be mapped and included in the result.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<User> premiumUsers = userDao.list(
     *     CF.eq("status", "ACTIVE"),
     *     rs -> rs.getDouble("accountBalance") > 1000.0,  // row filter
     *     rs -> userMapper.map(rs)                        // row mapper
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param cond the condition to match
     * @param rowFilter the predicate to filter rows before mapping
     * @param rowMapper the function to map filtered result set rows
     * @return a list of filtered and mapped results
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> List<R> list(final Condition cond, final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends R> rowMapper) throws UncheckedSQLException;

    /**
     * Returns a filtered list using bi-row filter and bi-row mapper for records matching the condition.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<Account> accounts = userDao.list(
     *     CF.in("type", Arrays.asList("PREMIUM", "GOLD")),
     *     (rs, cols) -> rs.getBoolean("verified"),                    // bi-row filter
     *     (rs, cols) -> Account.fromResultSet(rs, cols)              // bi-row mapper
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param cond the condition to match
     * @param rowFilter the bi-predicate to filter rows with column labels
     * @param rowMapper the function to map filtered rows with column labels
     * @return a list of filtered and mapped results
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> List<R> list(final Condition cond, final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends R> rowMapper) throws UncheckedSQLException;

    /**
     * Returns a list of entities with only selected properties for records matching the condition.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<User> users = userDao.list(
     *     Arrays.asList("id", "email", "firstName"),
     *     CF.like("email", "%@company.com")
     * );
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected, or null to select all
     * @param cond the condition to match
     * @return a list of entities with selected properties
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    List<T> list(final Collection<String> selectPropNames, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns a list of mapped results with selected properties for records matching the condition.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<String> fullNames = userDao.list(
     *     Arrays.asList("firstName", "lastName"),
     *     CF.eq("active", true),
     *     rs -> rs.getString("firstName") + " " + rs.getString("lastName")
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param selectPropNames the properties (columns) to be selected, or null to select all
     * @param cond the condition to match
     * @param rowMapper the function to map each result set row
     * @return a list of mapped results
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws UncheckedSQLException;

    /**
     * Returns a list of mapped results using bi-row mapper with selected properties.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<UserInfo> infos = userDao.list(
     *     Arrays.asList("id", "email", "createdDate"),
     *     CF.between("createdDate", startDate, endDate),
     *     (rs, cols) -> new UserInfo(rs)
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param selectPropNames the properties (columns) to be selected, or null to select all
     * @param cond the condition to match
     * @param rowMapper the function to map each row with column labels
     * @return a list of mapped results
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper)
            throws UncheckedSQLException;

    /**
     * Returns a filtered and mapped list with selected properties for records matching the condition.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<PremiumUser> premiumUsers = userDao.list(
     *     Arrays.asList("id", "email", "membershipLevel"),
     *     CF.eq("status", "ACTIVE"),
     *     rs -> rs.getInt("membershipLevel") >= 3,          // filter
     *     rs -> new PremiumUser(rs)                         // mapper
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param selectPropNames the properties (columns) to be selected, or null to select all
     * @param cond the condition to match
     * @param rowFilter the predicate to filter rows before mapping
     * @param rowMapper the function to map filtered rows
     * @return a list of filtered and mapped results
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends R> rowMapper) throws UncheckedSQLException;

    /**
     * Returns a filtered and mapped list using bi-filters and bi-mappers with selected properties.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<ValidatedUser> users = userDao.list(
     *     Arrays.asList("id", "email", "validated", "score"),
     *     CF.isNotNull("email"),
     *     (rs, cols) -> rs.getBoolean("validated") && rs.getInt("score") > 50,
     *     (rs, cols) -> ValidatedUser.create(rs, cols)
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param selectPropNames the properties (columns) to be selected, or null to select all
     * @param cond the condition to match
     * @param rowFilter the bi-predicate to filter rows with column labels
     * @param rowMapper the function to map filtered rows with column labels
     * @return a list of filtered and mapped results
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter,
            final Jdbc.BiRowMapper<? extends R> rowMapper) throws UncheckedSQLException;

    /**
     * Returns a list of values for a single property from records matching the condition.
     * This is a convenience method for selecting a single column.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<String> emails = userDao.list("email", CF.eq("newsletter", true));
     * }</pre>
     *
     * @param <R> the result type
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @return a list of values for the specified property
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <R> List<R> list(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException {
        @SuppressWarnings("deprecation")
        final PropInfo propInfo = ParserUtil.getBeanInfo(targetEntityClass()).getPropInfo(singleSelectPropName);
        final Jdbc.RowMapper<? extends R> rowMapper = propInfo == null ? ColumnOne.getObject() : ColumnOne.get((Type<R>) propInfo.dbType);

        return list(singleSelectPropName, cond, rowMapper);
    }

    /**
     * Returns a list of mapped values for a single property from records matching the condition.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<UserStatus> statuses = userDao.list(
     *     "statusCode",
     *     CF.eq("active", true),
     *     rs -> UserStatus.fromCode(rs.getString(1))
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @param rowMapper the function to map the single column value
     * @return a list of mapped values
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <R> List<R> list(final String singleSelectPropName, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper)
            throws UncheckedSQLException {
        return list(N.asList(singleSelectPropName), cond, rowMapper);
    }

    /**
     * Returns a filtered list of mapped values for a single property from records matching the condition.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * List<BigDecimal> highPrices = userDao.list(
     *     "price",
     *     CF.eq("category", "PREMIUM"),
     *     rs -> rs.getBigDecimal(1).compareTo(threshold) > 0,  // filter
     *     rs -> rs.getBigDecimal(1)                            // mapper
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param singleSelectPropName the single property name to select
     * @param cond the condition to match
     * @param rowFilter the predicate to filter rows before mapping
     * @param rowMapper the function to map filtered values
     * @return a list of filtered and mapped values
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <R> List<R> list(final String singleSelectPropName, final Condition cond, final Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends R> rowMapper) throws UncheckedSQLException {
        return list(N.asList(singleSelectPropName), cond, rowFilter, rowMapper);
    }

    /**
     * Iterates through all records matching the condition and processes each row with the row consumer.
     * This method is useful for processing large result sets without loading all data into memory.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * userDao.forEach(
     *     CF.eq("status", "PENDING"),
     *     rs -> sendNotification(rs.getString("email"))
     * );
     * }</pre>
     *
     * @param cond the condition to match
     * @param rowConsumer the consumer to process each result set row
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    void forEach(final Condition cond, final Jdbc.RowConsumer rowConsumer) throws UncheckedSQLException;

    /**
     * Iterates through records using a bi-row consumer that receives column labels.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * userDao.forEach(
     *     CF.like("email", "%@oldDomain.com"),
     *     (rs, cols) -> {
     *         System.out.println("Processing user: " + rs.getString("id"));
     *         updateEmail(rs.getString("email"));
     *     }
     * );
     * }</pre>
     *
     * @param cond the condition to match
     * @param rowConsumer the bi-consumer to process each row with column labels
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    void forEach(final Condition cond, final Jdbc.BiRowConsumer rowConsumer) throws UncheckedSQLException;

    /**
     * Iterates through filtered records matching the condition.
     * Only rows that pass the filter will be processed by the consumer.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * userDao.forEach(
     *     CF.isNotNull("lastLogin"),
     *     rs -> rs.getTimestamp("lastLogin").after(cutoffDate),  // filter
     *     rs -> archiveUser(rs.getLong("id"))                    // consumer
     * );
     * }</pre>
     *
     * @param cond the condition to match
     * @param rowFilter the predicate to filter rows
     * @param rowConsumer the consumer to process filtered rows
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    void forEach(final Condition cond, final Jdbc.RowFilter rowFilter, final Jdbc.RowConsumer rowConsumer) throws UncheckedSQLException;

    /**
     * Iterates through filtered records using bi-row filter and bi-row consumer.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * userDao.forEach(
     *     CF.in("status", Arrays.asList("ACTIVE", "PENDING")),
     *     (rs, cols) -> isEligibleForPromotion(rs),              // bi-filter
     *     (rs, cols) -> sendPromotionEmail(rs, cols)            // bi-consumer
     * );
     * }</pre>
     *
     * @param cond the condition to match
     * @param rowFilter the bi-predicate to filter rows with column labels
     * @param rowConsumer the bi-consumer to process filtered rows with column labels
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    void forEach(final Condition cond, final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowConsumer rowConsumer) throws UncheckedSQLException;

    /**
     * Iterates through records with selected properties matching the condition.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * userDao.forEach(
     *     Arrays.asList("id", "email", "firstName"),
     *     CF.eq("newsletter", true),
     *     rs -> sendNewsletter(rs.getString("email"), rs.getString("firstName"))
     * );
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected, or null to select all
     * @param cond the condition to match
     * @param rowConsumer the consumer to process each row
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowConsumer rowConsumer) throws UncheckedSQLException;

    /**
     * Iterates through records with selected properties using a bi-row consumer.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * userDao.forEach(
     *     Arrays.asList("id", "data"),
     *     CF.eq("needsProcessing", true),
     *     (rs, cols) -> processUserData(rs, cols)
     * );
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected, or null to select all
     * @param cond the condition to match
     * @param rowConsumer the bi-consumer to process each row with column labels
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowConsumer rowConsumer) throws UncheckedSQLException;

    /**
     * Iterates through filtered records with selected properties.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * userDao.forEach(
     *     Arrays.asList("id", "score", "level"),
     *     CF.gt("score", 0),
     *     rs -> rs.getInt("level") >= 5,                        // filter
     *     rs -> grantAchievement(rs.getLong("id"))             // consumer
     * );
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected, or null to select all
     * @param cond the condition to match
     * @param rowFilter the predicate to filter rows
     * @param rowConsumer the consumer to process filtered rows
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowFilter rowFilter, final Jdbc.RowConsumer rowConsumer)
            throws UncheckedSQLException;

    /**
     * Iterates through filtered records with selected properties using bi-row filter and consumer.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * userDao.forEach(
     *     Arrays.asList("id", "email", "preferences"),
     *     CF.eq("active", true),
     *     (rs, cols) -> shouldReceiveNotification(rs.getString("preferences")),
     *     (rs, cols) -> queueNotification(rs.getLong("id"), rs.getString("email"))
     * );
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected, or null to select all
     * @param cond the condition to match
     * @param rowFilter the bi-predicate to filter rows with column labels
     * @param rowConsumer the bi-consumer to process filtered rows with column labels
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowConsumer rowConsumer)
            throws UncheckedSQLException;

    /**
     * Processes each record with selected properties using a consumer that receives DisposableObjArray.
     * This is a beta API that provides an alternative way to consume row data.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * userDao.foreach(
     *     Arrays.asList("id", "email", "status"),
     *     CF.eq("needsVerification", true),
     *     arr -> verifyUser((Long)arr.get(0), (String)arr.get(1))
     * );
     * }</pre>
     *
     * @param selectPropNames the properties (columns) to be selected
     * @param cond the condition to match
     * @param rowConsumer the consumer that receives row data as DisposableObjArray
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @SuppressWarnings("deprecation")
    @Beta
    @Override
    default void foreach(final Collection<String> selectPropNames, final Condition cond, final Consumer<DisposableObjArray> rowConsumer)
            throws UncheckedSQLException {
        forEach(selectPropNames, cond, Jdbc.RowConsumer.oneOff(targetEntityClass(), rowConsumer));
    }

    /**
     * Processes each record matching the condition using a consumer that receives DisposableObjArray.
     * This is a beta API that selects all properties.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * userDao.foreach(
     *     CF.between("age", 18, 65),
     *     arr -> processEligibleUser(arr)
     * );
     * }</pre>
     *
     * @param cond the condition to match
     * @param rowConsumer the consumer that receives row data as DisposableObjArray
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @SuppressWarnings("deprecation")
    @Beta
    @Override
    default void foreach(final Condition cond, final Consumer<DisposableObjArray> rowConsumer) throws UncheckedSQLException {
        forEach(cond, Jdbc.RowConsumer.oneOff(targetEntityClass(), rowConsumer));
    }

    /**
     * Updates a single property value for all records matching the condition.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * int updated = userDao.update("status", "INACTIVE", CF.lt("lastLogin", thirtyDaysAgo));
     * }</pre>
     *
     * @param propName the property name to update
     * @param propValue the new value for the property
     * @param cond the condition to match records to update
     * @return the number of records updated
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int update(final String propName, final Object propValue, final Condition cond) throws UncheckedSQLException {
        final Map<String, Object> updateProps = new HashMap<>();
        updateProps.put(propName, propValue);

        return update(updateProps, cond);
    }

    /**
     * Updates multiple properties for all records matching the condition.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Map<String, Object> updates = new HashMap<>();
     * updates.put("status", "VERIFIED");
     * updates.put("verifiedDate", new Date());
     * int updated = userDao.update(updates, CF.eq("pendingVerification", true));
     * }</pre>
     *
     * @param updateProps a map of property names to their new values
     * @param cond the condition to match records to update
     * @return the number of records updated
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int update(final Map<String, Object> updateProps, final Condition cond) throws UncheckedSQLException;

    /**
     * Updates all records matching the condition with values from the specified entity.
     * All non-null properties in the entity will be used for the update.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * User template = new User();
     * template.setStatus("MIGRATED");
     * template.setMigratedDate(new Date());
     * int updated = userDao.update(template, CF.eq("legacySystem", true));
     * }</pre>
     *
     * @param entity the entity containing values to update
     * @param cond the condition to match records to update
     * @return the number of records updated
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int update(final T entity, final Condition cond) throws UncheckedSQLException {
        @SuppressWarnings("deprecation")
        final Collection<String> propNamesToUpdate = QueryUtil.getUpdatePropNames(targetEntityClass(), null);

        return update(entity, propNamesToUpdate, cond);
    }

    /**
     * Updates records matching the condition with specified properties from the entity.
     * Only the properties listed in propNamesToUpdate will be updated.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * User updates = new User();
     * updates.setEmail("newemail@example.com");
     * updates.setPhone("555-1234");
     * updates.setAddress("123 Main St");  // This won't be updated
     * 
     * int updated = userDao.update(
     *     updates,
     *     Arrays.asList("email", "phone"),  // Only update these fields
     *     CF.eq("id", 123)
     * );
     * }</pre>
     *
     * @param entity the entity containing values to update
     * @param propNamesToUpdate the properties to update from the entity
     * @param cond the condition to match records to update
     * @return the number of records updated
     * @throws UncheckedSQLException if a database access error occurs
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @Override
    int update(final T entity, final Collection<String> propNamesToUpdate, final Condition cond) throws UncheckedSQLException;

    /**
     * Executes an upsert operation: inserts the entity if no record matches the unique properties,
     * otherwise updates the existing record.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * User user = new User("john@example.com", "John", "Doe");
     * user.setLastLogin(new Date());
     * 
     * // Upsert based on email being unique
     * User result = userDao.upsert(user, Arrays.asList("email"));
     * }</pre>
     *
     * @param entity the entity to add or update
     * @param uniquePropNamesForQuery the list of property names that uniquely identify the record
     * @return the added or updated entity from the database
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
     * Executes an upsert operation: inserts the entity if no record matches the condition,
     * otherwise updates the existing record.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * User user = new User();
     * user.setEmail("john@example.com");
     * user.setScore(100);
     * 
     * // Custom condition for upsert
     * User result = userDao.upsert(user, CF.and(
     *     CF.eq("email", user.getEmail()),
     *     CF.eq("accountType", "PREMIUM")
     * ));
     * }</pre>
     *
     * @param entity the entity to add or update
     * @param cond the condition to verify if the record exists
     * @return the added or updated entity from the database
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default T upsert(final T entity, final Condition cond) throws UncheckedSQLException {
        N.checkArgNotNull(cond, cs.cond);

        final T dbEntity = findOnlyOne(cond).orElseNull();

        if (dbEntity == null) {
            save(entity);
            return entity;
        } else {
            N.merge(entity, dbEntity);
            update(dbEntity, cond);
            return dbEntity;
        }
    }

    /**
     * Deletes all records that match the specified condition.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * // Delete all inactive users
     * int deletedCount = userDao.delete(CF.and(
     *     CF.eq("status", "INACTIVE"),
     *     CF.lt("lastLogin", oneYearAgo)
     * ));
     * }</pre>
     *
     * @param cond the condition to match records to delete
     * @return the number of records deleted
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    int delete(final Condition cond) throws UncheckedSQLException;
}