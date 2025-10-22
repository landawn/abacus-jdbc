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
 * The UncheckedCrudDaoL interface is a specialized CRUD DAO that uses primitive {@code long} for ID operations.
 * The 'L' suffix indicates this DAO is optimized for entities with {@code long} or {@code Long} ID types,
 * providing convenience methods that accept primitive {@code long} values directly.
 * 
 * <p>This interface extends {@code UncheckedCrudDao} with {@code Long} as the ID type and adds overloaded
 * methods that accept primitive {@code long} parameters, avoiding unnecessary boxing/unboxing operations.</p>
 * 
 * <p>This is a beta API designed for performance-critical applications where avoiding object allocation
 * for ID values can make a difference.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * public interface UserDao extends UncheckedCrudDaoL<User, SQLBuilder.PSC, UserDao> {
 *     // Inherits both Long and long ID methods
 * }
 * 
 * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
 * 
 * // Using primitive long directly - no boxing needed
 * Optional<User> user = userDao.get(123L);
 * boolean exists = userDao.exists(123L);
 * int updated = userDao.update("status", "ACTIVE", 123L);
 * int deleted = userDao.deleteById(123L);
 * 
 * // Can still use Long objects when needed
 * Long userId = getUserIdFromSomewhere();
 * Optional<User> user2 = userDao.get(userId);
 * }</pre>
 *
 * @param <T> the entity type
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @param <TD> the self-type of the DAO for method chaining
 * @see UncheckedCrudDao
 */
