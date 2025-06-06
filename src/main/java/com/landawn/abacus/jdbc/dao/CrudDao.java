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
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.condition.ConditionFactory;
import com.landawn.abacus.condition.ConditionFactory.CF;
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
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.QueryUtil;
import com.landawn.abacus.util.SQLBuilder;
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
 * The Interface CrudDao.
 *
 * @param <T>
 * @param <ID> use {@code Void} if there is no id defined/annotated with {@code @Id} in target entity class {@code T}.
 * @param <SB> {@code SQLBuilder} used to generate SQL scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
 * @see Dao
 * @see com.landawn.abacus.annotation.JoinedBy
 * @see com.landawn.abacus.condition.ConditionFactory
 * @see com.landawn.abacus.condition.ConditionFactory.CF
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
public interface CrudDao<T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> extends Dao<T, SB, TD> {

    /**
     * Returns the functional interface of {@code Jdbc.BiRowMapper} that extracts the ID from a row. Default is {@code null}.
     *
     * @return a BiRowMapper that extracts the ID from a row.
     */
    @SuppressWarnings("SameReturnValue")
    @NonDBOperation
    default Jdbc.BiRowMapper<ID> idExtractor() {
        return null;
    }

    /**
     * Generates an ID.
     * <br> The default implementation is throwing {@code UnsupportedOperationException}.
     * <br> If the implementation supports this operation, override this method.
     *
     * @return the generated ID
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the operation is not supported
     * @deprecated unsupported Operation
     */
    @Deprecated
    @NonDBOperation
    default ID generateId() throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entityToInsert
     * @return
     * @throws SQLException
     */
    ID insert(final T entityToInsert) throws SQLException;

    /**
     *
     * @param entityToInsert
     * @param propNamesToInsert
     * @return
     * @throws SQLException
     */
    ID insert(final T entityToInsert, final Collection<String> propNamesToInsert) throws SQLException;

    /**
     *
     * @param namedInsertSQL
     * @param entityToInsert
     * @return
     * @throws SQLException
     */
    ID insert(final String namedInsertSQL, final T entityToInsert) throws SQLException;

    /**
     *
     * @param entities
     * @return
     * @throws SQLException
     */
    default List<ID> batchInsert(final Collection<? extends T> entities) throws SQLException {
        return batchInsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param entities
     * @param batchSize
     * @return
     * @throws SQLException
     */
    List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws SQLException;

    /**
     *
     * @param entities
     * @param propNamesToInsert
     * @return
     * @throws SQLException
     */
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert) throws SQLException {
        return batchInsert(entities, propNamesToInsert, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param entities
     * @param propNamesToInsert
     * @param batchSize
     * @return
     * @throws SQLException
     */
    List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize) throws SQLException;

    /**
     *
     * @param namedInsertSQL
     * @param entities
     * @return
     * @throws SQLException
     */
    @Beta
    default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities) throws SQLException {
        return batchInsert(namedInsertSQL, entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param namedInsertSQL
     * @param entities
     * @param batchSize
     * @return
     * @throws SQLException
     */
    @Beta
    List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities, final int batchSize) throws SQLException;

    /**
     * Returns an {@code OptionalBoolean} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalBoolean}.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForBoolean()
     */
    OptionalBoolean queryForBoolean(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Returns an {@code OptionalChar} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalChar}.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForChar()
     */
    OptionalChar queryForChar(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Returns an {@code OptionalByte} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalByte}.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForByte()
     */
    OptionalByte queryForByte(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Returns an {@code OptionalShort} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalShort}.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForShort()
     */
    OptionalShort queryForShort(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Returns an {@code OptionalInt} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalInt}.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForInt()
     */
    OptionalInt queryForInt(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Returns an {@code OptionalLong} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalLong}.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForLong()
     */
    OptionalLong queryForLong(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Returns an {@code OptionalFloat} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalFloat}.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForFloat()
     */
    OptionalFloat queryForFloat(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Returns an {@code OptionalDouble} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalDouble}.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForDouble()
     */
    OptionalDouble queryForDouble(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Returns a {@code Nullable<String>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForString()
     */
    Nullable<String> queryForString(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Returns a {@code Nullable<java.sql.Date>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForDate()
     */
    Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Returns a {@code Nullable<java.sql.Time>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForTime()
     */
    Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Returns a {@code Nullable<java.sql.Timestamp>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForTimestamp()
     */
    Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Returns a {@code Nullable<byte[]>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForBytes()
     */
    Nullable<byte[]> queryForBytes(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Returns a {@code Nullable<V>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     * @param singleSelectPropName
     * @param id
     * @param targetValueType
     *
     * @param <V>
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForSingleResult(Class)
     */
    <V> Nullable<V> queryForSingleResult(final String singleSelectPropName, final ID id, final Class<? extends V> targetValueType) throws SQLException;

    /**
     * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
     * @param singleSelectPropName
     * @param id
     * @param targetValueType
     *
     * @param <V> the value type
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForSingleNonNull(Class)
     */
    <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final ID id, final Class<? extends V> targetValueType) throws SQLException;

    /**
     * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
     *
     * @param <V> the value type
     * @param singleSelectPropName
     * @param id
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForSingleNonNull(Class)
     */
    @Beta
    <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final ID id, final Jdbc.RowMapper<? extends V> rowMapper) throws SQLException;

    /**
     * Returns a {@code Nullable} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     * And throws {@code DuplicatedResultException} if more than one record found.
     * @param singleSelectPropName
     * @param id
     * @param targetValueType
     *
     * @param <V> the value type
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForUniqueResult(Class)
     */
    <V> Nullable<V> queryForUniqueResult(final String singleSelectPropName, final ID id, final Class<? extends V> targetValueType)
            throws DuplicatedResultException, SQLException;

    /**
     * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
     * @param singleSelectPropName
     * @param id
     * @param targetValueType
     *
     * @param <V> the value type
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForUniqueNonNull(Class)
     */
    <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final ID id, final Class<? extends V> targetValueType)
            throws DuplicatedResultException, SQLException;

    /**
     * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
     *
     * @param <V> the value type
     * @param singleSelectPropName
     * @param id
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForUniqueNonNull(Class)
     */
    @Beta
    <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final ID id, final Jdbc.RowMapper<? extends V> rowMapper)
            throws DuplicatedResultException, SQLException;

    /**
     * Returns the record found by the specified {@code id} or an empty {@code Optional} if no record is found.
     *
     * @param id
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     */
    default Optional<T> get(final ID id) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id));
    }

    /**
     * Returns the record found by the specified {@code id} or an empty {@code Optional} if no record is found.
     *
     * @param id
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     */
    default Optional<T> get(final ID id, final Collection<String> selectPropNames) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, selectPropNames));
    }

