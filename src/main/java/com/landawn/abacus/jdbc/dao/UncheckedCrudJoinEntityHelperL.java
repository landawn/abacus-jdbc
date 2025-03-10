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
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.u.Optional;

/**
 * Interface for unchecked CRUD operations with join entity support.
 *
 * @param <T> the type of the entity
 * @param <SB> the type of the SQL builder
 * @param <TD> the type of the CRUD DAO
 */
public interface UncheckedCrudJoinEntityHelperL<T, SB extends SQLBuilder, TD extends UncheckedCrudDaoL<T, SB, TD>>
        extends UncheckedCrudJoinEntityHelper<T, Long, SB, TD>, CrudJoinEntityHelperL<T, SB, TD> {
    /**
     *
     * @param id
     * @param joinEntitiesToLoad
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException
     */
    @Override
    @Beta
    default Optional<T> get(final long id, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, joinEntitiesToLoad));
    }

    /**
     *
     * @param id
     * @param includeAllJoinEntities
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException
     */
    @Override
    @Beta
    default Optional<T> get(final long id, final boolean includeAllJoinEntities) throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, includeAllJoinEntities));
    }

    /**
     *
     * @param id
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException
     */
    @Override
    @Beta
    default Optional<T> get(final long id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
            throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
    }

    /**
     *
     * @param id
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException
     */
    @Override
    @Beta
    default Optional<T> get(final long id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
    }

    /**
     *
     * @param id
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param includeAllJoinEntities
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException
     */
    @Override
    @Beta
    default Optional<T> get(final long id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicatedResultException, UncheckedSQLException {
        return Optional.ofNullable(gett(id, selectPropNames, includeAllJoinEntities));
    }

    /**
     *
     * @param id
     * @param joinEntitiesToLoad
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException
     */
    @Override
    @Beta
    default T gett(final long id, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, UncheckedSQLException {
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
     * @throws UncheckedSQLException
     */
    @Override
    @Beta
    default T gett(final long id, final boolean includeAllJoinEntities) throws DuplicatedResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id);

        if (result != null && includeAllJoinEntities) {
            loadAllJoinEntities(result);
        }

        return result;
    }

    /**
     *
     * @param id
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException
     */
    @Override
    @Beta
    default T gett(final long id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
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
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param joinEntitiesToLoad
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException
     */
    @Override
    @Beta
    default T gett(final long id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
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
     * @param selectPropNames the properties (columns) to be selected, excluding the properties of joining entities. All the properties (columns) will be selected if the specified {@code selectPropNames} is {@code null}.
     * @param includeAllJoinEntities
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws UncheckedSQLException
     */
    @Override
    @Beta
    default T gett(final long id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicatedResultException, UncheckedSQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null && includeAllJoinEntities) {
            loadAllJoinEntities(result);
        }

        return result;
    }
}
