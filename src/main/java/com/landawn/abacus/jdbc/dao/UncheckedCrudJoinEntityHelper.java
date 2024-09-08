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

import java.util.Collection;
import java.util.List;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.stream.Stream.StreamEx;

public interface UncheckedCrudJoinEntityHelper<T, ID, SB extends SQLBuilder, TD extends UncheckedCrudDao<T, ID, SB, TD>>
        extends UncheckedJoinEntityHelper<T, SB, TD>, CrudJoinEntityHelper<T, ID, SB, TD> {
    /**
     *
     * @param id
     * @param joinEntitiesToLoad
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, joinEntitiesToLoad));
    }

    /**
     *
     * @param id
     * @param includeAllJoinEntities
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final boolean includeAllJoinEntities) throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, includeAllJoinEntities));
    }

    /**
     *
     * @param id
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
            throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
    }

    /**
     *
     * @param id
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
    }

    /**
     *
     * @param id
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param includeAllJoinEntities
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException
     */
    @Beta
    @Override
    default Optional<T> get(final ID id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, selectPropNames, includeAllJoinEntities));
    }

    /**
     *
     * @param id
     * @param joinEntitiesToLoad
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default T gett(final ID id, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id);

        if (result != null) {
            loadJoinEntities(result, joinEntitiesToLoad);
        }

        return result;
    }

    /**
     *
     * @param id
     * @param includeAllJoinEntities
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default T gett(final ID id, final boolean includeAllJoinEntities) throws DuplicatedResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id);

        if (result != null && includeAllJoinEntities) {
            loadAllJoinEntities(result);
        }

        return result;
    }

    /**
     *
     * @param id
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException
     */
    @Beta
    @Override
    default T gett(final ID id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
            throws DuplicatedResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null) {
            loadJoinEntities(result, joinEntitiesToLoad);
        }

        return result;
    }

    /**
     *
     * @param id
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException
     */
    @Beta
    @Override
    default T gett(final ID id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicatedResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null) {
            for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                loadJoinEntities(result, joinEntityClass);
            }
        }

        return result;
    }

    /**
     *
     * @param id
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param includeAllJoinEntities
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException
     */
    @Beta
    @Override
    default T gett(final ID id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicatedResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null && includeAllJoinEntities) {
            loadAllJoinEntities(result);
        }

        return result;
    }

    /**
     *
     * @param ids
     * @param joinEntitiesToLoad
     * @return
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, UncheckedSQLException {
        return batchGet(ids, null, JdbcUtil.DEFAULT_BATCH_SIZE, joinEntitiesToLoad);
    }

    /**
     *
     *
     * @param ids
     * @param includeAllJoinEntities
     * @return
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final boolean includeAllJoinEntities) throws DuplicatedResultException, UncheckedSQLException {
        return batchGet(ids, null, JdbcUtil.DEFAULT_BATCH_SIZE, includeAllJoinEntities);
    }

    /**
     *
     *
     * @param ids
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @return
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
            throws DuplicatedResultException, UncheckedSQLException {
        return batchGet(ids, selectPropNames, JdbcUtil.DEFAULT_BATCH_SIZE, joinEntitiesToLoad);
    }

    /**
     *
     *
     * @param ids
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @return
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicatedResultException, UncheckedSQLException {
        return batchGet(ids, selectPropNames, JdbcUtil.DEFAULT_BATCH_SIZE, joinEntitiesToLoad);
    }

    /**
     *
     *
     * @param ids
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
     * @param includeAllJoinEntities
     * @return
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicatedResultException, UncheckedSQLException {
        return batchGet(ids, selectPropNames, JdbcUtil.DEFAULT_BATCH_SIZE, includeAllJoinEntities);
    }

    /**
     *
     *
     * @param ids
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
     * @param batchSize
     * @param joinEntitiesToLoad
     * @return
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize,
            final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, UncheckedSQLException {
        final List<T> result = DaoUtil.getCrudDao(this).batchGet(ids, selectPropNames, batchSize);

        if (N.notEmpty(result)) {
            if (result.size() > batchSize) {
                StreamEx.of(result).splitToList(batchSize).forEach(it -> loadJoinEntities(it, joinEntitiesToLoad));
            } else {
                loadJoinEntities(result, joinEntitiesToLoad);
            }
        }

        return result;
    }

    /**
     *
     *
     * @param ids
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
     * @param batchSize
     * @param joinEntitiesToLoad
     * @return
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize,
            final Collection<Class<?>> joinEntitiesToLoad) throws DuplicatedResultException, UncheckedSQLException {
        final List<T> result = DaoUtil.getCrudDao(this).batchGet(ids, selectPropNames, batchSize);

        if (N.notEmpty(result) && N.notEmpty(joinEntitiesToLoad)) {
            if (result.size() > batchSize) {
                StreamEx.of(result).splitToList(batchSize).forEach(it -> {
                    for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                        loadJoinEntities(it, joinEntityClass);
                    }
                });
            } else {
                for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                    loadJoinEntities(result, joinEntityClass);
                }
            }
        }

        return result;
    }

    /**
     *
     * @param ids
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}. all properties(columns) will be selected, excluding the properties of joining entities, if {@code selectPropNames} is {@code null}.
     * @param batchSize
     * @param includeAllJoinEntities
     * @return
     * @throws DuplicatedResultException if the size of result is bigger than the size of input {@code ids}.
     * @throws UncheckedSQLException the unchecked SQL exception
     */
    @Beta
    @Override
    default List<T> batchGet(final Collection<? extends ID> ids, final Collection<String> selectPropNames, final int batchSize,
            final boolean includeAllJoinEntities) throws DuplicatedResultException, UncheckedSQLException {
        final List<T> result = DaoUtil.getCrudDao(this).batchGet(ids, selectPropNames, batchSize);

        if (includeAllJoinEntities && N.notEmpty(result)) {
            if (result.size() > batchSize) {
                StreamEx.of(result).splitToList(batchSize).forEach(this::loadAllJoinEntities);
            } else {
                loadAllJoinEntities(result);
            }
        }

        return result;
    }
}
