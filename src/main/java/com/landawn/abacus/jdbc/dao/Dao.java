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

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.annotation.LazyEvaluation;
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.condition.ConditionFactory;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.jdbc.AbstractQuery;
import com.landawn.abacus.jdbc.CallableQuery;
import com.landawn.abacus.jdbc.IsolationLevel;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.Jdbc.Columns.ColumnOne;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.jdbc.SQLExecutor;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.AsyncExecutor;
import com.landawn.abacus.util.CheckedStream;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.DataSet;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.ParsedSql;
import com.landawn.abacus.util.QueryUtil;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.SQLMapper;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.u.OptionalBoolean;
import com.landawn.abacus.util.u.OptionalByte;
import com.landawn.abacus.util.u.OptionalChar;
import com.landawn.abacus.util.u.OptionalDouble;
import com.landawn.abacus.util.u.OptionalFloat;
import com.landawn.abacus.util.u.OptionalInt;
import com.landawn.abacus.util.u.OptionalLong;
import com.landawn.abacus.util.u.OptionalShort;

/**
 * Performance Tips:
 * <li>Avoid unnecessary/repeated database calls.</li>
 * <li>Only fetch the columns you need or update the columns you want.</li>
 * <li>Index is the key point in a lot of database performance issues.</li>
 *
 * <br />
 *
 * This interface is designed to share/manager SQL queries by Java APIs/methods with static parameter types and return type, while hiding the SQL scripts.
 * It's a gift from nature and created by thoughts.
 *
 * <br />
 * Note: Setting parameters by 'ParametersSetter' or Retrieving result/record by 'ResultExtractor/BiResultExtractor/RowMapper/BiRowMapper' is not enabled at present.
 *
 * <br />
 *
 * <li>The SQL operations/methods should be annotated with SQL scripts by {@code @Select/@Insert/@Update/@Delete/@NamedSelect/@NamedInsert/@NamedUpdate/@NamedDelete}.</li>
 *
 * <li>The Order of the parameters in the method should be consistent with parameter order in SQL scripts for parameterized SQL.
 * For named parameterized SQL, the parameters must be binded with names through {@code @Bind}, or {@code Map/Entity} with getter/setter methods.</li>
 *
 * <li>SQL parameters can be set through input method parameters(by multiple parameters or a {@code Collection}, or a {@code Map/Entity} for named sql), or by {@code JdbcUtil.ParametersSetter<PreparedQuery/PreparedCallabeQuery...>}.</li>
 *
 * <li>{@code ResultExtractor/BiResultExtractor/RowMapper/BiRowMapper} can be specified by the last parameter of the method.</li>
 *
 * <li>The return type of the method must be same as the return type of {@code ResultExtractor/BiResultExtractor} if it's specified by the last parameter of the method.</li>
 *
 * <li>The return type of update/delete operations only can int/Integer/long/Long/boolean/Boolean/void. If it's long/Long, {@code PreparedQuery#largeUpdate()} will be called,
 * otherwise, {@code PreparedQuery#update()} will be called.</li>
 *
 * <li>Remember declaring {@code throws SQLException} in the method.</li>
 * <br />
 * <li>Which underline {@code PreparedQuery/CallableQuery} method to call for SQL methods/operations annotated with {@code @Select/@NamedSelect}:
 * <ul>
 *   <li>If {@code ResultExtractor/BiResultExtractor} is specified by the last parameter of the method, {@code PreparedQuery#query(ResultExtractor/BiResultExtractor)} will be called.</li>
 *   <li>Or else if {@code RowMapper/BiRowMapper} is specified by the last parameter of the method:</li>
 *      <ul>
 *          <li>If the return type of the method is {@code List} and one of below conditions is matched, {@code PreparedQuery#list(RowMapper/BiRowMapper)} will be called:</li>
 *          <ul>
 *              <li>The return type of the method is raw {@code List} without parameterized type, and the method name doesn't start with {@code "get"/"findFirst"/"findOne"/"findOnlyOne"}.</li>
 *          </ul>
 *          <ul>
 *              <li>The last parameter type is raw {@code RowMapper/BiRowMapper} without parameterized type, and the method name doesn't start with {@code "get"/"findFirst"/"findOne"/"findOnlyOne"}.</li>
 *          </ul>
 *          <ul>
 *              <li>The return type of the method is generic {@code List} with parameterized type and The last parameter type is generic {@code RowMapper/BiRowMapper} with parameterized types, but They're not same.</li>
 *          </ul>
 *      </ul>
 *      <ul>
 *          <li>Or else if the return type of the method is {@code CheckedStream/Stream}, {@code PreparedQuery#stream(RowMapper/BiRowMapper)} will be called.</li>
 *      </ul>
 *      <ul>
 *          <li>Or else if the return type of the method is {@code Optional}, {@code PreparedQuery#findFirst(RowMapper/BiRowMapper)} will be called.</li>
 *      </ul>
 *      <ul>
 *          <li>Or else, {@code PreparedQuery#findFirst(RowMapper/BiRowMapper).orElse(N.defaultValueOf(returnType))} will be called.</li>
 *      </ul>
 *   <li>Or else:</li>
 *      <ul>
 *          <li>If the return type of the method is {@code DataSet}, {@code PreparedQuery#query()} will be called.</li>
 *      </ul>
 *      <ul>
 *          <li>Or else if the return type of the method is {@code CheckedStream/Stream}, {@code PreparedQuery#stream(Class)} will be called.</li>
 *      </ul>
 *      <ul>
 *          <li>Or else if the return type of the method is {@code Map} or {@code Entity} class with {@code getter/setter} methods, {@code PreparedQuery#findFirst(Class).orElseNull()} will be called.</li>
 *      </ul>
 *      <ul>
 *          <li>Or else if the return type of the method is {@code Optional}:</li>
 *          <ul>
 *              <li>If the value type of {@code Optional} is {@code Map}, or {@code Entity} class with {@code getter/setter} methods, or {@code List}, or {@code Object[]}, {@code PreparedQuery#findFirst(Class)} will be called.</li>
 *          </ul>
 *          <ul>
 *              <li>Or else, {@code PreparedQuery#queryForSingleNonNull(Class)} will be called.</li>
 *          </ul>
 *      </ul>
 *      <ul>
 *          <li>Or else if the return type of the method is {@code Nullable}:</li>
 *          <ul>
 *              <li>If the value type of {@code Nullable} is {@code Map}, or {@code Entity} class with {@code getter/setter} methods, or {@code List}, or {@code Object[]}, {@code PreparedQuery#findFirst(Class)} will be called.</li>
 *          </ul>
 *          <ul>
 *              <li>Or else, {@code PreparedQuery#queryForSingleResult(Class)} will be called.</li>
 *          </ul>
 *      </ul>
 *      <ul>
 *          <li>Or else if the return type of the method is {@code OptionalBoolean/Byte/.../Double}, {@code PreparedQuery#queryForBoolean/Byte/...Double()} will called.</li>
 *      </ul>
 *      <ul>
 *          <li>Or else if the return type of the method is {@code List}, and the method name doesn't start with {@code "get"/"findFirst"/"findOne"/"findOnlyOne"}, {@code PreparedQuery#list(Class)} will be called.</li>
 *      </ul>
 *      <ul>
 *          <li>Or else if the return type of the method is {@code boolean/Boolean}, and the method name starts with {@code "exist"/"exists"/"notExist"/"notExists"}, {@code PreparedQuery#exists()} or {@code PreparedQuery#notExists()} will be called.</li>
 *      </ul>
 *      <ul>
 *          <li>Or else, {@code PreparedQuery#queryForSingleResult(Class).orElse(N.defaultValueOf(returnType)} will be called.</li>
 *      </ul>
 * </ul>
 *
 * <br />
 * <br />
 *
 * Here is a simple {@code UserDao} sample.
 *
 * <pre>
 * <code>
 * public static interface UserDao extends JdbcUtil.CrudDao<User, Long, SQLBuilder.PSC> {
 *     &#064NamedInsert("INSERT INTO user (id, first_name, last_name, email) VALUES (:id, :firstName, :lastName, :email)")
 *     void insertWithId(User user) throws SQLException;
 *
 *     &#064NamedUpdate("UPDATE user SET first_name = :firstName, last_name = :lastName WHERE id = :id")
 *     int updateFirstAndLastName(@Bind("firstName") String newFirstName, @Bind("lastName") String newLastName, @Bind("id") long id) throws SQLException;
 *
 *     &#064NamedSelect("SELECT first_name, last_name FROM user WHERE id = :id")
 *     User getFirstAndLastNameBy(@Bind("id") long id) throws SQLException;
 *
 *     &#064NamedSelect("SELECT id, first_name, last_name, email FROM user")
 *     Stream<User> allUsers() throws SQLException;
 * }
 * </code>
 * </pre>
 *
 * Here is the generate way to work with transaction started by {@code SQLExecutor}.
 *
 * <pre>
 * <code>
 * static final UserDao userDao = Dao.newInstance(UserDao.class, dataSource);
 * ...
 *
 * final SQLTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.READ_COMMITTED);
 *
 * try {
 *      userDao.getById(id);
 *      userDao.update(...);
 *      // more...
 *
 *      tran.commit();
 * } finally {
 *      // The connection will be automatically closed after the transaction is committed or rolled back.
 *      tran.rollbackIfNotCommitted();
 * }
 * </code>
 * </pre>
 *
 * @param <T>
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @param <TD>
 * @see {@link com.landawn.abacus.condition.ConditionFactory}
 * @see {@link com.landawn.abacus.condition.ConditionFactory.CF}
 * @see JdbcUtil#prepareQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#prepareNamedQuery(javax.sql.DataSource, String)
 * @see JdbcUtil#beginTransaction(javax.sql.DataSource, IsolationLevel, boolean)
 * @see Dao
 * @See CrudDao
 * @see SQLExecutor.Mapper
 * @see com.landawn.abacus.annotation.AccessFieldByMethod
 * @see com.landawn.abacus.condition.ConditionFactory
 * @see com.landawn.abacus.condition.ConditionFactory.CF
 *
 * @see <a href="https://stackoverflow.com/questions/1820908/how-to-turn-off-the-eclipse-code-formatter-for-certain-sections-of-java-code">How to turn off the Eclipse code formatter for certain sections of Java code?</a>
 */