@Beta
public interface UncheckedCrudDaoL<T, SB extends SQLBuilder, TD extends UncheckedCrudDaoL<T, SB, TD>>
        extends UncheckedCrudDao<T, Long, SB, TD>, CrudDaoL<T, SB, TD> {

    /**
     * Returns an {@code OptionalBoolean} describing the value of a single property for the entity with the specified ID.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * OptionalBoolean isActive = userDao.queryForBoolean("isActive", 123L);
     * if (isActive.isPresent() && isActive.getAsBoolean()) {
     *     // User is active
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @return an OptionalBoolean containing the value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalBoolean queryForBoolean(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForBoolean(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Returns an {@code OptionalChar} describing the value of a single property for the entity with the specified ID.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * OptionalChar grade = userDao.queryForChar("grade", 456L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @return an OptionalChar containing the value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalChar queryForChar(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForChar(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Returns an {@code OptionalByte} describing the value of a single property for the entity with the specified ID.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * OptionalByte level = userDao.queryForByte("userLevel", 789L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @return an OptionalByte containing the value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalByte queryForByte(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForByte(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Returns an {@code OptionalShort} describing the value of a single property for the entity with the specified ID.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * OptionalShort age = userDao.queryForShort("age", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @return an OptionalShort containing the value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalShort queryForShort(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForShort(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Returns an {@code OptionalInt} describing the value of a single property for the entity with the specified ID.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * OptionalInt loginCount = userDao.queryForInt("loginCount", 123L);
     * if (loginCount.isPresent() && loginCount.getAsInt() > 100) {
     *     // Frequent user
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @return an OptionalInt containing the value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalInt queryForInt(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForInt(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Returns an {@code OptionalLong} describing the value of a single property for the entity with the specified ID.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * OptionalLong totalBytes = userDao.queryForLong("storageUsed", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @return an OptionalLong containing the value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalLong queryForLong(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForLong(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Returns an {@code OptionalFloat} describing the value of a single property for the entity with the specified ID.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * OptionalFloat rating = userDao.queryForFloat("averageRating", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @return an OptionalFloat containing the value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalFloat queryForFloat(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForFloat(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Returns an {@code OptionalDouble} describing the value of a single property for the entity with the specified ID.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * OptionalDouble balance = userDao.queryForDouble("accountBalance", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @return an OptionalDouble containing the value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalDouble queryForDouble(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForDouble(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Returns a {@code Nullable<String>} describing the value of a single property for the entity with the specified ID.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Nullable<String> email = userDao.queryForString("email", 123L);
     * if (email.isPresent()) {
     *     sendNotification(email.get());
     * }
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @return a Nullable containing the String value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Nullable<String> queryForString(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForString(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Returns a {@code Nullable<java.sql.Date>} describing the value of a single property for the entity with the specified ID.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Nullable<java.sql.Date> birthDate = userDao.queryForDate("birthDate", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @return a Nullable containing the Date value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForDate(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Returns a {@code Nullable<java.sql.Time>} describing the value of a single property for the entity with the specified ID.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Nullable<java.sql.Time> startTime = userDao.queryForTime("workStartTime", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @return a Nullable containing the Time value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForTime(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Returns a {@code Nullable<java.sql.Timestamp>} describing the value of a single property for the entity with the specified ID.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Nullable<java.sql.Timestamp> lastLogin = userDao.queryForTimestamp("lastLoginTime", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @return a Nullable containing the Timestamp value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForTimestamp(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Returns a {@code Nullable<byte[]>} describing the value of a single property for the entity with the specified ID.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Nullable<byte[]> avatar = userDao.queryForBytes("profileImage", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @return a Nullable containing the byte array value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Nullable<byte[]> queryForBytes(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForBytes(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Returns a {@code Nullable<V>} describing the value of a single property converted to the target type.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Nullable<BigDecimal> price = userDao.queryForSingleResult("price", 123L, BigDecimal.class);
     * }</pre>
     *
     * @param <V> the target value type
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @param targetValueType the class of the target value type
     * @return a Nullable containing the converted value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <V> Nullable<V> queryForSingleResult(final String singleSelectPropName, final long id, final Class<? extends V> targetValueType)
            throws UncheckedSQLException {
        return queryForSingleResult(singleSelectPropName, Long.valueOf(id), targetValueType);
    }

    /**
     * Returns an {@code Optional} describing the non-null value of a single property.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<String> nickname = userDao.queryForSingleNonNull("nickname", 123L, String.class);
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @param targetValueType the class of the target value type
     * @return an Optional containing the non-null value, or empty if no entity found or value is null
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final long id, final Class<? extends V> targetValueType)
            throws UncheckedSQLException {
        return queryForSingleNonNull(singleSelectPropName, Long.valueOf(id), targetValueType);
    }

    /**
     * Returns an {@code Optional} describing the non-null value mapped by the row mapper.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<UserStatus> status = userDao.queryForSingleNonNull(
     *     "statusCode", 
     *     123L,
     *     rs -> UserStatus.fromCode(rs.getString(1))
     * );
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @param rowMapper the function to map the result set row
     * @return an Optional containing the non-null mapped value, or empty if no entity found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final long id, final Jdbc.RowMapper<? extends V> rowMapper)
            throws UncheckedSQLException {
        return queryForSingleNonNull(singleSelectPropName, Long.valueOf(id), rowMapper);
    }

    /**
     * Returns a {@code Nullable} describing the unique result value.
     * Throws {@code DuplicatedResultException} if more than one record is found.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Nullable<String> uniqueCode = userDao.queryForUniqueResult("code", 123L, String.class);
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @param targetValueType the class of the target value type
     * @return a Nullable containing the unique result value, or empty if no entity found
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <V> Nullable<V> queryForUniqueResult(final String singleSelectPropName, final long id, final Class<? extends V> targetValueType)
            throws DuplicatedResultException, UncheckedSQLException {
        return queryForUniqueResult(singleSelectPropName, Long.valueOf(id), targetValueType);
    }

    /**
     * Returns an {@code Optional} describing the unique non-null value.
     * Throws {@code DuplicatedResultException} if more than one record is found.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<Integer> level = userDao.queryForUniqueNonNull("level", 123L, Integer.class);
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @param targetValueType the class of the target value type
     * @return an Optional containing the unique non-null value, or empty if no entity found or value is null
     * @throws DuplicatedResultException if more than one record is found
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final long id, final Class<? extends V> targetValueType)
            throws DuplicatedResultException, UncheckedSQLException {
        return queryForUniqueNonNull(singleSelectPropName, Long.valueOf(id), targetValueType);
    }

    /**
     * Returns an {@code Optional} describing the unique non-null value mapped by the row mapper.
     * Throws {@code DuplicatedResultException} if more than one record is found.
     * This method accepts a primitive long ID for convenience.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<Permission> perm = userDao.queryForUniqueNonNull(
     *     "permissions",
     *     123L,
     *     rs -> Permission.parse(rs.getString(1))
     * );
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the property name to select
     * @param id the entity ID as primitive long
     * @param rowMapper the function to map the result set row
     * @return an Optional containing the unique non-null mapped value, or empty if no entity found
     * @throws DuplicatedResultException if more than one record is found
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
     * <p>Example usage:</p>
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
     * <p>Example usage:</p>
     * <pre>{@code
     * Optional<User> user = userDao.get(123L, Arrays.asList("id", "name", "email"));
     * }</pre>
     *
     * @param id the entity ID as primitive long
     * @param selectPropNames the properties to select, or null to select all
     * @return an Optional containing the entity with selected properties if found, otherwise empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Optional<T> get(final long id, final Collection<String> selectPropNames) throws UncheckedSQLException {
        return get(Long.valueOf(id), selectPropNames);
    }

    /**
     * Retrieves the entity with the specified ID, returning it directly or null if not found.
     * This method accepts a primitive long ID for convenience.
     *
     * <p>Example usage:</p>
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
     * Retrieves the entity with the specified ID and selected properties, returning it directly or null if not found.
     * This method accepts a primitive long ID for convenience.
     *
     * <p>Example usage:</p>
     * <pre>{@code
     * User user = userDao.gett(123L, Arrays.asList("id", "email", "status"));
     * }</pre>
     *
     * @param id the entity ID as primitive long
     * @param selectPropNames the properties to select, or null to select all
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
     * <p>Example usage:</p>
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
     * <p>Example usage:</p>
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
     * <p>Example usage:</p>
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
     * <p>Example usage:</p>
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
     * <p>Example usage:</p>
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