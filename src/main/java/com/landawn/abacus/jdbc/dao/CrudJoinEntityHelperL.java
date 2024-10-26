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
import java.util.Collection;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.u.Optional;

/**
 * Interface for CRUD operations with join entity support.
 *
 * @param <T> the type of the entity
 * @param <SB> the type of the SQL builder
 * @param <TD> the type of the CRUD DAO
 */
public interface CrudJoinEntityHelperL<T, SB extends SQLBuilder, TD extends CrudDaoL<T, SB, TD>> extends CrudJoinEntityHelper<T, Long, SB, TD> {

    /**
     * Retrieves an entity by its ID and loads the specified join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return an Optional containing the retrieved entity with the specified join entities loaded, or an empty Optional if no entity is found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final long id, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by its ID, optionally including all join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param includeAllJoinEntities if {@code true}, all join entities will be included
     * @return an Optional containing the retrieved entity with the specified join entities loaded, or an empty Optional if no entity is found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final long id, final boolean includeAllJoinEntities) throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, includeAllJoinEntities));
    }

    /**
     * Retrieves an entity by its ID, selecting specified properties and loads the specified join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return an Optional containing the retrieved entity with the specified join entities loaded, or an empty Optional if no entity is found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final long id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad)
            throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by its ID, selecting specified properties and loads the specified join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}
     * @param joinEntitiesToLoad the classes of the join entities to load
     * @return an Optional containing the retrieved entity with the specified join entities loaded, or an empty Optional if no entity is found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final long id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, selectPropNames, joinEntitiesToLoad));
    }

    /**
     * Retrieves an entity by its ID, selecting specified properties and optionally including all join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}
     * @param includeAllJoinEntities if {@code true}, all join entities will be included
     * @return an Optional containing the retrieved entity with the specified join entities loaded, or an empty Optional if no entity is found
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default Optional<T> get(final long id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicatedResultException, SQLException {
        return Optional.ofNullable(gett(id, selectPropNames, includeAllJoinEntities));
    }

    /**
     * Retrieves an entity by its ID and loads the specified join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return the retrieved entity with the specified join entities loaded
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default T gett(final long id, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, SQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id);

        if (result != null) {
            loadJoinEntities(result, joinEntitiesToLoad);
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID, optionally including all join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param includeAllJoinEntities if {@code true}, all join entities will be included
     * @return the retrieved entity with the specified join entities loaded
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default T gett(final long id, final boolean includeAllJoinEntities) throws DuplicatedResultException, SQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id);

        if (result != null && includeAllJoinEntities) {
            loadAllJoinEntities(result);
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID, selecting specified properties and loads the specified join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}
     * @param joinEntitiesToLoad the class of the join entities to load
     * @return the retrieved entity with the specified join entities loaded
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default T gett(final long id, final Collection<String> selectPropNames, final Class<?> joinEntitiesToLoad) throws DuplicatedResultException, SQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null) {
            loadJoinEntities(result, joinEntitiesToLoad);
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID, selecting specified properties and loads the specified join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}
     * @param joinEntitiesToLoad the classes of the join entities to load
     * @return the retrieved entity with the specified join entities loaded
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default T gett(final long id, final Collection<String> selectPropNames, final Collection<Class<?>> joinEntitiesToLoad)
            throws DuplicatedResultException, SQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null) {
            for (final Class<?> joinEntityClass : joinEntitiesToLoad) {
                loadJoinEntities(result, joinEntityClass);
            }
        }

        return result;
    }

    /**
     * Retrieves an entity by its ID, selecting specified properties and optionally including all join entities.
     *
     * @param id the ID of the entity to retrieve
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}
     * @param includeAllJoinEntities if {@code true}, all join entities will be included
     * @return the retrieved entity with the specified join entities loaded
     * @throws DuplicatedResultException if more than one record is found by the specified {@code id}
     * @throws SQLException if a database access error occurs
     */
    @Beta
    default T gett(final long id, final Collection<String> selectPropNames, final boolean includeAllJoinEntities)
            throws DuplicatedResultException, SQLException {
        final T result = DaoUtil.getCrudDao(this).gett(id, selectPropNames);

        if (result != null && includeAllJoinEntities) {
            loadAllJoinEntities(result);
        }

        return result;
    }
}