public interface Dao<T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> {

    /**
     *
     * @return
     */
    @NonDBOperation
    javax.sql.DataSource dataSource();

    // SQLExecutor sqlExecutor();

    /**
     *
     * @return
     */
    @NonDBOperation
    SQLMapper sqlMapper();

    /**
     *
     * @return
     * @deprecated for internal use only.
     */
    @Deprecated
    @NonDBOperation
    @Internal
    Class<T> targetEntityClass();

    /**
     *
     *
     * @return
     * @deprecated for internal use only.
     */
    @Deprecated
    @NonDBOperation
    @Internal
    Executor executor();

    /**
     *
     *
     * @return
     * @deprecated for internal use only.
     */
    @Deprecated
    @NonDBOperation
    @Internal
    AsyncExecutor asyncExecutor();

    //    @NonDBOperation
    //    @Beta
    //    void cacheSql(String key, String sql);
    //
    //    @NonDBOperation
    //    @Beta
    //    void cacheSqls(String key, Collection<String> sqls);
    //
    //    @NonDBOperation
    //    @Beta
    //    String getCachedSql(String key);
    //
    //    @NonDBOperation
    //    @Beta
    //    ImmutableList<String> getCachedSqls(String key);

    //    /**
    //     *
    //     * @param isolationLevel
    //     * @return
    //     * @throws UncheckedSQLException
    //     */
    //     @NonDBOperation
    //    default SQLTransaction beginTransaction(final IsolationLevel isolationLevel) throws UncheckedSQLException {
    //        return beginTransaction(isolationLevel, false);
    //    }
    //
    //    /**
    //     * The connection opened in the transaction will be automatically closed after the transaction is committed or rolled back.
    //     * DON'T close it again by calling the close method.
    //     * <br />
    //     * <br />
    //     * The transaction will be shared cross the instances of {@code SQLExecutor/Dao} by the methods called in the same thread with same {@code DataSource}.
    //     *
    //     * <br />
    //     * <br />
    //     *
    //     * The general programming way with SQLExecutor/Dao is to execute sql scripts(generated by SQLBuilder) with array/list/map/entity by calling (batch)insert/update/delete/query/... methods.
    //     * If Transaction is required, it can be started:
    //     *
    //     * <pre>
    //     * <code>
    //     *   final SQLTransaction tran = someDao.beginTransaction(IsolationLevel.READ_COMMITTED);
    //     *   try {
    //     *       // sqlExecutor.insert(...);
    //     *       // sqlExecutor.update(...);
    //     *       // sqlExecutor.query(...);
    //     *
    //     *       tran.commit();
    //     *   } finally {
    //     *       // The connection will be automatically closed after the transaction is committed or rolled back.
    //     *       tran.rollbackIfNotCommitted();
    //     *   }
    //     * </code>
    //     * </pre>
    //     *
    //     * @param isolationLevel
    //     * @param forUpdateOnly
    //     * @return
    //     * @throws UncheckedSQLException
    //     * @see {@link SQLExecutor#beginTransaction(IsolationLevel, boolean)}
    //     */
    //     @NonDBOperation
    //    default SQLTransaction beginTransaction(final IsolationLevel isolationLevel, final boolean forUpdateOnly) throws UncheckedSQLException {
    //        N.checkArgNotNull(isolationLevel, "isolationLevel");
    //
    //        final javax.sql.DataSource ds = dataSource();
    //        SQLTransaction tran = SQLTransaction.getTransaction(ds);
    //
    //        if (tran == null) {
    //            Connection conn = null;
    //            boolean noException = false;
    //
    //            try {
    //                conn = getConnection(ds);
    //                tran = new SQLTransaction(ds, conn, isolationLevel, true, true);
    //                tran.incrementAndGetRef(isolationLevel, forUpdateOnly);
    //
    //                noException = true;
    //            } catch (SQLException e) {
    //                throw new UncheckedSQLException(e);
    //            } finally {
    //                if (noException == false) {
    //                    JdbcUtil.releaseConnection(conn, ds);
    //                }
    //            }
    //
    //            logger.info("Create a new SQLTransaction(id={})", tran.id());
    //            SQLTransaction.putTransaction(ds, tran);
    //        } else {
    //            logger.info("Reusing the existing SQLTransaction(id={})", tran.id());
    //            tran.incrementAndGetRef(isolationLevel, forUpdateOnly);
    //        }
    //
    //        return tran;
    //    }

