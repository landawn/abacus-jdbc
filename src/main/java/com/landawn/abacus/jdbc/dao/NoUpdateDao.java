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

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.jdbc.CallableQuery;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.util.ParsedSql;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.Throwables;

/**
 * Interface for a Data Access Object (DAO) that does not support update operations.
 *
 * @param <T> the type of the entity
 * @param <SB> the type of the SQL builder
 * @param <TD> the type of the DAO
 * @see com.landawn.abacus.condition.ConditionFactory
 * @see com.landawn.abacus.condition.ConditionFactory.CF
 */
@SuppressWarnings("RedundantThrows")
@Beta
public interface NoUpdateDao<T, SB extends SQLBuilder, TD extends NoUpdateDao<T, SB, TD>> extends Dao<T, SB, TD> {
    /**
     *
     * @param query
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
     */
    @Beta
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(query) || DaoUtil.isInsertQuery(query))) {
            throw new UnsupportedOperationException("Only select/insert query is supported in non-update Dao");
        }

        return JdbcUtil.prepareQuery(dataSource(), query);
    }

    /**
     *
     * @param query
     * @param generateKeys
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
     */
    @Beta
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final boolean generateKeys) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(query) || DaoUtil.isInsertQuery(query))) {
            throw new UnsupportedOperationException("Only select/insert query is supported in non-update Dao");
        }

        return JdbcUtil.prepareQuery(dataSource(), query, generateKeys);
    }

    /**
     *
     * @param query
     * @param returnColumnIndexes
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
     */
    @Beta
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final int[] returnColumnIndexes) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(query) || DaoUtil.isInsertQuery(query))) {
            throw new UnsupportedOperationException("Only select/insert query is supported in non-update Dao");
        }

        return JdbcUtil.prepareQuery(dataSource(), query, returnColumnIndexes);
    }

    /**
     *
     *
     * @param query
     * @param returnColumnNames
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
     */
    @Beta
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final String[] returnColumnNames) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(query) || DaoUtil.isInsertQuery(query))) {
            throw new UnsupportedOperationException("Only select/insert query is supported in non-update Dao");
        }

        return JdbcUtil.prepareQuery(dataSource(), query, returnColumnNames);
    }

    /**
     *
     * @param query
     * @param stmtCreator
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param namedQuery
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
     */
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(namedQuery) || DaoUtil.isInsertQuery(namedQuery))) {
            throw new UnsupportedOperationException("Only select/insert query is supported in non-update Dao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery);
    }

    /**
     *
     * @param namedQuery
     * @param generateKeys
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
     */
    @Beta
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final boolean generateKeys) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(namedQuery) || DaoUtil.isInsertQuery(namedQuery))) {
            throw new UnsupportedOperationException("Only select/insert query is supported in non-update Dao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, generateKeys);
    }

    /**
     *
     * @param namedQuery
     * @param returnColumnIndexes
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
     */
    @Beta
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final int[] returnColumnIndexes) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(namedQuery) || DaoUtil.isInsertQuery(namedQuery))) {
            throw new UnsupportedOperationException("Only select/insert query is supported in non-update Dao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnIndexes);
    }

    /**
     *
     * @param namedQuery
     * @param returnColumnNames
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
     */
    @Beta
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final String[] returnColumnNames) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(namedQuery) || DaoUtil.isInsertQuery(namedQuery))) {
            throw new UnsupportedOperationException("Only select/insert query is supported in non-update Dao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnNames);
    }

    /**
     *
     * @param namedQuery
     * @param stmtCreator
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param namedQuery
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
     */
    @Beta
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(namedQuery.sql()) || DaoUtil.isInsertQuery(namedQuery.sql()))) {
            throw new UnsupportedOperationException("Only select/insert query is supported in non-update Dao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery);
    }

    /**
     *
     * @param namedQuery
     * @param generateKeys
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
     */
    @Beta
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final boolean generateKeys) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(namedQuery.sql()) || DaoUtil.isInsertQuery(namedQuery.sql()))) {
            throw new UnsupportedOperationException("Only select/insert query is supported in non-update Dao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, generateKeys);
    }

    /**
     *
     * @param namedQuery
     * @param returnColumnIndexes
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
     */
    @Beta
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final int[] returnColumnIndexes) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(namedQuery.sql()) || DaoUtil.isInsertQuery(namedQuery.sql()))) {
            throw new UnsupportedOperationException("Only select/insert query is supported in non-update Dao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnIndexes);
    }

    /**
     *
     * @param namedQuery
     * @param returnColumnNames
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
     */
    @Beta
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final String[] returnColumnNames) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(namedQuery.sql()) || DaoUtil.isInsertQuery(namedQuery.sql()))) {
            throw new UnsupportedOperationException("Only select/insert query is supported in non-update Dao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnNames);
    }

    /**
     *
     * @param namedQuery
     * @param stmtCreator
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param query
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @NonDBOperation
    @Override
    default CallableQuery prepareCallableQuery(final String query) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param query
     * @param stmtCreator
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @NonDBOperation
    @Override
    default CallableQuery prepareCallableQuery(final String query, final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     *
     * @param propName
     * @param propValue
     * @param cond
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Override
    @Deprecated
    default int update(final String propName, final Object propValue, final Condition cond) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param updateProps
     * @param cond
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final Condition cond) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entity
     * @param cond to verify if the record exists or not.
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final T entity, final Condition cond) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Update all the records found by specified {@code cond} with specified {@code propNamesToUpdate} from specified {@code entity}.
     *
     * @param entity
     * @param propNamesToUpdate
     * @param cond
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final T entity, final Collection<String> propNamesToUpdate, final Condition cond) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Inserts the specified entity if it does not exist, otherwise updates the existing entity.
     *
     * @param entity the entity to be upserted
     * @param uniquePropNamesForQuery the list of property names to be used for querying the uniqueness of the entity
     * @return the upserted entity
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the operation is not supported
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default T upsert(final T entity, final List<String> uniquePropNamesForQuery) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
     *
     * @param entity
     * @param cond to verify if the record exists or not.
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default T upsert(final T entity, final Condition cond) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param cond
     * @return
     * @throws SQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int delete(final Condition cond) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
