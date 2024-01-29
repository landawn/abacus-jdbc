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

import com.landawn.abacus.annotation.Beta;
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
public interface ReadOnlyCrudDao<T, ID, SB extends SQLBuilder, TD extends ReadOnlyCrudDao<T, ID, SB, TD>>
        extends ReadOnlyDao<T, SB, TD>, NoUpdateCrudDao<T, ID, SB, TD> {

    /**
     *
     * @param entityToInsert
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default ID insert(final T entityToInsert) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entityToInsert
     * @param propNamesToInsert
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default ID insert(final T entityToInsert, final Collection<String> propNamesToInsert) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param namedInsertSQL
     * @param entityToSave
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default ID insert(final String namedInsertSQL, final T entityToSave) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param batchSize
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param propNamesToInsert
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param propNamesToInsert
     * @param batchSize
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param namedInsertSQL
     * @param entities
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param namedInsertSQL
     * @param entities
     * @param batchSize
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities, final int batchSize)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