    /**
     *
     * @param query
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String query) throws SQLException {
        return JdbcUtil.prepareQuery(dataSource(), query);
    }

    /**
     *
     * @param query
     * @param generateKeys
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String query, final boolean generateKeys) throws SQLException {
        return JdbcUtil.prepareQuery(dataSource(), query, generateKeys);
    }

    /**
     *
     * @param query
     * @param returnColumnIndexes
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String query, final int[] returnColumnIndexes) throws SQLException {
        return JdbcUtil.prepareQuery(dataSource(), query, returnColumnIndexes);
    }

    /**
     *
     *
     * @param query
     * @param returnColumnNames
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String query, final String[] returnColumnNames) throws SQLException {
        return JdbcUtil.prepareQuery(dataSource(), query, returnColumnNames);
    }

    /**
     *
     * @param sql
     * @param stmtCreator
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String sql, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws SQLException {
        return JdbcUtil.prepareQuery(dataSource(), sql, stmtCreator);
    }

    /**
     *
     * @param query
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQueryForBigResult(final String query) throws SQLException {
        return JdbcUtil.prepareQueryForBigResult(dataSource(), query);
    }

    /**
     *
     * @param namedQuery
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedQuery) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery);
    }

    /**
     *
     * @param namedQuery
     * @param generateKeys
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedQuery, final boolean generateKeys) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, generateKeys);
    }

    /**
     *
     * @param namedQuery
     * @param returnColumnIndexes
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedQuery, final int[] returnColumnIndexes) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnIndexes);
    }

    /**
     *
     * @param namedQuery
     * @param returnColumnNames
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedQuery, final String[] returnColumnNames) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnNames);
    }

    /**
     *
     * @param namedQuery
     * @param stmtCreator
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedQuery, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, stmtCreator);
    }

    /**
     *
     * @param namedSql the named query
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedSql) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql);
    }

    /**
     *
     * @param namedSql the named query
     * @param generateKeys
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedSql, final boolean generateKeys) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, generateKeys);
    }

    /**
     *
     * @param namedQuery
     * @param returnColumnIndexes
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final int[] returnColumnIndexes) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnIndexes);
    }

    /**
     *
     * @param namedQuery
     * @param returnColumnNames
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final String[] returnColumnNames) throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery, returnColumnNames);
    }

    /**
     *
     * @param namedSql the named query
     * @param stmtCreator
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedSql, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws SQLException {
        return JdbcUtil.prepareNamedQuery(dataSource(), namedSql, stmtCreator);
    }

    /**
     *
     * @param namedQuery
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQueryForBigResult(final String namedQuery) throws SQLException {
        return JdbcUtil.prepareNamedQueryForBigResult(dataSource(), namedQuery);
    }

    /**
     *
     * @param query
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default CallableQuery prepareCallableQuery(final String query) throws SQLException {
        return JdbcUtil.prepareCallableQuery(dataSource(), query);
    }

    /**
     *
     * @param sql
     * @param stmtCreator
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default CallableQuery prepareCallableQuery(final String sql,
            final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator) throws SQLException {
        return JdbcUtil.prepareCallableQuery(dataSource(), sql, stmtCreator);
    }

    /**
     * Prepare a {@code select} query by specified {@code cond}.
     * <br />
     * {@code query} could be a {@code select/insert/update/delete} or other sql statement. If it's {@code select} by default if not specified.
     *
     * @param cond
     * @return
     * @throws SQLException
     * @see {@link #prepareQuery(Collection, Condition)}
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final Condition cond) throws SQLException {
        return prepareQuery(null, cond);
    }

    /**
     * Prepare a {@code select} query by specified {@code selectPropNames} and {@code cond}.
     * <br />
     *
     * {@code query} could be a {@code select/insert/update/delete} or other sql statement. If it's {@code select} by default if not specified.
     *
     * @param selectPropNames
     * @param cond
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final Collection<String> selectPropNames, final Condition cond) throws SQLException {
        return DaoUtil.getDaoPreparedQueryFunc(this)._1.apply(selectPropNames, cond);
    }

    /**
     * Prepare a big result {@code select} query by specified {@code cond}.
     * <br />
     *
     * {@code query} could be a {@code select/insert/update/delete} or other sql statement. If it's {@code select} by default if not specified.
     *
     * @param cond
     * @return
     * @throws SQLException
     * @see {@link #prepareQueryForBigResult(Collection, Condition)}
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQueryForBigResult(final Condition cond) throws SQLException {
        return prepareQueryForBigResult(null, cond);
    }

    /**
     * Prepare a big result {@code select} query by specified {@code selectPropNames} and {@code cond}.
     * <br />
     *
     * {@code query} could be a {@code select/insert/update/delete} or other sql statement. If it's {@code select} by default if not specified.
     *
     * @param selectPropNames
     * @param cond
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQueryForBigResult(final Collection<String> selectPropNames, final Condition cond) throws SQLException {
        return prepareQuery(selectPropNames, cond).configStmt(DaoUtil.stmtSetterForBigQueryResult);
    }

    /**
     * Prepare a {@code select} query by specified {@code cond}.
     * <br />
     * {@code query} could be a {@code select/insert/update/delete} or other sql statement. If it's {@code select} by default if not specified.
     *
     * @param cond
     * @return
     * @throws SQLException
     * @see {@link #prepareNamedQuery(Collection, Condition)}
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final Condition cond) throws SQLException {
        return prepareNamedQuery(null, cond);
    }

    /**
     * Prepare a {@code select} query by specified {@code selectPropNames} and {@code cond}.
     * <br />
     *
     * {@code query} could be a {@code select/insert/update/delete} or other sql statement. If it's {@code select} by default if not specified.
     *
     * @param selectPropNames
     * @param cond
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final Collection<String> selectPropNames, final Condition cond) throws SQLException {
        return DaoUtil.getDaoPreparedQueryFunc(this)._2.apply(selectPropNames, cond);
    }

    /**
     * Prepare a big result {@code select} query by specified {@code cond}.
     * <br />
     *
     * {@code query} could be a {@code select/insert/update/delete} or other sql statement. If it's {@code select} by default if not specified.
     *
     * @param cond
     * @return
     * @throws SQLException
     * @see {@link #prepareNamedQueryForBigResult(Collection, Condition)}
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQueryForBigResult(final Condition cond) throws SQLException {
        return prepareNamedQueryForBigResult(null, cond);
    }

    /**
     * Prepare a big result {@code select} query by specified {@code selectPropNames} and {@code cond}.
     * <br />
     *
     * {@code query} could be a {@code select/insert/update/delete} or other sql statement. If it's {@code select} by default if not specified.
     *
     * @param selectPropNames
     * @param cond
     * @return
     * @throws SQLException
     */
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQueryForBigResult(final Collection<String> selectPropNames, final Condition cond) throws SQLException {
        return prepareNamedQuery(selectPropNames, cond).configStmt(DaoUtil.stmtSetterForBigQueryResult);
    }

