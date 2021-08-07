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
package com.landawn.abacus.dao;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.dao.annotation.NonDBOperation;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.NamedQuery;
import com.landawn.abacus.util.ParsedSql;
import com.landawn.abacus.util.PreparedCallableQuery;
import com.landawn.abacus.util.PreparedQuery;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.Throwables;

/**
 * TODO
 *
 * @param <T>
 * @param <SB>
 * @param <TD>
 * @see com.landawn.abacus.condition.ConditionFactory
 * @see com.landawn.abacus.condition.ConditionFactory.CF
 */
@Beta
public interface NoUpdateDao<T, SB extends SQLBuilder, TD extends NoUpdateDao<T, SB, TD>> extends Dao<T, SB, TD> {
    /**
     *
     * @param query
     * @return
     * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
     * @throws SQLException
     */
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query) throws UnsupportedOperationException, SQLException {
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
     * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final boolean generateKeys) throws UnsupportedOperationException, SQLException {
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
    * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final int[] returnColumnIndexes) throws UnsupportedOperationException, SQLException {
        if (!(DaoUtil.isSelectQuery(query) || DaoUtil.isInsertQuery(query))) {
            throw new UnsupportedOperationException("Only select/insert query is supported in non-update Dao");
        }

        return JdbcUtil.prepareQuery(dataSource(), query, returnColumnIndexes);
    }

    /**
    *
    * @param query
    * @param returnColumnIndexes
    * @return
    * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final String[] returnColumnNames) throws UnsupportedOperationException, SQLException {
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
    * @throws UnsupportedOperationException
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
    *
    * @param namedQuery
    * @return
    * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
    * @throws SQLException
    */
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery) throws UnsupportedOperationException, SQLException {
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
    * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final boolean generateKeys) throws UnsupportedOperationException, SQLException {
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
    * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final int[] returnColumnIndexes) throws UnsupportedOperationException, SQLException {
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
    * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final String[] returnColumnNames) throws UnsupportedOperationException, SQLException {
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
    * @throws UnsupportedOperationException
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
    *
    * @param namedQuery the named query
    * @return
    * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
    * @throws SQLException
    */
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery) throws UnsupportedOperationException, SQLException {
        if (!(DaoUtil.isSelectQuery(namedQuery.sql()) || DaoUtil.isInsertQuery(namedQuery.sql()))) {
            throw new UnsupportedOperationException("Only select/insert query is supported in non-update Dao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery);
    }

    /**
    *
    * @param namedQuery the named query
    * @param generateKeys
    * @return
    * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final boolean generateKeys) throws UnsupportedOperationException, SQLException {
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
    * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final int[] returnColumnIndexes) throws UnsupportedOperationException, SQLException {
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
    * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select/insert} sql statement.
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final String[] returnColumnNames) throws UnsupportedOperationException, SQLException {
        if (!(DaoUtil.isSelectQuery(namedQuery.sql()) || DaoUtil.isInsertQuery(namedQuery.sql()))) {
            throw new UnsupportedOperationException("Only select/insert query is supported in non-update Dao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnNames);
    }

    /**
    *
    * @param namedQuery the named query
    * @param stmtCreator
    * @return
    * @throws UnsupportedOperationException
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
    *
    * @param query
    * @return
    * @throws UnsupportedOperationException
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedCallableQuery prepareCallableQuery(final String query) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
    *
    * @param query
    * @param stmtCreator
    * @return
    * @throws UnsupportedOperationException
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedCallableQuery prepareCallableQuery(final String query,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param propName
     * @param propValue
     * @param cond
     * @return
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Override
    @Deprecated
    default int update(final String propName, final Object propValue, final Condition cond) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param updateProps
     * @param cond
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final Condition cond) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
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
    default int update(final T entity, final Condition cond) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Update all the records found by specified {@code cond} with specified {@code propNamesToUpdate} from specified {@code entity}.
     *
     * @param entity
     * @param cond
     * @param propNamesToUpdate
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final T entity, final Collection<String> propNamesToUpdate, final Condition cond) throws UnsupportedOperationException, SQLException {
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
     *
     * @param cond
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int delete(final Condition cond) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }
}