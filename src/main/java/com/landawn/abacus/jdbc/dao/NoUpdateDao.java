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
import com.landawn.abacus.jdbc.CallableQuery;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.Throwables;

/**
 * This interface extends the base Dao interface but disables update and delete operations while allowing read and insert operations
 * to ensure data integrity in scenarios where modifications to existing records should be prevented.
 *
 * <p>This interface is particularly useful for:</p>
 * <ul>
 *   <li>Audit trail systems where historical data must remain immutable</li>
 *   <li>Append-only data stores and event sourcing patterns</li>
 *   <li>Data warehousing scenarios where only new data insertion is allowed</li>
 *   <li>Implementing the Command Query Responsibility Segregation (CQRS) pattern</li>
 * </ul>
 *
 * <p>All methods that would typically perform UPDATE, DELETE, or CALL operations will throw
 * {@link UnsupportedOperationException} when invoked. Only SELECT queries for reading data
 * and INSERT queries for adding new records are permitted.</p>
 *
 * <p><b>Supported Operations:</b></p>
 * <ul>
 *   <li><b>Read Operations:</b> {@code list}, {@code findFirst}, {@code findOnlyOne}, {@code count},
 *       {@code exists}, {@code queryForSingleResult}, etc.</li>
 *   <li><b>Insert Operations:</b> {@code save}, {@code batchSave} (inherited from Dao)</li>
 *   <li><b>Query Preparation:</b> {@code prepareQuery} and {@code prepareNamedQuery} for SELECT and INSERT statements</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a no-update DAO for append-only audit logs
 * public interface AuditLogDao extends NoUpdateDao<AuditLog, SQLBuilder.PSC, AuditLogDao> {
 *     // Custom read methods
 * }
 *
 * AuditLogDao auditDao = JdbcUtil.createDao(AuditLogDao.class, dataSource);
 *
 * // Supported operations - all work fine:
 *
 * // Read operations
 * List<AuditLog> logs = auditDao.list(Filters.eq("userId", 123L));
 * Optional<AuditLog> firstLog = auditDao.findFirst(Filters.gt("timestamp", yesterday));
 * int count = auditDao.count(Filters.eq("action", "LOGIN"));
 * boolean exists = auditDao.exists(Filters.eq("id", 456L));
 *
 * // Insert operations
 * AuditLog newLog = new AuditLog("USER_LOGIN", userId, timestamp);
 * auditDao.save(newLog);
 *
 * List<AuditLog> newLogs = createAuditLogs();
 * auditDao.batchSave(newLogs);
 *
 * // Prepare SELECT queries
 * List<AuditLog> results = auditDao.prepareQuery("SELECT * FROM audit_log WHERE user_id = ?")
 *                                  .setLong(1, userId)
 *                                  .list(AuditLog.class);
 *
 * // Prepare INSERT queries
 * auditDao.prepareNamedQuery("INSERT INTO audit_log (action, user_id) VALUES (:action, :userId)")
 *         .setString("action", "LOGOUT")
 *         .setLong("userId", userId)
 *         .execute();
 *
 * // Unsupported operations - all throw UnsupportedOperationException:
 * auditDao.update("status", "MODIFIED", Filters.eq("id", 123L));   // Throws exception
 * auditDao.delete(Filters.eq("id", 123L));   // Throws exception
 * auditDao.upsert(log, Filters.eq("id", 123L));   // Throws exception
 * auditDao.prepareCallableQuery("{call update_proc()}");   // Throws exception
 * auditDao.prepareQuery("UPDATE audit_log SET...");   // Throws exception
 * auditDao.prepareQuery("DELETE FROM audit_log...");   // Throws exception
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <SB> the SQLBuilder type used to generate SQL scripts (must be one of SQLBuilder.PSC/PAC/PLC)
 * @param <TD> the self-referential type of the DAO for fluent API support
 * @see com.landawn.abacus.query.Filters
 * @see Dao
 */
