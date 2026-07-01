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

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.Jdbc;
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
 * Unchecked-exception, {@code Long}-id read capability for CRUD DAOs &mdash; the unchecked counterpart
 * of {@link CrudLReadOps}. Supplies the primitive-{@code long} read convenience overloads that throw
 * {@link UncheckedSQLException} instead of checked {@link java.sql.SQLException}, shared by every
 * unchecked {@code Long}-keyed CRUD DAO ({@link UncheckedCrudLDao}, {@link UncheckedNoUpdateCrudLDao},
 * {@link UncheckedReadOnlyCrudLDao}).
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-referencing DAO type
 * @see CrudLReadOps
 * @see UncheckedCrudReadOps
 * @see UncheckedCrudLDao
 */
@Beta
sealed interface UncheckedCrudLReadOps<T, TD extends UncheckedDaoBase<T, TD>> extends CrudLReadOps<T, TD>, UncheckedCrudReadOps<T, Long, TD>
        permits UncheckedCrudLDao, UncheckedNoUpdateCrudLDao, UncheckedReadOnlyCrudLDao {

    /**
     * Queries for a boolean value from a single property of the entity with the specified ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
     * Returns an empty {@code OptionalBoolean} only when no record matches the given id. If a matching record's value is SQL {@code null},
     * the returned optional is <i>present</i> and holds the primitive default ({@code false}); use
     * {@link #queryForSingleValue(String, long, Class)} to distinguish SQL {@code null} from a real {@code false}.
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
     * @return an {@code OptionalBoolean} holding the selected value when a record matches the id (present, holding the primitive default {@code false} when the value is SQL {@code null}), or an empty {@code OptionalBoolean} when no record matches the id
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalBoolean queryForBoolean(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForBoolean(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a char value from a single property of the entity with the specified ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
     * Returns an empty {@code OptionalChar} only when no record matches the given id. If a matching record's value is SQL {@code null},
     * the returned optional is <i>present</i> and holds the primitive default ({@code (char) 0}); use
     * {@link #queryForSingleValue(String, long, Class)} to distinguish SQL {@code null} from a real {@code (char) 0}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalChar grade = studentDao.queryForChar("grade", 123L);
     * grade.ifPresent(g -> System.out.println("Student grade: " + g));
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return an {@code OptionalChar} holding the selected value when a record matches the id (present, holding the primitive default {@code (char) 0} when the value is SQL {@code null}), or an empty {@code OptionalChar} when no record matches the id
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalChar queryForChar(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForChar(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a byte value from a single property of the entity with the specified ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
     * Returns an empty {@code OptionalByte} only when no record matches the given id. If a matching record's value is SQL {@code null},
     * the returned optional is <i>present</i> and holds the primitive default ({@code 0}); use
     * {@link #queryForSingleValue(String, long, Class)} to distinguish SQL {@code null} from a real {@code 0}.
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
     * @return an {@code OptionalByte} holding the selected value when a record matches the id (present, holding the primitive default {@code 0} when the value is SQL {@code null}), or an empty {@code OptionalByte} when no record matches the id
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalByte queryForByte(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForByte(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a short value from a single property of the entity with the specified ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
     * Returns an empty {@code OptionalShort} only when no record matches the given id. If a matching record's value is SQL {@code null},
     * the returned optional is <i>present</i> and holds the primitive default ({@code 0}); use
     * {@link #queryForSingleValue(String, long, Class)} to distinguish SQL {@code null} from a real {@code 0}.
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
     * @return an {@code OptionalShort} holding the selected value when a record matches the id (present, holding the primitive default {@code 0} when the value is SQL {@code null}), or an empty {@code OptionalShort} when no record matches the id
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalShort queryForShort(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForShort(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for an integer value from a single property of the entity with the specified ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
     * Returns an empty {@code OptionalInt} only when no record matches the given id. If a matching record's value is SQL {@code null},
     * the returned optional is <i>present</i> and holds the primitive default ({@code 0}); use
     * {@link #queryForSingleValue(String, long, Class)} to distinguish SQL {@code null} from a real {@code 0}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalInt age = userDao.queryForInt("age", 123L);
     * int userAge = age.orElse(0);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return an {@code OptionalInt} holding the selected value when a record matches the id (present, holding the primitive default {@code 0} when the value is SQL {@code null}), or an empty {@code OptionalInt} when no record matches the id
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalInt queryForInt(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForInt(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a long value from a single property of the entity with the specified ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
     * Returns an empty {@code OptionalLong} only when no record matches the given id. If a matching record's value is SQL {@code null},
     * the returned optional is <i>present</i> and holds the primitive default ({@code 0L}); use
     * {@link #queryForSingleValue(String, long, Class)} to distinguish SQL {@code null} from a real {@code 0L}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalLong totalBytes = userDao.queryForLong("storageUsed", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return an {@code OptionalLong} holding the selected value when a record matches the id (present, holding the primitive default {@code 0L} when the value is SQL {@code null}), or an empty {@code OptionalLong} when no record matches the id
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalLong queryForLong(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForLong(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a float value from a single property of the entity with the specified ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
     * Returns an empty {@code OptionalFloat} only when no record matches the given id. If a matching record's value is SQL {@code null},
     * the returned optional is <i>present</i> and holds the primitive default ({@code 0f}); use
     * {@link #queryForSingleValue(String, long, Class)} to distinguish SQL {@code null} from a real {@code 0f}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalFloat rating = userDao.queryForFloat("averageRating", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return an {@code OptionalFloat} holding the selected value when a record matches the id (present, holding the primitive default {@code 0f} when the value is SQL {@code null}), or an empty {@code OptionalFloat} when no record matches the id
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalFloat queryForFloat(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForFloat(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a double value from a single property of the entity with the specified ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
     * Returns an empty {@code OptionalDouble} only when no record matches the given id. If a matching record's value is SQL {@code null},
     * the returned optional is <i>present</i> and holds the primitive default ({@code 0d}); use
     * {@link #queryForSingleValue(String, long, Class)} to distinguish SQL {@code null} from a real {@code 0d}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalDouble balance = userDao.queryForDouble("accountBalance", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return an {@code OptionalDouble} holding the selected value when a record matches the id (present, holding the primitive default {@code 0d} when the value is SQL {@code null}), or an empty {@code OptionalDouble} when no record matches the id
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default OptionalDouble queryForDouble(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForDouble(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a String value from a single property of the entity with the specified ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
     * Returns a {@link Nullable} holding the value, whose contained value may be {@code null} if the database value is {@code null}.
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
     * @return a {@code Nullable} containing the String value if found, or {@code Nullable.empty()} if no record exists
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Nullable<String> queryForString(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForString(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a {@link java.sql.Date} value from a single property of the entity with the specified ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
     * Returns a {@link Nullable} holding the value, whose contained value may be {@code null} if the database value is {@code null}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Date> birthDate = userDao.queryForDate("birthDate", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return a {@code Nullable} containing the {@link java.sql.Date} value if found, or {@code Nullable.empty()} if no record exists
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForDate(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a {@link java.sql.Time} value from a single property of the entity with the specified ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
     * Returns a {@link Nullable} holding the value, whose contained value may be {@code null} if the database value is {@code null}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Time> startTime = userDao.queryForTime("workStartTime", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return a {@code Nullable} containing the {@link java.sql.Time} value if found, or {@code Nullable.empty()} if no record exists
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForTime(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a {@link java.sql.Timestamp} value from a single property of the entity with the specified ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
     * Returns a {@link Nullable} holding the value, whose contained value may be {@code null} if the database value is {@code null}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Timestamp> lastLogin = userDao.queryForTimestamp("lastLoginTime", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return a {@code Nullable} containing the {@link java.sql.Timestamp} value if found, or {@code Nullable.empty()} if no record exists
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForTimestamp(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a byte array value from a single property of the entity with the specified ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
     * Returns a {@link Nullable} holding the value, whose contained value may be {@code null} if the database value is {@code null}.
     * This is typically used for BLOB data.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<byte[]> avatar = userDao.queryForBytes("profileImage", 123L);
     * }</pre>
     *
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @return a {@code Nullable} containing the byte array value if found, or {@code Nullable.empty()} if no record exists
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Nullable<byte[]> queryForBytes(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForBytes(singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Queries for a single value of the specified type from a property of the entity with the specified ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
     * This is a generic method that can handle any type conversion supported by the underlying JDBC driver.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<BigDecimal> price = userDao.queryForSingleValue("price", 123L, BigDecimal.class);
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @param targetValueType the class of the value type to convert to
     * @return a {@code Nullable} containing the value if found, or {@code Nullable.empty()} if no record exists
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <V> Nullable<V> queryForSingleValue(final String singleSelectPropName, final long id, final Class<? extends V> targetValueType)
            throws UncheckedSQLException {
        return queryForSingleValue(singleSelectPropName, Long.valueOf(id), targetValueType);
    }

    /**
     * Queries for a single non-null value of the specified type from a property of the entity with the specified ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
     * Returns an empty {@code Optional} only if no record is found.
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
     * @return an {@code Optional} containing the non-null value if a record matches the {@code id} and the value is not SQL {@code null}, otherwise empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final long id, final Class<? extends V> targetValueType)
            throws UncheckedSQLException {
        return queryForSingleNonNull(singleSelectPropName, Long.valueOf(id), targetValueType);
    }

    /**
     * Queries for a single non-null value using a custom row mapper.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
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
     * @param <V> the value type produced by the row mapper
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @param rowMapper the function to map the result set row
     * @return an {@code Optional} containing the mapped non-null value if a record matches the {@code id} and the value is not SQL {@code null}, otherwise empty
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final long id, final Jdbc.RowMapper<? extends V> rowMapper)
            throws UncheckedSQLException {
        return queryForSingleNonNull(singleSelectPropName, Long.valueOf(id), rowMapper);
    }

    /**
     * Queries for a unique single result of the specified type.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
     * Throws {@link DuplicateResultException} if more than one record is found.
     *
     * <p>This method ensures that at most one record matches the query.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<String> uniqueCode = userDao.queryForUniqueValue("code", 123L, String.class);
     * }</pre>
     *
     * @param <V> the specific property value type to be retrieved and converted
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @param targetValueType the class of the value type to convert to
     * @return a {@code Nullable} containing the unique value if found, or {@code Nullable.empty()} if no record exists
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <V> Nullable<V> queryForUniqueValue(final String singleSelectPropName, final long id, final Class<? extends V> targetValueType)
            throws DuplicateResultException, UncheckedSQLException {
        return queryForUniqueValue(singleSelectPropName, Long.valueOf(id), targetValueType);
    }

    /**
     * Queries for a unique non-null result of the specified type.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
     * Throws {@link DuplicateResultException} if more than one record is found.
     * Returns an empty {@code Optional} only if no record is found.
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
     * @return an {@code Optional} containing the unique non-null value if a record matches the {@code id} and the value is not SQL {@code null}, otherwise empty
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final long id, final Class<? extends V> targetValueType)
            throws DuplicateResultException, UncheckedSQLException {
        return queryForUniqueNonNull(singleSelectPropName, Long.valueOf(id), targetValueType);
    }

    /**
     * Queries for a unique non-null result using a custom row mapper.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to the corresponding {@code UncheckedCrudDao} method.
     * Throws {@link DuplicateResultException} if more than one record is found.
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
     * @param <V> the value type produced by the row mapper
     * @param singleSelectPropName the property name to select
     * @param id the primitive long ID of the entity
     * @param rowMapper the function to map the result set row
     * @return an {@code Optional} containing the mapped unique non-null value if a record matches the {@code id} and the value is not SQL {@code null}, otherwise empty
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final long id, final Jdbc.RowMapper<? extends V> rowMapper)
            throws DuplicateResultException, UncheckedSQLException {
        return queryForUniqueNonNull(singleSelectPropName, Long.valueOf(id), rowMapper);
    }

    /**
     * Retrieves an entity by its ID.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to {@link UncheckedCrudDao#get(Object)}.
     * Returns an {@link Optional} containing the entity if found, otherwise empty.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<User> user = userDao.get(123L);
     * user.ifPresent(u -> System.out.println("Found: " + u.getName()));
     * }</pre>
     *
     * @param id the primitive long ID of the entity to retrieve
     * @return an {@code Optional} containing the entity if found, otherwise empty
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Optional<T> get(final long id) throws UncheckedSQLException {
        return get(Long.valueOf(id));
    }

    /**
     * Retrieves an entity by its ID with only the selected properties populated.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to {@link UncheckedCrudDao#get(Object, Collection)}.
     * Properties not in the select list will have their default values.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<User> user = userDao.get(123L, Arrays.asList("id", "name", "email"));
     * }</pre>
     *
     * @param id the primitive long ID of the entity to retrieve
     * @param selectPropNames the properties to select, excluding properties of joining entities.
     *                        All properties will be selected if {@code null}
     * @return an {@code Optional} containing the entity with selected properties if found, otherwise empty
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default Optional<T> get(final long id, final Collection<String> selectPropNames) throws UncheckedSQLException {
        return get(Long.valueOf(id), selectPropNames);
    }

    /**
     * Retrieves an entity by its ID, returning {@code null} if not found.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to {@link UncheckedCrudDao#gett(Object)}. Unlike {@link #get(long)}, the
     * entity is returned directly rather than wrapped in an {@link Optional}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(123L);
     * if (user != null) {
     *     processUser(user);
     * }
     * }</pre>
     *
     * @param id the primitive long ID of the entity to retrieve
     * @return the entity if found, otherwise {@code null}
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default T gett(final long id) throws UncheckedSQLException {
        return gett(Long.valueOf(id));
    }

    /**
     * Retrieves an entity by its ID with only the selected properties populated, returning {@code null} if not found.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to {@link UncheckedCrudDao#gett(Object, Collection)}.
     * This is useful for performance optimization when you only need specific fields.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(123L, Arrays.asList("id", "email", "status"));
     * }</pre>
     *
     * @param id the primitive long ID of the entity to retrieve
     * @param selectPropNames the properties to select, excluding properties of joining entities.
     *                        All properties will be selected if {@code null}
     * @return the entity with selected properties if found, otherwise {@code null}
     * @throws DuplicateResultException if more than one record is found by the specified {@code id}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default T gett(final long id, final Collection<String> selectPropNames) throws UncheckedSQLException {
        return gett(Long.valueOf(id), selectPropNames);
    }

    /**
     * Checks if an entity with the specified ID exists in the database.
     * This is a convenience overload that accepts a primitive {@code long} ID; the value is boxed
     * to {@link Long} and delegated to {@link UncheckedCrudDao#exists(Object)}.
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
     * @param id the primitive long ID to check for existence
     * @return {@code true} if the entity exists, {@code false} otherwise
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Override
    default boolean exists(final long id) throws UncheckedSQLException {
        return exists(Long.valueOf(id));
    }

    /**
     * Checks if an entity with the specified ID does not exist in the database.
     * This is a convenience overload that accepts a primitive {@code long} ID;
     * it simply negates the result of {@link #exists(long)}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (userDao.notExists(123L)) {
     *     // Safe to create new user with this ID
     * }
     * }</pre>
     *
     * @param id the primitive long ID to check for non-existence
     * @return {@code true} if the entity does not exist, {@code false} if it exists
     * @throws UncheckedSQLException if a database access error occurs
     */
    @Beta
    @Override
    default boolean notExists(final long id) throws UncheckedSQLException {
        return !exists(id);
    }
}
