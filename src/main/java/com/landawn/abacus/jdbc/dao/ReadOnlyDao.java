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
 * <p><b>Supported Read Operations:</b></p>
 * <ul>
 *   <li>{@code list(Condition)} - Query multiple records matching a condition</li>
 *   <li>{@code findFirst(Condition)} - Find the first record matching a condition</li>
 *   <li>{@code findOnlyOne(Condition)} - Find exactly one record (throws exception if multiple found)</li>
 *   <li>{@code count(Condition)} - Count records matching a condition</li>
 *   <li>{@code exists(Condition)} - Check if any records match a condition</li>
 *   <li>{@code queryForSingleResult(...)} - Query for single column values</li>
 *   <li>{@code prepareQuery(String)} - Prepare SELECT queries for execution</li>
 *   <li>{@code prepareNamedQuery(String)} - Prepare named SELECT queries for execution</li>
 * </ul>
 *
 * <p>All save, batch save, and insert operations will throw {@link UnsupportedOperationException}.
 * Additionally, any prepared queries that are not SELECT statements will also throw
 * {@link UnsupportedOperationException}.</p>
 *
 * <p>This interface is marked as {@code @Beta}, indicating it may be subject to
 * incompatible changes, or even removal, in a future release.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a read-only DAO for viewing data
 * public interface CustomerViewDao extends ReadOnlyDao<Customer, SQLBuilder.PSC, CustomerViewDao> {
 *     // Custom query methods can be added
 * }
 *
 * CustomerViewDao dao = JdbcUtil.createDao(CustomerViewDao.class, dataSource);
 *
 * // Supported operations - all work fine:
 *
 * // List all active customers
 * List<Customer> activeCustomers = dao.list(Filters.eq("status", "ACTIVE"));
 *
 * // Find first customer by email
 * Optional<Customer> customer = dao.findFirst(Filters.eq("email", "john@example.com"));
 *
 * // Count customers by region
 * int count = dao.count(Filters.eq("region", "US"));
 *
 * // Check if a customer exists
 * boolean exists = dao.exists(Filters.eq("id", 123L));
 *
 * // Prepare custom SELECT queries
 * List<Customer> results = dao.prepareQuery("SELECT * FROM customers WHERE status = ?")
 *                             .setString(1, "ACTIVE")
 *                             .list(Customer.class);
 *
 * // Named queries also work
 * dao.prepareNamedQuery("SELECT * FROM customers WHERE region = :region")
 *    .setString("region", "US")
 *    .list(Customer.class);
 *
 * // Unsupported operations - all throw UnsupportedOperationException:
 * dao.save(new Customer());                        // Throws exception
 * dao.batchSave(customers);                        // Throws exception
 * dao.prepareQuery("INSERT INTO customers...");    // Throws exception
 * dao.prepareQuery("UPDATE customers...");         // Throws exception
 * dao.prepareQuery("DELETE FROM customers...");    // Throws exception
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <SB> the SQLBuilder type used for query construction
 * @param <TD> the DAO implementation type (self-referencing for method chaining)
 * @see NoUpdateDao
 * @see com.landawn.abacus.query.Filters
 */
@SuppressWarnings("RedundantThrows")
@Beta
public interface ReadOnlyDao<T, SB extends SQLBuilder, TD extends ReadOnlyDao<T, SB, TD>> extends NoUpdateDao<T, SB, TD> {