@SuppressWarnings("RedundantThrows")
@Beta
public interface NoUpdateDao<T, SB extends SQLBuilder, TD extends NoUpdateDao<T, SB, TD>> extends Dao<T, SB, TD> {
    /**
     * Prepares a SQL query for execution. Only SELECT and INSERT queries are supported.
     * This method creates a {@link PreparedQuery} object that can be used to execute
     * the query multiple times with different parameters efficiently.
     * 
     * <p>The query string should be a valid SQL SELECT or INSERT statement. Any attempt
     * to prepare UPDATE, DELETE, or other modification queries will result in an
     * {@link UnsupportedOperationException}.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> adults = dao.prepareQuery("SELECT * FROM users WHERE age > ?")
     *         .setInt(1, 18)
     *         .list(User.class);
     * }</pre>
     *
     * @param query the SQL query string to prepare (must be SELECT or INSERT)
     * @return a PreparedQuery object for executing the query
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the specified query is not a SELECT or INSERT statement
     */
    @Beta
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(query) || DaoUtil.isInsertQuery(query))) {
            throw new UnsupportedOperationException("Only SELECT and INSERT queries are supported in NoUpdateDao");
        }

        return JdbcUtil.prepareQuery(dataSource(), query);
    }

    /**
     * Prepares a SQL query with support for auto-generated keys retrieval.
     * This method is primarily useful for INSERT statements where you need to retrieve
     * the auto-generated primary key or other generated column values after insertion.
     * 
     * <p>When {@code generateKeys} is {@code true}, the prepared statement will be configured
     * to return auto-generated keys which can be retrieved after executing an INSERT.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Row generated = dao.prepareQuery("INSERT INTO users (name, email) VALUES (?, ?)", true)
     *         .setString(1, "John Doe")
     *         .setString(2, "john@example.com")
     *         .insert()
     *         .orElse(null);
     *
     * if (generated != null) {
     *     long generatedId = generated.getLong(1);
     * }
     * }</pre>
     *
     * @param query the SQL query string to prepare (must be SELECT or INSERT)
     * @param generateKeys {@code true} to enable retrieval of auto-generated keys
     * @return a PreparedQuery object configured for key generation if applicable
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the specified query is not a SELECT or INSERT statement
     */
    @Beta
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final boolean generateKeys) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(query) || DaoUtil.isInsertQuery(query))) {
            throw new UnsupportedOperationException("Only SELECT and INSERT queries are supported in NoUpdateDao");
        }

        return JdbcUtil.prepareQuery(dataSource(), query, generateKeys);
    }

    /**
     * Prepares a SQL query with specific column indexes for auto-generated key retrieval.
     * This method allows precise control over which auto-generated columns should be
     * returned after an INSERT operation by specifying their column indexes.
     * 
     * <p>The column indexes are 1-based, following JDBC conventions. This is useful
     * when your table has multiple auto-generated columns and you only need specific ones.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Retrieve only the first and third auto-generated columns
     * Row generated = dao.prepareQuery("INSERT INTO orders (customer_id, total) VALUES (?, ?)", new int[] {1, 3})
     *         .setLong(1, customerId)
     *         .setBigDecimal(2, orderTotal)
     *         .insert()
     *         .orElse(null);
     *
     * if (generated != null) {
     *     long firstColumn = generated.getLong(1);
     *     BigDecimal thirdColumn = generated.getBigDecimal(2);
     * }
     * }</pre>
     *
     * @param query the SQL query string to prepare (must be SELECT or INSERT)
     * @param returnColumnIndexes an array of column indexes indicating the columns
     *                           that should be returned from the inserted row
     * @return a PreparedQuery object configured for specific column retrieval
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the specified query is not a SELECT or INSERT statement
     */
    @Beta
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final int[] returnColumnIndexes) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(query) || DaoUtil.isInsertQuery(query))) {
            throw new UnsupportedOperationException("Only SELECT and INSERT queries are supported in NoUpdateDao");
        }

        return JdbcUtil.prepareQuery(dataSource(), query, returnColumnIndexes);
    }

    /**
     * Prepares a SQL query with specific column names for auto-generated key retrieval.
     * This method provides the most readable way to specify which auto-generated columns
     * should be returned after an INSERT operation by using column names instead of indexes.
     * 
     * <p>This approach is preferred over column indexes as it's more maintainable and
     * resistant to schema changes that might alter column positions.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Row generated = dao.prepareQuery("INSERT INTO users (name, email) VALUES (?, ?)",
     *         new String[] {"id", "created_timestamp"})
     *         .setString(1, "Jane Doe")
     *         .setString(2, "jane@example.com")
     *         .insert()
     *         .orElse(null);
     *
     * if (generated != null) {
     *     long id = generated.getLong("id");
     *     Timestamp created = generated.getTimestamp("created_timestamp");
     * }
     * }</pre>
     *
     * @param query the SQL query string to prepare (must be SELECT or INSERT)
     * @param returnColumnNames an array of column names indicating the columns
     *                         that should be returned from the inserted row
     * @return a PreparedQuery object configured for named column retrieval
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the specified query is not a SELECT or INSERT statement
     */
    @Beta
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final String[] returnColumnNames) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(query) || DaoUtil.isInsertQuery(query))) {
            throw new UnsupportedOperationException("Only SELECT and INSERT queries are supported in NoUpdateDao");
        }

        return JdbcUtil.prepareQuery(dataSource(), query, returnColumnNames);
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param query the SQL query string
     * @param stmtCreator custom statement creator function
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as custom statement creation operations are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Prepares a SQL query optimized for large result sets. Only SELECT and INSERT queries are supported.
     *
     * @param query the SQL query string to prepare (must be SELECT or INSERT)
     * @return a PreparedQuery object configured for large result sets
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the specified query is not a SELECT or INSERT statement
     */
    @Beta
    @NonDBOperation
    @Override
    default PreparedQuery prepareQueryForLargeResult(final String query) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(query) || DaoUtil.isInsertQuery(query))) {
            throw new UnsupportedOperationException("Only SELECT and INSERT queries are supported in NoUpdateDao");
        }

        return JdbcUtil.prepareQueryForLargeResult(dataSource(), query);
    }

    /**
     * Prepares a named parameter SQL query for execution. Only SELECT and INSERT queries are supported.
     * Named queries use parameter placeholders like :paramName instead of ? placeholders,
     * making complex queries more readable and maintainable.
     * 
     * <p>Named parameters can appear multiple times in the query and will all be set
     * to the same value when the parameter is bound.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String namedQuery = "SELECT * FROM users WHERE age > :minAge AND status = :status";
     * try (NamedQuery query = dao.prepareNamedQuery(namedQuery)) {
     *     query.setInt("minAge", 21);
     *     query.setString("status", "ACTIVE");
     *     List<User> users = query.list(User.class);
     * }
     * }</pre>
     *
     * @param namedQuery the SQL query string with named parameters (must be SELECT or INSERT)
     * @return a NamedQuery object for executing the query with named parameters
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the specified query is not a SELECT or INSERT statement
     */
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(namedQuery) || DaoUtil.isInsertQuery(namedQuery))) {
            throw new UnsupportedOperationException("Only SELECT and INSERT queries are supported in NoUpdateDao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery);
    }

    /**
     * Prepares a named parameter SQL query with support for auto-generated keys retrieval.
     * Combines the benefits of named parameters with the ability to retrieve
     * auto-generated keys after INSERT operations.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String namedQuery = "INSERT INTO users (name, email, age) VALUES (:name, :email, :age)";
     * try (NamedQuery query = dao.prepareNamedQuery(namedQuery, true)) {
     *     query.setString("name", "Bob Smith");
     *     query.setString("email", "bob@example.com");
     *     query.setInt("age", 30);
     *     Optional<Long> generatedId = query.insert();
     * }
     * }</pre>
     *
     * @param namedQuery the SQL query string with named parameters (must be SELECT or INSERT)
     * @param generateKeys {@code true} to enable retrieval of auto-generated keys
     * @return a NamedQuery object configured for key generation if applicable
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the specified query is not a SELECT or INSERT statement
     */
    @Beta
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final boolean generateKeys) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(namedQuery) || DaoUtil.isInsertQuery(namedQuery))) {
            throw new UnsupportedOperationException("Only SELECT and INSERT queries are supported in NoUpdateDao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, generateKeys);
    }

    /**
     * Prepares a named parameter SQL query with specific column indexes for auto-generated key retrieval.
     * This method combines named parameters with precise control over which auto-generated
     * columns should be returned by their index positions.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String namedQuery = "INSERT INTO orders (customer_id, total) VALUES (:customerId, :total)";
     * try (NamedQuery query = dao.prepareNamedQuery(namedQuery, new int[] {1})) {
     *     query.setLong("customerId", customerId);
     *     query.setBigDecimal("total", orderTotal);
     *     query.execute();
     *     // Retrieve only the first auto-generated column
     * }
     * }</pre>
     *
     * @param namedQuery the SQL query string with named parameters (must be SELECT or INSERT)
     * @param returnColumnIndexes an array of column indexes for generated key retrieval
     * @return a NamedQuery object configured for specific column retrieval
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the specified query is not a SELECT or INSERT statement
     */
    @Beta
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final int[] returnColumnIndexes) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(namedQuery) || DaoUtil.isInsertQuery(namedQuery))) {
            throw new UnsupportedOperationException("Only SELECT and INSERT queries are supported in NoUpdateDao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnIndexes);
    }

    /**
     * Prepares a named parameter SQL query with specific column names for auto-generated key retrieval.
     * This method provides the most maintainable approach by combining named parameters
     * with column name-based generated key retrieval.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * String namedQuery = "INSERT INTO users (name, email) VALUES (:name, :email)";
     * try (NamedQuery query = dao.prepareNamedQuery(
     *         namedQuery, new String[] {"id", "created_at"})) {
     *     query.setString("name", "Alice Johnson");
     *     query.setString("email", "alice@example.com");
     *     // Insert and retrieve generated keys using custom extractor
     *     Optional<Long> id = query.insert(rs -> rs.getLong("id"));
     * }
     * }</pre>
     *
     * @param namedQuery the SQL query string with named parameters (must be SELECT or INSERT)
     * @param returnColumnNames an array of column names for generated key retrieval
     * @return a NamedQuery object configured for named column retrieval
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the specified query is not a SELECT or INSERT statement
     */
    @Beta
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final String[] returnColumnNames) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(namedQuery) || DaoUtil.isInsertQuery(namedQuery))) {
            throw new UnsupportedOperationException("Only SELECT and INSERT queries are supported in NoUpdateDao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnNames);
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedQuery the SQL query string with named parameters
     * @param stmtCreator custom statement creator function
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as custom statement creation operations are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Prepares a named parameter SQL query optimized for large result sets. Only SELECT and INSERT queries are supported.
     *
     * @param namedQuery the SQL query string with named parameters (must be SELECT or INSERT)
     * @return a NamedQuery object configured for large result sets
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the specified query is not a SELECT or INSERT statement
     */
    @Beta
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQueryForLargeResult(final String namedQuery) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(namedQuery) || DaoUtil.isInsertQuery(namedQuery))) {
            throw new UnsupportedOperationException("Only SELECT and INSERT queries are supported in NoUpdateDao");
        }

        return JdbcUtil.prepareNamedQueryForLargeResult(dataSource(), namedQuery);
    }

    /**
     * Prepares a parsed named query optimized for large result sets. Only SELECT and INSERT queries are supported.
     *
     * @param namedQuery the pre-parsed SQL query object (must represent SELECT or INSERT)
     * @return a NamedQuery object configured for large result sets
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the query is not a SELECT or INSERT statement
     */
    @Beta
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQueryForLargeResult(final ParsedSql namedQuery) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(namedQuery.sql()) || DaoUtil.isInsertQuery(namedQuery.sql()))) {
            throw new UnsupportedOperationException("Only SELECT and INSERT queries are supported in NoUpdateDao");
        }

        return JdbcUtil.prepareNamedQueryForLargeResult(dataSource(), namedQuery);
    }

    /**
     * Prepares a named query using a pre-parsed SQL object. Only SELECT and INSERT queries are supported.
     * This method is useful when you have already parsed a named query and want to avoid
     * the overhead of parsing it again.
     * 
     * <p>The ParsedSql object contains the original SQL with named parameters and
     * metadata about parameter positions and names.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ParsedSql parsedSql = ParsedSql.parse("SELECT * FROM users WHERE id = :id");
     * try (NamedQuery query = dao.prepareNamedQuery(parsedSql)) {
     *     query.setLong("id", userId);
     *     User user = query.findFirst(User.class).orElse(null);
     * }
     * }</pre>
     *
     * @param namedQuery the pre-parsed SQL query object (must represent SELECT or INSERT)
     * @return a NamedQuery object for executing the parsed query
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the query is not a SELECT or INSERT statement
     */
    @Beta
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(namedQuery.sql()) || DaoUtil.isInsertQuery(namedQuery.sql()))) {
            throw new UnsupportedOperationException("Only SELECT and INSERT queries are supported in NoUpdateDao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery);
    }

    /**
     * Prepares a parsed named query with support for auto-generated keys retrieval.
     * Combines the efficiency of pre-parsed SQL with the ability to retrieve
     * auto-generated keys after INSERT operations.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ParsedSql parsedSql = ParsedSql.parse(
     *     "INSERT INTO users (name, email) VALUES (:name, :email)");
     * try (NamedQuery query = dao.prepareNamedQuery(parsedSql, true)) {
     *     query.setString("name", "Carol White");
     *     query.setString("email", "carol@example.com");
     *     Optional<Long> generatedId = query.insert();
     * }
     * }</pre>
     *
     * @param namedQuery the pre-parsed SQL query object (must represent SELECT or INSERT)
     * @param generateKeys {@code true} to enable retrieval of auto-generated keys
     * @return a NamedQuery object configured for key generation if applicable
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the query is not a SELECT or INSERT statement
     */
    @Beta
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final boolean generateKeys) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(namedQuery.sql()) || DaoUtil.isInsertQuery(namedQuery.sql()))) {
            throw new UnsupportedOperationException("Only SELECT and INSERT queries are supported in NoUpdateDao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, generateKeys);
    }

    /**
     * Prepares a parsed named query with specific column indexes for auto-generated key retrieval.
     * This method combines pre-parsed SQL efficiency with precise control over
     * which auto-generated columns should be returned.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ParsedSql parsedSql = ParsedSql.parse(
     *     "INSERT INTO products (name, price) VALUES (:name, :price)");
     * try (NamedQuery query = dao.prepareNamedQuery(parsedSql, new int[] {1})) {
     *     query.setString("name", "Widget");
     *     query.setBigDecimal("price", new BigDecimal("19.99"));
     *     query.execute();
     *     // Retrieve only the first auto-generated column
     * }
     * }</pre>
     *
     * @param namedQuery the pre-parsed SQL query object (must represent SELECT or INSERT)
     * @param returnColumnIndexes an array of column indexes for generated key retrieval
     * @return a NamedQuery object configured for specific column retrieval
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the query is not a SELECT or INSERT statement
     */
    @Beta
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final int[] returnColumnIndexes) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(namedQuery.sql()) || DaoUtil.isInsertQuery(namedQuery.sql()))) {
            throw new UnsupportedOperationException("Only SELECT and INSERT queries are supported in NoUpdateDao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnIndexes);
    }

    /**
     * Prepares a parsed named query with specific column names for auto-generated key retrieval.
     * This method provides the most efficient and maintainable approach by combining
     * pre-parsed SQL with column name-based generated key retrieval.
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ParsedSql parsedSql = ParsedSql.parse(
     *     "INSERT INTO customers (name, email) VALUES (:name, :email)");
     * try (NamedQuery query = dao.prepareNamedQuery(
     *         parsedSql, new String[] {"customer_id", "registration_date"})) {
     *     query.setString("name", "David Brown");
     *     query.setString("email", "david@example.com");
     *     // Insert and retrieve generated keys using custom extractor
     *     Optional<Long> id = query.insert(rs -> rs.getLong("customer_id"));
     * }
     * }</pre>
     *
     * @param namedQuery the pre-parsed SQL query object (must represent SELECT or INSERT)
     * @param returnColumnNames an array of column names for generated key retrieval
     * @return a NamedQuery object configured for named column retrieval
     * @throws SQLException if a database access error occurs
     * @throws UnsupportedOperationException if the query is not a SELECT or INSERT statement
     */
    @Beta
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final String[] returnColumnNames) throws SQLException, UnsupportedOperationException {
        if (!(DaoUtil.isSelectQuery(namedQuery.sql()) || DaoUtil.isInsertQuery(namedQuery.sql()))) {
            throw new UnsupportedOperationException("Only SELECT and INSERT queries are supported in NoUpdateDao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnNames);
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param namedQuery the pre-parsed SQL query object
     * @param stmtCreator custom statement creator function
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as custom statement creation operations are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery,
            final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param query the stored procedure call string
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as callable query operations are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @NonDBOperation
    @Override
    default CallableQuery prepareCallableQuery(final String query) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param query the stored procedure call string
     * @param stmtCreator custom statement creator function
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as callable query operations are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @NonDBOperation
    @Override
    default CallableQuery prepareCallableQuery(final String query, final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param propName the name of the property to update
     * @param propValue the new value for the property
     * @param cond the condition to identify records to update
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as update operations are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Override
    @Deprecated
    default int update(final String propName, final Object propValue, final Condition cond) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param updateProps a map of property names to their new values
     * @param cond the condition to identify records to update
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as update operations are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final Condition cond) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity containing values to update
     * @param cond the condition to identify records to update
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as update operations are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int update(final T entity, final Condition cond) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity containing values to update
     * @param propNamesToUpdate collection of property names to update from the entity
     * @param cond the condition to identify records to update
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as update operations are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int update(final T entity, final Collection<String> propNamesToUpdate, final Condition cond) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity to be upserted
     * @param uniquePropNamesForQuery the list of property names to determine uniqueness
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as upsert operations are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default T upsert(final T entity, final List<String> uniquePropNamesForQuery) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity to be upserted
     * @param cond the condition to check if the record exists
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as upsert operations are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default T upsert(final T entity, final Condition cond) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in no-update DAO.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param cond the condition to identify records to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always thrown as delete operations are not supported
     * @deprecated This operation is not supported in no-update DAO
     */
    @Deprecated
    @Override
    default int delete(final Condition cond) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
