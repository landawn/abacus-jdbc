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
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.query.SQLBuilder;
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
 * A specialized CrudDao interface that uses {@code Long} as the ID type with unchecked exception handling.
 * This interface provides convenience methods that accept primitive {@code long} values
 * in addition to the {@code Long} object methods inherited from {@link UncheckedCrudDao}.
 *
 * <p>This interface is particularly useful for entities that use numeric long IDs,
 * which is a common pattern in many database schemas. All methods delegate to their
 * corresponding UncheckedCrudDao methods after boxing the primitive long to Long.</p>
 *
 * <p>This interface throws {@link UncheckedSQLException} instead of checked {@link java.sql.SQLException},
 * making it easier to work with in functional programming contexts.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends UncheckedCrudDaoL<User, SQLBuilder.PSC, UserDao> {
 *     // Inherits all UncheckedCrudDao methods with Long ID type
 *     // Plus convenience methods that accept primitive long
 * }
 *
 * // Usage with primitive long
 * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
 * Optional<User> user = userDao.get(123L);   // Can use primitive long
 * userDao.deleteById(456L);   // More convenient than Long.valueOf(456)
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <SB> the SQLBuilder type used to generate SQL scripts (must be one of SQLBuilder.PSC/PAC/PLC)
 * @param <TD> the self-type of the DAO for method chaining
 * @see UncheckedCrudDao
 * @see com.landawn.abacus.query.Filters
 */
