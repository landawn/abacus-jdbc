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

import java.sql.SQLException;
import java.util.Collection;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.SQLBuilder;

/**
 * A strictly read-only Data Access Object interface that only allows SELECT queries.
 * This is the most restrictive DAO interface in the hierarchy, preventing all data
 * modification operations including inserts, updates, and deletes.
 * 
 * <p>This interface extends {@link NoUpdateDao} and further restricts it by also
 * disabling INSERT operations. Only SELECT queries are permitted. This makes it
 * ideal for:</p>
 * <ul>
 *   <li>Read-only database users or connections</li>
 *   <li>Public-facing APIs that should never modify data</li>
 *   <li>Reporting and analytics systems</li>
 *   <li>Enforcing strict read-only access at the application level</li>
 * </ul>
 * 
 * <p>All save, batch save, and insert operations will throw {@link UnsupportedOperationException}.
 * Additionally, any prepared queries that are not SELECT statements will also throw
 * {@link UnsupportedOperationException}.</p>
 * 
 * <p>This interface is marked as {@code @Beta}, indicating it may be subject to
 * incompatible changes, or even removal, in a future release.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * // Define a read-only DAO for viewing data
 * public interface CustomerViewDao extends ReadOnlyDao<Customer, SQLBuilder, CustomerViewDao> {
 *     @Select("SELECT * FROM customers WHERE status = ?")
 *     List<Customer> findByStatus(String status);
 *     
 *     @Select("SELECT COUNT(*) FROM customers")
 *     long countAll();
 * }
 * 
 * // Usage:
 * List<Customer> activeCustomers = dao.findByStatus("ACTIVE"); // Works
 * dao.prepareQuery("SELECT * FROM customers").list(); // Works
 * dao.save(new Customer()); // Throws UnsupportedOperationException
 * dao.prepareQuery("INSERT INTO customers..."); // Throws UnsupportedOperationException
 * }</pre>
 *
 * @param <T> the type of the entity
 * @param <SB> the type of SQLBuilder used for query construction
 * @param <TD> the type of the DAO implementation (self-referencing type parameter)
 * @see NoUpdateDao
 * @see com.landawn.abacus.query.condition.ConditionFactory
 * @see com.landawn.abacus.query.condition.ConditionFactory.CF
 */
@SuppressWarnings("RedundantThrows")
@Beta
public interface ReadOnlyDao<T, SB extends SQLBuilder, TD extends ReadOnlyDao<T, SB, TD>> extends NoUpdateDao<T, SB, TD> {

    /**
     * Prepares a query for execution. Only SELECT queries are supported.
     *
     * @param query the SQL query string to prepare
     * @return a {@link PreparedQuery} instance for the given query
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the query is not a SELECT statement
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
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param query the SQL query string
     * @param generateKeys true to retrieve auto-generated keys
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as only SELECT queries are supported
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final boolean generateKeys) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param query the SQL query string
     * @param returnColumnIndexes an array of column indexes for returned keys
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as only SELECT queries are supported
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final int[] returnColumnIndexes) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param query the SQL query string
     * @param returnColumnNames an array of column names for returned keys
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as only SELECT queries are supported
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final String[] returnColumnNames) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Prepares a named query for execution. Only SELECT queries are supported.
     * Named queries use named parameters (e.g., :paramName) instead of positional parameters (?).
     *
     * @param namedQuery the SQL query string with named parameters
     * @return a {@link NamedQuery} instance for the given query
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the query is not a SELECT statement
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
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedQuery the SQL query string with named parameters
     * @param generateKeys true to retrieve auto-generated keys
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as only SELECT queries are supported
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final boolean generateKeys) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedQuery the SQL query string with named parameters
     * @param returnColumnIndexes an array of column indexes for returned keys
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as only SELECT queries are supported
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final int[] returnColumnIndexes) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedQuery the SQL query string with named parameters
     * @param returnColumnNames an array of column names for returned keys
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as only SELECT queries are supported
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final String[] returnColumnNames) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Prepares a parsed named query for execution. Only SELECT queries are supported.
     *
     * @param namedQuery the parsed SQL query with named parameters
     * @return a {@link NamedQuery} instance for the given query
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the query is not a SELECT statement
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
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedQuery the parsed SQL query with named parameters
     * @param generateKeys true to retrieve auto-generated keys
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as only SELECT queries are supported
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final boolean generateKeys) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedQuery the parsed SQL query with named parameters
     * @param returnColumnIndexes an array of column indexes for returned keys
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as only SELECT queries are supported
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final int[] returnColumnIndexes) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedQuery the parsed SQL query with named parameters
     * @param returnColumnNames an array of column names for returned keys
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as only SELECT queries are supported
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final String[] returnColumnNames) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entityToSave the entity to save
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as saves are not supported
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default void save(final T entityToSave) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entityToSave the entity to save
     * @param propNamesToSave collection of property names to save
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as saves are not supported
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default void save(final T entityToSave, final Collection<String> propNamesToSave) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named SQL insert statement
     * @param entityToSave the entity to save
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as saves are not supported
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default void save(final String namedInsertSQL, final T entityToSave) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entitiesToSave collection of entities to save
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as saves are not supported
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entitiesToSave collection of entities to save
     * @param batchSize the batch size for batch execution
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as saves are not supported
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entitiesToSave collection of entities to save
     * @param propNamesToSave collection of property names to save
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as saves are not supported
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entitiesToSave collection of entities to save
     * @param propNamesToSave collection of property names to save
     * @param batchSize the batch size for batch execution
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as saves are not supported
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named SQL insert statement
     * @param entitiesToSave collection of entities to save
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as saves are not supported
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named SQL insert statement
     * @param entitiesToSave collection of entities to save
     * @param batchSize the batch size for batch execution
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as saves are not supported
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}