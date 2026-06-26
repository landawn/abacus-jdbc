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

import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.JoinInfo;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.query.AbstractQueryBuilder.SP;
import com.landawn.abacus.query.Dsl;
import com.landawn.abacus.query.SqlDialect;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.ExceptionUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Result;
import com.landawn.abacus.util.Seid;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.function.Function;

/**
 * Internal utility class providing helper methods for DAO operations.
 * <p>
 * This class contains static utility methods used internally by the DAO framework to support
 * various operations including:
 * <ul>
 *   <li>ID extraction from entities (single and composite IDs)</li>
 *   <li>DAO type casting and validation</li>
 *   <li>Asynchronous operation completion and result aggregation</li>
 *   <li>Query preparation and SQL type detection</li>
 *   <li>Join entity information retrieval</li>
 * </ul>
 *
 * <p>
 * This class is marked as {@link Internal} and is not intended for direct use by application code.
 * It is designed to support the internal implementation of DAO interfaces and should only be
 * used by the framework itself.
 * </p>
 *
 * @see Dao
 * @see CrudDao
 * @see UncheckedDao
 * @see UncheckedCrudDao
 */
@Internal
final class DaoUtil {
    private DaoUtil() {
        // utility class - prevent instantiation.
    }

    /**
     * A consumer that configures a {@link PreparedStatement} for handling large query results efficiently.
     * Sets the fetch direction to {@link ResultSet#FETCH_FORWARD} and the fetch size to
     * {@link JdbcUtil#DEFAULT_FETCH_SIZE_FOR_BIG_RESULT}.
     */
    static final Throwables.Consumer<PreparedStatement, SQLException> stmtSetterForBigQueryResult = stmt -> {
        stmt.setFetchDirection(ResultSet.FETCH_FORWARD);
        stmt.setFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT);
    };

    /**
     * Extracts the ID value(s) from an entity instance.
     * <p>
     * If the entity has a single ID property, returns the value directly.
     * If the entity has a composite ID (multiple ID properties), returns a {@link Seid} instance
     * containing all ID property values.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Single ID property
     * User user = new User();
     * user.setId(123L);
     * List<String> idPropNames = Arrays.asList("id");
     * Long id = DaoUtil.extractId(user, idPropNames, userBeanInfo);
     * // id = 123L
     *
     * // Composite ID (multiple properties)
     * OrderLine orderLine = new OrderLine();
     * orderLine.setOrderId(100);
     * orderLine.setLineNumber(5);
     * List<String> idPropNames = Arrays.asList("orderId", "lineNumber");
     * Seid compositeId = DaoUtil.extractId(orderLine, idPropNames, orderLineBeanInfo);
     * // compositeId contains both orderId=100 and lineNumber=5
     * }</pre>
     *
     * @param <T> the entity type
     * @param <ID> the ID type of the entity
     * @param entity the entity instance from which to extract the ID. Must not be {@code null}
     * @param idPropNameList the list of ID property names. Must not be {@code null} or empty
     * @param entityInfo the bean information for the entity class
     * @return the extracted ID value (simple value for single ID, {@link Seid} for composite ID)
     * @throws IllegalArgumentException if entity is {@code null}
     */
    @SuppressWarnings("deprecation")
    static <T, ID> ID extractId(final T entity, final List<String> idPropNameList, final BeanInfo entityInfo) {
        N.checkArgNotNull(entity, cs.entity);

        if (idPropNameList.size() == 1) {
            return entityInfo.getPropInfo(idPropNameList.get(0)).getPropValue(entity);
        } else {
            final Seid entityId = Seid.of(entityInfo.simpleClassName);

            for (final String idPropName : idPropNameList) {
                entityId.set(idPropName, entityInfo.getPropInfo(idPropName).getPropValue(entity));
            }

            return (ID) entityId;
        }
    }

    /**
     * Creates a function that extracts ID value(s) from entity instances.
     * <p>
     * This method returns a reusable function that can extract IDs from multiple entities.
     * For single ID properties, it returns the value directly. For composite IDs, it returns
     * a {@link Seid} instance containing all ID property values.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Create an ID extractor for single ID
     * List<String> idPropNames = Arrays.asList("id");
     * Function<User, Long> idExtractor = DaoUtil.createIdExtractor(idPropNames, userBeanInfo);
     *
     * // Use the extractor on multiple entities
     * List<User> users = Arrays.asList(user1, user2, user3);
     * List<Long> ids = users.stream().map(idExtractor).collect(Collectors.toList());
     *
     * // Create an extractor for composite ID
     * List<String> compositeIdPropNames = Arrays.asList("orderId", "lineNumber");
     * Function<OrderLine, Seid> compositeIdExtractor = DaoUtil.createIdExtractor(compositeIdPropNames, orderLineBeanInfo);
     * Seid id = compositeIdExtractor.apply(orderLine);
     * }</pre>
     *
     * @param <T> the entity type
     * @param <ID> the ID type of the entity
     * @param idPropNameList the list of ID property names. Must not be {@code null} or empty
     * @param entityInfo the bean information for the entity class
     * @return a function that extracts ID values from entities
     */
    @SuppressWarnings("deprecation")
    static <T, ID> Function<T, ID> createIdExtractor(final List<String> idPropNameList, final BeanInfo entityInfo) {
        if (idPropNameList.size() == 1) {
            final PropInfo idPropInfo = entityInfo.getPropInfo(idPropNameList.get(0));

            return idPropInfo::getPropValue;
        } else {
            final List<PropInfo> idPropInfos = N.map(idPropNameList, entityInfo::getPropInfo);

            return it -> {
                final Seid entityId = Seid.of(entityInfo.simpleClassName);

                for (final PropInfo propInfo : idPropInfos) {
                    entityId.set(propInfo.name, propInfo.getPropValue(it));
                }

                return (ID) entityId;
            };
        }
    }

    /**
     * Ensures that ID properties are included in the set of properties to be selected for refresh operations.
     * <p>
     * When refreshing an entity, the ID properties must always be included in the SELECT statement
     * to properly identify the entity. This method checks if all ID properties are present in the
     * requested properties to refresh, and if not, creates a new collection that includes them.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // ID properties already included - returns the same collection
     * Collection<String> propsToRefresh = Arrays.asList("id", "name", "email");
     * List<String> idProps = Arrays.asList("id");
     * Collection<String> result = DaoUtil.getRefreshSelectPropNames(propsToRefresh, idProps);
     * // result == propsToRefresh (same reference)
     *
     * // ID properties not included - creates a new HashSet containing the union
     * Collection<String> propsToRefresh = Arrays.asList("name", "email");
     * List<String> idProps = Arrays.asList("id");
     * Collection<String> result = DaoUtil.getRefreshSelectPropNames(propsToRefresh, idProps);
     * // result contains: "name", "email", "id" (HashSet, iteration order not guaranteed)
     * }</pre>
     *
     * @param propNamesToRefresh the collection of property names to refresh; may be {@code null}
     * @param idPropNameList the list of ID property names that must be included
     * @return a new {@link HashSet} of the ID properties when {@code propNamesToRefresh} is {@code null};
     *         the original collection if it already contains all ID properties; otherwise a new
     *         {@link HashSet} containing both the requested properties and all ID properties
     */
    static Collection<String> getRefreshSelectPropNames(final Collection<String> propNamesToRefresh, final List<String> idPropNameList) {
        if (propNamesToRefresh == null) {
            return new HashSet<>(idPropNameList);
        }

        if (propNamesToRefresh.containsAll(idPropNameList)) {
            return propNamesToRefresh;
        } else {
            final Collection<String> selectPropNames = new HashSet<>(propNamesToRefresh);
            selectPropNames.addAll(idPropNameList);
            return selectPropNames;
        }
    }

    /**
     * Casts a {@link CrudJoinEntityHelper} to a {@link CrudDao} instance.
     * <p>
     * This method is used internally to ensure type safety when working with DAO instances
     * that implement both CrudJoinEntityHelper and CrudDao interfaces. It validates that
     * the provided DAO actually extends CrudDao before performing the cast.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Typical usage in internal DAO operations
     * class UserDaoImpl implements CrudDao<User, Long, UserDaoImpl>,
     *                              CrudJoinEntityHelper<User, Long, UserDaoImpl> {
     *     // ... implementation
     * }
     *
     * UserDaoImpl dao = new UserDaoImpl();
     * CrudDao<User, Long, UserDaoImpl> crudDao = DaoUtil.getCrudDao(dao);
     * // Successfully casts to CrudDao
     * }</pre>
     *
     * @param <T> the entity type managed by this DAO
     * @param <ID> the ID type of the entity
     * @param <TD> the DAO type
     * @param dao the CrudJoinEntityHelper instance to cast
     * @return the DAO instance cast to CrudDao
     * @throws UnsupportedOperationException if the DAO does not implement CrudDao interface
     */
    static <T, ID, TD extends CrudDao<T, ID, TD>> TD getCrudDao(final CrudJoinEntityHelper<T, ID, TD> dao) {
        if (dao instanceof CrudDao) {
            return (TD) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " does not implement CrudDao interface"); //NOSONAR
        }
    }

    /**
     * Casts a {@link JoinEntityHelper} to a {@link Dao} instance.
     * <p>
     * This method is used internally to ensure type safety when working with DAO instances
     * that implement both JoinEntityHelper and Dao interfaces. It validates that the provided
     * DAO actually extends Dao before performing the cast.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Typical usage in internal DAO operations
     * class ProductDaoImpl implements Dao<Product, ProductDaoImpl>,
     *                                 JoinEntityHelper<Product, ProductDaoImpl> {
     *     // ... implementation
     * }
     *
     * ProductDaoImpl dao = new ProductDaoImpl();
     * Dao<Product, ProductDaoImpl> daoInstance = DaoUtil.getDao(dao);
     * // Successfully casts to Dao
     * }</pre>
     *
     * @param <T> the entity type managed by this DAO
     * @param <TD> the DAO type
     * @param dao the JoinEntityHelper instance to cast
     * @return the DAO instance cast to Dao
     * @throws UnsupportedOperationException if the DAO does not implement Dao interface
     */
    static <T, TD extends Dao<T, TD>> TD getDao(final JoinEntityHelper<T, TD> dao) {
        if (dao instanceof Dao) {
            return (TD) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " does not implement Dao interface");
        }
    }

    /**
     * Casts an {@link UncheckedJoinEntityHelper} to an {@link UncheckedDao} instance.
     * <p>
     * This method is used internally to ensure type safety when working with unchecked DAO instances
     * that implement both UncheckedJoinEntityHelper and UncheckedDao interfaces. It validates that
     * the provided DAO actually extends UncheckedDao before performing the cast.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Typical usage in internal DAO operations
     * class ProductDaoImpl implements UncheckedDao<Product, ProductDaoImpl>,
     *                                 UncheckedJoinEntityHelper<Product, ProductDaoImpl> {
     *     // ... implementation
     * }
     *
     * ProductDaoImpl dao = new ProductDaoImpl();
     * UncheckedDao<Product, ProductDaoImpl> daoInstance = DaoUtil.getDao(dao);
     * // Successfully casts to UncheckedDao
     * }</pre>
     *
     * @param <T> the entity type managed by this DAO
     * @param <TD> the DAO type
     * @param dao the UncheckedJoinEntityHelper instance to cast
     * @return the DAO instance cast to UncheckedDao
     * @throws UnsupportedOperationException if the DAO does not implement UncheckedDao interface
     */
    static <T, TD extends UncheckedDao<T, TD>> TD getDao(final UncheckedJoinEntityHelper<T, TD> dao) {
        if (dao instanceof UncheckedDao) {
            return (TD) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " does not implement UncheckedDao interface");
        }
    }

    /**
     * Casts an {@link UncheckedCrudJoinEntityHelper} to an {@link UncheckedCrudDao} instance.
     * <p>
     * This method is used internally to ensure type safety when working with unchecked CRUD DAO instances
     * that implement both UncheckedCrudJoinEntityHelper and UncheckedCrudDao interfaces. It validates that
     * the provided DAO actually extends UncheckedCrudDao before performing the cast.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Typical usage in internal DAO operations
     * class UserDaoImpl implements UncheckedCrudDao<User, Long, UserDaoImpl>,
     *                              UncheckedCrudJoinEntityHelper<User, Long, UserDaoImpl> {
     *     // ... implementation
     * }
     *
     * UserDaoImpl dao = new UserDaoImpl();
     * UncheckedCrudDao<User, Long, UserDaoImpl> crudDao = DaoUtil.getCrudDao(dao);
     * // Successfully casts to UncheckedCrudDao
     * }</pre>
     *
     * @param <T> the entity type managed by this DAO
     * @param <ID> the ID type of the entity
     * @param <TD> the DAO type
     * @param dao the UncheckedCrudJoinEntityHelper instance to cast
     * @return the DAO instance cast to UncheckedCrudDao
     * @throws UnsupportedOperationException if the DAO does not implement UncheckedCrudDao interface
     */
    static <T, ID, TD extends UncheckedCrudDao<T, ID, TD>> TD getCrudDao(final UncheckedCrudJoinEntityHelper<T, ID, TD> dao) {
        if (dao instanceof UncheckedCrudDao) {
            return (TD) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " does not implement UncheckedCrudDao interface");
        }
    }

    /**
     * A consumer that converts an exception to {@link UncheckedSQLException} (or another runtime exception) and throws it.
     * <p>
     * Used by {@link #uncheckedComplete(List)} and {@link #uncheckedCompleteSum(List)} to surface
     * failures from completed futures. If the exception is a {@link SQLException} or has a
     * SQLException as its cause, it is wrapped in an {@link UncheckedSQLException}. Otherwise, the
     * exception is converted to a runtime exception via {@link ExceptionUtil#toRuntimeException}.
     * This consumer never returns normally when invoked — it always throws.
     * </p>
     */
    static final Throwables.Consumer<? super Exception, UncheckedSQLException> throwUncheckedSQLException = e -> {
        if (e instanceof SQLException) {
            throw new UncheckedSQLException((SQLException) e);
        } else if (e.getCause() instanceof SQLException) {
            throw new UncheckedSQLException((SQLException) e.getCause());
        } else {
            throw ExceptionUtil.toRuntimeException(e, true);
        }
    };

    /**
     * A consumer that re-throws an exception as a checked {@link SQLException} when possible, or as a runtime exception otherwise.
     * <p>
     * Used by {@link #complete(List)} and {@link #completeSum(List)} to surface failures from
     * completed futures. If the exception is a {@link SQLException} or has a SQLException as its
     * cause, the SQLException is re-thrown. Otherwise, the exception is converted to a runtime
     * exception via {@link ExceptionUtil#toRuntimeException}. This consumer never returns normally
     * when invoked — it always throws.
     * </p>
     */
    static final Throwables.Consumer<? super Exception, SQLException> throwSQLExceptionAction = e -> {
        if (e instanceof SQLException) {
            throw (SQLException) e;
        } else if (e.getCause() instanceof SQLException) {
            throw (SQLException) e.getCause();
        } else {
            throw ExceptionUtil.toRuntimeException(e, true);
        }
    };

    /**
     * Completes all futures in the list and throws {@link UncheckedSQLException} if any fail.
     * <p>
     * This method waits for all futures to complete and checks for failures. If any future fails,
     * the exception is converted to an UncheckedSQLException and thrown. This is typically used
     * for batch operations where all operations must complete successfully.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Execute multiple async operations and wait for completion
     * List<ContinuableFuture<Void>> futures = new ArrayList<>();
     * futures.add(asyncExecutor.execute(() -> dao.save(entity1)));
     * futures.add(asyncExecutor.execute(() -> dao.save(entity2)));
     * futures.add(asyncExecutor.execute(() -> dao.save(entity3)));
     *
     * // Wait for all operations to complete
     * DaoUtil.uncheckedComplete(futures);
     * // Throws UncheckedSQLException if any operation failed
     * }</pre>
     *
     * @param futures the list of futures to complete. Must not be {@code null}
     * @throws UncheckedSQLException if any future fails with a SQL-related exception
     */
    static void uncheckedComplete(final List<ContinuableFuture<Void>> futures) throws UncheckedSQLException {
        for (final ContinuableFuture<Void> f : futures) {
            f.getAsResult().ifFailure(throwUncheckedSQLException);
        }
    }

    /**
     * Completes all futures in the list, sums their integer results, and throws {@link UncheckedSQLException} if any fail.
     * <p>
     * This method waits for all futures to complete, collecting their integer results and summing them.
     * If any future fails, the exception is converted to an UncheckedSQLException and thrown.
     * This is typically used for batch update/insert/delete operations where the return value indicates
     * the number of affected rows.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Execute multiple async update operations and sum affected rows
     * List<ContinuableFuture<Integer>> futures = new ArrayList<>();
     * futures.add(asyncExecutor.execute(() -> dao.update(entity1)));
     * futures.add(asyncExecutor.execute(() -> dao.update(entity2)));
     * futures.add(asyncExecutor.execute(() -> dao.update(entity3)));
     *
     * // Wait for all operations and get total affected rows
     * int totalAffectedRows = DaoUtil.uncheckedCompleteSum(futures);
     * // totalAffectedRows = sum of all affected rows
     * // Throws UncheckedSQLException if any operation failed
     * }</pre>
     *
     * @param futures the list of futures returning integer values to complete and sum. Must not be {@code null}
     * @return the sum of all integer results from the futures
     * @throws UncheckedSQLException if any future fails with a SQL-related exception
     */
    static int uncheckedCompleteSum(final List<ContinuableFuture<Integer>> futures) throws UncheckedSQLException {
        int result = 0;
        Result<Integer, Exception> ret = null;

        for (final ContinuableFuture<Integer> f : futures) {
            ret = f.getAsResult();

            if (ret.isFailure()) {
                throwUncheckedSQLException.accept(ret.getException());
            }

            result += ret.orElseIfFailure(0);
        }

        return result;
    }

    /**
     * Completes all futures in the list and throws {@link SQLException} if any fail.
     * <p>
     * This method waits for all futures to complete and checks for failures. If any future fails,
     * the exception is thrown as a checked SQLException. This is the checked exception variant
     * of {@link #uncheckedComplete(List)}, typically used in methods that declare SQLException.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Execute multiple async operations and wait for completion (checked exception)
     * List<ContinuableFuture<Void>> futures = new ArrayList<>();
     * futures.add(asyncExecutor.execute(() -> dao.save(entity1)));
     * futures.add(asyncExecutor.execute(() -> dao.save(entity2)));
     * futures.add(asyncExecutor.execute(() -> dao.save(entity3)));
     *
     * // Wait for all operations to complete
     * DaoUtil.complete(futures);
     * // Throws SQLException if any operation failed
     * }</pre>
     *
     * @param futures the list of futures to complete. Must not be {@code null}
     * @throws SQLException if any future fails with a SQL-related exception
     */
    static void complete(final List<ContinuableFuture<Void>> futures) throws SQLException {
        for (final ContinuableFuture<Void> f : futures) {
            f.getAsResult().ifFailure(throwSQLExceptionAction);
        }
    }

    /**
     * Completes all futures in the list, sums their integer results, and throws {@link SQLException} if any fail.
     * <p>
     * This method waits for all futures to complete, collecting their integer results and summing them.
     * If any future fails, the exception is thrown as a checked SQLException. This is the checked
     * exception variant of {@link #uncheckedCompleteSum(List)}, typically used for batch operations
     * where the return value indicates the total number of affected rows.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Execute multiple async update operations and sum affected rows (checked exception)
     * List<ContinuableFuture<Integer>> futures = new ArrayList<>();
     * futures.add(asyncExecutor.execute(() -> dao.update(entity1)));
     * futures.add(asyncExecutor.execute(() -> dao.update(entity2)));
     * futures.add(asyncExecutor.execute(() -> dao.update(entity3)));
     *
     * // Wait for all operations and get total affected rows
     * int totalAffectedRows = DaoUtil.completeSum(futures);
     * // totalAffectedRows = sum of all affected rows
     * // Throws SQLException if any operation failed
     * }</pre>
     *
     * @param futures the list of futures returning integer values to complete and sum. Must not be {@code null}
     * @return the sum of all integer results from the futures
     * @throws SQLException if any future fails with a SQL-related exception
     */
    static int completeSum(final List<ContinuableFuture<Integer>> futures) throws SQLException {
        int result = 0;
        Result<Integer, Exception> ret = null;

        for (final ContinuableFuture<Integer> f : futures) {
            ret = f.getAsResult();

            if (ret.isFailure()) {
                throwSQLExceptionAction.accept(ret.getException());
            }

            result += ret.orElseIfFailure(0);
        }

        return result;
    }

    // getDaoPreparedQueryFunc(dao) is called on every prepareQuery(Collection,Condition) /
    // prepareNamedQuery(Collection,Condition) path. The returned Tuple2 (and the 6 closures it
    // wraps) are bound to the specific DAO instance (dataSource()/targetEntityClass()), so it is
    // cached per DAO instance rather than per class. DAO proxies are pooled/long-lived (held in
    // DaoImpl.daoPool); the proxy's hashCode/equals are identity-based, so this map is bounded by
    // the number of distinct DAO instances.
    @SuppressWarnings("rawtypes")
    private static final Map<Dao, Tuple2<Throwables.BiFunction<Collection<String>, Condition, PreparedQuery, SQLException>, Throwables.BiFunction<Collection<String>, Condition, NamedQuery, SQLException>>> daoPreparedQueryFuncPool = new ConcurrentHashMap<>();

    /**
     * Creates query preparation functions for a given DAO instance.
     * <p>
     * This method returns a tuple containing two functions: one for creating PreparedQuery instances
     * and one for creating NamedQuery instances. The functions are determined from the {@link Dsl}
     * configured on the DAO instance and are instance-bound to avoid cross-DAO state leakage.
     * </p>
     * <p>
     * The returned functions can be used to build and prepare SQL queries based on select property
     * names and conditions.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get the query preparation functions for a DAO
     * Dao<User, UserDao> userDao = ...;
     * Tuple2<BiFunction, BiFunction> funcs = DaoUtil.getDaoPreparedQueryFunc(userDao);
     *
     * // Use the PreparedQuery function
     * Throwables.BiFunction<Collection<String>, Condition, PreparedQuery, SQLException> preparedQueryFunc = funcs._1;
     * Collection<String> selectProps = Arrays.asList("id", "name", "email");
     * Condition condition = Filters.eq("status", "active");
     * PreparedQuery query = preparedQueryFunc.apply(selectProps, condition);
     *
     * // Use the NamedQuery function
     * Throwables.BiFunction<Collection<String>, Condition, NamedQuery, SQLException> namedQueryFunc = funcs._2;
     * NamedQuery namedQuery = namedQueryFunc.apply(selectProps, condition);
     * }</pre>
     *
     * @param dao the DAO instance for which to get query preparation functions
     * @return a tuple containing two bi-functions: the first for creating {@link PreparedQuery} instances,
     *         the second for creating {@link NamedQuery} instances
     */
    @SuppressWarnings("rawtypes")
    static Tuple2<Throwables.BiFunction<Collection<String>, Condition, PreparedQuery, SQLException>, Throwables.BiFunction<Collection<String>, Condition, NamedQuery, SQLException>> getDaoPreparedQueryFunc(
            final Dao dao) {
        return daoPreparedQueryFuncPool.computeIfAbsent(dao, DaoUtil::computeDaoPreparedQueryFunc);
    }

    @SuppressWarnings("rawtypes")
    private static Tuple2<Throwables.BiFunction<Collection<String>, Condition, PreparedQuery, SQLException>, Throwables.BiFunction<Collection<String>, Condition, NamedQuery, SQLException>> computeDaoPreparedQueryFunc(
            final Dao dao) {
        final Dsl dsl = dao.dsl();
        final Dsl namedDsl = namedDsl(dsl);

        @SuppressWarnings("deprecation")
        final Class<?> targetEntityClass = dao.targetEntityClass();

        final Throwables.BiFunction<javax.sql.DataSource, SP, PreparedQuery, SQLException> prepareQueryFunc = (dataSource, sp) -> {
            final PreparedQuery query = JdbcUtil.prepareQuery(dataSource, sp.query());

            if (N.notEmpty(sp.parameters())) {
                boolean noException = false;

                try {
                    query.setParameters(sp.parameters());

                    noException = true;
                } finally {
                    if (!noException) {
                        query.close();
                    }
                }
            }

            return query;
        };

        final Throwables.BiFunction<javax.sql.DataSource, SP, NamedQuery, SQLException> prepareNamedQueryFunc = (dataSource, sp) -> {
            final NamedQuery query = JdbcUtil.prepareNamedQuery(dataSource, sp.query());

            if (N.notEmpty(sp.parameters())) {
                boolean noException = false;

                try {
                    query.setParameters(sp.parameters());

                    noException = true;
                } finally {
                    if (!noException) {
                        query.close();
                    }
                }
            }

            return query;
        };

        return Tuple.of((selectPropNames, cond) -> {
            final SP sp = (selectPropNames == null ? dsl.selectFrom(targetEntityClass) : dsl.select(selectPropNames).from(targetEntityClass)).append(cond)
                    .build();

            return prepareQueryFunc.apply(dao.dataSource(), sp);
        }, (selectPropNames, cond) -> {
            final SP sp = (selectPropNames == null ? namedDsl.selectFrom(targetEntityClass) : namedDsl.select(selectPropNames).from(targetEntityClass))
                    .append(cond)
                    .build();

            return prepareNamedQueryFunc.apply(dao.dataSource(), sp);
        });
    }

    private static Dsl namedDsl(final Dsl dsl) {
        if (dsl == Dsl.NSB || dsl == Dsl.NSC || dsl == Dsl.NAC || dsl == Dsl.NLC) {
            return dsl;
        } else if (dsl == Dsl.PSB) {
            return Dsl.NSB;
        } else if (dsl == Dsl.PSC) {
            return Dsl.NSC;
        } else if (dsl == Dsl.PAC) {
            return Dsl.NAC;
        } else if (dsl == Dsl.PLC) {
            return Dsl.NLC;
        }

        return dslWithPolicy(dsl, "NAMED_SQL");
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Dsl dslWithPolicy(final Dsl dsl, final String sqlPolicyName) {
        final SqlDialect sqlDialect = dsl.sqlDialect();
        final Object dialectBuilder = SqlDialect.builder()
                .productInfo(sqlDialect.productInfo())
                .namingPolicy(sqlDialect.namingPolicy())
                .identifierQuote(sqlDialect.identifierQuote());

        try {
            final Class<? extends Enum> sqlPolicyClass = (Class<? extends Enum>) Class.forName("com.landawn.abacus.query.AbstractQueryBuilder$SQLPolicy");
            final Method sqlPolicyMethod = dialectBuilder.getClass().getDeclaredMethod("sqlPolicy", sqlPolicyClass);
            final Method buildMethod = dialectBuilder.getClass().getDeclaredMethod("build");
            ClassUtil.setAccessibleQuietly(sqlPolicyMethod, true);
            ClassUtil.setAccessibleQuietly(buildMethod, true);
            sqlPolicyMethod.invoke(dialectBuilder, Enum.valueOf(sqlPolicyClass, sqlPolicyName));
            return Dsl.forDialect((SqlDialect) buildMethod.invoke(dialectBuilder));
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to create Dsl with SQL policy: " + sqlPolicyName, e);
        }
    }

    /**
     * Checks if the given SQL statement is a SELECT query.
     * <p>
     * This method performs a case-insensitive check on the leading SQL keyword (after skipping
     * any leading whitespace and SQL comments). For statements that start with a {@code WITH}
     * (CTE) clause, the keyword that follows the CTE definitions is examined instead.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Valid SELECT queries
     * boolean result1 = DaoUtil.isSelectQuery("SELECT * FROM users");
     * // result1 = true
     *
     * boolean result2 = DaoUtil.isSelectQuery("select id, name from products");
     * // result2 = true
     *
     * boolean result3 = DaoUtil.isSelectQuery("  SELECT count(*) FROM orders");
     * // result3 = true
     *
     * // Non-SELECT queries
     * boolean result4 = DaoUtil.isSelectQuery("UPDATE users SET name = 'John'");
     * // result4 = false
     *
     * boolean result5 = DaoUtil.isSelectQuery("INSERT INTO users VALUES (1, 'John')");
     * // result5 = false
     * }</pre>
     *
     * @param sql the SQL statement to check; may be empty or {@code null}
     * @return {@code true} if the SQL is a SELECT query, {@code false} otherwise
     */
    static boolean isSelectQuery(final String sql) {
        return "SELECT".equalsIgnoreCase(getLeadingQueryKeyword(sql));
    }

    /**
     * Checks if the given SQL statement is an INSERT query.
     * <p>
     * This method performs a case-insensitive check on the leading SQL keyword (after skipping
     * any leading whitespace and SQL comments). For statements that start with a {@code WITH}
     * (CTE) clause, the keyword that follows the CTE definitions is examined instead.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Valid INSERT queries
     * boolean result1 = DaoUtil.isInsertQuery("INSERT INTO users VALUES (1, 'John')");
     * // result1 = true
     *
     * boolean result2 = DaoUtil.isInsertQuery("insert into products (name, price) values ('Widget', 9.99)");
     * // result2 = true
     *
     * boolean result3 = DaoUtil.isInsertQuery("  INSERT INTO orders (order_id) VALUES (100)");
     * // result3 = true
     *
     * // Non-INSERT queries
     * boolean result4 = DaoUtil.isInsertQuery("UPDATE users SET name = 'John'");
     * // result4 = false
     *
     * boolean result5 = DaoUtil.isInsertQuery("SELECT * FROM users");
     * // result5 = false
     * }</pre>
     *
     * @param sql the SQL statement to check; may be empty or {@code null}
     * @return {@code true} if the SQL is an INSERT query, {@code false} otherwise
     */
    static boolean isInsertQuery(final String sql) {
        return "INSERT".equalsIgnoreCase(getLeadingQueryKeyword(sql));
    }

    static boolean isReadOnlyQuery(final String sql) {
        return isSelectQuery(sql) && !containsMutationQueryKeyword(sql);
    }

    static boolean isNoUpdateQuery(final String sql) {
        if (!(isSelectQuery(sql) || isInsertQuery(sql))) {
            return false;
        }

        return !containsQueryKeyword(sql, "UPDATE") && !containsQueryKeyword(sql, "DELETE") && !containsQueryKeyword(sql, "MERGE")
                && !containsInsertUpdateClause(sql);
    }

    private static boolean containsMutationQueryKeyword(final String sql) {
        return containsQueryKeyword(sql, "INSERT") || containsQueryKeyword(sql, "UPDATE") || containsQueryKeyword(sql, "DELETE")
                || containsQueryKeyword(sql, "MERGE");
    }

    private static boolean containsInsertUpdateClause(final String sql) {
        return isInsertOrReplaceQuery(sql) || containsTokenSequence(sql, "DUPLICATE", "KEY", "UPDATE") || containsTokenSequence(sql, "DO", "UPDATE");
    }

    private static boolean isInsertOrReplaceQuery(final String sql) {
        int index = skipLeadingWhitespaceAndComments(sql, 0);
        String keyword = readKeyword(sql, index);

        if (!"INSERT".equalsIgnoreCase(keyword)) {
            return false;
        }

        index = skipLeadingWhitespaceAndComments(sql, index + keyword.length());
        keyword = readKeyword(sql, index);

        if (!"OR".equalsIgnoreCase(keyword)) {
            return false;
        }

        index = skipLeadingWhitespaceAndComments(sql, index + keyword.length());

        return "REPLACE".equalsIgnoreCase(readKeyword(sql, index));
    }

    private static boolean containsTokenSequence(final String sql, final String... tokens) {
        if (Strings.isEmpty(sql) || tokens.length == 0) {
            return false;
        }

        int index = 0;
        int matched = 0;

        while (index < sql.length()) {
            index = skipLeadingWhitespaceAndComments(sql, index);

            if (index >= sql.length()) {
                break;
            }

            final char ch = sql.charAt(index);

            if (ch == '\'' || ch == '"' || ch == '`') {
                index = skipQuotedLiteral(sql, index, ch);
                matched = 0;
                continue;
            }

            if (Character.isLetter(ch)) {
                final String token = readKeyword(sql, index);

                if (tokens[matched].equalsIgnoreCase(token)) {
                    matched++;

                    if (matched == tokens.length) {
                        return true;
                    }
                } else {
                    matched = tokens[0].equalsIgnoreCase(token) ? 1 : 0;
                }

                index += token.length();
                continue;
            }

            matched = 0;
            index++;
        }

        return false;
    }

    private static boolean containsQueryKeyword(final String sql, final String keywordToFind) {
        if (Strings.isEmpty(sql)) {
            return false;
        }

        int index = 0;
        boolean canStartQueryKeyword = true;
        String previousKeyword = "";

        while (index < sql.length()) {
            index = skipLeadingWhitespaceAndComments(sql, index);

            if (index >= sql.length()) {
                break;
            }

            final char ch = sql.charAt(index);

            if (ch == '\'' || ch == '"' || ch == '`') {
                index = skipQuotedLiteral(sql, index, ch);
                canStartQueryKeyword = false;
                continue;
            }

            if (Character.isLetter(ch)) {
                final String token = readKeyword(sql, index);

                if (canStartQueryKeyword && keywordToFind.equalsIgnoreCase(token)) {
                    return true;
                }

                previousKeyword = token;
                canStartQueryKeyword = false;
                index += token.length();
                continue;
            }

            if (ch == ';') {
                canStartQueryKeyword = true;
            } else if (ch == '(') {
                canStartQueryKeyword = "AS".equalsIgnoreCase(previousKeyword) || "MATERIALIZED".equalsIgnoreCase(previousKeyword);
            } else if (!Character.isWhitespace(ch)) {
                canStartQueryKeyword = false;
            }

            index++;
        }

        return false;
    }

    private static String getLeadingQueryKeyword(final String sql) {
        if (Strings.isEmpty(sql)) {
            return "";
        }

        int index = skipLeadingWhitespaceAndComments(sql, 0);

        // A query may be wrapped in one or more leading parentheses, e.g. "(SELECT 1)" or
        // "(SELECT a FROM t1) UNION ALL (SELECT a FROM t2)". Skip past those so the leading verb
        // (SELECT/INSERT/...) is still recognized instead of being classified as no leading keyword.
        while (index < sql.length() && sql.charAt(index) == '(') {
            index = skipLeadingWhitespaceAndComments(sql, index + 1);
        }

        if (index >= sql.length()) {
            return "";
        }

        String keyword = readKeyword(sql, index);

        if (Strings.isEmpty(keyword)) {
            return "";
        }

        if (!"WITH".equalsIgnoreCase(keyword)) {
            return keyword;
        }

        index += keyword.length();
        index = skipLeadingWhitespaceAndComments(sql, index);

        keyword = readKeyword(sql, index);

        if ("RECURSIVE".equalsIgnoreCase(keyword)) {
            index += keyword.length();
        }

        return findKeywordAfterWithClause(sql, index);
    }

    private static String findKeywordAfterWithClause(final String sql, int fromIndex) {
        int depth = 0;

        while (fromIndex < sql.length()) {
            fromIndex = skipLeadingWhitespaceAndComments(sql, fromIndex);

            if (fromIndex >= sql.length()) {
                break;
            }

            final char ch = sql.charAt(fromIndex);

            if (ch == '\'' || ch == '"' || ch == '`') {
                fromIndex = skipQuotedLiteral(sql, fromIndex, ch);
                continue;
            }

            if (ch == '(') {
                depth++;
                fromIndex++;
                continue;
            }

            if (ch == ')') {
                if (depth > 0) {
                    depth--;
                }

                fromIndex++;
                continue;
            }

            if (Character.isLetter(ch)) {
                final String token = readKeyword(sql, fromIndex);

                if (depth == 0 && isQueryKeyword(token)) {
                    return token;
                }

                fromIndex += token.length();
                continue;
            }

            fromIndex++;
        }

        return "";
    }

    private static boolean isQueryKeyword(final String token) {
        return "SELECT".equalsIgnoreCase(token) || "INSERT".equalsIgnoreCase(token) || "UPDATE".equalsIgnoreCase(token) || "DELETE".equalsIgnoreCase(token)
                || "MERGE".equalsIgnoreCase(token);
    }

    private static int skipLeadingWhitespaceAndComments(final String sql, int fromIndex) {
        while (fromIndex < sql.length()) {
            while (fromIndex < sql.length() && Character.isWhitespace(sql.charAt(fromIndex))) {
                fromIndex++;
            }

            if (fromIndex >= sql.length()) {
                break;
            }

            if ((fromIndex + 1 < sql.length()) && sql.charAt(fromIndex) == '-' && sql.charAt(fromIndex + 1) == '-') {
                fromIndex += 2;

                while (fromIndex < sql.length() && sql.charAt(fromIndex) != '\n' && sql.charAt(fromIndex) != '\r') {
                    fromIndex++;
                }

                continue;
            }

            if ((fromIndex + 1 < sql.length()) && sql.charAt(fromIndex) == '/' && sql.charAt(fromIndex + 1) == '*') {
                fromIndex += 2;

                while ((fromIndex + 1 < sql.length()) && !(sql.charAt(fromIndex) == '*' && sql.charAt(fromIndex + 1) == '/')) {
                    fromIndex++;
                }

                fromIndex = Math.min(fromIndex + 2, sql.length());
                continue;
            }

            if (sql.charAt(fromIndex) == '#') {
                do {
                    fromIndex++;
                } while (fromIndex < sql.length() && sql.charAt(fromIndex) != '\n' && sql.charAt(fromIndex) != '\r');

                continue;
            }

            break;
        }

        return fromIndex;
    }

    private static int skipQuotedLiteral(final String sql, int fromIndex, final char quoteChar) {
        fromIndex++;

        while (fromIndex < sql.length()) {
            final char ch = sql.charAt(fromIndex);

            if (ch == '\\') {
                // Skip backslash-escaped character (e.g., \' in MySQL)
                fromIndex += 2;
                if (fromIndex >= sql.length()) {
                    break;
                }
            } else if (ch == quoteChar) {
                if ((fromIndex + 1 < sql.length()) && sql.charAt(fromIndex + 1) == quoteChar) {
                    // Doubled quote escape (SQL standard)
                    fromIndex += 2;
                } else {
                    fromIndex++;
                    break;
                }
            } else {
                fromIndex++;
            }
        }

        return fromIndex;
    }

    private static String readKeyword(final String sql, int fromIndex) {
        fromIndex = skipLeadingWhitespaceAndComments(sql, fromIndex);

        final int startIndex = fromIndex;

        while (fromIndex < sql.length() && Character.isLetter(sql.charAt(fromIndex))) {
            fromIndex++;
        }

        return fromIndex > startIndex ? sql.substring(startIndex, fromIndex) : "";
    }

    /**
     * Retrieves the join information for an entity class.
     * <p>
     * This method delegates to {@link JoinInfo#getEntityJoinInfo(Class, Class, String)} to retrieve
     * metadata about join relationships for the target entity. The returned map contains property names
     * as keys and their corresponding {@link JoinInfo} objects as values, which describe how to join
     * related entities.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get join info for a User entity with DAO interface
     * Map<String, JoinInfo> joinInfo = DaoUtil.getEntityJoinInfo(
     *     UserDao.class,
     *     User.class,
     *     "users"
     * );
     *
     * // Access join information for specific properties
     * JoinInfo addressJoinInfo = joinInfo.get("address");
     * JoinInfo ordersJoinInfo = joinInfo.get("orders");
     * }</pre>
     *
     * @param targetDaoInterface the DAO interface class for the target entity
     * @param targetEntityClass the entity class to get join information for
     * @param targetTableName the database table name for the entity
     * @return a map of property names to their corresponding {@link JoinInfo} objects
     */
    static Map<String, JoinInfo> getEntityJoinInfo(final Class<?> targetDaoInterface, final Class<?> targetEntityClass, final String targetTableName) {
        return JoinInfo.getEntityJoinInfo(targetDaoInterface, targetEntityClass, targetTableName);
    }

    /**
     * Retrieves the property names for join entities of a specific type.
     * <p>
     * This method delegates to {@link JoinInfo#getJoinEntityPropNamesByType(Class, Class, String, Class)}
     * to find all properties in the target entity class that represent joins to entities of the specified
     * type. This is useful when you need to identify which properties should be populated when loading
     * related entities of a particular type.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get property names for all Address-type join entities in User
     * List<String> addressPropNames = DaoUtil.getJoinEntityPropNamesByType(
     *     UserDao.class,
     *     User.class,
     *     "users",
     *     Address.class
     * );
     * // Returns: ["homeAddress", "workAddress"] if User has multiple Address properties
     *
     * // Get property names for Order-type join entities
     * List<String> orderPropNames = DaoUtil.getJoinEntityPropNamesByType(
     *     UserDao.class,
     *     User.class,
     *     "users",
     *     Order.class
     * );
     * // Returns: ["orders"] if User has a List<Order> property
     * }</pre>
     *
     * @param targetDaoInterface the DAO interface class for the target entity
     * @param targetEntityClass the entity class to search for join properties
     * @param targetTableName the database table name for the target entity
     * @param joinEntityClass the class of the join entity to find properties for
     * @return a list of property names that represent joins to the specified entity type
     */
    static List<String> getJoinEntityPropNamesByType(final Class<?> targetDaoInterface, final Class<?> targetEntityClass, final String targetTableName,
            final Class<?> joinEntityClass) {
        return JoinInfo.getJoinEntityPropNamesByType(targetDaoInterface, targetEntityClass, targetTableName, joinEntityClass);
    }
}