@Beta
public interface UncheckedCrudDaoL<T, SB extends SQLBuilder, TD extends UncheckedCrudDaoL<T, SB, TD>>
        extends UncheckedCrudDao<T, Long, SB, TD>, CrudDaoL<T, SB, TD> {

    /**
     * Queries for a boolean value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * Returns an empty OptionalBoolean if no record is found.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalBoolean isActive = userDao.queryForBoolean("isActive", 123L);
     * if (isActive.isPresent() && isActive.getAsBoolean()) {
     *     System.out.println("User is active");
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return an OptionalBoolean containing the value if found, otherwise empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalBoolean queryForBoolean(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForBoolean(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a char value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * Returns an empty OptionalChar if no record is found.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalChar grade = studentDao.queryForChar("grade", 123L);
     * grade.ifPresent(g -> System.out.println("Student grade: " + g));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return an OptionalChar containing the value if found, otherwise empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalChar queryForChar(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForChar(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a byte value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * Returns an empty OptionalByte if no record is found.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalByte level = userDao.queryForByte("level", 123L);
     * if (level.isPresent() && level.getAsByte() >= 5) {
     *     // User has sufficient level
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return an OptionalByte containing the value if found, otherwise empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalByte queryForByte(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForByte(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a short value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * Returns an empty OptionalShort if no record is found.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalShort year = userDao.queryForShort("birthYear", 123L);
     * if (year.isPresent()) {
     *     int age = currentYear - year.getAsShort();
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return an OptionalShort containing the value if found, otherwise empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalShort queryForShort(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForShort(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for an integer value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * Returns an empty OptionalInt if no record is found.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalInt age = userDao.queryForInt("age", 123L);
     * int userAge = age.orElse(0);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return an OptionalInt containing the value if found, otherwise empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalInt queryForInt(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForInt(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a long value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * Returns an empty OptionalLong if no record is found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalLong totalBytes = userDao.queryForLong("storageUsed", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return an OptionalLong containing the value if found, otherwise empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalLong queryForLong(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForLong(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a float value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * Returns an empty OptionalFloat if no record is found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalFloat rating = userDao.queryForFloat("averageRating", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return an OptionalFloat containing the value if found, otherwise empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalFloat queryForFloat(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForFloat(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a double value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * Returns an empty OptionalDouble if no record is found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalDouble balance = userDao.queryForDouble("accountBalance", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return an OptionalDouble containing the value if found, otherwise empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalDouble queryForDouble(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForDouble(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a String value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<String> email = userDao.queryForString("email", 123L);
     * if (email.isPresent()) {
     *     sendNotification(email.get());
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return a Nullable containing the String value if found, or Nullable.empty() if no record exists
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Nullable<String> queryForString(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForString(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a Date value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Date> birthDate = userDao.queryForDate("birthDate", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return a Nullable containing the Date value if found, or Nullable.empty() if no record exists
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForDate(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a Time value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Time> startTime = userDao.queryForTime("workStartTime", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return a Nullable containing the Time value if found, or Nullable.empty() if no record exists
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForTime(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a Timestamp value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Timestamp> lastLogin = userDao.queryForTimestamp("lastLoginTime", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return a Nullable containing the Timestamp value if found, or Nullable.empty() if no record exists
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForTimestamp(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a byte array value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * Returns a Nullable containing the value, which can be {@code null} if the database value is {@code null}.
     * This is typically used for BLOB data.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<byte[]> avatar = userDao.queryForBytes("profileImage", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return a Nullable containing the byte array value if found, or Nullable.empty() if no record exists
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Nullable<byte[]> queryForBytes(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForBytes(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a single value of the specified type from a property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * This is a generic method that can handle any type conversion supported by the underlying JDBC driver.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<BigDecimal> price = userDao.queryForSingleResult("price", 123L, BigDecimal.class);
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @param targetValueType the class of the value type to convert to
     * @return a Nullable containing the value if found, or Nullable.empty() if no record exists
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <V> Nullable<V> queryForSingleResult(final String singleSelectPropName, final long id, final Class<? extends V> targetValueType)
            throws UncheckedSQLException {
        return queryForSingleResult(singleSelectPropName, Long.valueOf(id), targetValueType);
    }

    /**
     * Queries for a single non-null value of the specified type from a property of the entity.
     * This is a convenience method that accepts a primitive long ID.
     * Returns an empty Optional if no record is found or if the value is {@code null}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<String> nickname = userDao.queryForSingleNonNull("nickname", 123L, String.class);
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @param targetValueType the class of the value type to convert to
     * @return an Optional containing the non-null value if found, otherwise empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final long id, final Class<? extends V> targetValueType)
            throws UncheckedSQLException {
        return queryForSingleNonNull(singleSelectPropName, Long.valueOf(id), targetValueType);
    }

    /**
     * Queries for a single non-null value using a custom row mapper.
     * This is a convenience method that accepts a primitive long ID.
     * This allows for complex transformations of the result.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<UserStatus> status = userDao.queryForSingleNonNull(
     *     "statusCode", 
     *     123L,
     *     rs -> UserStatus.fromCode(rs.getString(1))
     * );
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @param rowMapper the custom mapper to transform the result
     * @return an Optional containing the mapped non-null value if found, otherwise empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final long id, final Jdbc.RowMapper<? extends V> rowMapper)
            throws UncheckedSQLException {
        return queryForSingleNonNull(singleSelectPropName, Long.valueOf(id), rowMapper);
    }

    /**
     * Queries for a unique single result of the specified type.
     * This is a convenience method that accepts a primitive long ID.
     * Throws DuplicatedResultException if more than one record is found.
     *
     * <p>This method ensures that at most one record matches the query.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<String> uniqueCode = userDao.queryForUniqueResult("code", 123L, String.class);
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @param targetValueType the class of the value type to convert to
     * @return a Nullable containing the unique value if found, or Nullable.empty() if no record exists
     * @throws DuplicatedResultException if more than one record found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <V> Nullable<V> queryForUniqueResult(final String singleSelectPropName, final long id, final Class<? extends V> targetValueType)
            throws DuplicatedResultException, UncheckedSQLException {
        return queryForUniqueResult(singleSelectPropName, Long.valueOf(id), targetValueType);
    }

    /**
     * Queries for a unique non-null result of the specified type.
     * This is a convenience method that accepts a primitive long ID.
     * Throws DuplicatedResultException if more than one record is found.
     * Returns empty Optional if no record found or value is {@code null}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<Integer> level = userDao.queryForUniqueNonNull("level", 123L, Integer.class);
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @param targetValueType the class of the value type to convert to
     * @return an Optional containing the unique non-null value if found, otherwise empty
     * @throws DuplicatedResultException if more than one record found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final long id, final Class<? extends V> targetValueType)
            throws DuplicatedResultException, UncheckedSQLException {
        return queryForUniqueNonNull(singleSelectPropName, Long.valueOf(id), targetValueType);
    }

    /**
     * Queries for a unique non-null result using a custom row mapper.
     * This is a convenience method that accepts a primitive long ID.
     * Throws DuplicatedResultException if more than one record is found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<Permission> perm = userDao.queryForUniqueNonNull(
     *     "permissions",
     *     123L,
     *     rs -> Permission.parse(rs.getString(1))
     * );
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @param rowMapper the custom mapper to transform the result
     * @return an Optional containing the mapped unique non-null value if found, otherwise empty
     * @throws DuplicatedResultException if more than one record found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final long id, final Jdbc.RowMapper<? extends V> rowMapper)
            throws DuplicatedResultException, UncheckedSQLException {
        return queryForUniqueNonNull(singleSelectPropName, Long.valueOf(id), rowMapper);
    }

    /**
     * Retrieves the entity with the specified ID.
     * This method accepts a primitive long ID for convenience.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<User> user = userDao.get(123L);
     * user.ifPresent(u -> System.out.println("Found: " + u.getName()));
     * }</pre>
     *
     * @param id the entity ID as primitive long
     * @return an Optional containing the entity if found, otherwise empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Optional<T> get(final long id) throws UncheckedSQLException {
        return get(Long.valueOf(id));
    }

    /**
     * Retrieves the entity with the specified ID, selecting only the specified properties.
     * This method accepts a primitive long ID for convenience.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<User> user = userDao.get(123L, Arrays.asList("id", "name", "email"));
     * }</pre>
     *
     * @param id the entity ID as primitive long
     * @param selectPropNames the properties to select, or {@code null} to select all
     * @return an Optional containing the entity with selected properties if found, otherwise empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Optional<T> get(final long id, final Collection<String> selectPropNames) throws UncheckedSQLException {
        return get(Long.valueOf(id), selectPropNames);
    }

    /**
     * Retrieves the entity with the specified ID, returning it directly or {@code null} if not found.
     * This method accepts a primitive long ID for convenience.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(123L);
     * if (user != null) {
     *     processUser(user);
     * }
     * }</pre>
     *
     * @param id the entity ID as primitive long
     * @return the entity if found, otherwise null
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default T gett(final long id) throws UncheckedSQLException {
        return gett(Long.valueOf(id));
    }

    /**
     * Retrieves the entity with the specified ID and selected properties, returning it directly or {@code null} if not found.
     * This method accepts a primitive long ID for convenience.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(123L, Arrays.asList("id", "email", "status"));
     * }</pre>
     *
     * @param id the entity ID as primitive long
     * @param selectPropNames the properties to select, or {@code null} to select all
     * @return the entity with selected properties if found, otherwise null
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default T gett(final long id, final Collection<String> selectPropNames) throws UncheckedSQLException {
        return gett(Long.valueOf(id), selectPropNames);
    }

    /**
     * Checks if an entity with the specified ID exists.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (userDao.exists(123L)) {
     *     // User exists
     * } else {
     *     // Create new user
     * }
     * }</pre>
     *
     * @param id the entity ID as primitive long
     * @return {@code true} if the entity exists, {@code false} otherwise
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default boolean exists(final long id) throws UncheckedSQLException {
        return exists(Long.valueOf(id));
    }

    /**
     * Checks if an entity with the specified ID does not exist.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (userDao.notExists(123L)) {
     *     // Safe to create new user with this ID
     * }
     * }</pre>
     *
     * @param id the entity ID as primitive long
     * @return {@code true} if the entity does not exist, {@code false} if it exists
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default boolean notExists(final long id) throws UncheckedSQLException {
        return !exists(id);
    }

    /**
     * Updates a single property value for the entity with the specified ID.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * int updated = userDao.update("status", "ACTIVE", 123L);
     * int updated2 = userDao.update("lastLogin", new Date(), 123L);
     * }</pre>
     *
     * @param propName the property name to update
     * @param propValue the new value for the property
     * @param id the entity ID as primitive long
     * @return the number of rows updated
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int update(final String propName, final Object propValue, final long id) throws UncheckedSQLException {
        return update(propName, propValue, Long.valueOf(id));
    }

    /**
     * Updates multiple properties for the entity with the specified ID.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Map<String, Object> updates = new HashMap<>();
     * updates.put("email", "newemail@example.com");
     * updates.put("phone", "555-1234");
     * updates.put("modifiedDate", new Date());
     * 
     * int updated = userDao.update(updates, 123L);
     * }</pre>
     *
     * @param updateProps a map of property names to their new values
     * @param id the entity ID as primitive long
     * @return the number of rows updated
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int update(final Map<String, Object> updateProps, final long id) throws UncheckedSQLException {
        return update(updateProps, Long.valueOf(id));
    }

    /**
     * Deletes the entity with the specified ID.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * int deleted = userDao.deleteById(123L);
     * if (deleted > 0) {
     *     System.out.println("User deleted successfully");
     * }
     * }</pre>
     *
     * @param id the entity ID as primitive long
     * @return the number of rows deleted (typically 1 or 0)
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default int deleteById(final long id) throws UncheckedSQLException {
        return deleteById(Long.valueOf(id));
    }
}