    /**
     *
     *
     * @param entityToSave
     * @throws SQLException
     */
    void save(final T entityToSave) throws SQLException;

    /**
     *
     *
     * @param entityToSave
     * @param propNamesToSave
     * @throws SQLException
     */
    void save(final T entityToSave, final Collection<String> propNamesToSave) throws SQLException;

    /**
     *
     *
     * @param namedInsertSQL
     * @param entityToSave
     * @throws SQLException
     */
    void save(final String namedInsertSQL, final T entityToSave) throws SQLException;

    /**
     * Insert the specified entities to database by batch.
     *
     * @param entitiesToSave
     * @throws SQLException
     * @see CrudDao#batchInsert(Collection)
     */
    default void batchSave(final Collection<? extends T> entitiesToSave) throws SQLException {
        batchSave(entitiesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Insert the specified entities to database by batch.
     *
     * @param entitiesToSave
     * @param batchSize
     * @throws SQLException
     * @see CrudDao#batchInsert(Collection)
     */
    void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws SQLException;

    /**
     * Insert the specified entities to database by batch.
     *
     * @param entitiesToSave
     * @param propNamesToSave
     * @throws SQLException
     * @see CrudDao#batchInsert(Collection)
     */
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave) throws SQLException {
        batchSave(entitiesToSave, propNamesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Insert the specified entities to database by batch.
     *
     * @param entitiesToSave
     * @param propNamesToSave
     * @param batchSize
     * @throws SQLException
     * @see CrudDao#batchInsert(Collection)
     */
    void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize) throws SQLException;

    /**
     * Insert the specified entities to database by batch.
     *
     * @param namedInsertSQL
     * @param entitiesToSave
     * @throws SQLException
     * @see CrudDao#batchInsert(Collection)
     */
    @Beta
    default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave) throws SQLException {
        batchSave(namedInsertSQL, entitiesToSave, JdbcUtil.DEFAULT_BATCH_SIZE);
    }

    /**
     * Insert the specified entities to database by batch.
     *
     * @param namedInsertSQL
     * @param entitiesToSave
     * @param batchSize
     * @throws SQLException
     * @see CrudDao#batchInsert(Collection)
     */
    @Beta
    void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize) throws SQLException;

