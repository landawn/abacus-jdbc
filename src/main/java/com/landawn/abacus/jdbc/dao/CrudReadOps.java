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
import java.util.List;
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.jdbc.AbstractQuery;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.query.QueryUtil;
import com.landawn.abacus.util.Beans;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.N;
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

/**
 * Read capability of {@link CrudDao}: id-based reads ({@code get}/{@code gett}/{@code batchGet}),
 * {@code exists}/{@code count} by id, {@code queryForXxx(propName, id)}, {@code refresh}, and {@code generateId()}.
 * Extends {@link ReadOps}.
 *
 * @param <T> entity type
 * @param <ID> id type
 * @param <TD> self DAO type
 * @see CrudDao
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
sealed interface CrudReadOps<T, ID, TD extends ReadOps<T, TD>> extends ReadOps<T, TD>
        permits CrudDao, CrudLReadOps, NoUpdateCrudDao, ReadOnlyCrudDao, UncheckedCrudReadOps {
    /**
     * Returns a {@link Jdbc.BiRowMapper} that extracts the ID from a database row.
     * This mapper is used internally to extract ID values from query results (for example,
     * after an insert returns generated keys).
     *
     * <p>Override this method to provide a custom ID extractor if the default behavior doesn't suit your needs.
     * The default implementation returns {@code null}, which signals that the framework should use its
     * default ID extraction strategy.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Override
     * public Jdbc.BiRowMapper<Long> idExtractor() {
     *     return (rs, columnLabels) -> rs.getLong("id");
     * }
     * }</pre>
     *
     * @return a {@link Jdbc.BiRowMapper} that extracts the ID from a row, or {@code null} to use default extraction
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
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @Override
     * public Long generateId() throws SQLException {
     *     return System.currentTimeMillis();   // Simple timestamp-based ID
     * }
     * }</pre>
     *
     * @return the generated ID
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the operation is not supported (default behavior)
     * @deprecated This operation is deprecated as ID generation should typically be handled by the database
     *             (e.g., via auto-increment columns or sequences). Override this method only if a client-side
     *             ID generation strategy is required.
     */
    @Deprecated
    @NonDBOperation
    default ID generateId() throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException("ID generation is not supported by default");
    }

    /**
     * Queries for a boolean value from a single property of the entity with the specified ID.
     * Returns an empty {@code OptionalBoolean} only when no record matches the given id. If a matching record's value is SQL {@code null},
     * the returned optional is <i>present</i> and holds the primitive default ({@code false}); use
     * {@link #queryForSingleValue(String, Object, Class)} to distinguish SQL {@code null} from a real {@code false}.
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
     * @return an {@code OptionalBoolean} holding the selected value when a record matches the id (present, holding the primitive default {@code false} when the value is SQL {@code null}), or an empty {@code OptionalBoolean} when no record matches the id
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForBoolean()
     */
    OptionalBoolean queryForBoolean(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a char value from a single property of the entity with the specified ID.
     * Returns an empty {@code OptionalChar} only when no record matches the given id. If a matching record's value is SQL {@code null},
     * the returned optional is <i>present</i> and holds the primitive default ({@code (char) 0}); use
     * {@link #queryForSingleValue(String, Object, Class)} to distinguish SQL {@code null} from a real {@code (char) 0}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalChar grade = studentDao.queryForChar("grade", studentId);
     * grade.ifPresent(g -> System.out.println("Student grade: " + g));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an {@code OptionalChar} holding the selected value when a record matches the id (present, holding the primitive default {@code (char) 0} when the value is SQL {@code null}), or an empty {@code OptionalChar} when no record matches the id
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForChar()
     */
    OptionalChar queryForChar(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a byte value from a single property of the entity with the specified ID.
     * Returns an empty {@code OptionalByte} only when no record matches the given id. If a matching record's value is SQL {@code null},
     * the returned optional is <i>present</i> and holds the primitive default ({@code 0}); use
     * {@link #queryForSingleValue(String, Object, Class)} to distinguish SQL {@code null} from a real {@code 0}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalByte flags = entityDao.queryForByte("flags", entityId);
     * byte flagValue = flags.orElse((byte) 0);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an {@code OptionalByte} holding the selected value when a record matches the id (present, holding the primitive default {@code 0} when the value is SQL {@code null}), or an empty {@code OptionalByte} when no record matches the id
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForByte()
     */
    OptionalByte queryForByte(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a short value from a single property of the entity with the specified ID.
     * Returns an empty {@code OptionalShort} only when no record matches the given id. If a matching record's value is SQL {@code null},
     * the returned optional is <i>present</i> and holds the primitive default ({@code 0}); use
     * {@link #queryForSingleValue(String, Object, Class)} to distinguish SQL {@code null} from a real {@code 0}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalShort port = serverDao.queryForShort("port", serverId);
     * short portNumber = port.orElse((short) 8080);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an {@code OptionalShort} holding the selected value when a record matches the id (present, holding the primitive default {@code 0} when the value is SQL {@code null}), or an empty {@code OptionalShort} when no record matches the id
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForShort()
     */
    OptionalShort queryForShort(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for an integer value from a single property of the entity with the specified ID.
     * Returns an empty {@code OptionalInt} only when no record matches the given id. If a matching record's value is SQL {@code null},
     * the returned optional is <i>present</i> and holds the primitive default ({@code 0}); use
     * {@link #queryForSingleValue(String, Object, Class)} to distinguish SQL {@code null} from a real {@code 0}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalInt age = userDao.queryForInt("age", userId);
     * int userAge = age.orElse(0);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an {@code OptionalInt} holding the selected value when a record matches the id (present, holding the primitive default {@code 0} when the value is SQL {@code null}), or an empty {@code OptionalInt} when no record matches the id
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForInt()
     */
    OptionalInt queryForInt(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a long value from a single property of the entity with the specified ID.
     * Returns an empty {@code OptionalLong} only when no record matches the given id. If a matching record's value is SQL {@code null},
     * the returned optional is <i>present</i> and holds the primitive default ({@code 0L}); use
     * {@link #queryForSingleValue(String, Object, Class)} to distinguish SQL {@code null} from a real {@code 0L}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalLong lastLoginTime = userDao.queryForLong("lastLoginTimestamp", userId);
     * lastLoginTime.ifPresent(time -> System.out.println("Last login: " + new Date(time)));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an {@code OptionalLong} holding the selected value when a record matches the id (present, holding the primitive default {@code 0L} when the value is SQL {@code null}), or an empty {@code OptionalLong} when no record matches the id
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForLong()
     */
    OptionalLong queryForLong(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a float value from a single property of the entity with the specified ID.
     * Returns an empty {@code OptionalFloat} only when no record matches the given id. If a matching record's value is SQL {@code null},
     * the returned optional is <i>present</i> and holds the primitive default ({@code 0f}); use
     * {@link #queryForSingleValue(String, Object, Class)} to distinguish SQL {@code null} from a real {@code 0f}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalFloat rating = productDao.queryForFloat("rating", productId);
     * float productRating = rating.orElse(0.0f);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an {@code OptionalFloat} holding the selected value when a record matches the id (present, holding the primitive default {@code 0f} when the value is SQL {@code null}), or an empty {@code OptionalFloat} when no record matches the id
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForFloat()
     */
    OptionalFloat queryForFloat(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a double value from a single property of the entity with the specified ID.
     * Returns an empty {@code OptionalDouble} only when no record matches the given id. If a matching record's value is SQL {@code null},
     * the returned optional is <i>present</i> and holds the primitive default ({@code 0d}); use
     * {@link #queryForSingleValue(String, Object, Class)} to distinguish SQL {@code null} from a real {@code 0d}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalDouble balance = accountDao.queryForDouble("balance", accountId);
     * double amount = balance.orElse(0.0);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return an {@code OptionalDouble} holding the selected value when a record matches the id (present, holding the primitive default {@code 0d} when the value is SQL {@code null}), or an empty {@code OptionalDouble} when no record matches the id
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForDouble()
     */
    OptionalDouble queryForDouble(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a String value from a single property of the entity with the specified ID.
     * Returns a {@code Nullable} containing the value, which can be {@code null} if the database value is {@code null}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<String> email = userDao.queryForString("email", userId);
     * String userEmail = email.orElse("no-email@example.com");
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return a {@code Nullable} containing the String value if found, or {@code Nullable.empty()} if no record exists
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForString()
     */
    Nullable<String> queryForString(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a Date value from a single property of the entity with the specified ID.
     * Returns a {@code Nullable} containing the value, which can be {@code null} if the database value is {@code null}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Date> birthDate = userDao.queryForDate("birthDate", userId);
     * birthDate.ifPresent(date -> System.out.println("Birth date: " + date));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return a {@code Nullable} containing the Date value if found, or {@code Nullable.empty()} if no record exists
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForDate()
     */
    Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a Time value from a single property of the entity with the specified ID.
     * Returns a {@code Nullable} containing the value, which can be {@code null} if the database value is {@code null}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Time> startTime = scheduleDao.queryForTime("startTime", scheduleId);
     * startTime.ifPresent(time -> System.out.println("Start time: " + time));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return a {@code Nullable} containing the Time value if found, or {@code Nullable.empty()} if no record exists
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForTime()
     */
    Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a Timestamp value from a single property of the entity with the specified ID.
     * Returns a {@code Nullable} containing the value, which can be {@code null} if the database value is {@code null}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<Timestamp> lastModified = userDao.queryForTimestamp("lastModified", userId);
     * lastModified.ifPresent(ts -> System.out.println("Last modified: " + ts));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @return a {@code Nullable} containing the Timestamp value if found, or {@code Nullable.empty()} if no record exists
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForTimestamp()
     */
    Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a byte array value from a single property of the entity with the specified ID.
     * Returns a {@code Nullable} containing the value, which can be {@code null} if the database value is {@code null}.
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
     * @return a {@code Nullable} containing the byte array value if found, or {@code Nullable.empty()} if no record exists
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForBytes()
     */
    Nullable<byte[]> queryForBytes(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Queries for a single value of the specified type from a property of the entity with the specified ID.
     * This is a generic method that can handle any type conversion supported by the underlying JDBC driver.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<BigDecimal> salary = userDao.queryForSingleValue("salary", userId, BigDecimal.class);
     * Nullable<UserStatus> status = userDao.queryForSingleValue("status", userId, UserStatus.class);
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @param targetValueType the class of the value type to convert to
     * @return a {@code Nullable} containing the value if found, or {@code Nullable.empty()} if no record exists
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForSingleValue(Class)
     */
    <V> Nullable<V> queryForSingleValue(final String singleSelectPropName, final ID id, final Class<? extends V> targetValueType) throws SQLException;

    /**
     * Queries for a single non-null value of the specified type from a property of the entity.
     * Returns an empty {@code Optional} only if no record is found.
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
     * @return an {@code Optional} containing the non-null value if a record matches the {@code id}, otherwise empty
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForSingleNonNull(Class)
     */
    <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final ID id, final Class<? extends V> targetValueType) throws SQLException;

    /**
     * Queries for a single non-null value using a custom row mapper.
     * This allows for complex transformations of the result.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<String> upperFirstName = userDao.queryForSingleNonNull("firstName", userId,
     *     rs -> rs.getString(1).toUpperCase());
     * }</pre>
     *
     * @param <V> the value type produced by the row mapper
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @param rowMapper the custom mapper that transforms a single-column {@link java.sql.ResultSet} row
     * @return an {@link Optional} containing the mapped non-null value if a record matches the {@code id}, otherwise empty
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForSingleNonNull(Class)
     */
    @Beta
    <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final ID id, final Jdbc.RowMapper<? extends V> rowMapper) throws SQLException;

    /**
     * Queries for a unique single result of the specified type.
     * Throws {@link DuplicateResultException} if more than one record is found.
     *
     * <p>This method ensures that at most one record matches the query.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<String> email = userDao.queryForUniqueValue("email", userId, String.class);
     * // Throws DuplicateResultException if multiple records found
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @param targetValueType the class of the value type to convert to
     * @return a {@code Nullable} containing the unique value if found, or {@code Nullable.empty()} if no record exists
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForUniqueValue(Class)
     */
    <V> Nullable<V> queryForUniqueValue(final String singleSelectPropName, final ID id, final Class<? extends V> targetValueType)
            throws DuplicateResultException, SQLException;

    /**
     * Queries for a unique non-null result of the specified type.
     * Throws {@link DuplicateResultException} if more than one record is found.
     * Returns an empty {@code Optional} only if no record is found.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<String> email = userDao.queryForUniqueNonNull("email", userId, String.class);
     * email.ifPresent(e -> sendEmail(e));
     * // Throws DuplicateResultException if multiple records found
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @param targetValueType the class of the value type to convert to
     * @return an {@code Optional} containing the unique non-null value if a record matches the {@code id}, otherwise empty
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForUniqueNonNull(Class)
     */
    <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final ID id, final Class<? extends V> targetValueType)
            throws DuplicateResultException, SQLException;

    /**
     * Queries for a unique non-null result using a custom row mapper.
     * Throws {@link DuplicateResultException} if more than one record is found.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<String> upperName = userDao.queryForUniqueNonNull("name", userId,
     *     rs -> rs.getString(1).toUpperCase());
     * upperName.ifPresent(name -> System.out.println("Name: " + name));
     * }</pre>
     *
     * @param <V> the value type produced by the row mapper
     * @param singleSelectPropName the property name to select
     * @param id the entity ID
     * @param rowMapper the custom mapper that transforms a single-column {@link java.sql.ResultSet} row
     * @return an {@link Optional} containing the mapped unique non-null value if a record matches the {@code id}, otherwise empty
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForUniqueNonNull(Class)
     */
    @Beta
    <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final ID id, final Jdbc.RowMapper<? extends V> rowMapper)
            throws DuplicateResultException, SQLException;

    /**
     * Retrieves an entity by its ID.
     * This is a convenience default method that wraps the result of {@link #gett(Object)} in an {@link Optional}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<User> user = userDao.get(userId);
     * user.ifPresent(u -> System.out.println("Found user: " + u.getName()));
     * }</pre>
     *
     * @param id the ID of the entity to retrieve
     * @return an {@link Optional} containing the entity if found, otherwise empty
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    default Optional<T> get(final ID id) throws DuplicateResultException, SQLException {
        return Optional.ofNullable(gett(id));
    }

    /**
     * Retrieves an entity by its ID with only the selected properties populated.
     * Properties not in the select list will have their default (un-set) values.
     * This is a convenience default method that wraps the result of {@link #gett(Object, Collection)}
     * in an {@link Optional}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Only load id, name, and email fields
     * Optional<User> user = userDao.get(userId, Arrays.asList("id", "name", "email"));
     * }</pre>
     *
     * @param id the ID of the entity to retrieve
     * @param selectPropNames the properties to select, excluding properties of joining entities.
     *                        All properties will be selected if {@code null}
     * @return an {@link Optional} containing the entity if found, otherwise empty
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    default Optional<T> get(final ID id, final Collection<String> selectPropNames) throws DuplicateResultException, SQLException {
        return Optional.ofNullable(gett(id, selectPropNames));
    }

    /**
     * Retrieves an entity by its ID, returning {@code null} if not found.
     * Unlike {@link #get(Object)}, the entity is returned directly rather than wrapped in an {@link Optional}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * if (user != null) {
     *     System.out.println("Found user: " + user.getName());
     * }
     * }</pre>
     *
     * @param id the ID of the entity to retrieve
     * @return the entity if found, otherwise {@code null}
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    T gett(final ID id) throws DuplicateResultException, SQLException;

    /**
     * Retrieves an entity by its ID with only the selected properties populated, returning {@code null} if not found.
     * This is useful for performance optimization when you only need specific fields.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Only load id, name, and email fields
     * User user = userDao.gett(userId, Arrays.asList("id", "name", "email"));
     * if (user != null) {
     *     System.out.println("User name: " + user.getName());
     * }
     * }</pre>
     *
     * @param id the ID of the entity to retrieve
     * @param selectPropNames the properties to select, excluding properties of joining entities.
     *                        All properties will be selected if {@code null}
     * @return the entity if found, otherwise {@code null}
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    T gett(final ID id, final Collection<String> selectPropNames) throws DuplicateResultException, SQLException;

    /**
     * Retrieves multiple entities by their IDs using the default batch size
     * ({@link JdbcUtil#DEFAULT_BATCH_SIZE}). All properties are loaded for the matching entities.
     * The returned list may be smaller than the input ID collection if some entities are not found.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> userIds = Arrays.asList(1L, 2L, 3L);
     * List<User> users = userDao.batchGet(userIds);
     * }</pre>
     *
     * @param ids the collection of IDs to retrieve
     * @return a list of found entities (order is not guaranteed to match the input IDs)
     * @throws DuplicateResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    default List<T> batchGet(final Collection<? extends ID> ids) throws DuplicateResultException, SQLException {
        return batchGet(ids, null);
    }

    /**
     * Retrieves multiple entities by their IDs with a specified batch size.
     * All properties are loaded for the matching entities.
     * Large ID collections will be processed in batches to avoid database query size limits.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> largeIdList = getIdsFromReport();   // 50000 IDs
     * // Process in batches of 1000 to avoid SQL query size limits
     * List<User> users = userDao.batchGet(largeIdList, 1000);
     * }</pre>
     *
     * @param ids the collection of IDs to retrieve
     * @param batchSize the number of IDs to query for in each batch. The operation will split
     *                  large collections into chunks of this size.
     * @return a list of found entities (order is not guaranteed to match the input IDs)
     * @throws DuplicateResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    default List<T> batchGet(final Collection<? extends ID> ids, final int batchSize) throws DuplicateResultException, SQLException {
        return batchGet(ids, null, batchSize);
    }

    /**
     * Retrieves multiple entities by their IDs with only selected properties populated.
     * Uses the default batch size ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> userIds = Arrays.asList(1L, 2L, 3L);
     * List<User> users = userDao.batchGet(userIds, Arrays.asList("id", "name", "email"));
     * }</pre>
     *
     * @param ids the collection of IDs to retrieve
     * @param selectPropNames the properties to select, excluding properties of joining entities.
     *                        All properties will be selected if {@code null}
     * @return a list of found entities (order is not guaranteed to match the input IDs)
     * @throws DuplicateResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames) throws DuplicateResultException, SQLException {
        return batchGet(ids, selectPropNames, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Retrieves multiple entities by their IDs with only selected properties populated and custom batch size.
     * This provides the most control over batch retrieval operations.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<Long> largeIdList = getAllUserIds();   // 30000 IDs
     * // Only fetch id, name, email in batches of 2000
     * List<User> users = userDao.batchGet(largeIdList,
     *                                     Arrays.asList("id", "name", "email"),
     *                                     2000);
     * }</pre>
     *
     * @param ids the collection of IDs to retrieve
     * @param selectPropNames the properties to select, excluding properties of joining entities.
     *                        All properties will be selected if {@code null}
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return a list of found entities (order is not guaranteed to match the input IDs)
     * @throws DuplicateResultException if the size of result is bigger than the size of input {@code ids}
     * @throws SQLException if a database access error occurs
     */
    List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize)
            throws DuplicateResultException, SQLException;

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
     * This is a convenience default method that negates the result of {@link #exists(Object)}.
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
     * @return the number of records in the database whose IDs are contained in {@code ids}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    int count(final Collection<? extends ID> ids) throws SQLException;

    /**
     * Refreshes the given entity by reloading all of its (non-join) properties from the database
     * and copying them into the entity in place. The ID property of {@code entity} is used to
     * locate the database record.
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
     * @param entity the entity to refresh (must not be {@code null} and must have its ID populated)
     * @return {@code true} if the matching database row was found and {@code entity} was updated;
     *         {@code false} if no matching row exists
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code entity} is {@code null}
     */
    @Beta
    default boolean refresh(final T entity) throws SQLException {
        N.checkArgNotNull(entity, cs.entity);

        final Class<?> cls = entity.getClass();
        final Collection<String> propNamesToRefresh = JdbcUtil.getSelectPropNames(cls);

        return refresh(entity, propNamesToRefresh);
    }

    /**
     * Refreshes specific properties of the given entity from the database.
     * Only the specified properties will be reloaded and copied into {@code entity} in place;
     * other properties are left untouched. The ID property of {@code entity} is used to locate
     * the database record.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = getUser();
     * // Only refresh balance and status fields
     * userDao.refresh(user, Arrays.asList("balance", "status"));
     * }</pre>
     *
     * @param entity the entity to refresh (must not be {@code null} and must have its ID populated)
     * @param propNamesToRefresh the properties to refresh from the database (must not be {@code null} or empty)
     * @return {@code true} if the matching database row was found and {@code entity} was updated;
     *         {@code false} if no matching row exists
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code entity} is {@code null} or {@code propNamesToRefresh} is {@code null} or empty
     */
    @Beta
    default boolean refresh(final T entity, final Collection<String> propNamesToRefresh) throws SQLException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotEmpty(propNamesToRefresh, cs.propNamesToRefresh);

        final Class<?> cls = entity.getClass();
        final List<String> idPropNameList = QueryUtil.getIdPropNames(cls); // guaranteed non-empty for a CRUD entity class.
        final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);

        final ID id = DaoUtil.extractId(entity, idPropNameList, entityInfo);
        final Collection<String> selectPropNames = DaoUtil.getRefreshSelectPropNames(propNamesToRefresh, idPropNameList);

        final T dbEntity = gett(id, selectPropNames);

        if (dbEntity == null) {
            return false;
        } else {
            Beans.mergeInto(dbEntity, entity, propNamesToRefresh);

            return true;
        }
    }

    /**
     * Refreshes multiple entities from the database using the default batch size
     * ({@link JdbcUtil#DEFAULT_BATCH_SIZE}). All non-join properties are reloaded.
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
     * @return the number of entities (input elements) that were updated from a matching database row.
     *         Note: if multiple input entities share the same ID, all of them are refreshed and counted.
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default int batchRefresh(final Collection<? extends T> entities) throws SQLException {
        return batchRefresh(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Refreshes multiple entities from the database with a specified batch size.
     * All non-join properties are reloaded.
     * Large collections will be processed in batches of the specified size.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> cachedUsers = getCachedUsers();   // 10000 cached entities
     * int refreshedCount = userDao.batchRefresh(cachedUsers, 1000);
     * System.out.println(refreshedCount + " users refreshed from database");
     * }</pre>
     *
     * @param entities the collection of entities to refresh
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return the number of entities (input elements) that were updated from a matching database row.
     *         Note: if multiple input entities share the same ID, all of them are refreshed and counted.
     * @throws SQLException if a database access error occurs
     */
    @Beta
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
     * Refreshes specific properties of multiple entities using the default batch size
     * ({@link JdbcUtil#DEFAULT_BATCH_SIZE}).
     * Only the specified properties will be reloaded and copied into the input entities in place.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getCachedUsers();
     * // Only refresh balance and lastModified for all users
     * int count = userDao.batchRefresh(users, Arrays.asList("balance", "lastModified"));
     * }</pre>
     *
     * @param entities the collection of entities to refresh
     * @param propNamesToRefresh the properties to refresh from the database (must not be {@code null} or empty)
     * @return the number of entities (input elements) that were updated from a matching database row.
     *         Note: if multiple input entities share the same ID, all of them are refreshed and counted.
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code propNamesToRefresh} is {@code null} or empty
     */
    @Beta
    default int batchRefresh(final Collection<? extends T> entities, final Collection<String> propNamesToRefresh) throws SQLException {
        return batchRefresh(entities, propNamesToRefresh, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Refreshes specific properties of multiple entities with a custom batch size.
     * This provides the most control over batch refresh operations.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> cachedUsers = getCachedUsers();   // 8000 cached entities
     * // Refresh only balance and status fields in batches of 800
     * int count = userDao.batchRefresh(cachedUsers,
     *                                  Arrays.asList("balance", "status"),
     *                                  800);
     * }</pre>
     *
     * @param entities the collection of entities to refresh
     * @param propNamesToRefresh the properties to refresh from the database (must not be {@code null} or empty)
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @return the number of entities (input elements) that were updated from a matching database row.
     *         Note: if multiple input entities share the same ID, all of them are refreshed and counted.
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code propNamesToRefresh} is {@code null}/empty or {@code batchSize} is not positive
     */
    @Beta
    default int batchRefresh(final Collection<? extends T> entities, final Collection<String> propNamesToRefresh, final int batchSize) throws SQLException {
        N.checkArgNotEmpty(propNamesToRefresh, cs.propNamesToRefresh);
        N.checkArgPositive(batchSize, cs.batchSize);

        if (N.isEmpty(entities)) {
            return 0;
        }

        final T first = N.firstOrNullIfEmpty(entities);
        final Class<?> cls = first.getClass();
        final List<String> idPropNameList = QueryUtil.getIdPropNames(cls); // guaranteed non-empty for a CRUD entity class.
        final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);

        final com.landawn.abacus.util.function.Function<T, ID> idExtractorFunc = DaoUtil.createIdExtractor(idPropNameList, entityInfo);
        final Map<ID, List<T>> idEntityMap = Stream.of(entities).groupTo(idExtractorFunc, Fn.identity());
        final Collection<String> selectPropNames = DaoUtil.getRefreshSelectPropNames(propNamesToRefresh, idPropNameList);

        final List<T> dbEntities = batchGet(idEntityMap.keySet(), selectPropNames, batchSize);

        if (N.isEmpty(dbEntities)) {
            return 0;
        } else {
            return dbEntities.stream().mapToInt(dbEntity -> {
                final ID id = idExtractorFunc.apply(dbEntity);
                final List<T> matchingEntities = idEntityMap.get(id);

                if (N.notEmpty(matchingEntities)) {
                    for (final T entity : matchingEntities) {
                        Beans.mergeInto(dbEntity, entity, propNamesToRefresh);
                    }
                }

                return N.size(matchingEntities);
            }).sum();
        }
    }

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

}
