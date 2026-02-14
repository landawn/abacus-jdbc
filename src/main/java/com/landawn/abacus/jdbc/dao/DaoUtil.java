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

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
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
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.query.SQLBuilder.NAC;
import com.landawn.abacus.query.SQLBuilder.NLC;
import com.landawn.abacus.query.SQLBuilder.NSB;
import com.landawn.abacus.query.SQLBuilder.NSC;
import com.landawn.abacus.query.SQLBuilder.PAC;
import com.landawn.abacus.query.SQLBuilder.PLC;
import com.landawn.abacus.query.SQLBuilder.PSB;
import com.landawn.abacus.query.SQLBuilder.PSC;
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
 * </p>
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
        // singleton.
    }

    /**
     * A consumer that configures a PreparedStatement for handling large query results efficiently.
     * Sets the fetch direction to FETCH_FORWARD and fetch size to the default for big results.
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
     * @param <T> the entity type managed by this DAO
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
     * // ID properties not included - creates new collection with IDs added
     * Collection<String> propsToRefresh = Arrays.asList("name", "email");
     * List<String> idProps = Arrays.asList("id");
     * Collection<String> result = DaoUtil.getRefreshSelectPropNames(propsToRefresh, idProps);
     * // result contains: "name", "email", "id"
     * }</pre>
     *
     * @param propNamesToRefresh the collection of property names to refresh
     * @param idPropNameList the list of ID property names that must be included
     * @return the original collection if it contains all ID properties, otherwise a new collection
     *         containing both the requested properties and all ID properties
     */
    static Collection<String> getRefreshSelectPropNames(final Collection<String> propNamesToRefresh, final List<String> idPropNameList) {
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
     * class UserDaoImpl implements CrudDao<User, Long, PSC, UserDaoImpl>,
     *                              CrudJoinEntityHelper<User, Long, PSC, UserDaoImpl> {
     *     // ... implementation
     * }
     *
     * UserDaoImpl dao = new UserDaoImpl();
     * CrudDao<User, Long, PSC, UserDaoImpl> crudDao = DaoUtil.getCrudDao(dao);
     * // Successfully casts to CrudDao
     * }</pre>
     *
     * @param <T> the entity type managed by this DAO
     * @param <ID> the ID type of the entity
     * @param <SB> the SQLBuilder type used to generate SQL scripts (must be one of SQLBuilder.PSC/PAC/PLC)
     * @param <TD> the DAO type
     * @param dao the CrudJoinEntityHelper instance to cast
     * @return the DAO instance cast to CrudDao
     * @throws UnsupportedOperationException if the DAO does not implement CrudDao interface
     */
    static <T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>> TD getCrudDao(final CrudJoinEntityHelper<T, ID, SB, TD> dao) {
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
     * class ProductDaoImpl implements Dao<Product, PSC, ProductDaoImpl>,
     *                                 JoinEntityHelper<Product, PSC, ProductDaoImpl> {
     *     // ... implementation
     * }
     *
     * ProductDaoImpl dao = new ProductDaoImpl();
     * Dao<Product, PSC, ProductDaoImpl> daoInstance = DaoUtil.getDao(dao);
     * // Successfully casts to Dao
     * }</pre>
     *
     * @param <T> the entity type managed by this DAO
     * @param <SB> the SQLBuilder type used to generate SQL scripts (must be one of SQLBuilder.PSC/PAC/PLC)
     * @param <TD> the DAO type
     * @param dao the JoinEntityHelper instance to cast
     * @return the DAO instance cast to Dao
     * @throws UnsupportedOperationException if the DAO does not implement Dao interface
     */
    static <T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> TD getDao(final JoinEntityHelper<T, SB, TD> dao) {
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
     * class ProductDaoImpl implements UncheckedDao<Product, PSC, ProductDaoImpl>,
     *                                 UncheckedJoinEntityHelper<Product, PSC, ProductDaoImpl> {
     *     // ... implementation
     * }
     *
     * ProductDaoImpl dao = new ProductDaoImpl();
     * UncheckedDao<Product, PSC, ProductDaoImpl> daoInstance = DaoUtil.getDao(dao);
     * // Successfully casts to UncheckedDao
     * }</pre>
     *
     * @param <T> the entity type managed by this DAO
     * @param <SB> the SQLBuilder type used to generate SQL scripts (must be one of SQLBuilder.PSC/PAC/PLC)
     * @param <TD> the DAO type
     * @param dao the UncheckedJoinEntityHelper instance to cast
     * @return the DAO instance cast to UncheckedDao
     * @throws UnsupportedOperationException if the DAO does not implement UncheckedDao interface
     */
    static <T, SB extends SQLBuilder, TD extends UncheckedDao<T, SB, TD>> TD getDao(final UncheckedJoinEntityHelper<T, SB, TD> dao) {
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
     * class UserDaoImpl implements UncheckedCrudDao<User, Long, PSC, UserDaoImpl>,
     *                              UncheckedCrudJoinEntityHelper<User, Long, PSC, UserDaoImpl> {
     *     // ... implementation
     * }
     *
     * UserDaoImpl dao = new UserDaoImpl();
     * UncheckedCrudDao<User, Long, PSC, UserDaoImpl> crudDao = DaoUtil.getCrudDao(dao);
     * // Successfully casts to UncheckedCrudDao
     * }</pre>
     *
     * @param <T> the entity type managed by this DAO
     * @param <ID> the ID type of the entity
     * @param <SB> the SQLBuilder type used to generate SQL scripts (must be one of SQLBuilder.PSC/PAC/PLC)
     * @param <TD> the DAO type
     * @param dao the UncheckedCrudJoinEntityHelper instance to cast
     * @return the DAO instance cast to UncheckedCrudDao
     * @throws UnsupportedOperationException if the DAO does not implement UncheckedCrudDao interface
     */
    static <T, ID, SB extends SQLBuilder, TD extends UncheckedCrudDao<T, ID, SB, TD>> TD getCrudDao(final UncheckedCrudJoinEntityHelper<T, ID, SB, TD> dao) {
        if (dao instanceof UncheckedCrudDao) {
            return (TD) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " does not implement UncheckedCrudDao interface");
        }
    }

    /**
     * A consumer that converts exceptions to {@link UncheckedSQLException}.
     * <p>
     * This consumer is used to handle exceptions in asynchronous operations, converting SQL-related
     * exceptions into unchecked exceptions. If the exception is a {@link SQLException} or has a
     * SQLException as its cause, it wraps it in an UncheckedSQLException. Otherwise, it converts
     * the exception to a runtime exception.
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
     * A consumer that throws {@link SQLException} or converts exceptions to runtime exceptions.
     * <p>
     * This consumer is used to handle exceptions in synchronous operations. If the exception is a
     * {@link SQLException} or has a SQLException as its cause, it re-throws the SQLException.
     * Otherwise, it converts the exception to a runtime exception.
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
     * futures.add(asyncExecutor.execute(() -> dao.insert(entity1)));
     * futures.add(asyncExecutor.execute(() -> dao.insert(entity2)));
     * futures.add(asyncExecutor.execute(() -> dao.insert(entity3)));
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
            f.gett().ifFailure(throwUncheckedSQLException);
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
            ret = f.gett();

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
     * futures.add(asyncExecutor.execute(() -> dao.insert(entity1)));
     * futures.add(asyncExecutor.execute(() -> dao.insert(entity2)));
     * futures.add(asyncExecutor.execute(() -> dao.insert(entity3)));
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
            f.gett().ifFailure(throwSQLExceptionAction);
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
            ret = f.gett();

            if (ret.isFailure()) {
                throwSQLExceptionAction.accept(ret.getException());
            }

            result += ret.orElseIfFailure(0);
        }

        return result;
    }

    /**
     * A cache of query preparation functions for DAO interfaces.
     * <p>
     * Maps each DAO class to a tuple containing two functions: one for creating PreparedQuery instances
     * and one for creating NamedQuery instances. This cache avoids the overhead of reflection-based
     * type analysis on every query preparation.
     * </p>
     */
    @SuppressWarnings("rawtypes")
    static final Map<Class<? extends Dao>, Tuple2<Throwables.BiFunction<Collection<String>, Condition, PreparedQuery, SQLException>, Throwables.BiFunction<Collection<String>, Condition, NamedQuery, SQLException>>> daoPrepareQueryFuncPool = new ConcurrentHashMap<>();

    /**
     * Retrieves or creates the query preparation functions for a given DAO instance.
     * <p>
     * This method returns a tuple containing two functions: one for creating PreparedQuery instances
     * and one for creating NamedQuery instances. The functions are determined based on the SQLBuilder
     * type parameter of the DAO interface (PSC, PAC, PLC, or PSB). The results are cached to avoid
     * repeated reflection-based type analysis.
     * </p>
     * <p>
     * The returned functions can be used to build and prepare SQL queries based on select property
     * names and conditions.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get the query preparation functions for a DAO
     * Dao<User, PSC, UserDao> userDao = ...;
     * Tuple2<BiFunction, BiFunction> funcs = DaoUtil.getDaoPreparedQueryFunc(userDao);
     *
     * // Use the PreparedQuery function
     * BiFunction<Collection<String>, Condition, PreparedQuery, SQLException> preparedQueryFunc = funcs._1;
     * Collection<String> selectProps = Arrays.asList("id", "name", "email");
     * Condition condition = Filters.eq("status", "active");
     * PreparedQuery query = preparedQueryFunc.apply(selectProps, condition);
     *
     * // Use the NamedQuery function
     * BiFunction<Collection<String>, Condition, NamedQuery, SQLException> namedQueryFunc = funcs._2;
     * NamedQuery namedQuery = namedQueryFunc.apply(selectProps, condition);
     * }</pre>
     *
     * @param dao the DAO instance for which to get query preparation functions
     * @return a tuple containing two bi-functions: the first for creating PreparedQuery instances,
     *         the second for creating NamedQuery instances
     * @throws IllegalArgumentException if the DAO's SQLBuilder type parameter is not one of
     *         PSC, PAC, PLC, or PSB
     */
    @SuppressWarnings("rawtypes")
    static Tuple2<Throwables.BiFunction<Collection<String>, Condition, PreparedQuery, SQLException>, Throwables.BiFunction<Collection<String>, Condition, NamedQuery, SQLException>> getDaoPreparedQueryFunc(
            final Dao dao) {
        final Class<? extends Dao> daoInterface = dao.getClass();

        Tuple2<Throwables.BiFunction<Collection<String>, Condition, PreparedQuery, SQLException>, Throwables.BiFunction<Collection<String>, Condition, NamedQuery, SQLException>> tp = daoPrepareQueryFuncPool
                .get(daoInterface);

        if (tp == null) {
            final Class<? extends SQLBuilder> sbc = getDaoSQLBuilderClass(daoInterface);

            @SuppressWarnings("deprecation")
            final Class<?> targetEntityClass = dao.targetEntityClass();

            final Throwables.BiFunction<javax.sql.DataSource, SP, PreparedQuery, SQLException> prepareQueryFunc = (dataSource, sp) -> {
                final PreparedQuery query = JdbcUtil.prepareQuery(dataSource, sp.query);

                if (N.notEmpty(sp.parameters)) {
                    boolean noException = false;

                    try {
                        query.setParameters(sp.parameters);

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
                final NamedQuery query = JdbcUtil.prepareNamedQuery(dataSource, sp.query);

                if (N.notEmpty(sp.parameters)) {
                    boolean noException = false;

                    try {
                        query.setParameters(sp.parameters);

                        noException = true;
                    } finally {
                        if (!noException) {
                            query.close();
                        }
                    }
                }

                return query;
            };

            if (PSC.class.isAssignableFrom(sbc)) {
                tp = Tuple.of((selectPropNames, cond) -> {
                    final SP sp = (selectPropNames == null ? PSC.selectFrom(targetEntityClass) : PSC.select(selectPropNames).from(targetEntityClass))
                            .append(cond)
                            .build();

                    return prepareQueryFunc.apply(dao.dataSource(), sp);
                }, (selectPropNames, cond) -> {
                    final SP sp = (selectPropNames == null ? NSC.selectFrom(targetEntityClass) : NSC.select(selectPropNames).from(targetEntityClass))
                            .append(cond)
                            .build();

                    return prepareNamedQueryFunc.apply(dao.dataSource(), sp);
                });
            } else if (PAC.class.isAssignableFrom(sbc)) {
                tp = Tuple.of((selectPropNames, cond) -> {
                    final SP sp = (selectPropNames == null ? PAC.selectFrom(targetEntityClass) : PAC.select(selectPropNames).from(targetEntityClass))
                            .append(cond)
                            .build();

                    return prepareQueryFunc.apply(dao.dataSource(), sp);
                }, (selectPropNames, cond) -> {
                    final SP sp = (selectPropNames == null ? NAC.selectFrom(targetEntityClass) : NAC.select(selectPropNames).from(targetEntityClass))
                            .append(cond)
                            .build();

                    return prepareNamedQueryFunc.apply(dao.dataSource(), sp);
                });
            } else if (PLC.class.isAssignableFrom(sbc)) {
                tp = Tuple.of((selectPropNames, cond) -> {
                    final SP sp = (selectPropNames == null ? PLC.selectFrom(targetEntityClass) : PLC.select(selectPropNames).from(targetEntityClass))
                            .append(cond)
                            .build();

                    return prepareQueryFunc.apply(dao.dataSource(), sp);
                }, (selectPropNames, cond) -> {
                    final SP sp = (selectPropNames == null ? NLC.selectFrom(targetEntityClass) : NLC.select(selectPropNames).from(targetEntityClass))
                            .append(cond)
                            .build();

                    return prepareNamedQueryFunc.apply(dao.dataSource(), sp);
                });
            } else if (PSB.class.isAssignableFrom(sbc)) {
                tp = Tuple.of((selectPropNames, cond) -> {
                    final SP sp = (selectPropNames == null ? PSB.selectFrom(targetEntityClass) : PSB.select(selectPropNames).from(targetEntityClass))
                            .append(cond)
                            .build();

                    return prepareQueryFunc.apply(dao.dataSource(), sp);
                }, (selectPropNames, cond) -> {
                    final SP sp = (selectPropNames == null ? NSB.selectFrom(targetEntityClass) : NSB.select(selectPropNames).from(targetEntityClass))
                            .append(cond)
                            .build();

                    return prepareNamedQueryFunc.apply(dao.dataSource(), sp);
                });
            } else {
                throw new IllegalArgumentException("SQLBuilder type parameter must be SQLBuilder.PSC/PAC/PLC/PSB, but was: " + sbc);
            }

            daoPrepareQueryFuncPool.put(daoInterface, tp);
        }

        return tp;
    }

    private static Class<? extends SQLBuilder> getDaoSQLBuilderClass(final Class<?> daoInterface) {
        final Deque<Type> typesToScan = new ArrayDeque<>();
        final HashSet<Type> visited = new HashSet<>();
        typesToScan.add(daoInterface);

        while (!typesToScan.isEmpty()) {
            final Type type = typesToScan.pollFirst();

            if (type == null || !visited.add(type)) {
                continue;
            }

            final Class<? extends SQLBuilder> sbc = findSQLBuilderClassFromDaoType(type);

            if (sbc != null) {
                return sbc;
            }

            if (type instanceof final Class<?> cls) {
                final Type genericSuperClass = cls.getGenericSuperclass();

                if (genericSuperClass != null && genericSuperClass != Object.class) {
                    typesToScan.add(genericSuperClass);
                }

                for (final Type genericInterface : cls.getGenericInterfaces()) {
                    typesToScan.add(genericInterface);
                }
            } else if (type instanceof final ParameterizedType parameterizedType) {
                typesToScan.add(parameterizedType.getRawType());
            }
        }

        return PSC.class;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends SQLBuilder> findSQLBuilderClassFromDaoType(final Type type) {
        if (!(type instanceof final ParameterizedType parameterizedType)) {
            return null;
        }

        if (!(parameterizedType.getRawType() instanceof final Class<?> rawType)) {
            return null;
        }

        if (!(Dao.class.isAssignableFrom(rawType) || CrudDao.class.isAssignableFrom(rawType) || UncheckedDao.class.isAssignableFrom(rawType))) {
            return null;
        }

        for (final Type typeArgument : parameterizedType.getActualTypeArguments()) {
            final Class<? extends SQLBuilder> sbc = toSQLBuilderClass(typeArgument);

            if (sbc != null) {
                return sbc;
            }
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends SQLBuilder> toSQLBuilderClass(final Type type) {
        if (type instanceof final Class<?> cls) {
            return SQLBuilder.class.isAssignableFrom(cls) && cls != SQLBuilder.class ? (Class<? extends SQLBuilder>) cls : null;
        }

        if (type instanceof final ParameterizedType parameterizedType) {
            return toSQLBuilderClass(parameterizedType.getRawType());
        }

        if (type instanceof final WildcardType wildcardType) {
            for (final Type upperBound : wildcardType.getUpperBounds()) {
                final Class<? extends SQLBuilder> sbc = toSQLBuilderClass(upperBound);

                if (sbc != null) {
                    return sbc;
                }
            }

            return null;
        }

        if (type instanceof final TypeVariable<?> typeVariable) {
            for (final Type bound : typeVariable.getBounds()) {
                final Class<? extends SQLBuilder> sbc = toSQLBuilderClass(bound);

                if (sbc != null) {
                    return sbc;
                }
            }
        }

        return null;
    }

    /**
     * Checks if the given SQL statement is a SELECT query.
     * <p>
     * This method performs a case-insensitive check to determine if the SQL statement starts
     * with the "SELECT" keyword, allowing for leading whitespace. It checks for "select " prefix
     * in lowercase, uppercase, and mixed case formats.
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
     * @param sql the SQL statement to check. Must not be {@code null}
     * @return {@code true} if the SQL is a SELECT query, {@code false} otherwise
     * @throws UnsupportedOperationException if the operation is not supported
     */
    static boolean isSelectQuery(final String sql) throws UnsupportedOperationException {
        return "SELECT".equalsIgnoreCase(getLeadingQueryKeyword(sql));
    }

    /**
     * Checks if the given SQL statement is an INSERT query.
     * <p>
     * This method performs a case-insensitive check to determine if the SQL statement starts
     * with the "INSERT" keyword, allowing for leading whitespace. It checks for "insert " prefix
     * in lowercase, uppercase, and mixed case formats.
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
     * @param sql the SQL statement to check. Must not be {@code null}
     * @return {@code true} if the SQL is an INSERT query, {@code false} otherwise
     * @throws UnsupportedOperationException if the operation is not supported
     */
    static boolean isInsertQuery(final String sql) throws UnsupportedOperationException {
        return "INSERT".equalsIgnoreCase(getLeadingQueryKeyword(sql));
    }

    private static String getLeadingQueryKeyword(final String sql) {
        if (Strings.isEmpty(sql)) {
            return "";
        }

        int index = skipLeadingWhitespaceAndComments(sql, 0);

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

            if (ch == '\'' || ch == '"') {
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
                fromIndex++;

                while (fromIndex < sql.length() && sql.charAt(fromIndex) != '\n' && sql.charAt(fromIndex) != '\r') {
                    fromIndex++;
                }

                continue;
            }

            break;
        }

        return fromIndex;
    }

    private static int skipQuotedLiteral(final String sql, int fromIndex, final char quoteChar) {
        fromIndex++;

        while (fromIndex < sql.length()) {
            if (sql.charAt(fromIndex) == quoteChar) {
                if ((fromIndex + 1 < sql.length()) && sql.charAt(fromIndex + 1) == quoteChar) {
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