    /**
     *
     * @param cond
     * @return true, if there is at least one record found.
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#exists()
     */
    boolean exists(final Condition cond) throws SQLException;

    /**
     *
     * @param cond
     * @return true, if there is no record found.
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#notExists()
     */
    @Beta
    default boolean notExists(final Condition cond) throws SQLException {
        return !exists(cond);
    }

    /**
     *
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    int count(final Condition cond) throws SQLException;

    // TODO dao.anyMatch/allMatch/noneMatch(...)
    //    /**
    //     *
    //     * @param selectPropNames
    //     * @param cond
    //     * @param rowFilter
    //     * @return
    //     * @throws SQLException
    //     */
    //    @Beta
    //    default boolean anyMatch(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowFilter rowFilter) throws SQLException {
    //        return prepareQuery(selectPropNames, cond).anyMatch(rowFilter);
    //    }
    //
    //    /**
    //     *
    //     * @param selectPropNames
    //     * @param cond
    //     * @param rowFilter
    //     * @return
    //     * @throws SQLException
    //     */
    //    @Beta
    //    default boolean anyMatch(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter) throws SQLException {
    //        return prepareQuery(selectPropNames, cond).anyMatch(rowFilter);
    //    }
    //
    //    /**
    //     *
    //     * @param selectPropNames
    //     * @param cond
    //     * @param rowFilter
    //     * @return
    //     * @throws SQLException
    //     */
    //    @Beta
    //    default boolean allMatch(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowFilter rowFilter) throws SQLException {
    //        return prepareQuery(selectPropNames, cond).allMatch(rowFilter);
    //    }
    //
    //    /**
    //     *
    //     * @param selectPropNames
    //     * @param cond
    //     * @param rowFilter
    //     * @return
    //     * @throws SQLException
    //     */
    //    @Beta
    //    default boolean allMatch(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter) throws SQLException {
    //        return prepareQuery(selectPropNames, cond).allMatch(rowFilter);
    //    }
    //
    //    /**
    //     *
    //     * @param selectPropNames
    //     * @param cond
    //     * @param rowFilter
    //     * @return
    //     * @throws SQLException
    //     */
    //    @Beta
    //    default boolean noneMatch(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowFilter rowFilter) throws SQLException {
    //        return prepareQuery(selectPropNames, cond).noneMatch(rowFilter);
    //    }
    //
    //    /**
    //     *
    //     * @param selectPropNames
    //     * @param cond
    //     * @param rowFilter
    //     * @return
    //     * @throws SQLException
    //     */
    //    @Beta
    //    default boolean noneMatch(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter) throws SQLException {
    //        return prepareQuery(selectPropNames, cond).noneMatch(rowFilter);
    //    }