    /**
     * Prepares a SQL query for execution. Only SELECT queries are supported in read-only DAO.
     * This method creates a {@link PreparedQuery} object that can be used to execute
     * the query multiple times with different parameters efficiently.
     *
     * <p>The query string must be a valid SQL SELECT statement. Any attempt to prepare
     * INSERT, UPDATE, DELETE, or other modification queries will result in an
     * {@link UnsupportedOperationException}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Prepare and execute a simple SELECT query
     * List<User> adults = dao.prepareQuery("SELECT * FROM users WHERE age > ?")
     *         .setInt(1, 18)
     *         .list(User.class);
     *
     * // Query with multiple parameters
     * Optional<User> user = dao.prepareQuery("SELECT * FROM users WHERE email = ? AND status = ?")
     *         .setString(1, "john@example.com")
     *         .setString(2, "ACTIVE")
     *         .findFirst(User.class);
     * }</pre>
     *
     * @param query the SQL query string to prepare (must be a SELECT statement)
     * @return a PreparedQuery object for executing the query
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the specified query is not a SELECT statement
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
     * This operation is not supported in read-only DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param query the SQL query string
     * @param generateKeys {@code true} to retrieve auto-generated keys
     * @return never returns normally 
     * @throws UnsupportedOperationException always thrown as key generation operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final boolean generateKeys) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param query the SQL query string
     * @param returnColumnIndexes an array of column indexes for returned keys
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as key generation operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final int[] returnColumnIndexes) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param query the SQL query string
     * @param returnColumnNames an array of column names for returned keys
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as key generation operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final String[] returnColumnNames) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Prepares a named parameter SQL query for execution. Only SELECT queries are supported in read-only DAO.
     * Named queries use parameter placeholders like :paramName instead of ? placeholders,
     * making complex queries more readable and maintainable.
     *
     * <p>Named parameters can appear multiple times in the query and will all be set
     * to the same value when the parameter is bound.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Prepare and execute a named query
     * String namedQuery = "SELECT * FROM users WHERE age > :minAge AND status = :status";
     * try (NamedQuery query = dao.prepareNamedQuery(namedQuery)) {
     *     query.setInt("minAge", 21);
     *     query.setString("status", "ACTIVE");
     *     List<User> users = query.list(User.class);
     * }
     *
     * // Named parameter used multiple times
     * String query2 = "SELECT * FROM orders WHERE customer_id = :id OR seller_id = :id";
     * List<Order> orders = dao.prepareNamedQuery(query2)
     *         .setLong("id", userId)
     *         .list(Order.class);
     * }</pre>
     *
     * @param namedQuery the SQL query string with named parameters (must be a SELECT statement)
     * @return a NamedQuery object for executing the query with named parameters
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the specified query is not a SELECT statement
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
     * This operation is not supported in read-only DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedQuery the SQL query string with named parameters
     * @param generateKeys {@code true} to retrieve auto-generated keys
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as key generation operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final boolean generateKeys) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedQuery the SQL query string with named parameters
     * @param returnColumnIndexes an array of column indexes for returned keys
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as key generation operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final int[] returnColumnIndexes) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedQuery the SQL query string with named parameters
     * @param returnColumnNames an array of column names for returned keys
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as key generation operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final String[] returnColumnNames) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Prepares a named query using a pre-parsed SQL object. Only SELECT queries are supported in read-only DAO.
     * This method is useful when you have already parsed a named query and want to avoid
     * the overhead of parsing it again.
     *
     * <p>The ParsedSql object contains the original SQL with named parameters and
     * metadata about parameter positions and names.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Parse query once and reuse
     * ParsedSql parsedSql = NamedQuery.parse("SELECT * FROM users WHERE id = :id");
     *
     * // Use the parsed query multiple times
     * try (NamedQuery query = dao.prepareNamedQuery(parsedSql)) {
     *     query.setLong("id", userId);
     *     User user = query.findFirst(User.class).orElse(null);
     * }
     *
     * // Reuse the same parsed SQL with different parameter values
     * try (NamedQuery query = dao.prepareNamedQuery(parsedSql)) {
     *     query.setLong("id", anotherUserId);
     *     User anotherUser = query.findFirst(User.class).orElse(null);
     * }
     * }</pre>
     *
     * @param namedQuery the pre-parsed SQL query object (must represent a SELECT statement)
     * @return a NamedQuery object for executing the parsed query
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
     * This operation is not supported in read-only DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedQuery the parsed SQL query with named parameters
     * @param generateKeys {@code true} to retrieve auto-generated keys
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as key generation operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final boolean generateKeys) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedQuery the parsed SQL query with named parameters
     * @param returnColumnIndexes an array of column indexes for returned keys
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as key generation operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final int[] returnColumnIndexes) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedQuery the parsed SQL query with named parameters
     * @param returnColumnNames an array of column names for returned keys
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as key generation operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final String[] returnColumnNames) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entityToSave the entity to save
     * @throws UnsupportedOperationException always thrown as save operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default void save(final T entityToSave) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entityToSave the entity to save
     * @param propNamesToSave collection of property names to save
     * @throws UnsupportedOperationException always thrown as save operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default void save(final T entityToSave, final Collection<String> propNamesToSave) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named SQL insert statement
     * @param entityToSave the entity to save
     * @throws UnsupportedOperationException always thrown as save operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default void save(final String namedInsertSQL, final T entityToSave) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entitiesToSave collection of entities to save
     * @throws UnsupportedOperationException always thrown as save operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entitiesToSave collection of entities to save
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @throws UnsupportedOperationException always thrown as save operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entitiesToSave collection of entities to save
     * @param propNamesToSave collection of property names to save
     * @throws UnsupportedOperationException always thrown as save operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entitiesToSave collection of entities to save
     * @param propNamesToSave collection of property names to save
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @throws UnsupportedOperationException always thrown as save operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named SQL insert statement
     * @param entitiesToSave collection of entities to save
     * @throws UnsupportedOperationException always thrown as save operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedInsertSQL the named SQL insert statement
     * @param entitiesToSave collection of entities to save
     * @param batchSize the number of entities to process in each batch. The operation will split
     *                     large collections into chunks of this size for optimal performance.
     * @throws UnsupportedOperationException always thrown as save operations are not supported
     * @deprecated This operation is not supported in read-only DAO
     */
    @Deprecated
    @Override
    default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
