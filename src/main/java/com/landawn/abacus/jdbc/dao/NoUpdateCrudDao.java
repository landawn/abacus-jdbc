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
import java.util.List;
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.util.SQLBuilder;

/**
 * TODO
 *
 * @param <T>
 * @param <ID>
 * @param <SB>
 * @param <TD>
 * @see com.landawn.abacus.condition.ConditionFactory
 * @see com.landawn.abacus.condition.ConditionFactory.CF
 */
@Beta
public interface NoUpdateCrudDao<T, ID, SB extends SQLBuilder, TD extends NoUpdateCrudDao<T, ID, SB, TD>>
        extends NoUpdateDao<T, SB, TD>, CrudDao<T, ID, SB, TD> {

    /**
     *
     * @param entityToUpdate
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final T entityToUpdate) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entityToUpdate
     * @param propNamesToUpdate
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final T entityToUpdate, final Collection<String> propNamesToUpdate) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param propName
     * @param propValue
     * @param id
     * @return
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Override
    @Deprecated
    default int update(final String propName, final Object propValue, final ID id) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param updateProps
     * @param id
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final ID id) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param batchSize
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param propNamesToUpdate
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate)
            throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param propNamesToUpdate
     * @param batchSize
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize)
            throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
     *
     * @param entity
     * @param cond to verify if the record exists or not.
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default T upsert(final T entity, final Condition cond) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
     *
     * @param entity
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default T upsert(final T entity) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param batchSize
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entity
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int delete(final T entity) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Delete by id.
     *
     * @param id
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteById(final ID id) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    //    /**
    //     *
    //     * @param entity
    //     * @param onDeleteAction
    //     * @return
    //     * @throws UnsupportedOperationException
    //     * @throws SQLException
    //     * @deprecated unsupported Operation
    //     */
    //    @Deprecated
    //    @Override
    //    default int delete(final T entity, final OnDeleteAction onDeleteAction) throws UnsupportedOperationException, SQLException {
    //        throw new UnsupportedOperationException();
    //    }

    /**
     *
     * @param entities
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int batchDelete(final Collection<? extends T> entities) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param batchSize
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int batchDelete(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    //    /**
    //     *
    //     * @param entities
    //     * @param onDeleteAction
    //     * @return
    //     * @throws UnsupportedOperationException
    //     * @throws SQLException
    //     * @deprecated unsupported Operation
    //     */
    //    @Deprecated
    //    @Override
    //    default int batchDelete(final Collection<? extends T> entities, final OnDeleteAction onDeleteAction)
    //            throws UnsupportedOperationException, SQLException {
    //        throw new UnsupportedOperationException();
    //    }
    //
    //    /**
    //     *
    //     * @param entities
    //     * @param onDeleteAction
    //     * @param batchSize
    //     * @return
    //     * @throws UnsupportedOperationException
    //     * @throws SQLException
    //     * @deprecated unsupported Operation
    //     */
    //    @Deprecated
    //    @Override
    //    default int batchDelete(final Collection<? extends T> entities, final OnDeleteAction onDeleteAction, final int batchSize)
    //            throws UnsupportedOperationException, SQLException {
    //        throw new UnsupportedOperationException();
    //    }

    /**
     *
     * @param ids
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int batchDeleteByIds(final Collection<? extends ID> ids) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param ids
     * @param batchSize
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }
}