    /**
     *
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    Optional<T> findFirst(final Condition cond) throws SQLException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> Optional<R> findFirst(final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException, IllegalArgumentException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> Optional<R> findFirst(final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper) throws SQLException, IllegalArgumentException;

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    Optional<T> findFirst(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper)
            throws SQLException, IllegalArgumentException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper)
            throws SQLException, IllegalArgumentException;

    /**
     *
     * @param cond
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    Optional<T> findOnlyOne(final Condition cond) throws DuplicatedResultException, SQLException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> Optional<R> findOnlyOne(final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper)
            throws DuplicatedResultException, SQLException, IllegalArgumentException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> Optional<R> findOnlyOne(final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper)
            throws DuplicatedResultException, SQLException, IllegalArgumentException;

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    Optional<T> findOnlyOne(final Collection<String> selectPropNames, final Condition cond) throws DuplicatedResultException, SQLException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     */
    <R> Optional<R> findOnlyOne(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper)
            throws DuplicatedResultException, SQLException, IllegalArgumentException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     * @throws IllegalArgumentException if {@code rowMapper} returns {@code null} for the found record.
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> Optional<R> findOnlyOne(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper)
            throws DuplicatedResultException, SQLException, IllegalArgumentException;

    /**
     * Returns an {@code OptionalBoolean} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalBoolean}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForBoolean()
     */
    OptionalBoolean queryForBoolean(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Returns an {@code OptionalChar} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalChar}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForChar()
     */
    OptionalChar queryForChar(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Returns an {@code OptionalByte} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalByte}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForByte()
     */
    OptionalByte queryForByte(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Returns an {@code OptionalShort} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalShort}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForShort()
     */
    OptionalShort queryForShort(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Returns an {@code OptionalInt} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalInt}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForInt()
     */
    OptionalInt queryForInt(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Returns an {@code OptionalLong} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalLong}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForLong()
     */
    OptionalLong queryForLong(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Returns an {@code OptionalFloat} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalFloat}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForFloat()
     */
    OptionalFloat queryForFloat(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Returns an {@code OptionalDouble} describing the value in the first row/column if it exists, otherwise return an empty {@code OptionalDouble}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForDouble()
     */
    OptionalDouble queryForDouble(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Returns a {@code Nullable<String>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForString()
     */
    Nullable<String> queryForString(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Returns a {@code Nullable<java.sql.Date>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForDate()
     */
    Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Returns a {@code Nullable<java.sql.Time>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForTime()
     */
    Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Returns a {@code Nullable<java.sql.Timestamp>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForTimestamp()
     */
    Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Returns a {@code Nullable<byte[]>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForBytes()
     */
    Nullable<byte[]> queryForBytes(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Returns a {@code Nullable<V>} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForSingleResult(Class)
     */
    <V> Nullable<V> queryForSingleResult(final Class<? extends V> targetValueClass, final String singleSelectPropName, final Condition cond)
            throws SQLException;

    /**
     * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
     *
     * @param <V> the value type
     * @param targetValueClass
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForSingleNonNull(Class)
     */
    <V> Optional<V> queryForSingleNonNull(final Class<? extends V> targetValueClass, final String singleSelectPropName, final Condition cond)
            throws SQLException;

    /**
     * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
     *
     * @param <V> the value type
     * @param singleSelectPropName
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForSingleNonNull(Class)
     */
    @Beta
    <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final Condition cond, final Jdbc.RowMapper<? extends V> rowMapper)
            throws SQLException;

    /**
     * Returns a {@code Nullable} describing the value in the first row/column if it exists, otherwise return an empty {@code Nullable}.
     * And throws {@code DuplicatedResultException} if more than one record found.
     *
     * @param <V> the value type
     * @param targetValueClass
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForUniqueResult(Class)
     */
    <V> Nullable<V> queryForUniqueResult(final Class<? extends V> targetValueClass, final String singleSelectPropName, final Condition cond)
            throws DuplicatedResultException, SQLException;

    /**
     * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
     *
     * @param <V> the value type
     * @param targetValueClass
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForUniqueNonNull(Class)
     */
    <V> Optional<V> queryForUniqueNonNull(final Class<? extends V> targetValueClass, final String singleSelectPropName, final Condition cond)
            throws DuplicatedResultException, SQLException;

    /**
     * Returns an {@code Optional} describing the value in the first row/column if it exists, otherwise return an empty {@code Optional}.
     *
     * @param <V> the value type
     * @param singleSelectPropName
     * @param cond
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractQuery#queryForUniqueNonNull(Class)
     */
    @Beta
    <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final Condition cond, final Jdbc.RowMapper<? extends V> rowMapper)
            throws DuplicatedResultException, SQLException;

    /**
     *
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    DataSet query(final Condition cond) throws SQLException;

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    DataSet query(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param resultExtractor Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> R query(final Condition cond, final Jdbc.ResultExtractor<? extends R> resultExtractor) throws SQLException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param resultExtractor Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> R query(final Collection<String> selectPropNames, final Condition cond, final Jdbc.ResultExtractor<? extends R> resultExtractor) throws SQLException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param resultExtractor Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return
     * @throws SQLException
     */
    <R> R query(final Condition cond, final Jdbc.BiResultExtractor<? extends R> resultExtractor) throws SQLException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param resultExtractor Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> R query(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiResultExtractor<? extends R> resultExtractor) throws SQLException;

    /**
     *
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    List<T> list(final Condition cond) throws SQLException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> List<R> list(final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> List<R> list(final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper) throws SQLException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> List<R> list(final Condition cond, final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException;

    /**
     *
     *
     * @param <R>
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> List<R> list(final Condition cond, final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends R> rowMapper) throws SQLException;

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    List<T> list(final Collection<String> selectPropNames, final Condition cond) throws SQLException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper) throws SQLException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException;

    /**
     *
     *
     * @param <R>
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter,
            final Jdbc.BiRowMapper<? extends R> rowMapper) throws SQLException;

    /**
     *
     *
     * @param <R>
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    default <R> List<R> list(final String singleSelectPropName, final Condition cond) throws SQLException {
        final PropInfo propInfo = ParserUtil.getBeanInfo(targetEntityClass()).getPropInfo(singleSelectPropName);
        final Jdbc.RowMapper<? extends R> rowMapper = propInfo == null ? ColumnOne.<R> getObject() : ColumnOne.get((Type<R>) propInfo.dbType);

        return list(singleSelectPropName, cond, rowMapper);
    }

    /**
     *
     *
     * @param <R>
     * @param singleSelectPropName
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    default <R> List<R> list(final String singleSelectPropName, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException {
        return list(N.asList(singleSelectPropName), cond, rowMapper);
    }

    /**
     *
     *
     * @param <R>
     * @param singleSelectPropName
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    default <R> List<R> list(final String singleSelectPropName, final Condition cond, final Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends R> rowMapper) throws SQLException {
        return list(N.asList(singleSelectPropName), cond, rowFilter, rowMapper);
    }

    // Will it cause confusion if it's called in transaction?
    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param cond
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    CheckedStream<T, SQLException> stream(final Condition cond);

    // Will it cause confusion if it's called in transaction?
    /**
     * lazy-execution, lazy-fetch.
     *
     * @param <R>
     * @param cond
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    <R> CheckedStream<R, SQLException> stream(final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper);

    // Will it cause confusion if it's called in transaction?
    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param <R>
     * @param cond
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    <R> CheckedStream<R, SQLException> stream(final Condition cond, final Jdbc.BiRowMapper<? extends R> rowMapper);

    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param <R>
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    <R> CheckedStream<R, SQLException> stream(final Condition cond, final Jdbc.RowFilter rowFilter, final Jdbc.RowMapper<? extends R> rowMapper);

    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param <R>
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    <R> CheckedStream<R, SQLException> stream(final Condition cond, final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowMapper<? extends R> rowMapper);

    // Will it cause confusion if it's called in transaction?
    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    CheckedStream<T, SQLException> stream(final Collection<String> selectPropNames, final Condition cond);

    // Will it cause confusion if it's called in transaction?
    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param <R>
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    <R> CheckedStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowMapper<? extends R> rowMapper);

    // Will it cause confusion if it's called in transaction?
    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param <R>
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    <R> CheckedStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond,
            final Jdbc.BiRowMapper<? extends R> rowMapper);

    // Will it cause confusion if it's called in transaction?
    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param <R>
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    <R> CheckedStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends R> rowMapper);

    // Will it cause confusion if it's called in transaction?
    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param <R>
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    <R> CheckedStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter,
            final Jdbc.BiRowMapper<? extends R> rowMapper);

    // Will it cause confusion if it's called in transaction?
    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param <R>
     * @param singleSelectPropName
     * @param cond
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    default <R> CheckedStream<R, SQLException> stream(final String singleSelectPropName, final Condition cond) {
        final PropInfo propInfo = ParserUtil.getBeanInfo(targetEntityClass()).getPropInfo(singleSelectPropName);
        final Jdbc.RowMapper<? extends R> rowMapper = propInfo == null ? ColumnOne.<R> getObject() : ColumnOne.get((Type<R>) propInfo.dbType);

        return stream(singleSelectPropName, cond, rowMapper);
    }

    // Will it cause confusion if it's called in transaction?
    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param <R>
     * @param singleSelectPropName
     * @param cond
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    default <R> CheckedStream<R, SQLException> stream(final String singleSelectPropName, final Condition cond,
            final Jdbc.RowMapper<? extends R> rowMapper) {
        return stream(N.asList(singleSelectPropName), cond, rowMapper);
    }

    // Will it cause confusion if it's called in transaction?
    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param <R>
     * @param singleSelectPropName
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    default <R> CheckedStream<R, SQLException> stream(final String singleSelectPropName, final Condition cond, final Jdbc.RowFilter rowFilter,
            final Jdbc.RowMapper<? extends R> rowMapper) {
        return stream(N.asList(singleSelectPropName), cond, rowFilter, rowMapper);
    }

    /**
     *
     *
     * @param cond
     * @param rowConsumer
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    void forEach(final Condition cond, final Jdbc.RowConsumer rowConsumer) throws SQLException;

    /**
     *
     *
     * @param cond
     * @param rowConsumer
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    void forEach(final Condition cond, final Jdbc.BiRowConsumer rowConsumer) throws SQLException;

    /**
     *
     *
     * @param cond
     * @param rowFilter
     * @param rowConsumer
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    void forEach(final Condition cond, final Jdbc.RowFilter rowFilter, final Jdbc.RowConsumer rowConsumer) throws SQLException;

    /**
     *
     *
     * @param cond
     * @param rowFilter
     * @param rowConsumer
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    void forEach(final Condition cond, final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowConsumer rowConsumer) throws SQLException;

    /**
     *
     *
     * @param selectPropNames
     * @param cond
     * @param rowConsumer
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowConsumer rowConsumer) throws SQLException;

    /**
     *
     *
     * @param selectPropNames
     * @param cond
     * @param rowConsumer
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowConsumer rowConsumer) throws SQLException;

    /**
     *
     *
     * @param selectPropNames
     * @param cond
     * @param rowFilter
     * @param rowConsumer
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.RowFilter rowFilter, final Jdbc.RowConsumer rowConsumer)
            throws SQLException;

    /**
     *
     *
     * @param selectPropNames
     * @param cond
     * @param rowFilter
     * @param rowConsumer
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    void forEach(final Collection<String> selectPropNames, final Condition cond, final Jdbc.BiRowFilter rowFilter, final Jdbc.BiRowConsumer rowConsumer)
            throws SQLException;

    /**
     *
     *
     * @param selectPropNames
     * @param cond
     * @param rowConsumer
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @Beta
    default void foreach(final Collection<String> selectPropNames, final Condition cond, final Consumer<DisposableObjArray> rowConsumer) throws SQLException { //NOSONAR
        forEach(selectPropNames, cond, Jdbc.RowConsumer.oneOff(targetEntityClass(), rowConsumer));
    }

    /**
     *
     *
     * @param cond
     * @param rowConsumer
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @Beta
    default void foreach(final Condition cond, final Consumer<DisposableObjArray> rowConsumer) throws SQLException { //NOSONAR
        forEach(cond, Jdbc.RowConsumer.oneOff(targetEntityClass(), rowConsumer));
    }

    /**
     *
     * @param propName
     * @param propValue
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    default int update(final String propName, final Object propValue, final Condition cond) throws SQLException {
        final Map<String, Object> updateProps = new HashMap<>();
        updateProps.put(propName, propValue);

        return update(updateProps, cond);
    }

    /**
     * Update all the records found by specified {@code cond} with all the properties from specified {@code updateProps}.
     *
     * @param updateProps
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    int update(final Map<String, Object> updateProps, final Condition cond) throws SQLException;

    /**
     * Update all the records found by specified {@code cond} with the properties from specified {@code entity}.
     *
     * @param entity
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    default int update(final T entity, final Condition cond) throws SQLException {
        final Collection<String> propNamesToUpdate = QueryUtil.getUpdatePropNames(targetEntityClass(), null);

        return update(entity, propNamesToUpdate, cond);
    }

    /**
     * Update all the records found by specified {@code cond} with specified {@code propNamesToUpdate} from specified {@code entity}.
     *
     * @param entity
     * @param propNamesToUpdate
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    int update(final T entity, final Collection<String> propNamesToUpdate, final Condition cond) throws SQLException;

    /**
     * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
     *
     * @param entity
     * @param cond to verify if the record exists or not.
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    default T upsert(final T entity, final Condition cond) throws SQLException {
        N.checkArgNotNull(entity, "entity");
        N.checkArgNotNull(cond, "cond");

        final T dbEntity = findOnlyOne(cond).orElseNull();

        if (dbEntity == null) {
            save(entity);
            return entity;
        } else {
            N.merge(entity, dbEntity);
            update(dbEntity, cond);
            return dbEntity;
        }
    }

    /**
     *
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    int delete(final Condition cond) throws SQLException;

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     * @param <R>
     * @param sqlAction
     * @return
     */
    @Beta
    @NonDBOperation
    default <R> ContinuableFuture<R> asyncCall(final Throwables.Function<TD, R, SQLException> sqlAction) {
        return asyncCall(sqlAction, executor());
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     *
     * @param <R>
     * @param sqlAction
     * @param executor
     * @return
     */
    @Beta
    @NonDBOperation
    default <R> ContinuableFuture<R> asyncCall(final Throwables.Function<TD, R, SQLException> sqlAction, final Executor executor) {
        N.checkArgNotNull(sqlAction, "func");
        N.checkArgNotNull(executor, "executor");

        final TD tdao = (TD) this;

        return ContinuableFuture.call(() -> sqlAction.apply(tdao), executor);
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     *
     * @param sqlAction
     * @return
     */
    @Beta
    @NonDBOperation
    default ContinuableFuture<Void> asyncRun(final Throwables.Consumer<TD, SQLException> sqlAction) {
        return asyncRun(sqlAction, executor());
    }

    /**
     * Any transaction started in current thread won't be automatically applied to specified {@code sqlAction} which will be executed in another thread.
     *
     *
     * @param sqlAction
     * @param executor
     * @return
     */
    @Beta
    @NonDBOperation
    default ContinuableFuture<Void> asyncRun(final Throwables.Consumer<TD, SQLException> sqlAction, final Executor executor) {
        N.checkArgNotNull(sqlAction, "action");
        N.checkArgNotNull(executor, "executor");

        final TD tdao = (TD) this;

        return ContinuableFuture.run(() -> sqlAction.accept(tdao), executor);
    }
}