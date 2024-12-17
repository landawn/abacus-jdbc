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
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.util.ParsedSql;
import com.landawn.abacus.util.SQLBuilder;

/**
 * Interface for a read-only Data Access Object (DAO).
 *
 * @param <T>
 * @param <SB>
 * @param <TD>
 * @see com.landawn.abacus.condition.ConditionFactory
 * @see com.landawn.abacus.condition.ConditionFactory.CF
 */
@SuppressWarnings("RedundantThrows")
@Beta
public interface ReadOnlyDao<T, SB extends SQLBuilder, TD extends ReadOnlyDao<T, SB, TD>> extends NoUpdateDao<T, SB, TD> {
    /**
     *
     * @param query
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select} sql statement.
     */
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query) throws SQLException, UnsupportedOperationException {
        if (!DaoUtil.isSelectQuery(query)) {
            throw new UnsupportedOperationException("Only select query is supported in read-only Dao");
        }

        return JdbcUtil.prepareQuery(dataSource(), query);
    }

    /**
     *
     * @param query
     * @param generateKeys
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final boolean generateKeys) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param query
     * @param returnColumnIndexes
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final int[] returnColumnIndexes) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     *
     * @param query
     * @param returnColumnNames
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final String[] returnColumnNames) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param namedQuery
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException if the specified {@code namedQuery} is not a {@code select} sql statement.
     */
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery) throws SQLException, UnsupportedOperationException {
        if (!DaoUtil.isSelectQuery(namedQuery)) {
            throw new UnsupportedOperationException("Only select query is supported in read-only Dao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery);
    }

    /**
     *
     * @param namedQuery
     * @param generateKeys
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final boolean generateKeys) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param namedQuery
     * @param returnColumnIndexes
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final int[] returnColumnIndexes) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param namedQuery
     * @param returnColumnNames
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final String[] returnColumnNames) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param namedQuery
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException if the specified {@code namedQuery} is not a {@code select} sql statement.
     */
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery) throws SQLException, UnsupportedOperationException {
        if (!DaoUtil.isSelectQuery(namedQuery.sql())) {
            throw new UnsupportedOperationException("Only select query is supported in read-only Dao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery);
    }

    /**
     *
     * @param namedQuery
     * @param generateKeys
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final boolean generateKeys) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param namedQuery
     * @param returnColumnIndexes
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final int[] returnColumnIndexes) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param namedQuery
     * @param returnColumnNames
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final String[] returnColumnNames) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     *
     * @param entityToSave
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void save(final T entityToSave) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     *
     * @param entityToSave
     * @param propNamesToSave
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void save(final T entityToSave, final Collection<String> propNamesToSave) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     *
     * @param namedInsertSQL
     * @param entityToSave
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void save(final String namedInsertSQL, final T entityToSave) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave
     * @param batchSize
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave
     * @param propNamesToSave
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave
     * @param propNamesToSave
     * @param batchSize
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param namedInsertSQL
     * @param entitiesToSave
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param namedInsertSQL
     * @param entitiesToSave
     * @param batchSize
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
