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
import java.util.function.Consumer;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.LazyEvaluation;
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.jdbc.AbstractQuery;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.Jdbc.Columns.ColumnOne;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.Dataset;
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
import com.landawn.abacus.util.stream.Stream;

/**
 * Read-only capability of {@link Dao}: queries, single-value lookups, and streaming/paging. The shared
 * DAO accessors ({@code dataSource()}, {@code targetEntityClass()}, etc.) and the SELECT-form
 * {@code prepareQuery}/{@code prepareNamedQuery} builders are inherited from {@link DaoBase}.
 *
 * <p>Contains no data-modifying operation, so it is the root mixed into read-only DAOs
 * ({@link ReadOnlyDao}).</p>
 *
 * <p><b>&#9888; Warning:</b> The caller owns streams returned by this API and must close them. Fetch
 * size is a driver hint, not a portable guarantee of cursor streaming or bounded memory use.</p>
 *
 * <p><b>&#9888; Warning:</b> Callback overloads that expose {@link DisposableObjArray} may reuse the
 * same array for subsequent rows; copy it before retaining it. Results described as the "first"
 * rows are deterministic only when the query supplies an ordering.</p>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-referencing DAO type
 * @see Dao
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
sealed interface ReadOps<T, TD extends DaoBase<T, TD>> extends DaoBase<T, TD> permits UncheckedReadOps, CrudReadOps, ReadOnlyDao, Dao, NoUpdateDao {

    /**
     * Checks if at least one record exists that matches the specified condition.
     * More efficient than counting when you only need to know if records exist.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * boolean hasActiveUsers = dao.exists(Filters.eq("status", "ACTIVE"));
     * if (hasActiveUsers) {
     *     // Process active users
     * }
     * }</pre>
     *
     * @param cond the condition to check
     * @return {@code true} if at least one matching record exists
     * @throws SQLException if a database access error occurs
     * @see Filters
     */
    boolean exists(final Condition cond) throws SQLException;

    /**
     * Convenience method equivalent to the negation of {@link #exists(Condition)}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * if (dao.notExists(Filters.eq("email", email))) {
     *     // Email is available, proceed with registration
     * }
     * }</pre>
     *
     * @param cond the condition to check
     * @return {@code true} if no matching records exist
     * @throws SQLException if a database access error occurs
     * @see #exists(Condition)
     */
    @Beta
    default boolean notExists(final Condition cond) throws SQLException {
        return !exists(cond);
    }

    /**
     * Counts the number of records that match the specified condition.
     * Returns the exact count of matching records.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * int activeCount = dao.count(Filters.eq("status", "ACTIVE"));
     * System.out.println("Active users: " + activeCount);
     * }</pre>
     *
     * @param cond the condition for counting
     * @return the number of matching records, or {@code 0} if none match
     * @throws SQLException if a database access error occurs
     */
    int count(final Condition cond) throws SQLException;

    /**
     * Finds the first record that matches the specified condition.
     * Returns an Optional containing the entity if found, empty otherwise.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<User> user = dao.findFirst(Filters.eq("email", "john@example.com"));
     * user.ifPresent(u -> System.out.println("Found: " + u.getName()));
     * }</pre>
     *
     * @param cond the search condition
     * @return an {@code Optional} containing the first matching entity, or an empty {@code Optional} if no record matches
     * @throws SQLException if a database access error occurs
     */
    Optional<T> findFirst(final Condition cond) throws SQLException;

    /**
     * Finds the first record matching the condition and maps it using the provided mapper.
     * Allows custom transformation of the result.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<String> userName = dao.findFirst(
     *     Filters.eq("id", 123),
     *     rs -> rs.getString("name")
     * );
     * }</pre>
     *
     * @param <R> the result type after applying the mapping function
     * @param cond the search condition
     * @param rowMapper the function to map the result row
     * @return an {@code Optional} containing the mapped result, or an empty {@code Optional} if no record matches
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code rowMapper} is {@code null}
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the first matched record
     *                              (a {@code null} mapping result is not collapsed to an empty {@code Optional})
     */
    <R> Optional<R> findFirst(final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException, IllegalArgumentException;

    /**
     * Finds the first record matching the condition and maps it using a bi-function mapper.
     * The mapper receives both the ResultSet and column labels.
     *
     * @param <R> the result type after applying the mapping function
     * @param cond the search condition
     * @param rowMapper the bi-function to map the result row
     * @return an {@code Optional} containing the mapped result, or an empty {@code Optional} if no record matches
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code rowMapper} is {@code null}
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the first matched record
     *                              (a {@code null} mapping result is not collapsed to an empty {@code Optional})
     */
    <R> Optional<R> findFirst(final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper) throws SQLException, IllegalArgumentException;

    /**
     * Finds the first record with only specified properties matching the condition.
     * Useful for retrieving partial entities with only needed fields.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<User> user = dao.findFirst(
     *     Arrays.asList("id", "name", "email"),
     *     Filters.eq("status", "ACTIVE")
     * );
     * }</pre>
     *
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @return an {@code Optional} containing the first matching entity, or an empty {@code Optional} if no record matches
     * @throws SQLException if a database access error occurs
     */
    Optional<T> findFirst(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

    /**
     * Finds the first record with specified properties and maps the result.
     * Combines property selection with custom result mapping.
     *
     * @param <R> the result type after applying the mapping function
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param rowMapper the function to map the result
     * @return an {@code Optional} containing the mapped result, or an empty {@code Optional} if no record matches
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code rowMapper} is {@code null}
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the first matched record
     *                              (a {@code null} mapping result is not collapsed to an empty {@code Optional})
     */
    <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper)
            throws SQLException, IllegalArgumentException;

    /**
     * Finds the first record with specified properties using a bi-function mapper.
     * Provides maximum flexibility for property selection and result mapping.
     *
     * @param <R> the result type after applying the mapping function
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param rowMapper the bi-function to map the result
     * @return an {@code Optional} containing the mapped result, or an empty {@code Optional} if no record matches
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code rowMapper} is {@code null}
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the first matched record
     *                              (a {@code null} mapping result is not collapsed to an empty {@code Optional})
     */
    <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper)
            throws SQLException, IllegalArgumentException;

    /**
     * Finds exactly one record matching the condition, throwing exception if multiple found.
     * Use this when you expect exactly zero or one result.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<User> user = dao.findOnlyOne(Filters.eq("email", "john@example.com"));
     * // Throws DuplicateResultException if multiple users have this email
     * }</pre>
     *
     * @param cond the search condition
     * @return an {@code Optional} containing the single matching entity, or an empty {@code Optional} if no record matches
     * @throws DuplicateResultException if more than one record matches
     * @throws SQLException if a database access error occurs
     */
    Optional<T> findOnlyOne(final Condition cond) throws DuplicateResultException, SQLException;

    /**
     * Finds exactly one record and maps it, throwing exception if multiple found.
     * Ensures uniqueness while allowing custom result transformation.
     *
     * @param <R> the result type after applying the mapping function
     * @param cond the search condition
     * @param rowMapper the function to map the result
     * @return an {@code Optional} containing the mapped result, or an empty {@code Optional} if no record matches
     * @throws DuplicateResultException if more than one record matches
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code rowMapper} is {@code null}
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the single matched record
     *                              (a {@code null} mapping result is not collapsed to an empty {@code Optional})
     */
    <R> Optional<R> findOnlyOne(final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper)
            throws DuplicateResultException, SQLException, IllegalArgumentException;

    /**
     * Finds exactly one record using a bi-function mapper, throwing if multiple found.
     * The mapper receives both the ResultSet and column labels.
     *
     * @param <R> the result type after applying the mapping function
     * @param cond the search condition
     * @param rowMapper the bi-function to map the result
     * @return an {@code Optional} containing the mapped result, or an empty {@code Optional} if no record matches
     * @throws DuplicateResultException if more than one record matches
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code rowMapper} is {@code null}
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the single matched record
     *                              (a {@code null} mapping result is not collapsed to an empty {@code Optional})
     */
    <R> Optional<R> findOnlyOne(final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper)
            throws DuplicateResultException, SQLException, IllegalArgumentException;

    /**
     * Finds exactly one record with specified properties, throwing if multiple found.
     * Combines property selection with uniqueness constraint.
     *
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @return an {@code Optional} containing the single matching entity, or an empty {@code Optional} if no record matches
     * @throws DuplicateResultException if more than one record matches
     * @throws SQLException if a database access error occurs
     */
    Optional<T> findOnlyOne(final Collection<String> selectPropNames, final Condition cond) throws DuplicateResultException, SQLException;

    /**
     * Finds exactly one record with specified properties and maps it.
     * Ensures both property selection and uniqueness with custom mapping.
     *
     * @param <R> the result type after applying the mapping function
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param rowMapper the function to map the result
     * @return an {@code Optional} containing the mapped result, or an empty {@code Optional} if no record matches
     * @throws DuplicateResultException if more than one record matches
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code rowMapper} is {@code null}
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the single matched record
     *                              (a {@code null} mapping result is not collapsed to an empty {@code Optional})
     */
    <R> Optional<R> findOnlyOne(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper)
            throws DuplicateResultException, SQLException, IllegalArgumentException;

    /**
     * Finds exactly one record with specified properties using a bi-function mapper.
     * Maximum flexibility with property selection, uniqueness, and custom mapping.
     *
     * @param <R> the result type after applying the mapping function
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param rowMapper the bi-function to map the result
     * @return an {@code Optional} containing the mapped result, or an empty {@code Optional} if no record matches
     * @throws DuplicateResultException if more than one record matches
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if {@code rowMapper} is {@code null}
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the single matched record
     *                              (a {@code null} mapping result is not collapsed to an empty {@code Optional})
     */
    <R> Optional<R> findOnlyOne(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper)
            throws DuplicateResultException, SQLException, IllegalArgumentException;

    /**
     * Queries the value of a single boolean column for the first record matching the condition.
     * Only the first matching record is read; any remaining matching records are ignored.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalBoolean isActive = dao.queryForBoolean("is_active", Filters.eq("id", 123));
     * if (isActive.orElse(false)) {
     *     // User is active
     * }
     * }</pre>
     *
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @return an {@code OptionalBoolean} holding the selected value when at least one record matches,
     *         or an empty {@code OptionalBoolean} if no record matches the condition. A SQL {@code NULL}
     *         value is returned as <i>present</i> holding the primitive default {@code false}; use
     *         {@link #queryForSingleValue(String, Condition, Class)} to distinguish SQL {@code NULL} from a real {@code false}.
     * @throws SQLException if a database access error occurs
     * @see Filters
     */
    OptionalBoolean queryForBoolean(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries the value of a single char column for the first record matching the condition.
     * Only the first matching record is read; any remaining matching records are ignored.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalChar grade = dao.queryForChar("grade", Filters.eq("student_id", 123));
     * if (grade.isPresent()) {
     *     System.out.println("Grade: " + grade.getAsChar());
     * }
     * }</pre>
     *
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @return an {@code OptionalChar} holding the selected value when at least one record matches,
     *         or an empty {@code OptionalChar} if no record matches the condition. A SQL {@code NULL}
     *         value is returned as <i>present</i> holding the primitive default {@code (char) 0}; use
     *         {@link #queryForSingleValue(String, Condition, Class)} to distinguish SQL {@code NULL} from a real {@code (char) 0}.
     * @throws SQLException if a database access error occurs
     * @see Filters
     */
    OptionalChar queryForChar(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries the value of a single byte column for the first record matching the condition.
     * Only the first matching record is read; any remaining matching records are ignored.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalByte status = dao.queryForByte("status", Filters.eq("id", 1));
     * }</pre>
     *
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @return an {@code OptionalByte} holding the selected value when at least one record matches,
     *         or an empty {@code OptionalByte} if no record matches the condition. A SQL {@code NULL}
     *         value is returned as <i>present</i> holding the primitive default {@code 0}; use
     *         {@link #queryForSingleValue(String, Condition, Class)} to distinguish SQL {@code NULL} from a real {@code 0}.
     * @throws SQLException if a database access error occurs
     * @see Filters
     */
    OptionalByte queryForByte(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries the value of a single short column for the first record matching the condition.
     * Only the first matching record is read; any remaining matching records are ignored.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalShort year = dao.queryForShort("year", Filters.eq("id", 1));
     * }</pre>
     *
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @return an {@code OptionalShort} holding the selected value when at least one record matches,
     *         or an empty {@code OptionalShort} if no record matches the condition. A SQL {@code NULL}
     *         value is returned as <i>present</i> holding the primitive default {@code 0}; use
     *         {@link #queryForSingleValue(String, Condition, Class)} to distinguish SQL {@code NULL} from a real {@code 0}.
     * @throws SQLException if a database access error occurs
     * @see Filters
     */
    OptionalShort queryForShort(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries the value of a single int column for the first record matching the condition.
     * Only the first matching record is read; any remaining matching records are ignored.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalInt age = dao.queryForInt("age", Filters.eq("id", 123));
     * System.out.println("Age: " + age.orElse(0));
     * }</pre>
     *
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @return an {@code OptionalInt} holding the selected value when at least one record matches,
     *         or an empty {@code OptionalInt} if no record matches the condition. A SQL {@code NULL}
     *         value is returned as <i>present</i> holding the primitive default {@code 0}; use
     *         {@link #queryForSingleValue(String, Condition, Class)} to distinguish SQL {@code NULL} from a real {@code 0}.
     * @throws SQLException if a database access error occurs
     * @see Filters
     */
    OptionalInt queryForInt(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries the value of a single long column for the first record matching the condition.
     * Only the first matching record is read; any remaining matching records are ignored.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalLong count = dao.queryForLong("count", Filters.eq("category", "A"));
     * }</pre>
     *
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @return an {@code OptionalLong} holding the selected value when at least one record matches,
     *         or an empty {@code OptionalLong} if no record matches the condition. A SQL {@code NULL}
     *         value is returned as <i>present</i> holding the primitive default {@code 0}; use
     *         {@link #queryForSingleValue(String, Condition, Class)} to distinguish SQL {@code NULL} from a real {@code 0}.
     * @throws SQLException if a database access error occurs
     * @see Filters
     */
    OptionalLong queryForLong(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries the value of a single float column for the first record matching the condition.
     * Only the first matching record is read; any remaining matching records are ignored.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalFloat temperature = dao.queryForFloat("temperature", Filters.eq("city", "London"));
     * }</pre>
     *
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @return an {@code OptionalFloat} holding the selected value when at least one record matches,
     *         or an empty {@code OptionalFloat} if no record matches the condition. A SQL {@code NULL}
     *         value is returned as <i>present</i> holding the primitive default {@code 0f}; use
     *         {@link #queryForSingleValue(String, Condition, Class)} to distinguish SQL {@code NULL} from a real {@code 0f}.
     * @throws SQLException if a database access error occurs
     * @see Filters
     */
    OptionalFloat queryForFloat(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries the value of a single double column for the first record matching the condition.
     * Only the first matching record is read; any remaining matching records are ignored.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * OptionalDouble average = dao.queryForDouble("average", Filters.eq("group_id", 1));
     * }</pre>
     *
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @return an {@code OptionalDouble} holding the selected value when at least one record matches,
     *         or an empty {@code OptionalDouble} if no record matches the condition. A SQL {@code NULL}
     *         value is returned as <i>present</i> holding the primitive default {@code 0d}; use
     *         {@link #queryForSingleValue(String, Condition, Class)} to distinguish SQL {@code NULL} from a real {@code 0d}.
     * @throws SQLException if a database access error occurs
     * @see Filters
     */
    OptionalDouble queryForDouble(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries the value of a single String column for the first record matching the condition.
     * Only the first matching record is read; any remaining matching records are ignored.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<String> name = dao.queryForString("name", Filters.eq("id", 123));
     * System.out.println("Name: " + name.orElse("Unknown"));
     * }</pre>
     *
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @return a <i>present</i> {@code Nullable} holding the selected value (possibly {@code null} for a
     *         SQL {@code NULL}) when at least one record matches, or an empty {@code Nullable} if no record
     *         matches the condition
     * @throws SQLException if a database access error occurs
     * @see Filters
     */
    Nullable<String> queryForString(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries the value of a single {@code java.sql.Date} column for the first record matching the condition.
     * Only the first matching record is read; any remaining matching records are ignored.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Date> birthDate = dao.queryForDate("birth_date", Filters.eq("id", 1));
     * }</pre>
     *
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @return a <i>present</i> {@code Nullable} holding the selected value (possibly {@code null} for a
     *         SQL {@code NULL}) when at least one record matches, or an empty {@code Nullable} if no record
     *         matches the condition
     * @throws SQLException if a database access error occurs
     * @see Filters
     */
    Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries the value of a single {@code java.sql.Time} column for the first record matching the condition.
     * Only the first matching record is read; any remaining matching records are ignored.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Time> startTime = dao.queryForTime("start_time", Filters.eq("id", 1));
     * }</pre>
     *
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @return a <i>present</i> {@code Nullable} holding the selected value (possibly {@code null} for a
     *         SQL {@code NULL}) when at least one record matches, or an empty {@code Nullable} if no record
     *         matches the condition
     * @throws SQLException if a database access error occurs
     * @see Filters
     */
    Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries the value of a single {@code java.sql.Timestamp} column for the first record matching the condition.
     * Only the first matching record is read; any remaining matching records are ignored.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<java.sql.Timestamp> createdAt = dao.queryForTimestamp("created_at", Filters.eq("id", 1));
     * }</pre>
     *
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @return a <i>present</i> {@code Nullable} holding the selected value (possibly {@code null} for a
     *         SQL {@code NULL}) when at least one record matches, or an empty {@code Nullable} if no record
     *         matches the condition
     * @throws SQLException if a database access error occurs
     * @see Filters
     */
    Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries the value of a single byte-array column for the first record matching the condition.
     * Only the first matching record is read; any remaining matching records are ignored.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<byte[]> data = dao.queryForBytes("data", Filters.eq("id", 1));
     * }</pre>
     *
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @return a <i>present</i> {@code Nullable} holding the selected value (possibly {@code null} for a
     *         SQL {@code NULL}) when at least one record matches, or an empty {@code Nullable} if no record
     *         matches the condition
     * @throws SQLException if a database access error occurs
     * @see Filters
     */
    Nullable<byte[]> queryForBytes(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Queries a single value of the specified type from one column for the first record matching the condition.
     * Only the first matching record is read; any remaining matching records are ignored.
     * The returned {@code Nullable} preserves the distinction between "no record matched" (empty) and
     * "the matched value is SQL {@code NULL}" (present-but-null).
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<BigDecimal> balance = dao.queryForSingleValue(
     *     "balance",
     *     Filters.eq("account_id", 123),
     *     BigDecimal.class
     * );
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @param targetValueType the class of the target value type to convert the column value to
     * @return a <i>present</i> {@code Nullable} holding the converted value (possibly {@code null} for a
     *         SQL {@code NULL}) when at least one record matches, or an empty {@code Nullable} if no record
     *         matches the condition
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForSingleValue(Class)
     */
    <V> Nullable<V> queryForSingleValue(final String singleSelectPropName, final Condition cond, final Class<? extends V> targetValueType) throws SQLException;

    /**
     * Queries a single non-null value of the specified type from one column for the first record matching the condition.
     * Only the first matching record is read; any remaining matching records are ignored.
     * Unlike {@link #queryForSingleValue(String, Condition, Class)}, this method collapses both
     * "no record matched" and "the matched value is SQL {@code NULL}" into an empty {@code Optional}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<String> email = dao.queryForSingleNonNull(
     *     "email",
     *     Filters.eq("id", 123),
     *     String.class
     * );
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @param targetValueType the class of the target value type to convert the column value to
     * @return an {@code Optional} containing the converted value, or an empty {@code Optional} if no record
     *         matches the condition or the matched value is SQL {@code NULL}
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForSingleNonNull(Class)
     */
    <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final Condition cond, final Class<? extends V> targetValueType)
            throws SQLException;

    /**
     * Queries a single non-null value from one column for the first record matching the condition, mapping it with a custom row mapper.
     * Only the first matching record is read; any remaining matching records are ignored.
     * Provides flexibility in how the single column value is transformed.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<LocalDateTime> createdDate = dao.queryForSingleNonNull(
     *     "created_at",
     *     Filters.eq("id", 123),
     *     rs -> rs.getTimestamp(1).toLocalDateTime()
     * );
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @param rowMapper the function to map the selected value
     * @return an {@code Optional} containing the mapped value, or an empty {@code Optional} if no record
     *         matches the condition
     * @throws IllegalArgumentException if {@code rowMapper} is {@code null}
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the matched record
     *                              (unlike the {@code Class}-based variant, a {@code null} value is not collapsed to an empty {@code Optional})
     * @throws SQLException if a database access error occurs
     * @see #queryForSingleNonNull(String, Condition, Class)
     */
    @Beta
    <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final Condition cond, final Jdbc.RowMapper<? extends V> rowMapper)
            throws SQLException;

    /**
     * Queries a unique single value of the specified type from one column, throwing if more than one record matches.
     * The returned {@code Nullable} preserves the distinction between "no record matched" (empty) and
     * "the matched value is SQL {@code NULL}" (present-but-null).
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Nullable<String> uniqueEmail = dao.queryForUniqueValue(
     *     "email",
     *     Filters.eq("username", "john_doe"),
     *     String.class
     * );
     * // Throws DuplicateResultException if multiple users have this username
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @param targetValueType the class of the target value type to convert the column value to
     * @return a <i>present</i> {@code Nullable} holding the converted value (possibly {@code null} for a
     *         SQL {@code NULL}) when exactly one record matches, or an empty {@code Nullable} if no record
     *         matches the condition
     * @throws DuplicateResultException if more than one record matches the condition
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForUniqueValue(Class)
     */
    <V> Nullable<V> queryForUniqueValue(final String singleSelectPropName, final Condition cond, final Class<? extends V> targetValueType)
            throws DuplicateResultException, SQLException;

    /**
     * Queries a unique non-null single value of the specified type from one column, throwing if more than one record matches.
     * Combines the uniqueness constraint with a non-null requirement: both "no record matched" and
     * "the matched value is SQL {@code NULL}" collapse into an empty {@code Optional}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<BigDecimal> balance = dao.queryForUniqueNonNull(
     *     "balance",
     *     Filters.eq("account_number", "ACC123"),
     *     BigDecimal.class
     * );
     * balance.ifPresent(b -> System.out.println("Balance: $" + b));
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @param targetValueType the class of the target value type to convert the column value to
     * @return an {@code Optional} containing the converted value, or an empty {@code Optional} if no record
     *         matches the condition or the matched value is SQL {@code NULL}
     * @throws DuplicateResultException if more than one record matches the condition
     * @throws SQLException if a database access error occurs
     * @see AbstractQuery#queryForUniqueNonNull(Class)
     */
    <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final Condition cond, final Class<? extends V> targetValueType)
            throws DuplicateResultException, SQLException;

    /**
     * Queries a unique non-null value from one column using a custom row mapper, throwing if more than one record matches.
     * Ensures uniqueness while allowing custom value transformation.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Optional<ZonedDateTime> lastLogin = dao.queryForUniqueNonNull(
     *     "last_login",
     *     Filters.eq("username", "john_doe"),
     *     rs -> ZonedDateTime.ofInstant(
     *         rs.getTimestamp(1).toInstant(),
     *         ZoneId.systemDefault()
     *     )
     * );
     * }</pre>
     *
     * @param <V> the value type
     * @param singleSelectPropName the name of the single property/column to select
     * @param cond the search condition
     * @param rowMapper the function to map the selected value
     * @return an {@code Optional} containing the unique mapped value, or an empty {@code Optional} if no record
     *         matches the condition
     * @throws IllegalArgumentException if {@code rowMapper} is {@code null}
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the matched record
     *                              (unlike the {@code Class}-based variant, a {@code null} value is not collapsed to an empty {@code Optional})
     * @throws DuplicateResultException if more than one record matches the condition
     * @throws SQLException if a database access error occurs
     * @see #queryForUniqueNonNull(String, Condition, Class)
     */
    @Beta
    <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final Condition cond, final Jdbc.RowMapper<? extends V> rowMapper)
            throws DuplicateResultException, SQLException;

    /**
     * Executes a query and returns the results as a Dataset.
     * Dataset provides a flexible, column-oriented view of the results.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Dataset ds = dao.query(Filters.gt("age", 18));
     * ds.getColumn("name").forEach(System.out::println);
     * }</pre>
     *
     * @param cond the search condition
     * @return a {@code Dataset} containing the query results; never {@code null} (an empty {@code Dataset} is
     *         returned when no record matches)
     * @throws SQLException if a database access error occurs
     */
    Dataset query(final Condition cond) throws SQLException;

    /**
     * Executes a query for specific columns and returns results as a Dataset.
     * Only the specified properties will be included in the result.
     *
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @return a {@code Dataset} containing the query results; never {@code null} (an empty {@code Dataset} is
     *         returned when no record matches)
     * @throws SQLException if a database access error occurs
     */
    Dataset query(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

    /**
     * Executes a query and processes results with a custom result extractor.
     * The ResultSet is passed to the extractor for custom processing.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Map<Long, String> idToName = dao.query(
     *     Filters.isNotNull("id"),
     *     rs -> {
     *         Map<Long, String> map = new HashMap<>();
     *         while (rs.next()) {
     *             map.put(rs.getLong("id"), rs.getString("name"));
     *         }
     *         return map;
     *     }
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param resultExtractor function to process the ResultSet; it is responsible for iterating the
     *                        {@code ResultSet} and must not save or hold a reference to it after returning
     * @return the result produced by {@code resultExtractor} (may be {@code null} if the extractor returns {@code null})
     * @throws SQLException if a database access error occurs
     */
    <R> R query(final Condition cond, final Jdbc.ResultExtractor<? extends R> resultExtractor) throws SQLException;

    /**
     * Executes a query for specific columns with a custom result extractor.
     * Combines column selection with custom result processing.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param resultExtractor function to process the ResultSet; it is responsible for iterating the
     *                        {@code ResultSet} and must not save or hold a reference to it after returning
     * @return the result produced by {@code resultExtractor} (may be {@code null} if the extractor returns {@code null})
     * @throws SQLException if a database access error occurs
     */
    <R> R query(final Collection<String> selectPropNames, final Condition cond, final Jdbc.ResultExtractor<? extends R> resultExtractor) throws SQLException;

    /**
     * Executes a query with a bi-function result extractor.
     * The extractor receives both ResultSet and column labels.
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param resultExtractor bi-function to process the ResultSet; it receives the {@code ResultSet} and the
     *                        list of column labels, and must not save or hold a reference to the {@code ResultSet}
     *                        after returning
     * @return the result produced by {@code resultExtractor} (may be {@code null} if the extractor returns {@code null})
     * @throws SQLException if a database access error occurs
     */
    <R> R query(final Condition cond, final Jdbc.BiResultExtractor<? extends R> resultExtractor) throws SQLException;

    /**
     * Executes a query for specific columns with a bi-function result extractor.
     * Maximum flexibility for column selection and result processing.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param resultExtractor bi-function to process the ResultSet; it receives the {@code ResultSet} and the
     *                        list of column labels, and must not save or hold a reference to the {@code ResultSet}
     *                        after returning
     * @return the result produced by {@code resultExtractor} (may be {@code null} if the extractor returns {@code null})
     * @throws SQLException if a database access error occurs
     */
    <R> R query(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiResultExtractor<? extends R> resultExtractor) throws SQLException;

    /**
     * Returns a list of all entities matching the specified condition.
     * The results are eagerly loaded into memory.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> activeUsers = dao.list(Filters.eq("status", "ACTIVE"));
     * for (User user : activeUsers) {
     *     System.out.println(user.getName());
     * }
     * }</pre>
     *
     * @param cond the search condition
     * @return a list of matching entities, or an empty list if none match
     * @throws SQLException if a database access error occurs
     */
    List<T> list(final Condition cond) throws SQLException;

    /**
     * Returns a list of results mapped by the provided row mapper.
     * Each row is transformed by the mapper function.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<String> names = dao.list(
     *     Filters.eq("active", true),
     *     rs -> rs.getString("name")
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param rowMapper function to map each row
     * @return a list of mapped results, or an empty list if no record matches the condition
     * @throws SQLException if a database access error occurs
     */
    <R> List<R> list(final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException;

    /**
     * Returns a list of results mapped by a bi-function row mapper.
     * The mapper receives both ResultSet and column labels for each row.
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param rowMapper bi-function to map each row
     * @return a list of mapped results, or an empty list if no record matches the condition
     * @throws SQLException if a database access error occurs
     */
    <R> List<R> list(final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper) throws SQLException;

    /**
     * Returns a filtered list of results mapped by the row mapper.
     * Only rows passing the filter are included in the result.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> adultUsers = dao.list(
     *     Filters.isNotNull("age"),
     *     rs -> rs.getInt("age") >= 18,  // filter
     *     rs -> mapToUser(rs)            // mapper
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param rowFilter predicate to filter rows
     * @param rowMapper function to map filtered rows
     * @return a list of filtered and mapped results, or an empty list if no record matches or passes the filter
     * @throws SQLException if a database access error occurs
     */
    <R> List<R> list(final Condition cond, final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException;

    /**
     * Returns a filtered list using bi-function filter and mapper.
     * Both filter and mapper receive ResultSet and column labels.
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param rowFilter bi-predicate to filter rows
     * @param rowMapper bi-function to map filtered rows
     * @return a list of filtered and mapped results, or an empty list if no record matches or passes the filter
     * @throws SQLException if a database access error occurs
     */
    <R> List<R> list(final Condition cond, final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends R> rowMapper) throws SQLException;

    /**
     * Returns a list of entities with only the specified properties populated.
     * More efficient than loading full entities when only specific fields are needed.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = dao.list(
     *     Arrays.asList("id", "name", "email"),
     *     Filters.eq("department", "IT")
     * );
     * }</pre>
     *
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @return a list of partially loaded entities, or an empty list if no record matches the condition
     * @throws SQLException if a database access error occurs
     */
    List<T> list(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

    /**
     * Returns a list of selected properties mapped by the row mapper.
     * Combines property selection with custom mapping.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param rowMapper function to map each row
     * @return a list of mapped results, or an empty list if no record matches the condition
     * @throws SQLException if a database access error occurs
     */
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException;

    /**
     * Returns a list of selected properties mapped by a bi-function mapper.
     * The mapper receives column labels for the selected properties.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param rowMapper bi-function to map each row
     * @return a list of mapped results, or an empty list if no record matches the condition
     * @throws SQLException if a database access error occurs
     */
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper) throws SQLException;

    /**
     * Returns a filtered list of selected properties mapped by the row mapper.
     * Combines property selection, filtering, and mapping.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param rowFilter predicate to filter rows
     * @param rowMapper function to map filtered rows
     * @return a list of filtered and mapped results, or an empty list if no record matches or passes the filter
     * @throws SQLException if a database access error occurs
     */
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException;

    /**
     * Returns a filtered list with bi-function filter and mapper for selected properties.
     * Maximum flexibility for property selection, filtering, and mapping.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param rowFilter bi-predicate to filter rows
     * @param rowMapper bi-function to map filtered rows
     * @return a list of filtered and mapped results, or an empty list if no record matches or passes the filter
     * @throws SQLException if a database access error occurs
     */
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter,
            final Jdbc.BiRowMapper<? extends R> rowMapper) throws SQLException;

    /**
     * Returns a list of values from a single property/column.
     * The property type is automatically detected and used for mapping.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<String> emails = dao.list("email", Filters.eq("newsletter", true));
     * }</pre>
     *
     * @param <R> the result type
     * @param singleSelectPropName the single property to select
     * @param cond the search condition
     * @return a list of property values, or an empty list if no record matches the condition
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    default <R> List<R> list(final String singleSelectPropName, final Condition cond) throws SQLException {
        final PropInfo propInfo = ParserUtil.getBeanInfo(targetEntityClass()).getPropInfo(singleSelectPropName);
        final Jdbc.RowMapper<? extends R> rowMapper = propInfo == null ? ColumnOne.getObject() : ColumnOne.get((Type<R>) propInfo.dbType);

        return list(singleSelectPropName, cond, rowMapper);
    }

    /**
     * Returns a list of single property values mapped by the row mapper.
     * Allows custom transformation of single column values.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<String> upperNames = dao.list(
     *     "name",
     *     Filters.isNotNull("name"),
     *     rs -> rs.getString(1).toUpperCase()
     * );
     * }</pre>
     *
     * @param <R> the result type
     * @param singleSelectPropName the single property to select
     * @param cond the search condition
     * @param rowMapper function to map the property value
     * @return a list of mapped values, or an empty list if no record matches the condition
     * @throws SQLException if a database access error occurs
     */
    default <R> List<R> list(final String singleSelectPropName, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException {
        return list(N.asList(singleSelectPropName), cond, rowMapper);
    }

    /**
     * Returns a filtered list of single property values.
     * Only values passing the filter are included.
     *
     * @param <R> the result type
     * @param singleSelectPropName the single property to select
     * @param cond the search condition
     * @param rowFilter predicate to filter values
     * @param rowMapper function to map filtered values
     * @return a list of filtered and mapped values, or an empty list if no record matches or passes the filter
     * @throws SQLException if a database access error occurs
     */
    default <R> List<R> list(final String singleSelectPropName, final Condition cond, final Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException {
        return list(N.asList(singleSelectPropName), cond, rowFilter, rowMapper);
    }

    /**
     * Returns a lazy Stream of entities matching the condition.
     * The stream uses lazy evaluation - no database connection or query execution occurs until a terminal operation is called.
     * Any {@link SQLException} raised during stream consumption is wrapped as an
     * {@link com.landawn.abacus.exception.UncheckedSQLException}. The stream must be closed
     * (e.g. via try-with-resources) to release the underlying JDBC resources.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Stream<User> users = dao.stream(Filters.eq("status", "ACTIVE"))) {
     *     users.filter(u -> u.getAge() > 21)
     *          .map(User::getEmail)
     *          .forEach(System.out::println);
     * }
     * }</pre>
     *
     * @param cond the search condition
     * @return lazy stream of matching entities
     * @see Filters
     */
    @LazyEvaluation
    Stream<T> stream(final Condition cond);

    /**
     * Returns a lazy Stream with custom row mapping.
     * Combines lazy evaluation with custom result transformation.
     * This stream has the same lazy, close, and SQLException wrapping contract as {@link #stream(Condition)}.
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param rowMapper function to map each row
     * @return lazy stream of mapped results
     */
    @LazyEvaluation
    <R> Stream<R> stream(final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper);

    /**
     * Returns a lazy Stream with bi-function row mapping.
     * The mapper receives both ResultSet and column labels.
     * This stream has the same lazy, close, and SQLException wrapping contract as {@link #stream(Condition)}.
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param rowMapper bi-function to map each row
     * @return lazy stream of mapped results
     */
    @LazyEvaluation
    <R> Stream<R> stream(final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper);

    /**
     * Returns a filtered lazy Stream with row mapping.
     * Only rows passing the filter are included in the stream.
     * This stream has the same lazy, close, and SQLException wrapping contract as {@link #stream(Condition)}.
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param rowFilter predicate to filter rows
     * @param rowMapper function to map filtered rows
     * @return lazy stream of filtered and mapped results
     */
    @LazyEvaluation
    <R> Stream<R> stream(final Condition cond, final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends R> rowMapper);

    /**
     * Returns a filtered lazy Stream with bi-function filter and mapper.
     * Maximum flexibility for stream processing with filtering.
     * This stream has the same lazy, close, and SQLException wrapping contract as {@link #stream(Condition)}.
     *
     * @param <R> the result type
     * @param cond the search condition
     * @param rowFilter bi-predicate to filter rows
     * @param rowMapper bi-function to map filtered rows
     * @return lazy stream of filtered and mapped results
     */
    @LazyEvaluation
    <R> Stream<R> stream(final Condition cond, final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends R> rowMapper);

    /**
     * Returns a lazy Stream of entities with selected properties.
     * Only specified properties are loaded for each entity.
     * Any {@link SQLException} raised during stream consumption is wrapped as an
     * {@link com.landawn.abacus.exception.UncheckedSQLException}. The stream must be closed
     * (e.g. via try-with-resources) to release the underlying JDBC resources.
     *
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @return lazy stream of partially loaded entities
     */
    @LazyEvaluation
    Stream<T> stream(final Collection<String> selectPropNames, final Condition cond);

    /**
     * Returns a lazy Stream of selected properties with row mapping.
     * Combines property selection with custom mapping in a lazy stream.
     * This stream has the same lazy, close, and SQLException wrapping contract as {@link #stream(Collection, Condition)}.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param rowMapper function to map each row
     * @return lazy stream of mapped results
     */
    @LazyEvaluation
    <R> Stream<R> stream(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper);

    /**
     * Returns a lazy Stream with bi-function mapping for selected properties.
     * The mapper receives column labels for the selected properties.
     * This stream has the same lazy, close, and SQLException wrapping contract as {@link #stream(Collection, Condition)}.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param rowMapper bi-function to map each row
     * @return lazy stream of mapped results
     */
    @LazyEvaluation
    <R> Stream<R> stream(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper);

    /**
     * Returns a filtered lazy Stream of selected properties with mapping.
     * Combines property selection, filtering, and mapping in a lazy stream.
     * This stream has the same lazy, close, and SQLException wrapping contract as {@link #stream(Collection, Condition)}.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param rowFilter predicate to filter rows
     * @param rowMapper function to map filtered rows
     * @return lazy stream of filtered and mapped results
     */
    @LazyEvaluation
    <R> Stream<R> stream(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends R> rowMapper);

    /**
     * Returns a filtered lazy Stream with maximum flexibility.
     * Combines all features: property selection, bi-function filtering and mapping.
     * This stream has the same lazy, close, and SQLException wrapping contract as {@link #stream(Collection, Condition)}.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param rowFilter bi-predicate to filter rows
     * @param rowMapper bi-function to map filtered rows
     * @return lazy stream of filtered and mapped results
     */
    @LazyEvaluation
    <R> Stream<R> stream(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter,
            final Jdbc.BiRowMapper<? extends R> rowMapper);

    /**
     * Returns a lazy Stream of values from a single property.
     * The property type is automatically detected for mapping.
     * This stream has the same lazy, close, and SQLException wrapping contract as {@link #stream(Collection, Condition)}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * try (Stream<String> emails = dao.stream("email", Filters.eq("active", true))) {
     *     emails.distinct()
     *           .sorted()
     *           .forEach(System.out::println);
     * }
     * }</pre>
     *
     * @param <R> the result type
     * @param singleSelectPropName the single property to select
     * @param cond the search condition
     * @return lazy stream of property values
     */
    @LazyEvaluation
    default <R> Stream<R> stream(final String singleSelectPropName, final Condition cond) {
        @SuppressWarnings("deprecation")
        final PropInfo propInfo = ParserUtil.getBeanInfo(targetEntityClass()).getPropInfo(singleSelectPropName);
        final Jdbc.RowMapper<? extends R> rowMapper = propInfo == null ? ColumnOne.getObject() : ColumnOne.get((Type<R>) propInfo.dbType);

        return stream(singleSelectPropName, cond, rowMapper);
    }

    /**
     * Returns a lazy Stream of single property values with custom mapping.
     * Allows transformation of single column values in a stream.
     * This stream has the same lazy, close, and SQLException wrapping contract as {@link #stream(Collection, Condition)}.
     *
     * @param <R> the result type
     * @param singleSelectPropName the single property to select
     * @param cond the search condition
     * @param rowMapper function to map property values
     * @return lazy stream of mapped values
     */
    @LazyEvaluation
    default <R> Stream<R> stream(final String singleSelectPropName, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) {
        return stream(N.asList(singleSelectPropName), cond, rowMapper);
    }

    /**
     * Returns a filtered lazy Stream of single property values.
     * Only values passing the filter are included in the stream.
     * This stream has the same lazy, close, and SQLException wrapping contract as {@link #stream(Collection, Condition)}.
     *
     * @param <R> the result type
     * @param singleSelectPropName the single property to select
     * @param cond the search condition
     * @param rowFilter predicate to filter values
     * @param rowMapper function to map filtered values
     * @return lazy stream of filtered and mapped values
     */
    @LazyEvaluation
    default <R> Stream<R> stream(final String singleSelectPropName, final Condition cond, final Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends R> rowMapper) {
        return stream(N.asList(singleSelectPropName), cond, rowFilter, rowMapper);
    }

    /**
     * Returns a paginated Stream of query results as Dataset pages.
     * Each element in the stream represents one page of results. The condition must include an {@code orderBy} clause for consistent pagination.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Stream<Dataset> pages = dao.paginate(
     *     Criteria.builder().where(Filters.gt("id", 0)).orderBy("id").build(),
     *     100,
     *     (query, lastPageResult) -> {
     *         if (lastPageResult != null && lastPageResult.size() > 0) {
     *             long lastId = (Long) N.lastOrNullIfEmpty(lastPageResult.getColumn("id"));
     *             query.setLong(1, lastId);
     *         }
     *     }
     * );
     * }</pre>
     *
     * @param cond the condition; must include an {@code orderBy} clause for consistent pagination
     * @param pageSize the number of records per page
     * @param paramSetter function to set parameters for the next page based on the previous page's result
     *                   (the second argument is {@code null} when fetching the first page)
     * @return stream of Dataset pages
     * @throws IllegalArgumentException if {@code cond} or {@code paramSetter} is {@code null}, {@code pageSize} is not positive,
     *                                  or {@code cond} does not include an {@code orderBy} clause
     */
    @Beta
    @LazyEvaluation
    Stream<Dataset> paginate(final Condition cond, final int pageSize, final Jdbc.BiParametersSetter<? super PreparedQuery, Dataset> paramSetter);

    /**
     * Returns a paginated Stream with custom result extraction.
     * Each page is processed by the result extractor.
     *
     * @param <R> the result type
     * @param cond the condition; must include an {@code orderBy} clause for consistent pagination
     * @param pageSize the number of records per page
     * @param paramSetter function to set parameters for the next page based on the previous page's result
     *                   (the second argument is {@code null} when fetching the first page)
     * @param resultExtractor function to process each page's ResultSet
     * @return stream of processed page results
     * @throws IllegalArgumentException if {@code cond}, {@code paramSetter}, or {@code resultExtractor} is {@code null}, {@code pageSize} is not positive,
     *                                  or {@code cond} does not include an {@code orderBy} clause
     */
    @Beta
    @LazyEvaluation
    <R> Stream<R> paginate(final Condition cond, final int pageSize, final Jdbc.BiParametersSetter<? super PreparedQuery, R> paramSetter,
            final Jdbc.ResultExtractor<? extends R> resultExtractor);

    /**
     * Returns a paginated Stream with bi-function result extraction.
     * The extractor receives both ResultSet and column labels for each page.
     *
     * @param <R> the result type
     * @param cond the condition; must include an {@code orderBy} clause for consistent pagination
     * @param pageSize the number of records per page
     * @param paramSetter function to set parameters for the next page based on the previous page's result
     *                   (the second argument is {@code null} when fetching the first page)
     * @param resultExtractor bi-function to process each page
     * @return stream of processed page results
     * @throws IllegalArgumentException if {@code cond}, {@code paramSetter}, or {@code resultExtractor} is {@code null}, {@code pageSize} is not positive,
     *                                  or {@code cond} does not include an {@code orderBy} clause
     */
    @Beta
    @LazyEvaluation
    <R> Stream<R> paginate(final Condition cond, final int pageSize, final Jdbc.BiParametersSetter<? super PreparedQuery, R> paramSetter,
            final Jdbc.BiResultExtractor<? extends R> resultExtractor);

    /**
     * Returns a paginated Stream with selected properties as Dataset pages.
     * Only specified properties are included in each page.
     *
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the condition; must include an {@code orderBy} clause for consistent pagination
     * @param pageSize the number of records per page
     * @param paramSetter function to set parameters for the next page based on the previous page's result
     *                   (the second argument is {@code null} when fetching the first page)
     * @return stream of Dataset pages with selected properties
     * @throws IllegalArgumentException if {@code cond} or {@code paramSetter} is {@code null}, {@code pageSize} is not positive,
     *                                  or {@code cond} does not include an {@code orderBy} clause
     */
    @Beta
    @LazyEvaluation
    Stream<Dataset> paginate(final Collection<String> selectPropNames, final Condition cond, final int pageSize,
            final Jdbc.BiParametersSetter<? super PreparedQuery, Dataset> paramSetter);

    /**
     * Returns a paginated Stream of selected properties with custom extraction.
     * Combines property selection with custom page processing.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the condition; must include an {@code orderBy} clause for consistent pagination
     * @param pageSize the number of records per page
     * @param paramSetter function to set parameters for the next page based on the previous page's result
     *                   (the second argument is {@code null} when fetching the first page)
     * @param resultExtractor function to process each page
     * @return stream of processed page results
     * @throws IllegalArgumentException if {@code cond}, {@code paramSetter}, or {@code resultExtractor} is {@code null}, {@code pageSize} is not positive,
     *                                  or {@code cond} does not include an {@code orderBy} clause
     */
    @Beta
    @LazyEvaluation
    <R> Stream<R> paginate(final Collection<String> selectPropNames, final Condition cond, final int pageSize,
            final Jdbc.BiParametersSetter<? super PreparedQuery, R> paramSetter, final Jdbc.ResultExtractor<? extends R> resultExtractor);

    /**
     * Returns a paginated Stream with bi-function extraction for selected properties.
     * Maximum flexibility for paginated queries with custom processing.
     *
     * @param <R> the result type
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the condition; must include an {@code orderBy} clause for consistent pagination
     * @param pageSize the number of records per page
     * @param paramSetter function to set parameters for the next page based on the previous page's result
     *                   (the second argument is {@code null} when fetching the first page)
     * @param resultExtractor bi-function to process each page
     * @return stream of processed page results
     * @throws IllegalArgumentException if {@code cond}, {@code paramSetter}, or {@code resultExtractor} is {@code null}, {@code pageSize} is not positive,
     *                                  or {@code cond} does not include an {@code orderBy} clause
     */
    @Beta
    @LazyEvaluation
    <R> Stream<R> paginate(final Collection<String> selectPropNames, final Condition cond, final int pageSize,
            final Jdbc.BiParametersSetter<? super PreparedQuery, R> paramSetter, final Jdbc.BiResultExtractor<? extends R> resultExtractor);

    /**
     * Iterates over query results, applying the row consumer to each row.
     * This is useful for processing large result sets without loading all data into memory.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * dao.forEach(
     *     Filters.eq("status", "PENDING"),
     *     rs -> processRecord(rs.getLong("id"), rs.getString("data"))
     * );
     * }</pre>
     *
     * @param cond the search condition
     * @param rowConsumer consumer to process each row
     * @throws SQLException if a database access error occurs
     */
    void forEach(final Condition cond, final Jdbc.RowConsumer rowConsumer) throws SQLException;

    /**
     * Iterates over results with a bi-consumer receiving ResultSet and column labels.
     * Provides column metadata along with row data.
     *
     * @param cond the search condition
     * @param rowConsumer bi-consumer to process each row
     * @throws SQLException if a database access error occurs
     */
    void forEach(final Condition cond, final Jdbc.BiRowConsumer rowConsumer) throws SQLException;

    /**
     * Iterates over filtered results, processing only rows that pass the filter.
     * Combines filtering with row processing for efficiency.
     *
     * @param cond the search condition
     * @param rowFilter predicate to filter rows
     * @param rowConsumer consumer for filtered rows
     * @throws SQLException if a database access error occurs
     */
    void forEach(final Condition cond, final Jdbc.RowFilter rowFilter, final Jdbc.RowConsumer rowConsumer) throws SQLException;

    /**
     * Iterates over filtered results with bi-function filter and consumer.
     * Both receive ResultSet and column labels.
     *
     * @param cond the search condition
     * @param rowFilter bi-predicate to filter rows
     * @param rowConsumer bi-consumer for filtered rows
     * @throws SQLException if a database access error occurs
     */
    void forEach(final Condition cond, final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowConsumer rowConsumer) throws SQLException;

    /**
     * Iterates over selected properties, applying the consumer to each row.
     * Only specified properties are retrieved and processed.
     *
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param rowConsumer consumer to process each row
     * @throws SQLException if a database access error occurs
     */
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowConsumer rowConsumer) throws SQLException;

    /**
     * Iterates over selected properties with a bi-consumer.
     * The consumer receives column labels for the selected properties.
     *
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param rowConsumer bi-consumer to process each row
     * @throws SQLException if a database access error occurs
     */
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowConsumer rowConsumer) throws SQLException;

    /**
     * Iterates over filtered results of selected properties.
     * Combines property selection, filtering, and processing.
     *
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param rowFilter predicate to filter rows
     * @param rowConsumer consumer for filtered rows
     * @throws SQLException if a database access error occurs
     */
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowFilter rowFilter, final Jdbc.RowConsumer rowConsumer)
            throws SQLException;

    /**
     * Iterates over filtered results with maximum flexibility.
     * All parameters support bi-function interfaces.
     *
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param rowFilter bi-predicate to filter rows
     * @param rowConsumer bi-consumer for filtered rows
     * @throws SQLException if a database access error occurs
     */
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowConsumer rowConsumer)
            throws SQLException;

    /**
     * Iterates over results using a disposable object array consumer.
     * The array is reused for each row to minimize object allocation.
     *
     * <p><b>WARNING:</b> Do not store or cache the array parameter as it is reused for every row.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * dao.foreach(
     *     Arrays.asList("id", "name", "age"),
     *     Filters.gt("age", 18),
     *     row -> {
     *         Long id = (Long) row.get(0);
     *         String name = (String) row.get(1);
     *         Integer age = (Integer) row.get(2);
     *         // Process immediately, don't store row
     *     }
     * );
     * }</pre>
     *
     * @param selectPropNames the properties to select, {@code null} for all
     * @param cond the search condition
     * @param rowConsumer consumer that receives reusable row array
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Beta
    default void foreach(final Collection<String> selectPropNames, final Condition cond, final Consumer<DisposableObjArray> rowConsumer) throws SQLException {
        forEach(selectPropNames, cond, Jdbc.RowConsumer.oneOff(targetEntityClass(), rowConsumer));
    }

    /**
     * Iterates over all results using a disposable object array consumer.
     * Convenience method that selects all properties.
     *
     * <p><b>WARNING:</b> Do not store or cache the array parameter as it is reused for every row.</p>
     *
     * @param cond the search condition
     * @param rowConsumer consumer that receives reusable row array
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Beta
    default void foreach(final Condition cond, final Consumer<DisposableObjArray> rowConsumer) throws SQLException {
        forEach(cond, Jdbc.RowConsumer.oneOff(targetEntityClass(), rowConsumer));
    }

}