    /**
     * Returns the record found by the specified {@code id} or {@code null} if no record is found.
     *
     * @param id
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     */
    T gett(final ID id) throws DuplicatedResultException, SQLException;

    /**
     * Returns the record found by the specified {@code id} or {@code null} if no record is found.
     *
     * @param id
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     *
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     */
    T gett(final ID id, final Collection<String> selectPropNames) throws DuplicatedResultException, SQLException;

    /**
     *
     *
     * @param ids
     * @return
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
     * @throws SQLException
     */
    default List<T> batchGet(final Collection<? extends ID> ids) throws DuplicatedResultException, SQLException {
        return batchGet(ids, (Collection<String>) null);
    }

    /**
     *
     * @param ids
     * @param batchSize
     * @return
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
     * @throws SQLException
     */
    default List<T> batchGet(final Collection<? extends ID> ids, final int batchSize) throws DuplicatedResultException, SQLException {
        return batchGet(ids, (Collection<String>) null, batchSize);
    }

    /**
     *
     *
     * @param ids
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities.
     *      All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     *      All properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @return
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
     * @throws SQLException
     */
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames) throws DuplicatedResultException, SQLException {
        return batchGet(ids, selectPropNames, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param ids
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities.
     *      All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     *      All properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
     * @param batchSize
     * @return
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
     * @throws SQLException
     */
    List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize)
            throws DuplicatedResultException, SQLException;

    /**
     *
     * @param id
     * @return {@code true}, if successful
     * @throws SQLException
     * @see AbstractQuery#exists()
     */
    boolean exists(final ID id) throws SQLException;

    /**
     *
     * @param id
     * @return
     * @throws SQLException
     * @see AbstractQuery#notExists()
     */
    @Beta
    default boolean notExists(final ID id) throws SQLException {
        return !exists(id);
    }

    /**
     * Count the records in db by input {@code ids}.
     * @param ids
     * @return
     * @throws SQLException
     */
    @Beta
    int count(final Collection<? extends ID> ids) throws SQLException;

    /**
     *
     * @param entityToUpdate
     * @return
     * @throws SQLException
     */
    int update(final T entityToUpdate) throws SQLException;

    /**
     *
     * @param entityToUpdate
     * @param propNamesToUpdate
     * @return
     * @throws SQLException
     */
    int update(final T entityToUpdate, final Collection<String> propNamesToUpdate) throws SQLException;

    /**
     *
     * @param propName
     * @param propValue
     * @param id
     * @return
     * @throws SQLException
     */
    default int update(final String propName, final Object propValue, final ID id) throws SQLException {
        final Map<String, Object> updateProps = new HashMap<>();
        updateProps.put(propName, propValue);

        return update(updateProps, id);
    }

    /**
     *
     * @param updateProps
     * @param id
     * @return
     * @throws SQLException
     */
    int update(final Map<String, Object> updateProps, final ID id) throws SQLException;

    /**
     *
     * @param entities
     * @return
     * @throws SQLException
     */
    default int batchUpdate(final Collection<? extends T> entities) throws SQLException {
        return batchUpdate(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param entities
     * @param batchSize
     * @return
     * @throws SQLException
     */
    int batchUpdate(final Collection<? extends T> entities, final int batchSize) throws SQLException;

    /**
     *
     * @param entities
     * @param propNamesToUpdate
     * @return
     * @throws SQLException
     */
    default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate) throws SQLException {
        return batchUpdate(entities, propNamesToUpdate, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param entities
     * @param propNamesToUpdate
     * @param batchSize
     * @return
     * @throws SQLException
     */
    int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize) throws SQLException;

    /**
     * Executes {@code insertion} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
     *
     * @param entity the entity to add or update.
     * @return the added or updated db record.
     * @throws SQLException
     */
    default T upsert(final T entity) throws SQLException {
        N.checkArgNotNull(entity, cs.entity);

        final Class<?> cls = entity.getClass();
        @SuppressWarnings("deprecation")
        final List<String> idPropNameList = QueryUtil.getIdFieldNames(cls); // must not empty.

        return upsert(entity, idPropNameList);
    }

    /**
     * Execute {@code insertion} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
     *
     * @param entity the entity to add or update.
     * @param cond to verify if the record exists or not.
     * @return the added or updated db record.
     * @throws SQLException
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
            N.merge(entity, dbEntity, false, N.newHashSet(idPropNameList));
            update(dbEntity);
            return dbEntity;
        }
    }

    /**
     *
     * @param entities
     * @return
     * @throws SQLException
     */
    default List<T> batchUpsert(final Collection<? extends T> entities) throws SQLException {
        return batchUpsert(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param entities
     * @param batchSize
     * @return
     * @throws SQLException
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
     *
     * @param entities
     * @param uniquePropNamesForQuery
     * @return
     * @throws SQLException
     */
    default List<T> batchUpsert(final Collection<? extends T> entities, final List<String> uniquePropNamesForQuery) throws SQLException {
        return batchUpsert(entities, uniquePropNamesForQuery, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param entities
     * @param uniquePropNamesForQuery
     * @param batchSize
     * @return
     * @throws SQLException
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
                        .map(it -> N.merge(it, dbIdEntityMap.get(keysExtractor.apply(it)), false, ignoredPropNames))
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
     * Refreshes the given entity by reloading its properties from the database.
     *
     * @param entity the entity to refresh
     * @return {@code true} if the entity was successfully refreshed by reloading its properties from the database, {@code false} otherwise if no record found by the id in the specified {@code entity}.
     * @throws SQLException if a database access error occurs
     */
    default boolean refresh(final T entity) throws SQLException {
        N.checkArgNotNull(entity, cs.entity);

        final Class<?> cls = entity.getClass();
        final Collection<String> propNamesToRefresh = JdbcUtil.getSelectPropNames(cls);

        return refresh(entity, propNamesToRefresh);
    }

    /**
     * Refreshes the given entity by reloading its specified properties from the database.
     *
     * @param entity the entity to refresh
     * @param propNamesToRefresh the properties to refresh
     * @return {@code true} if the entity was successfully refreshed by reloading its properties from the database, {@code false} otherwise if no record found by the id in the specified {@code entity}.
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
            N.merge(dbEntity, entity, propNamesToRefresh);

            return true;
        }
    }

    /**
     * Refreshes entities by reloading their properties from the database by batch.
     *
     * @param entities the collection of entities to refresh
     * @return the count of refreshed entities
     * @throws SQLException if a database access error occurs
     */
    default int batchRefresh(final Collection<? extends T> entities) throws SQLException {
        return batchRefresh(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Refreshes entities by reloading their properties from the database by batch.
     *
     * @param entities the collection of entities to refresh
     * @param batchSize the number of entities to refresh in each batch
     * @return the count of refreshed entities
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
     * Refreshes entities by reloading their specified properties from the database by batch.
     *
     * @param entities the collection of entities to refresh
     * @param propNamesToRefresh the properties to refresh
     * @return the count of refreshed entities
     * @throws SQLException if a database access error occurs
     */
    default int batchRefresh(final Collection<? extends T> entities, final Collection<String> propNamesToRefresh) throws SQLException {
        return batchRefresh(entities, propNamesToRefresh, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Refreshes entities by reloading their specified properties from the database by batch.
     *
     * @param entities the collection of entities to refresh
     * @param propNamesToRefresh the properties to refresh
     * @param batchSize the number of entities to refresh in each batch
     * @return the count of refreshed entities
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
                        N.merge(dbEntity, entity, propNamesToRefresh);
                    }
                }

                return N.size(tmp);
            }).sum();
        }
    }

    /**
     *
     * @param entity
     * @return
     * @throws SQLException
     */
    int delete(final T entity) throws SQLException;

    /**
     * Delete by id.
     *
     * @param id
     * @return
     * @throws SQLException
     */
    int deleteById(final ID id) throws SQLException;

    /**
     *
     * @param entities
     * @return
     * @throws SQLException
     */
    default int batchDelete(final Collection<? extends T> entities) throws SQLException {
        return batchDelete(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param entities
     * @param batchSize
     * @return
     * @throws SQLException
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
     *
     * @param ids
     * @return
     * @throws SQLException
     */
    default int batchDeleteByIds(final Collection<? extends ID> ids) throws SQLException {
        return batchDeleteByIds(ids, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param ids
     * @param batchSize
     * @return
     * @throws SQLException
     */
    int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws SQLException;
}
