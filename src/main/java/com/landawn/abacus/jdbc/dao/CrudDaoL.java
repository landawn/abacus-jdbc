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
import java.util.Collection;
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicatedResultException;
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
 * A specialized CrudDao interface that uses {@code Long} as the ID type.
 * This interface provides convenience methods that accept primitive {@code long} values
 * in addition to the {@code Long} object methods inherited from CrudDao.
 * 
 * <p>This interface is particularly useful for entities that use numeric long IDs,
 * which is a common pattern in many database schemas. All methods delegate to their
 * corresponding CrudDao methods after boxing the primitive long to Long.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends CrudDaoL<User, SQLBuilder.PSC, UserDao> {
 *     // Inherits all CrudDao methods with Long ID type
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
 * @param <TD> The self-type of the DAO for fluent interface support
 * 
 * @see com.landawn.abacus.query.Filters
 */
@Beta
public interface CrudDaoL<T, SB extends SQLBuilder, TD extends CrudDaoL<T, SB, TD>> extends CrudDao<T, Long, SB, TD> {

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
     * @throws SQLException if a database access error occurs
     */
    default OptionalBoolean queryForBoolean(final String singleSelectPropName, final long id) throws SQLException {
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
     * @throws SQLException if a database access error occurs
     */
    default OptionalChar queryForChar(final String singleSelectPropName, final long id) throws SQLException {
        return queryForChar(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a byte value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * Returns an empty OptionalByte if no record is found.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalByte flags = entityDao.queryForByte("flags", 123L);
     * byte flagValue = flags.orElse((byte) 0);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return an OptionalByte containing the value if found, otherwise empty
     * @throws SQLException if a database access error occurs
     */
    default OptionalByte queryForByte(final String singleSelectPropName, final long id) throws SQLException {
        return queryForByte(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a short value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * Returns an empty OptionalShort if no record is found.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalShort port = serverDao.queryForShort("port", 123L);
     * short portNumber = port.orElse((short) 8080);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return an OptionalShort containing the value if found, otherwise empty
     * @throws SQLException if a database access error occurs
     */
    default OptionalShort queryForShort(final String singleSelectPropName, final long id) throws SQLException {
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
     * @throws SQLException if a database access error occurs
     */
    default OptionalInt queryForInt(final String singleSelectPropName, final long id) throws SQLException {
        return queryForInt(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a long value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * Returns an empty OptionalLong if no record is found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalLong lastLoginTime = userDao.queryForLong("lastLoginTimestamp", 123L);
     * lastLoginTime.ifPresent(time -> System.out.println("Last login: " + new Date(time)));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return an OptionalLong containing the value if found, otherwise empty
     * @throws SQLException if a database access error occurs
     */
    default OptionalLong queryForLong(final String singleSelectPropName, final long id) throws SQLException {
        return queryForLong(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a float value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * Returns an empty OptionalFloat if no record is found.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalFloat rating = productDao.queryForFloat("rating", 123L);
     * float productRating = rating.orElse(0.0f);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return an OptionalFloat containing the value if found, otherwise empty
     * @throws SQLException if a database access error occurs
     */
    default OptionalFloat queryForFloat(final String singleSelectPropName, final long id) throws SQLException {
        return queryForFloat(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a double value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * Returns an empty OptionalDouble if no record is found.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalDouble balance = accountDao.queryForDouble("balance", 123L);
     * double amount = balance.orElse(0.0);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return an OptionalDouble containing the value if found, otherwise empty
     * @throws SQLException if a database access error occurs
     */
    default OptionalDouble queryForDouble(final String singleSelectPropName, final long id) throws SQLException {
        return queryForDouble(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a String value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<String> email = userDao.queryForString("email", 123L);
     * String userEmail = email.orElse("no-email@example.com");
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return a Nullable containing the String value if found, or Nullable.empty() if no record exists
     * @throws SQLException if a database access error occurs
     */
    default Nullable<String> queryForString(final String singleSelectPropName, final long id) throws SQLException {
        return queryForString(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a Date value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Date> birthDate = userDao.queryForDate("birthDate", 123L);
     * birthDate.ifPresent(date -> System.out.println("Birth date: " + date));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return a Nullable containing the Date value if found, or Nullable.empty() if no record exists
     * @throws SQLException if a database access error occurs
     */
    default Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final long id) throws SQLException {
        return queryForDate(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a Time value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * Returns a Nullable containing the value, which can be {@code null} if the database value is {@code null}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Time> startTime = scheduleDao.queryForTime("startTime", 123L);
     * startTime.ifPresent(time -> System.out.println("Start time: " + time));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return a Nullable containing the Time value if found, or Nullable.empty() if no record exists
     * @throws SQLException if a database access error occurs
     */
    default Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final long id) throws SQLException {
        return queryForTime(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a Timestamp value from a single property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<Timestamp> lastModified = userDao.queryForTimestamp("lastModified", 123L);
     * lastModified.ifPresent(ts -> System.out.println("Last modified: " + ts));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return a Nullable containing the Timestamp value if found, or Nullable.empty() if no record exists
     * @throws SQLException if a database access error occurs
     */
    default Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final long id) throws SQLException {
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
     * Nullable<byte[]> avatar = userDao.queryForBytes("avatarImage", 123L);
     * avatar.ifPresent(bytes -> saveImageToFile(bytes));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return a Nullable containing the byte array value if found, or Nullable.empty() if no record exists
     * @throws SQLException if a database access error occurs
     */
    default Nullable<byte[]> queryForBytes(final String singleSelectPropName, final long id) throws SQLException {
        return queryForBytes(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a single value of the specified type from a property of the entity with the specified ID.
     * This is a convenience method that accepts a primitive long ID.
     * This is a generic method that can handle any type conversion supported by the underlying JDBC driver.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<BigDecimal> salary = userDao.queryForSingleResult("salary", 123L, BigDecimal.class);
     * Nullable<UserStatus> status = userDao.queryForSingleResult("status", 123L, UserStatus.class);
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @param targetValueType the class of the value type to convert to
     * @return a Nullable containing the value if found, or Nullable.empty() if no record exists
     * @throws SQLException if a database access error occurs
     */
    default <V> Nullable<V> queryForSingleResult(final String singleSelectPropName, final long id, final Class<? extends V> targetValueType)
            throws SQLException {
        return queryForSingleResult(singleSelectPropName, Long.valueOf(id), targetValueType);
    }

    /**
     * Queries for a single non-null value of the specified type from a property of the entity.
     * This is a convenience method that accepts a primitive long ID.
     * Returns an empty Optional if no record is found or if the value is {@code null}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<String> username = userDao.queryForSingleNonNull("username", 123L, String.class);
     * username.ifPresent(name -> System.out.println("Username: " + name));
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @param targetValueType the class of the value type to convert to
     * @return an Optional containing the non-null value if found, otherwise empty
     * @throws SQLException if a database access error occurs
     */
    default <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final long id, final Class<? extends V> targetValueType)
            throws SQLException {
        return queryForSingleNonNull(singleSelectPropName, Long.valueOf(id), targetValueType);
    }

    /**
     * Queries for a single non-null value using a custom row mapper.
     * This is a convenience method that accepts a primitive long ID.
     * This allows for complex transformations of the result.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<String> fullName = userDao.queryForSingleNonNull("firstName", 123L,
     *     (rs, columnNames) -> rs.getString(1).toUpperCase());
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @param rowMapper the custom mapper to transform the result
     * @return an Optional containing the mapped non-null value if found, otherwise empty
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final long id, final Jdbc.RowMapper<? extends V> rowMapper)
            throws SQLException {
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
     * Nullable<String> email = userDao.queryForUniqueResult("email", 123L, String.class);
     * // Throws DuplicatedResultException if multiple records found
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @param targetValueType the class of the value type to convert to
     * @return a Nullable containing the unique value if found, or Nullable.empty() if no record exists
     * @throws DuplicatedResultException if more than one record found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    default <V> Nullable<V> queryForUniqueResult(final String singleSelectPropName, final long id, final Class<? extends V> targetValueType)
            throws DuplicatedResultException, SQLException {
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
     * Optional<String> email = userDao.queryForUniqueNonNull("email", 123L, String.class);
     * email.ifPresent(e -> System.out.println("Email: " + e));
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @param targetValueType the class of the value type to convert to
     * @return an Optional containing the unique non-null value if found, otherwise empty
     * @throws DuplicatedResultException if more than one record found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    default <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final long id, final Class<? extends V> targetValueType)
            throws DuplicatedResultException, SQLException {
        return queryForUniqueNonNull(singleSelectPropName, Long.valueOf(id), targetValueType);
    }

    /**
     * Queries for a unique non-null result using a custom row mapper.
     * This is a convenience method that accepts a primitive long ID.
     * Throws DuplicatedResultException if more than one record is found.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<String> fullName = userDao.queryForUniqueNonNull("firstName", 123L,
     *     (rs, columnNames) -> rs.getString(1).toUpperCase());
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @param rowMapper the custom mapper to transform the result
     * @return an Optional containing the mapped unique non-null value if found, otherwise empty
     * @throws DuplicatedResultException if more than one record found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final long id, final Jdbc.RowMapper<? extends V> rowMapper)
            throws DuplicatedResultException, SQLException {
        return queryForUniqueNonNull(singleSelectPropName, Long.valueOf(id), rowMapper);
    }

    /**
     * Retrieves an entity by its ID.
     * This is a convenience method that accepts a primitive long ID.
     * Returns an Optional containing the entity if found, otherwise empty.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<User> user = userDao.get(123L);
     * user.ifPresent(u -> System.out.println("Found user: " + u.getName()));
     * }</pre>
     *
     * @param id the primitive long ID of the entity to retrieve
     * @return an Optional containing the entity if found, otherwise empty
     * @throws SQLException if a database access error occurs
     */
    default Optional<T> get(final long id) throws SQLException {
        return get(Long.valueOf(id));
    }

    /**
     * Retrieves an entity by its ID with only selected properties populated.
     * This is a convenience method that accepts a primitive long ID.
     * Properties not in the select list will have their default values.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Only load id, name, and email fields
     * Optional<User> user = userDao.get(123L, Arrays.asList("id", "name", "email"));
     * }</pre>
     *
     * @param id the primitive long ID of the entity to retrieve
     * @param selectPropNames the properties to select, excluding properties of joining entities.
     *                        All properties will be selected if null
     * @return an Optional containing the entity if found, otherwise empty
     * @throws SQLException if a database access error occurs
     */
    default Optional<T> get(final long id, final Collection<String> selectPropNames) throws SQLException {
        return get(Long.valueOf(id), selectPropNames);
    }

    /**
     * Retrieves an entity by its ID, returning {@code null} if not found.
     * This is a convenience method that accepts a primitive long ID.
     * This is a convenience method that returns the entity directly instead of wrapped in Optional.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(123L);
     * if (user != null) {
     *     System.out.println("Found user: " + user.getName());
     * }
     * }</pre>
     *
     * @param id the primitive long ID of the entity to retrieve
     * @return the entity if found, otherwise null
     * @throws SQLException if a database access error occurs
     */
    default T gett(final long id) throws SQLException {
        return gett(Long.valueOf(id));
    }

    /**
     * Retrieves an entity by its ID with only selected properties populated, returning {@code null} if not found.
     * This is a convenience method that accepts a primitive long ID.
     * This is useful for performance optimization when you only need specific fields.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Only load id, name, email fields
     * User user = userDao.gett(123L, Arrays.asList("id", "name", "email"));
     * if (user != null) {
     *     System.out.println("User name: " + user.getName());
     * }
     * }</pre>
     *
     * @param id the primitive long ID of the entity to retrieve
     * @param selectPropNames the properties to select, excluding properties of joining entities.
     *                        All properties will be selected if null
     * @return the entity if found, otherwise null
     * @throws SQLException if a database access error occurs
     */
    default T gett(final long id, final Collection<String> selectPropNames) throws SQLException {
        return gett(Long.valueOf(id), selectPropNames);
    }

    /**
     * Checks if an entity with the specified ID exists in the database.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (userDao.exists(123L)) {
     *     System.out.println("User exists");
     * }
     * }</pre>
     *
     * @param id the primitive long ID to check for existence
     * @return {@code true} if an entity with the given ID exists, {@code false} otherwise
     * @throws SQLException if a database access error occurs
     */
    default boolean exists(final long id) throws SQLException {
        return exists(Long.valueOf(id));
    }

    /**
     * Checks if an entity with the specified ID does not exist in the database.
     * This is a convenience method that accepts a primitive long ID.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (userDao.notExists(123L)) {
     *     // Create new user with this ID
     * }
     * }</pre>
     *
     * @param id the primitive long ID to check for non-existence
     * @return {@code true} if no entity with the given ID exists, {@code false} otherwise
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default boolean notExists(final long id) throws SQLException {
        return !exists(id);
    }

    /**
     * Updates a single property of an entity identified by ID.
     * This is a convenience method that accepts a primitive long ID.
     * This is a convenience method for updating one field.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * userDao.update("lastLoginTime", new Date(), 123L);
     * userDao.update("failedLoginAttempts", 0, 123L);
     * }</pre>
     *
     * @param propName the property name to update
     * @param propValue the new value for the property
     * @param id the primitive long ID of the entity to update
     * @return the number of rows updated
     * @throws SQLException if a database access error occurs
     */
    default int update(final String propName, final Object propValue, final long id) throws SQLException {
        return update(propName, propValue, Long.valueOf(id));
    }

    /**
     * Updates multiple properties of an entity identified by ID.
     * This is a convenience method that accepts a primitive long ID.
     * This allows updating multiple fields without loading the entire entity.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Map<String, Object> updates = new HashMap<>();
     * updates.put("status", "ACTIVE");
     * updates.put("lastModified", new Date());
     * userDao.update(updates, 123L);
     * }</pre>
     *
     * @param updateProps a map of property names to their new values
     * @param id the primitive long ID of the entity to update
     * @return the number of rows updated
     * @throws SQLException if a database access error occurs
     */
    default int update(final Map<String, Object> updateProps, final long id) throws SQLException {
        return update(updateProps, Long.valueOf(id));
    }

    /**
     * Deletes an entity by its ID.
     * This is a convenience method that accepts a primitive long ID.
     * This is more efficient than loading the entity first and then deleting it.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * int deletedRows = userDao.deleteById(123L);
     * if (deletedRows == 0) {
     *     System.out.println("User not found");
     * }
     * }</pre>
     *
     * @param id the primitive long ID of the entity to delete
     * @return the number of rows deleted (typically 1 if successful, 0 if not found)
     * @throws SQLException if a database access error occurs
     */
    default int deleteById(final long id) throws SQLException {
        return deleteById(Long.valueOf(id));
    }
}
