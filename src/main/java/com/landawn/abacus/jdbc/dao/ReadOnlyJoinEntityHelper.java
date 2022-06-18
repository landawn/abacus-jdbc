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
import java.util.concurrent.Executor;

import com.landawn.abacus.util.SQLBuilder;

public interface ReadOnlyJoinEntityHelper<T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> extends JoinEntityHelper<T, SB, TD> {

    /**
     *
     * @param entity
     * @param joinEntityClass
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param joinEntityClass
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entity
     * @param joinEntityPropName
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final String joinEntityPropName) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entity
     * @param joinEntityPropNames
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entity
     * @param joinEntityPropNames
     * @param inParallel
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entity
     * @param joinEntityPropNames
     * @param executor
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @param inParallel
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @param executor
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entity
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(T entity) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entity
     * @param inParallel
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final boolean inParallel) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entity
     * @param executor
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final Executor executor) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param inParallel
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param executor
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}