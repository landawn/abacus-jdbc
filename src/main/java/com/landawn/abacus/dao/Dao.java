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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import com.landawn.abacus.DataSet;
import com.landawn.abacus.DirtyMarker;
import com.landawn.abacus.IsolationLevel;
import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.LazyEvaluation;
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.condition.ConditionFactory;
import com.landawn.abacus.dao.annotation.NonDBOperation;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.AbstractPreparedQuery;
import com.landawn.abacus.util.AsyncExecutor;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.Columns.ColumnOne;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.ExceptionalStream;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.JdbcUtil.BiResultExtractor;
import com.landawn.abacus.util.JdbcUtil.BiRowConsumer;
import com.landawn.abacus.util.JdbcUtil.BiRowFilter;
import com.landawn.abacus.util.JdbcUtil.BiRowMapper;
import com.landawn.abacus.util.JdbcUtil.ResultExtractor;
import com.landawn.abacus.util.JdbcUtil.RowConsumer;
import com.landawn.abacus.util.JdbcUtil.RowFilter;
import com.landawn.abacus.util.JdbcUtil.RowMapper;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NamedQuery;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.ParsedSql;
import com.landawn.abacus.util.PreparedCallableQuery;
import com.landawn.abacus.util.PreparedQuery;
import com.landawn.abacus.util.QueryUtil;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.SQLExecutor;
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
import com.landawn.abacus.util.function.Consumer;

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
 * <li>Which underline {@code PreparedQuery/PreparedCallableQuery} method to call for SQL methods/operations annotated with {@code @Select/@NamedSelect}:
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
 *          <li>Or else if the return type of the method is {@code ExceptionalStream/Stream}, {@code PreparedQuery#stream(RowMapper/BiRowMapper)} will be called.</li>
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
 *          <li>Or else if the return type of the method is {@code ExceptionalStream/Stream}, {@code PreparedQuery#stream(Class)} will be called.</li>
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
 *          <li>Or else if the return type of the method is {@code boolean/Boolean}, and the method name starts with {@code "exist"/"exists"/"notExists"/"has"}, {@code PreparedQuery#exist()} will be called.</li>
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
     * @deprecated for internal use only.
     */
    @Deprecated
    @NonDBOperation
    Class<T> targetEntityClass();

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
     * @deprecated for internal use only.
     */
    @Deprecated
    @NonDBOperation
    Executor executor();

    /**
     *
     * @deprecated for internal use only.
     */
    @Deprecated
    @NonDBOperation
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
     * @param query
     * @param returnColumnIndexes
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
    default PreparedCallableQuery prepareCallableQuery(final String query) throws SQLException {
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
    default PreparedCallableQuery prepareCallableQuery(final String sql,
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
        return prepareQuery(selectPropNames, cond).setFetchDirectionToForward().setFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT);
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
        return prepareNamedQuery(selectPropNames, cond).setFetchDirectionToForward().setFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT);
    }

    /**
     *
     * @param entityToSave
     * @return
     * @throws SQLException
     */
    void save(final T entityToSave) throws SQLException;

    /**
     *
     * @param entityToSave
     * @param propNamesToSave
     * @return
     * @throws SQLException
     */
    void save(final T entityToSave, final Collection<String> propNamesToSave) throws SQLException;

    /**
     *
     * @param namedInsertSQL
     * @param entityToSave
     * @return
     * @throws SQLException
     */
    void save(final String namedInsertSQL, final T entityToSave) throws SQLException;

    /**
     * Insert the specified entities to database by batch.
     *
     * @param entitiesToSave
     * @return
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
     * @return
     * @throws SQLException
     * @see CrudDao#batchInsert(Collection)
     */
    void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws SQLException;

    /**
     * Insert the specified entities to database by batch.
     *
     * @param entitiesToSave
     * @param propNamesToSave
     * @return
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
     * @return
     * @throws SQLException
     * @see CrudDao#batchInsert(Collection)
     */
    void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize) throws SQLException;

    /**
     * Insert the specified entities to database by batch.
     *
     * @param namedInsertSQL
     * @param entitiesToSave
     * @return
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
     * @return
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
     * @see AbstractPreparedQuery#exists()
     */
    boolean exists(final Condition cond) throws SQLException;

    /**
     *
     * @param cond
     * @return true, if there is no record found.
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     * @see AbstractPreparedQuery#notExists()
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
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> Optional<R> findFirst(final Condition cond, final RowMapper<R> rowMapper) throws SQLException, NullPointerException;

    /**
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> Optional<R> findFirst(final Condition cond, final BiRowMapper<R> rowMapper) throws SQLException, NullPointerException;

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
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final RowMapper<R> rowMapper)
            throws SQLException, NullPointerException;

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> Optional<R> findFirst(final Collection<String> selectPropNames, final Condition cond, final BiRowMapper<R> rowMapper)
            throws SQLException, NullPointerException;

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
     * @param cond
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> Optional<R> findOnlyOne(final Condition cond, final RowMapper<R> rowMapper) throws DuplicatedResultException, SQLException, NullPointerException;

    /**
     * @param cond
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> Optional<R> findOnlyOne(final Condition cond, final BiRowMapper<R> rowMapper) throws DuplicatedResultException, SQLException, NullPointerException;

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
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     */
    <R> Optional<R> findOnlyOne(final Collection<String> selectPropNames, final Condition cond, final RowMapper<R> rowMapper)
            throws DuplicatedResultException, SQLException, NullPointerException;

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException if more than one record found by the specified {@code id} (or {@code condition}).
     * @throws SQLException
     * @throws NullPointerException if {@code rowMapper} returns {@code null} for the found record.
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> Optional<R> findOnlyOne(final Collection<String> selectPropNames, final Condition cond, final BiRowMapper<R> rowMapper)
            throws DuplicatedResultException, SQLException, NullPointerException;

    /**
     * Query for boolean.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    OptionalBoolean queryForBoolean(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Query for char.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    OptionalChar queryForChar(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Query for byte.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    OptionalByte queryForByte(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Query for short.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    OptionalShort queryForShort(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Query for int.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    OptionalInt queryForInt(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Query for long.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    OptionalLong queryForLong(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Query for float.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    OptionalFloat queryForFloat(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Query for double.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    OptionalDouble queryForDouble(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Query for string.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    Nullable<String> queryForString(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Query for date.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Query for time.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Query for timestamp.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Query for single result.
     *
     * @param <V> the value type
     * @param targetValueClass
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <V> Nullable<V> queryForSingleResult(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Query for single non null.
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
     */
    <V> Optional<V> queryForSingleNonNull(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond) throws SQLException;

    /**
     * Query for unique result.
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
     */
    <V> Nullable<V> queryForUniqueResult(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond)
            throws DuplicatedResultException, SQLException;

    /**
     * Query for unique non null.
     *
     * @param <V> the value type
     * @param targetValueClass
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <V> Optional<V> queryForUniqueNonNull(final Class<V> targetValueClass, final String singleSelectPropName, final Condition cond)
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
     * @param cond
     * @param resultExtrator Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> R query(final Condition cond, final ResultExtractor<R> resultExtrator) throws SQLException;

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param resultExtrator Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> R query(final Collection<String> selectPropNames, final Condition cond, final ResultExtractor<R> resultExtrator) throws SQLException;

    /**
     *
     * @param cond
     * @param resultExtrator Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return
     * @throws SQLException
     */
    <R> R query(final Condition cond, final BiResultExtractor<R> resultExtrator) throws SQLException;

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param resultExtrator Don't save/return {@code ResultSet}. It will be closed after this call.
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> R query(final Collection<String> selectPropNames, final Condition cond, final BiResultExtractor<R> resultExtrator) throws SQLException;

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
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> List<R> list(final Condition cond, final RowMapper<R> rowMapper) throws SQLException;

    /**
     *
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> List<R> list(final Condition cond, final BiRowMapper<R> rowMapper) throws SQLException;

    /**
     *
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> List<R> list(final Condition cond, final RowFilter rowFilter, final RowMapper<R> rowMapper) throws SQLException;

    /**
     *
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> List<R> list(final Condition cond, final BiRowFilter rowFilter, final BiRowMapper<R> rowMapper) throws SQLException;

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
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final RowMapper<R> rowMapper) throws SQLException;

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final BiRowMapper<R> rowMapper) throws SQLException;

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final RowFilter rowFilter, final RowMapper<R> rowMapper)
            throws SQLException;

    /**
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    <R> List<R> list(final Collection<String> selectPropNames, final Condition cond, final BiRowFilter rowFilter, final BiRowMapper<R> rowMapper)
            throws SQLException;

    /**
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    default <R> List<R> list(final String singleSelectPropName, final Condition cond) throws SQLException {
        final PropInfo propInfo = ParserUtil.getEntityInfo(targetEntityClass()).getPropInfo(singleSelectPropName);
        final RowMapper<R> rowMapper = propInfo == null ? ColumnOne.<R> getObject() : ColumnOne.get((Type<R>) propInfo.dbType);

        return list(singleSelectPropName, cond, rowMapper);
    }

    /**
     *
     * @param singleSelectPropName
     * @param cond
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    default <R> List<R> list(final String singleSelectPropName, final Condition cond, final RowMapper<R> rowMapper) throws SQLException {
        return list(N.asList(singleSelectPropName), cond, rowMapper);
    }

    /**
     *
     * @param singleSelectPropName
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    default <R> List<R> list(final String singleSelectPropName, final Condition cond, final RowFilter rowFilter, final RowMapper<R> rowMapper)
            throws SQLException {
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
    ExceptionalStream<T, SQLException> stream(final Condition cond);

    // Will it cause confusion if it's called in transaction?
    /**
     * lazy-execution, lazy-fetch.
     *
     * @param cond
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    <R> ExceptionalStream<R, SQLException> stream(final Condition cond, final RowMapper<R> rowMapper);

    // Will it cause confusion if it's called in transaction?
    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param cond
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    <R> ExceptionalStream<R, SQLException> stream(final Condition cond, final BiRowMapper<R> rowMapper);

    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    <R> ExceptionalStream<R, SQLException> stream(final Condition cond, final RowFilter rowFilter, final RowMapper<R> rowMapper);

    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    <R> ExceptionalStream<R, SQLException> stream(final Condition cond, final BiRowFilter rowFilter, final BiRowMapper<R> rowMapper);

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
    ExceptionalStream<T, SQLException> stream(final Collection<String> selectPropNames, final Condition cond);

    // Will it cause confusion if it's called in transaction?
    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    <R> ExceptionalStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, final RowMapper<R> rowMapper);

    // Will it cause confusion if it's called in transaction?
    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    <R> ExceptionalStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, final BiRowMapper<R> rowMapper);

    // Will it cause confusion if it's called in transaction?
    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    <R> ExceptionalStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, RowFilter rowFilter,
            final RowMapper<R> rowMapper);

    // Will it cause confusion if it's called in transaction?
    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     *
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @param cond
     * @param rowFilter
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    <R> ExceptionalStream<R, SQLException> stream(final Collection<String> selectPropNames, final Condition cond, final BiRowFilter rowFilter,
            final BiRowMapper<R> rowMapper);

    // Will it cause confusion if it's called in transaction?
    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param singleSelectPropName
     * @param cond
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    default <R> ExceptionalStream<R, SQLException> stream(final String singleSelectPropName, final Condition cond) {
        final PropInfo propInfo = ParserUtil.getEntityInfo(targetEntityClass()).getPropInfo(singleSelectPropName);
        final RowMapper<R> rowMapper = propInfo == null ? ColumnOne.<R> getObject() : ColumnOne.get((Type<R>) propInfo.dbType);

        return stream(singleSelectPropName, cond, rowMapper);
    }

    // Will it cause confusion if it's called in transaction?
    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param singleSelectPropName
     * @param cond
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    default <R> ExceptionalStream<R, SQLException> stream(final String singleSelectPropName, final Condition cond, final RowMapper<R> rowMapper) {
        return stream(N.asList(singleSelectPropName), cond, rowMapper);
    }

    // Will it cause confusion if it's called in transaction?
    /**
     * Lazy execution, lazy fetching. No connection fetching/creating, no statement preparing or execution, no result fetching until {@code @TerminalOp} or {@code @TerminalOpTriggered} stream operation is called.
     *
     * @param singleSelectPropName
     * @param cond
     * @param rowMapper
     * @return
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @LazyEvaluation
    default <R> ExceptionalStream<R, SQLException> stream(final String singleSelectPropName, final Condition cond, final RowFilter rowFilter,
            final RowMapper<R> rowMapper) {
        return stream(N.asList(singleSelectPropName), cond, rowFilter, rowMapper);
    }

    /**
     *
     * @param cond
     * @param rowConsumer
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    void forEach(final Condition cond, final RowConsumer rowConsumer) throws SQLException;

    /**
     *
     * @param cond
     * @param rowConsumer
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    void forEach(final Condition cond, final BiRowConsumer rowConsumer) throws SQLException;

    /**
     *
     * @param cond
     * @param rowFilter
     * @param rowConsumer
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    void forEach(final Condition cond, final RowFilter rowFilter, final RowConsumer rowConsumer) throws SQLException;

    /**
     *
     * @param cond
     * @param rowFilter
     * @param rowConsumer
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    void forEach(final Condition cond, final BiRowFilter rowFilter, final BiRowConsumer rowConsumer) throws SQLException;

    /**
     *
     * @param selectPropNames
     * @param cond
     * @param rowConsumer
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    void forEach(final Collection<String> selectPropNames, final Condition cond, final RowConsumer rowConsumer) throws SQLException;

    /**
     *
     * @param selectPropNames
     * @param cond
     * @param rowConsumer
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    void forEach(final Collection<String> selectPropNames, final Condition cond, final BiRowConsumer rowConsumer) throws SQLException;

    /**
     *
     * @param selectPropNames
     * @param cond
     * @param rowFilter
     * @param rowConsumer
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    void forEach(final Collection<String> selectPropNames, final Condition cond, final RowFilter rowFilter, final RowConsumer rowConsumer) throws SQLException;

    /**
     *
     * @param selectPropNames
     * @param cond
     * @param rowFilter
     * @param rowConsumer
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    void forEach(final Collection<String> selectPropNames, final Condition cond, final BiRowFilter rowFilter, final BiRowConsumer rowConsumer)
            throws SQLException;

    /**
     *
     * @param selectPropNames
     * @param cond
     * @param rowConsumer
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @Beta
    default void foreach(final Collection<String> selectPropNames, final Condition cond, final Consumer<DisposableObjArray> rowConsumer) throws SQLException {
        forEach(selectPropNames, cond, RowConsumer.oneOff(targetEntityClass(), rowConsumer));
    }

    /**
     *
     * @param cond
     * @param rowConsumer
     * @return
     * @throws SQLException
     * @see ConditionFactory
     * @see ConditionFactory.CF
     */
    @Beta
    default void foreach(final Condition cond, final Consumer<DisposableObjArray> rowConsumer) throws SQLException {
        forEach(cond, RowConsumer.oneOff(targetEntityClass(), rowConsumer));
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
        @SuppressWarnings("deprecation")
        final Collection<String> propNamesToUpdate = ClassUtil.isDirtyMarker(targetEntityClass()) ? ((DirtyMarker) entity).dirtyPropNames()
                : QueryUtil.getUpdatePropNames(targetEntityClass(), null);

        return update(entity, propNamesToUpdate, cond);
    }

    /**
     * Update all the records found by specified {@code cond} with specified {@code propNamesToUpdate} from specified {@code entity}.
     *
     * @param entity
     * @param cond
     * @param propNamesToUpdate
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