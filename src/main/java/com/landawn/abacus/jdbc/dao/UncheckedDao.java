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
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.condition.ConditionFactory;
import com.landawn.abacus.condition.ConditionFactory.CF;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.AbstractQuery;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.Jdbc.Columns.ColumnOne;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.DataSet;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.QueryUtil;
import com.landawn.abacus.util.SQLBuilder;
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
 * Its methods throw {@code UncheckedSQLException} instead of {@code SQLException}.
 *
 * @param <T>
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @param <TD>
 * @see com.landawn.abacus.condition.ConditionFactory
 * @see com.landawn.abacus.condition.ConditionFactory.CF
 */
@Beta
public interface UncheckedDao<T, SB extends SQLBuilder, TD extends UncheckedDao<T, SB, TD>> extends Dao<T, SB, TD> {

    /**
     *
     *
     * @param entityToSave
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    void save(final T entityToSave) throws UncheckedSQLException;

    /**
     *
     *
     * @param entityToSave
     * @param propNamesToSave
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    void save(final T entityToSave, final Collection<String> propNamesToSave) throws UncheckedSQLException;

    /**
     *
     *
     * @param namedInsertSQL
     * @param entityToSave
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    void save(final String namedInsertSQL, final T entityToSave) throws UncheckedSQLException;

    /**
     * Insert the specified entities to database by batch.
     *
     * @param entitiesToSave
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see CrudDao#batchInsert(Collection)
     */
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave) throws UncheckedSQLException {
        batchSave(entitiesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Insert the specified entities to database by batch.
     *
     * @param entitiesToSave
     * @param batchSize
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see CrudDao#batchInsert(Collection)
     */
    @Override
    void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws UncheckedSQLException;

    /**
     * Insert the specified entities to database by batch.
     *
     * @param entitiesToSave
     * @param propNamesToSave
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see CrudDao#batchInsert(Collection)
     */
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave) throws UncheckedSQLException {
        batchSave(entitiesToSave, propNamesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Insert the specified entities to database by batch.
     *
     * @param entitiesToSave
     * @param propNamesToSave
     * @param batchSize
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see CrudDao#batchInsert(Collection)
     */
    @Override
    void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize) throws UncheckedSQLException;

    /**
     * Insert the specified entities to database by batch.
     *
     * @param namedInsertSQL
     * @param entitiesToSave
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see CrudDao#batchInsert(Collection)
     */
    @Beta
    @Override
    default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave) throws UncheckedSQLException {
        batchSave(namedInsertSQL, entitiesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Insert the specified entities to database by batch.
     *
     * @param namedInsertSQL
     * @param entitiesToSave
     * @param batchSize
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see CrudDao#batchInsert(Collection)
     */
    @Beta
    @Override
    void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize) throws UncheckedSQLException;

    /**
     *
     * @param cond
     * @return {@code true}, if there is at least one record found.
     * @throws UncheckedSQLException the unchecked SQL exception
     * @see AbstractQuery#exists()
     */
    @Override
    boolean exists(final Condition cond) throws UncheckedSQLException;

    /**
     *
     * @param cond
     * @return {@code true}, if there is no record found.
     * @throws UncheckedSQLException
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
     *
     * @param cond
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    int count(final Condition cond) throws UncheckedSQLException;

    //    /**
    //     *
    //     * @param selectPropNames
    //     * @param cond
    //     * @param rowFilter
    //     * @return
    //     * @throws UncheckedSQLException
    //     */
    //    @Override
    //    @Beta
    //    default boolean anyMatch(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowFilter rowFilter) throws UncheckedSQLException {
    //        try {
    //            return prepareQuery(selectPropNames, cond).anyMatch(rowFilter);
    //        } catch (SQLException e) {
    //            throw new UncheckedSQLException(e);
    //        }
    //    }
    //
    //    /**
    //     *
    //     * @param selectPropNames
    //     * @param cond
    //     * @param rowFilter
    //     * @return
    //     * @throws UncheckedSQLException
    //     */
    //    @Override
    //    @Beta
    //    default boolean anyMatch(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter) throws UncheckedSQLException {
    //        try {
    //            return prepareQuery(selectPropNames, cond).anyMatch(rowFilter);
    //        } catch (SQLException e) {
    //            throw new UncheckedSQLException(e);
    //        }
    //    }
    //
    //    /**
    //     *
    //     * @param selectPropNames
    //     * @param cond
    //     * @param rowFilter
    //     * @return
    //     * @throws UncheckedSQLException
    //     */
    //    @Override
    //    @Beta
    //    default boolean allMatch(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowFilter rowFilter) throws UncheckedSQLException {
    //        try {
    //            return prepareQuery(selectPropNames, cond).allMatch(rowFilter);
    //        } catch (SQLException e) {
    //            throw new UncheckedSQLException(e);
    //        }
    //    }
    //
    //    /**
    //     *
    //     * @param selectPropNames
    //     * @param cond
    //     * @param rowFilter
    //     * @return
    //     * @throws UncheckedSQLException
    //     */
    //    @Override
    //    @Beta
    //    default boolean allMatch(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter) throws UncheckedSQLException {
    //        try {
    //            return prepareQuery(selectPropNames, cond).allMatch(rowFilter);
    //        } catch (SQLException e) {
    //            throw new UncheckedSQLException(e);
    //        }
    //    }
    //
    //    /**
    //     *
    //     * @param selectPropNames
    //     * @param cond
    //     * @param rowFilter
    //     * @return
    //     * @throws UncheckedSQLException
    //     */
    //    @Override
    //    @Beta
    //    default boolean noneMatch(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowFilter rowFilter) throws UncheckedSQLException {
    //        try {
    //            return prepareQuery(selectPropNames, cond).noneMatch(rowFilter);
    //        } catch (SQLException e) {
    //            throw new UncheckedSQLException(e);
    //        }
    //    }
    //
    //    /**
    //     *
    //     * @param selectPropNames
    //     * @param cond
    //     * @param rowFilter
    //     * @return
    //     * @throws UncheckedSQLException
    //     */
    //    @Override
    //    @Beta
    //    default boolean noneMatch(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter) throws UncheckedSQLException {
    //        try {
    //            return prepareQuery(selectPropNames, cond).noneMatch(rowFilter);
    //        } catch (SQLException e) {
    //            throw new UncheckedSQLException(e);
    //        }
    //    }

    /**
     *
     * @param cond
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    Optional<T> findFirst(final Condition cond) throws UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param rowMapper
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> Optional<R> findFirst(final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param rowMapper
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> Optional<R> findFirst(final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper) throws UncheckedSQLException;

    /**
     *
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    Optional<T> findFirst(final Collection<String> selectPropNames, final Condition cond) throws UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper)
            throws UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper)
            throws UncheckedSQLException;

    /**
     *
     * @param cond
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    Optional<T> findOnlyOne(final Condition cond) throws DuplicatedResultException, UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> Optional<R> findOnlyOne(final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws DuplicatedResultException, UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> Optional<R> findOnlyOne(final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper) throws DuplicatedResultException, UncheckedSQLException;

    /**
     *
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    Optional<T> findOnlyOne(final Collection<String> selectPropNames, final Condition cond) throws DuplicatedResultException, UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> Optional<R> findOnlyOne(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper)
            throws DuplicatedResultException, UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> Optional<R> findOnlyOne(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper)
            throws DuplicatedResultException, UncheckedSQLException;

    /**
     * Returns an {@code OptionalBoolean} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalBoolean}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForBoolean()
     */
    @Override
    OptionalBoolean queryForBoolean(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalChar} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalChar}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForChar()
     */
    @Override
    OptionalChar queryForChar(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalByte} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalByte}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForByte()
     */
    @Override
    OptionalByte queryForByte(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalShort} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalShort}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForShort()
     */
    @Override
    OptionalShort queryForShort(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalInt} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalInt}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForInt()
     */
    @Override
    OptionalInt queryForInt(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalLong} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalLong}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForLong()
     */
    @Override
    OptionalLong queryForLong(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalFloat} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalFloat}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForFloat()
     */
    @Override
    OptionalFloat queryForFloat(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns an {@code OptionalDouble} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalDouble}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForDouble()
     */
    @Override
    OptionalDouble queryForDouble(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable<String>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForString()
     */
    @Override
    Nullable<String> queryForString(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable<java.sql.Date>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForDate()
     */
    @Override
    Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable<java.sql.Time>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForTime()
     */
    @Override
    Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable<java.sql.Timestamp>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForTimestamp()
     */
    @Override
    Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable<byte[]>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForBytes()
     */
    @Override
    Nullable<byte[]> queryForBytes(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable<V>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     * @param singleSelectPropName
     * @param cond
     * @param targetValueType
     *
     * @param <V>
     * @return
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForSingleResult(Class)
     */
    @Override
    <V> Nullable<V> queryForSingleResult(final String singleSelectPropName, final Condition cond, final Class<? extends V> targetValueType)
            throws UncheckedSQLException;

    /**
     * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
     * @param singleSelectPropName
     * @param cond
     * @param targetValueType
     *
     * @param <V> the value type
     * @return
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForSingleNonNull(Class)
     */
    @Override
    <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final Condition cond, final Class<? extends V> targetValueType)
            throws UncheckedSQLException;

    /**
     * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
     *
     * @param <V> the value type
     * @param singleSelectPropName
     * @param cond
     * @param rowMapper
     * @return
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForSingleNonNull(Class)
     */
    @Override
    <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final Condition cond, final Jdbc.RowMapper<? extends V> rowMapper)
            throws UncheckedSQLException;

    /**
     * Returns a {@code Nullable} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     * And throws {@code DuplicatedResultException} if more than one record found.
     * @param singleSelectPropName
     * @param cond
     * @param targetValueType
     *
     * @param <V> the value type
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForUniqueResult(Class)
     */
    @Override
    <V> Nullable<V> queryForUniqueResult(final String singleSelectPropName, final Condition cond, final Class<? extends V> targetValueType)
            throws DuplicatedResultException, UncheckedSQLException;

    /**
     * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
     * @param singleSelectPropName
     * @param cond
     * @param targetValueType
     *
     * @param <V> the value type
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForUniqueNonNull(Class)
     */
    @Override
    <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final Condition cond, final Class<? extends V> targetValueType)
            throws DuplicatedResultException, UncheckedSQLException;

    /**
     * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
     *
     * @param <V> the value type
     * @param singleSelectPropName
     * @param cond
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForUniqueNonNull(Class)
     */
    @Override
    <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final Condition cond, final Jdbc.RowMapper<? extends V> rowMapper)
            throws DuplicatedResultException, UncheckedSQLException;

    /**
     *
     * @param cond
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    DataSet query(final Condition cond) throws UncheckedSQLException;

    /**
     *
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    DataSet query(final Collection<String> selectPropNames, final Condition cond) throws UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param resultExtractor Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> R query(final Condition cond, final Jdbc.ResultExtractor<? extends R> resultExtractor) throws UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param resultExtractor Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> R query(final Collection<String> selectPropNames, final Condition cond, final Jdbc.ResultExtractor<? extends R> resultExtractor)
            throws UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param resultExtractor Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> R query(final Condition cond, final Jdbc.BiResultExtractor<? extends R> resultExtractor) throws UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param resultExtractor Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> R query(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiResultExtractor<? extends R> resultExtractor)
            throws UncheckedSQLException;

    /**
     *
     * @param cond
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    List<T> list(final Condition cond) throws UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param rowMapper
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> List<R> list(final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param rowMapper
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> List<R> list(final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper) throws UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> List<R> list(final Condition cond, final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends R> rowMapper) throws UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> List<R> list(final Condition cond, final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends R> rowMapper) throws UncheckedSQLException;

    /**
     *
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    List<T> list(final Collection<String> selectPropNames, final Condition cond) throws UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper)
            throws UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends R> rowMapper) throws UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter,
            final Jdbc.BiRowMapper<? extends R> rowMapper) throws UncheckedSQLException;

    /**
     *
     *
     * @param <R>
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default <R> List<R> list(final String singleSelectPropName, final Condition cond) throws UncheckedSQLException {
        @SuppressWarnings("deprecation")
        final PropInfo propInfo = ParserUtil.getBeanInfo(targetEntityClass()).getPropInfo(singleSelectPropName);
        final Jdbc.RowMapper<? extends R> rowMapper = propInfo == null ? ColumnOne.getObject() : ColumnOne.get((Type<R>) propInfo.dbType);

        return list(singleSelectPropName, cond, rowMapper);
    }

    /**
     *
     *
     * @param <R>
     * @param singleSelectPropName
     * @param cond
     * @param rowMapper
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default <R> List<R> list(final String singleSelectPropName, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper)
            throws UncheckedSQLException {
        return list(N.asList(singleSelectPropName), cond, rowMapper);
    }

    /**
     *
     *
     * @param <R>
     * @param singleSelectPropName
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default <R> List<R> list(final String singleSelectPropName, final Condition cond, final Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends R> rowMapper) throws UncheckedSQLException {
        return list(N.asList(singleSelectPropName), cond, rowFilter, rowMapper);
    }

    /**
     *
     *
     * @param cond
     * @param rowConsumer
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    void forEach(final Condition cond, final Jdbc.RowConsumer rowConsumer) throws UncheckedSQLException;

    /**
     *
     *
     * @param cond
     * @param rowConsumer
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    void forEach(final Condition cond, final Jdbc.BiRowConsumer rowConsumer) throws UncheckedSQLException;

    /**
     *
     *
     * @param cond
     * @param rowFilter
     * @param rowConsumer
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    void forEach(final Condition cond, final Jdbc.RowFilter rowFilter, final Jdbc.RowConsumer rowConsumer) throws UncheckedSQLException;

    /**
     *
     *
     * @param cond
     * @param rowFilter
     * @param rowConsumer
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    void forEach(final Condition cond, final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowConsumer rowConsumer) throws UncheckedSQLException;

    /**
     *
     *
     * @param selectPropNames
     * @param cond
     * @param rowConsumer
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowConsumer rowConsumer) throws UncheckedSQLException;

    /**
     *
     *
     * @param selectPropNames
     * @param cond
     * @param rowConsumer
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowConsumer rowConsumer) throws UncheckedSQLException;

    /**
     *
     *
     * @param selectPropNames
     * @param cond
     * @param rowFilter
     * @param rowConsumer
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowFilter rowFilter, final Jdbc.RowConsumer rowConsumer)
            throws UncheckedSQLException;

    /**
     *
     *
     * @param selectPropNames
     * @param cond
     * @param rowFilter
     * @param rowConsumer
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowConsumer rowConsumer)
            throws UncheckedSQLException;

    /**
     *
     *
     * @param selectPropNames
     * @param cond
     * @param rowConsumer
     * @throws UncheckedSQLException
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
     *
     *
     * @param cond
     * @param rowConsumer
     * @throws UncheckedSQLException
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
     *
     * @param propName
     * @param propValue
     * @param cond
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    default int update(final String propName, final Object propValue, final Condition cond) throws UncheckedSQLException {
        final Map<String, Object> updateProps = new HashMap<>();
        updateProps.put(propName, propValue);

        return update(updateProps, cond);
    }

    /**
     *
     * @param updateProps
     * @param cond
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    int update(final Map<String, Object> updateProps, final Condition cond) throws UncheckedSQLException;

    /**
     * Update all the records found by specified {@code cond} with the properties from specified {@code entity}.
     *
     * @param entity
     * @param cond
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default int update(final T entity, final Condition cond) throws UncheckedSQLException {
        @SuppressWarnings("deprecation")
        final Collection<String> propNamesToUpdate = QueryUtil.getUpdatePropNames(targetEntityClass(), null);

        return update(entity, propNamesToUpdate, cond);
    }

    /**
     * Update all the records found by specified {@code cond} with specified {@code propNamesToUpdate} from specified {@code entity}.
     *
     * @param entity
     * @param propNamesToUpdate
     * @param cond
     * @return
     * @throws UncheckedSQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @Override
    int update(final T entity, final Collection<String> propNamesToUpdate, final Condition cond) throws UncheckedSQLException;

    /**
     * Executes {@code insertion} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
     *
     * @param entity the entity to add or update.
     * @param uniquePropNamesForQuery the list of unique property names to use to verify if the record exists or not.
     * @return the added or updated db record.
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
     * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
     *
     * @param entity
     * @param cond to verify if the record exists or not.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
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
     *
     * @param cond
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Override
    int delete(final Condition cond) throws UncheckedSQLException;
}
