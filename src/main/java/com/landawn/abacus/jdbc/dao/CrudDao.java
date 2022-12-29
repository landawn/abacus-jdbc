/*
 * Copyright (c) 2021, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.jdbc.AbstractPreparedQuery;
import com.landawn.abacus.jdbc.IsolationLevel;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.SQLExecutor;
import com.landawn.abacus.jdbc.SQLTransaction;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.N;
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
import com.landawn.abacus.util.stream.Stream.StreamEx;

/**
 * The Interface CrudDao.
 *
 * @param <T>
 * @param <ID> use {@code Void} if there is no id defined/annotated with {@code @Id} in target entity class {@code T}.
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
 * @see Dao
 * @see SQLExecutor.Mapper
 * @see com.landawn.abacus.condition.ConditionFactory
 * @see com.landawn.abacus.condition.ConditionFactory.CF
 */
public interface CrudDao<T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> extends Dao<T, SB, TD> {

    @NonDBOperation
    default Jdbc.BiRowMapper<ID> idExtractor() {
        return null;
    }

    /**
     *
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @NonDBOperation
    default ID generateId() throws UnsupportedOperationException, SQLException {
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
     * Query for boolean.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see IDFactory
     * @see IDFactory.CF
     */
    OptionalBoolean queryForBoolean(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Query for char.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see IDFactory
     * @see IDFactory.CF
     */
    OptionalChar queryForChar(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Query for byte.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see IDFactory
     * @see IDFactory.CF
     */
    OptionalByte queryForByte(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Query for short.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see IDFactory
     * @see IDFactory.CF
     */
    OptionalShort queryForShort(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Query for int.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see IDFactory
     * @see IDFactory.CF
     */
    OptionalInt queryForInt(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Query for long.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see IDFactory
     * @see IDFactory.CF
     */
    OptionalLong queryForLong(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Query for float.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see IDFactory
     * @see IDFactory.CF
     */
    OptionalFloat queryForFloat(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Query for double.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see IDFactory
     * @see IDFactory.CF
     */
    OptionalDouble queryForDouble(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Query for string.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see IDFactory
     * @see IDFactory.CF
     */
    Nullable<String> queryForString(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Query for date.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see IDFactory
     * @see IDFactory.CF
     */
    Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Query for time.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see IDFactory
     * @see IDFactory.CF
     */
    Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Query for timestamp.
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see IDFactory
     * @see IDFactory.CF
     */
    Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Query for single result.
     *
     * @param <V> the value type
     * @param targetValueClass
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see IDFactory
     * @see IDFactory.CF
     */
    <V> Nullable<V> queryForSingleResult(final Class<V> targetValueClass, final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Query for single non null.
     *
     * @param <V> the value type
     * @param targetValueClass
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code idition}).
     * @throws SQLException
     * @see IDFactory
     * @see IDFactory.CF
     */
    <V> Optional<V> queryForSingleNonNull(final Class<V> targetValueClass, final String singleSelectPropName, final ID id) throws SQLException;

    /**
     * Query for unique result.
     *
     * @param <V> the value type
     * @param targetValueClass
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code idition}).
     * @throws SQLException
     * @see IDFactory
     * @see IDFactory.CF
     */
    <V> Nullable<V> queryForUniqueResult(final Class<V> targetValueClass, final String singleSelectPropName, final ID id)
            throws DuplicatedResultException, SQLException;

    /**
     * Query for unique non null.
     *
     * @param <V> the value type
     * @param targetValueClass
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     * @see IDFactory
     * @see IDFactory.CF
     */
    <V> Optional<V> queryForUniqueNonNull(final Class<V> targetValueClass, final String singleSelectPropName, final ID id)
            throws DuplicatedResultException, SQLException;

    /**
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
     *
     * @param id
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     */
    default Optional<T> get(final ID id, final Collection<String> selectPropNames) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, selectPropNames));
    }

    /**
     *
     * @param id
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     */
    T gett(final ID id) throws DuplicatedResultException, SQLException;

    /**
     *
     * @param id
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
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
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
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
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
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
     * @return true, if successful
     * @throws SQLException
     * @see AbstractPreparedQuery#exists()
     */
    boolean exists(final ID id) throws SQLException;

    /**
     *
     * @param id
     * @return
     * @throws SQLException
     * @see AbstractPreparedQuery#notExists()
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
    int count(final Collection<ID> ids) throws SQLException;

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
     * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
     *
     * @param entity
     * @param cond to verify if the record exists or not.
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @Override
    default T upsert(final T entity, final Condition cond) throws SQLException {
        N.checkArgNotNull(entity, "entity");
        N.checkArgNotNull(cond, "cond");

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
     * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
     *
     * @param entity
     * @return
     * @throws SQLException
     */
    default T upsert(final T entity) throws SQLException {
        N.checkArgNotNull(entity, "entity");

        final Class<?> cls = entity.getClass();
        @SuppressWarnings("deprecation")
        final List<String> idPropNameList = QueryUtil.getIdFieldNames(cls); // must not empty.
        final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);
        final T dbEntity = gett(DaoUtil.extractId(entity, idPropNameList, entityInfo));

        if (dbEntity == null) {
            insert(entity);
            return entity;
        } else {
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
        N.checkArgPositive(batchSize, "batchSize");

        if (N.isNullOrEmpty(entities)) {
            return new ArrayList<>();
        }

        final T first = N.firstOrNullIfEmpty(entities);
        final Class<?> cls = first.getClass();
        @SuppressWarnings("deprecation")
        final List<String> idPropNameList = QueryUtil.getIdFieldNames(cls); // must not empty.
        final BeanInfo entityInfo = ParserUtil.getBeanInfo(cls);

        final com.landawn.abacus.util.function.Function<T, ID> idExtractorFunc = DaoUtil.createIdExtractor(idPropNameList, entityInfo);
        final List<ID> ids = N.map(entities, idExtractorFunc);

        final List<T> dbEntities = batchGet(ids, batchSize);

        final Map<ID, T> dbIdEntityMap = StreamEx.of(dbEntities).toMap(idExtractorFunc, Fn.identity(), Fn.ignoringMerger());
        final Map<Boolean, List<T>> map = StreamEx.of(entities).groupTo(it -> dbIdEntityMap.containsKey(idExtractorFunc.apply(it)), Fn.identity());
        final List<T> entitiesToUpdate = map.get(true);
        final List<T> entitiesToInsert = map.get(false);

        final SQLTransaction tran = N.notNullOrEmpty(entitiesToInsert) && N.notNullOrEmpty(entitiesToUpdate) ? JdbcUtil.beginTransaction(dataSource()) : null;

        try {
            if (N.notNullOrEmpty(entitiesToInsert)) {
                batchInsert(entitiesToInsert, batchSize);
            }

            if (N.notNullOrEmpty(entitiesToUpdate)) {
                final Set<String> idPropNameSet = N.newHashSet(idPropNameList);

                final List<T> dbEntitiesToUpdate = StreamEx.of(entitiesToUpdate)
                        .map(it -> N.merge(it, dbIdEntityMap.get(idExtractorFunc.apply(it)), false, idPropNameSet))
                        .toList();

                batchUpdate(dbEntitiesToUpdate, batchSize);

                entitiesToInsert.addAll(dbEntitiesToUpdate);
            }

            if (tran != null) {
                tran.commit();
            }
        } finally {
            if (tran != null) {
                tran.rollbackIfNotCommitted();
            }
        }

        return entitiesToInsert;
    }

    /**
     *
     * @param entity
     * @return true, if successful
     * @throws SQLException
     */
    default boolean refresh(final T entity) throws SQLException {
        N.checkArgNotNull(entity, "entity");

        final Class<?> cls = entity.getClass();
        final Collection<String> propNamesToRefresh = JdbcUtil.getSelectPropNames(cls);

        return refresh(entity, propNamesToRefresh);
    }

    /**
     *
     * @param entity
     * @param propNamesToRefresh
     * @return {@code false} if no record found by the ids in the specified {@code entity}.
     * @throws SQLException
     */
    @SuppressWarnings("deprecation")
    default boolean refresh(final T entity, Collection<String> propNamesToRefresh) throws SQLException {
        N.checkArgNotNull(entity, "entity");
        N.checkArgNotNullOrEmpty(propNamesToRefresh, "propNamesToRefresh");

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
     *
     * @param entities
     * @return the count of refreshed entities.
     * @throws SQLException
     */
    default int batchRefresh(final Collection<? extends T> entities) throws SQLException {
        return batchRefresh(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param entities
     * @param batchSize
     * @return the count of refreshed entities.
     * @throws SQLException
     */
    default int batchRefresh(final Collection<? extends T> entities, final int batchSize) throws SQLException {
        if (N.isNullOrEmpty(entities)) {
            return 0;
        }

        final T first = N.firstOrNullIfEmpty(entities);
        final Class<?> cls = first.getClass();
        final Collection<String> propNamesToRefresh = JdbcUtil.getSelectPropNames(cls);

        return batchRefresh(entities, propNamesToRefresh, batchSize);
    }

    /**
     *
     * @param entities
     * @param propNamesToRefresh
     * @return the count of refreshed entities.
     * @throws SQLException
     */
    default int batchRefresh(final Collection<? extends T> entities, final Collection<String> propNamesToRefresh) throws SQLException {
        return batchRefresh(entities, propNamesToRefresh, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     *
     * @param entities
     * @param propNamesToRefresh
     * @param batchSize
     * @return the count of refreshed entities.
     * @throws SQLException
     */
    @SuppressWarnings("deprecation")
    default int batchRefresh(final Collection<? extends T> entities, Collection<String> propNamesToRefresh, final int batchSize) throws SQLException {
        N.checkArgNotNullOrEmpty(propNamesToRefresh, "propNamesToRefresh");
        N.checkArgPositive(batchSize, "batchSize");

        if (N.isNullOrEmpty(entities)) {
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

        if (N.isNullOrEmpty(dbEntities)) {
            return 0;
        } else {
            return dbEntities.stream().mapToInt(dbEntity -> {
                final ID id = idExtractorFunc.apply(dbEntity);
                final List<T> tmp = idEntityMap.get(id);

                if (N.notNullOrEmpty(tmp)) {
                    for (T entity : tmp) {
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