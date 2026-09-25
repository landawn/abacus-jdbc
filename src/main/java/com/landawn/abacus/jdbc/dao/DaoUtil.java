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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.exception.UncheckedInterruptedException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.JoinInfo;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.ExceptionUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Result;
import com.landawn.abacus.util.Seid;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.function.Function;

/**
 * Internal utility class providing helper methods for DAO operations.
 * <p>
 * This class contains static utility methods used internally by the DAO framework to support
 * various operations including:
 * <ul>
 *   <li>Capability detection — determining which optional DAO interfaces (for example
 *       {@link CrudReadOps}, {@link JoinEntityReadOps}) a given DAO
 *       interface extends</li>
 *   <li>Method classification — recognizing whether a method's declaring class belongs to the base
 *       DAO, CRUD DAO, or join-entity-helper families, used to drive proxy-based dispatch</li>
 *   <li>ID handling — extraction of single and composite IDs from entities (see
 *       {@link #extractId(Object, List, BeanInfo)} and {@link #createIdExtractor(List, BeanInfo)})
 *       and client-side ID generation (see {@link #generateId(DaoBase)})</li>
 *   <li>Refresh support — computing the set of properties to select so that ID columns are always
 *       included (see {@link #getRefreshSelectPropNames(Collection, List)})</li>
 *   <li>DAO type casting and validation — narrowing join-entity helpers to their backing
 *       {@link ReadOps}/{@link CrudReadOps} (and unchecked) views</li>
 *   <li>Asynchronous operation completion and result aggregation — joining batches of futures,
 *       surfacing the first failure as a checked or unchecked SQL exception, and attaching later
 *       failures as suppressed exceptions</li>
 *   <li>Join metadata retrieval — looking up {@link JoinInfo} for an entity's join properties</li>
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
public final class DaoUtil {
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private DaoUtil() {
        // utility class - prevent instantiation.
    }

    /**
     * Returns whether the specified DAO interface supports DAO result caching.
     *
     * @param daoInterface the DAO interface to inspect.
     * @return {@code true} if {@code daoInterface} extends {@link NonUpdateDao} or {@link ReadOnlyDao}
     *         (and therefore cannot perform update/delete operations that would invalidate cached rows);
     *         otherwise {@code false}.
     * @throws IllegalArgumentException if {@code daoInterface} is {@code null}
     */
    public static boolean isCacheable(final Class<?> daoInterface) throws IllegalArgumentException {
        N.checkArgNotNull(daoInterface, cs.daoInterface);

        return NonUpdateDao.class.isAssignableFrom(daoInterface) || ReadOnlyDao.class.isAssignableFrom(daoInterface);
    }

    /**
     * Returns whether the specified DAO interface exposes readable CRUD operations.
     *
     * @param daoInterface the DAO interface to inspect.
     * @return {@code true} if {@code daoInterface} extends {@link CrudReadOps}; otherwise {@code false}.
     * @throws IllegalArgumentException if {@code daoInterface} is {@code null}
     */
    public static boolean isCrudReadOps(final Class<?> daoInterface) throws IllegalArgumentException {
        N.checkArgNotNull(daoInterface, cs.daoInterface);

        return CrudReadOps.class.isAssignableFrom(daoInterface);
    }

    /**
     * Returns the {@code idExtractor()} declared by the given DAO when it is a CRUD-insert-capable DAO
     * (any {@link CrudInsertOps} variant, including the non-update composite), otherwise {@code null}.
     *
     * @param dao the DAO instance to inspect.
     * @return the DAO's declared id extractor, or {@code null} if the DAO has none.
     */
    @SuppressWarnings("rawtypes")
    public static Jdbc.BiRowMapper getDeclaredIdExtractor(final DaoBase dao) {
        return dao instanceof CrudInsertOps ? ((CrudInsertOps) dao).idExtractor() : null;
    }

    /**
     * Returns whether the specified DAO interface exposes readable CRUD join-entity helper operations.
     *
     * @param daoInterface the DAO interface to inspect.
     * @return {@code true} if {@code daoInterface} extends {@link CrudJoinEntityReadOps}; otherwise {@code false}.
     * @throws IllegalArgumentException if {@code daoInterface} is {@code null}
     */
    public static boolean isCrudJoinEntityReadOps(final Class<?> daoInterface) throws IllegalArgumentException {
        N.checkArgNotNull(daoInterface, cs.daoInterface);

        return CrudJoinEntityReadOps.class.isAssignableFrom(daoInterface);
    }

    /**
     * Returns whether the specified DAO interface exposes readable join-entity helper operations.
     *
     * @param daoInterface the DAO interface to inspect.
     * @return {@code true} if {@code daoInterface} extends {@link JoinEntityReadOps}; otherwise {@code false}.
     * @throws IllegalArgumentException if {@code daoInterface} is {@code null}
     */
    public static boolean isJoinEntityReadOps(final Class<?> daoInterface) throws IllegalArgumentException {
        N.checkArgNotNull(daoInterface, cs.daoInterface);

        return JoinEntityReadOps.class.isAssignableFrom(daoInterface);
    }

    /**
     * Returns whether the specified DAO interface exposes unchecked readable operations.
     *
     * @param daoInterface the DAO interface to inspect.
     * @return {@code true} if {@code daoInterface} extends {@link UncheckedReadOps}; otherwise {@code false}.
     * @throws IllegalArgumentException if {@code daoInterface} is {@code null}
     */
    public static boolean isUncheckedReadOps(final Class<?> daoInterface) throws IllegalArgumentException {
        N.checkArgNotNull(daoInterface, cs.daoInterface);

        return UncheckedReadOps.class.isAssignableFrom(daoInterface);
    }

    /**
     * Returns whether methods declared by the specified class are handled as base DAO operations.
     *
     * @param declaringClass the declaring class of a DAO method.
     * @return {@code true} if methods declared by {@code declaringClass} are base DAO operations; otherwise {@code false}.
     * @throws IllegalArgumentException if {@code declaringClass} is {@code null}
     */
    public static boolean isDaoOperationDeclaringClass(final Class<?> declaringClass) throws IllegalArgumentException {
        N.checkArgNotNull(declaringClass, cs.declaringClass);

        return declaringClass.equals(Dao.class) || declaringClass.equals(UncheckedDao.class) || declaringClass.equals(ReadOps.class)
                || declaringClass.equals(InsertOps.class) || declaringClass.equals(UpdateOps.class) || declaringClass.equals(DeleteOps.class)
                || declaringClass.equals(UncheckedReadOps.class) || declaringClass.equals(UncheckedInsertOps.class)
                || declaringClass.equals(UncheckedUpdateOps.class) || declaringClass.equals(UncheckedDeleteOps.class) || declaringClass.equals(DaoBase.class)
                || declaringClass.equals(UncheckedDaoBase.class);
    }

    /**
     * Returns whether methods declared by the specified class are handled as CRUD DAO operations.
     *
     * @param declaringClass the declaring class of a DAO method.
     * @return {@code true} if methods declared by {@code declaringClass} are CRUD DAO operations; otherwise {@code false}.
     * @throws IllegalArgumentException if {@code declaringClass} is {@code null}
     */
    public static boolean isCrudDaoOperationDeclaringClass(final Class<?> declaringClass) throws IllegalArgumentException {
        N.checkArgNotNull(declaringClass, cs.declaringClass);

        return declaringClass.equals(CrudDao.class) || declaringClass.equals(UncheckedCrudDao.class) || declaringClass.equals(CrudReadOps.class)
                || declaringClass.equals(CrudInsertOps.class) || declaringClass.equals(CrudUpdateOps.class) || declaringClass.equals(CrudDeleteOps.class)
                || declaringClass.equals(UncheckedCrudReadOps.class) || declaringClass.equals(UncheckedCrudInsertOps.class)
                || declaringClass.equals(UncheckedCrudUpdateOps.class) || declaringClass.equals(UncheckedCrudDeleteOps.class);
    }

    /**
     * Returns whether methods declared by the specified class are handled as join-entity helper operations.
     *
     * @param declaringClass the declaring class of a DAO method.
     * @return {@code true} if methods declared by {@code declaringClass} are join-entity helper operations; otherwise {@code false}.
     * @throws IllegalArgumentException if {@code declaringClass} is {@code null}
     */
    public static boolean isJoinEntityHelperDeclaringClass(final Class<?> declaringClass) throws IllegalArgumentException {
        N.checkArgNotNull(declaringClass, cs.declaringClass);

        return declaringClass.equals(JoinEntityReadOps.class) || declaringClass.equals(JoinEntityDeleteOps.class)
                || declaringClass.equals(UncheckedJoinEntityReadOps.class) || declaringClass.equals(UncheckedJoinEntityDeleteOps.class)
                || declaringClass.equals(JoinEntityHelper.class) || declaringClass.equals(UncheckedJoinEntityHelper.class);
    }

    /**
     * Generates a new ID for entity insertion by delegating to {@link CrudInsertOps#generateId()}.
     * <p>
     * The default {@code generateId()} implementation throws {@link UnsupportedOperationException};
     * a value is only produced when the DAO overrides it with a client-side ID generation strategy
     * (for example a UUID or sequence). ID generation is normally handled by the database, so this
     * path is rarely used.
     * </p>
     *
     * @param dao the DAO used to generate the identifier; must implement {@link CrudInsertOps}.
     * @return the generated identifier.
     * @throws IllegalArgumentException if {@code dao} is {@code null}
     * @throws ClassCastException if {@code dao} does not implement {@link CrudInsertOps}.
     * @throws UnsupportedOperationException if {@code dao} does not override {@link CrudInsertOps#generateId()}.
     * @throws SQLException if the DAO's overriding ID generator fails while accessing the database.
     */
    @SuppressWarnings({ "rawtypes", "unchecked", "deprecation" })
    public static Object generateId(final DaoBase dao) throws IllegalArgumentException, ClassCastException, UnsupportedOperationException, SQLException {
        N.checkArgNotNull(dao, cs.dao);

        return ((CrudInsertOps) dao).generateId();
    }

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
     * List<String> compositeIdPropNames = Arrays.asList("orderId", "lineNumber");
     * Seid compositeId = DaoUtil.extractId(orderLine, compositeIdPropNames, orderLineBeanInfo);
     * // compositeId contains both orderId=100 and lineNumber=5
     * }</pre>
     *
     * @param <T> the entity type
     * @param <ID> the ID type of the entity
     * @param entity the entity instance from which to extract the ID. Must not be {@code null}.
     * @param idPropNameList the list of ID property names. Must not be {@code null} or empty.
     * @param entityInfo the bean information for the entity class
     * @return the extracted ID value (simple value for single ID, {@link Seid} for composite ID)
     * @throws IllegalArgumentException if {@code entity}, {@code idPropNameList}, or
     *                                  {@code entityInfo} is {@code null}, if
     *                                  {@code idPropNameList} is empty, or if it contains a {@code null} name
     * @throws NullPointerException if a name in {@code idPropNameList} is not a property described by {@code entityInfo}
     */
    @SuppressWarnings({ "deprecation", "unchecked" })
    static <T, ID> ID extractId(final T entity, final List<String> idPropNameList, final BeanInfo entityInfo)
            throws IllegalArgumentException, NullPointerException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotEmpty(idPropNameList, cs.idPropNameList);
        N.checkArgNotNull(entityInfo, cs.entityInfo);

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
     * List<Long> ids = users.stream().map(idExtractor).toList();
     *
     * // Create an extractor for composite ID
     * List<String> compositeIdPropNames = Arrays.asList("orderId", "lineNumber");
     * Function<OrderLine, Seid> compositeIdExtractor = DaoUtil.createIdExtractor(compositeIdPropNames, orderLineBeanInfo);
     * Seid id = compositeIdExtractor.apply(orderLine);
     * }</pre>
     *
     * @param <T> the entity type
     * @param <ID> the ID type of the entity
     * @param idPropNameList the list of ID property names. Must not be {@code null} or empty.
     * @param entityInfo the bean information for the entity class
     * @return a function that extracts ID values from entities
     * @throws IllegalArgumentException if {@code idPropNameList} or {@code entityInfo} is
     *                                  {@code null}, if {@code idPropNameList} is empty, or if it contains a {@code null} name
     * @throws NullPointerException if {@code idPropNameList} holds a single name that is not a property described by {@code entityInfo}
     *                              (for a composite id, an unknown name fails only when the returned function is applied)
     */
    @SuppressWarnings({ "deprecation", "unchecked" })
    static <T, ID> Function<T, ID> createIdExtractor(final List<String> idPropNameList, final BeanInfo entityInfo)
            throws IllegalArgumentException, NullPointerException {
        N.checkArgNotEmpty(idPropNameList, cs.idPropNameList);
        N.checkArgNotNull(entityInfo, cs.entityInfo);

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
     * Builds the {@code WHERE} condition selecting the rows whose IDs are contained in the given (sub-)collection,
     * dispatching on the shape of the IDs: {@link EntityId}s, {@link Map}s, a single-column id (rendered as an
     * {@code IN} clause with an {@code IS NULL} branch when needed), or multi-column ids. Shared by
     * {@link CrudReadOps#batchGet(Collection, Collection, int)} and
     * {@link CrudReadOps#count(Collection)}.
     *
     * @param ids the (batch of) IDs to match
     * @param idPropNameList the id property names of the entity
     * @param isEntityId whether the IDs are {@link EntityId} instances
     * @param isMap whether the IDs are {@link Map} instances
     * @return a condition matching any row whose id is in {@code ids}
     * @throws IllegalArgumentException if {@code ids} is empty; if {@code isEntityId} and an element is {@code null} or is an
     *                                  {@link EntityId} with no keys; if {@code isMap} and the non-null elements are not all
     *                                  {@link Map}s, or one of them is empty or has a key that is not a non-blank {@link String};
     *                                  or if {@code idPropNameList} holds more than one name and either it names a property that
     *                                  is not readable from the ids, a non-null element of {@code ids} is a {@link Map}, or every
     *                                  element of {@code ids} is {@code null}
     * @throws ClassCastException if {@code isEntityId} and a non-null element of {@code ids} is not an {@link EntityId}
     */
    @SuppressWarnings("unchecked")
    static Condition idsToCondition(final Collection<?> ids, final List<String> idPropNameList, final boolean isEntityId, final boolean isMap)
            throws IllegalArgumentException, ClassCastException {
        if (isEntityId) {
            return Filters.idToCond((Collection<? extends EntityId>) ids);
        } else if (isMap) {
            return Filters.anyOfAllEqual(ids);
        } else if (idPropNameList.size() == 1) {
            return singlePropValuesToCondition(idPropNameList.get(0), ids);
        } else {
            return Filters.anyOfAllEqual(ids, idPropNameList);
        }
    }

    /**
     * Builds a condition matching any supplied value for one property while preserving SQL
     * {@code NULL} equality semantics. A plain {@code IN (..., NULL)} predicate never matches a
     * null column, so a null value is expressed as a separate {@link Filters#isNull(String)} branch.
     *
     * @param propName the property to match
     * @param values the non-empty values to match
     * @return an {@code IN}, {@code IS NULL}, or combined {@code OR} condition as appropriate
     * @throws IllegalArgumentException if {@code propName} or {@code values} is {@code null} or empty.
     */
    static Condition singlePropValuesToCondition(final String propName, final Collection<?> values) throws IllegalArgumentException {
        N.checkArgNotEmpty(propName, cs.propName);
        N.checkArgNotEmpty(values, cs.values);

        final List<Object> nonNullValues = new ArrayList<>(values);
        final boolean containsNull = nonNullValues.removeIf(value -> value == null);

        if (!containsNull) {
            return Filters.in(propName, nonNullValues);
        } else if (nonNullValues.isEmpty()) {
            return Filters.isNull(propName);
        } else {
            return Filters.in(propName, nonNullValues).or(Filters.isNull(propName));
        }
    }

    /**
     * A consumer that configures a {@link PreparedStatement} for handling large query results efficiently.
     * Sets the fetch direction to {@link ResultSet#FETCH_FORWARD} and raises the fetch size to
     * {@link JdbcUtil#DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET} (a larger pre-configured size is preserved,
     * matching {@code JdbcUtil.stmtSetterForBigQueryResult}).
     */
    static final Throwables.Consumer<PreparedStatement, SQLException> stmtSetterForBigQueryResult = stmt -> {
        stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

        if (stmt.getFetchSize() < JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET) {
            stmt.setFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET);
        }
    };

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
     * Collection<String> propsMissingId = Arrays.asList("name", "email");
     * List<String> requiredIdProps = Arrays.asList("id");
     * Collection<String> augmentedResult = DaoUtil.getRefreshSelectPropNames(propsMissingId, requiredIdProps);
     * // augmentedResult contains: "name", "email", "id" (HashSet, iteration order not guaranteed)
     * }</pre>
     *
     * @param propNamesToRefresh the collection of property names to refresh; may be {@code null}
     * @param idPropNameList the list of ID property names that must be included
     * @return a new {@link HashSet} of the ID properties when {@code propNamesToRefresh} is {@code null};
     *         the original collection if it already contains all ID properties; otherwise a new
     *         {@link HashSet} containing both the requested properties and all ID properties
     * @throws NullPointerException if {@code idPropNameList} is {@code null}
     */
    static Collection<String> getRefreshSelectPropNames(final Collection<String> propNamesToRefresh, final List<String> idPropNameList)
            throws NullPointerException {
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
     * Casts a {@link CrudJoinEntityReadOps} to a {@link CrudReadOps} instance.
     * <p>
     * This method is used internally to ensure type safety when working with DAO instances
     * that implement both CrudJoinEntityReadOps and CrudReadOps interfaces. It validates that
     * the provided DAO actually extends CrudReadOps before performing the cast.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Typical usage in internal DAO operations
     * interface UserDao extends CrudDao<User, Long, UserDao>,
     *                           CrudJoinEntityHelper<User, Long, UserDao> {
     * }
     *
     * UserDao dao = JdbcUtil.createDao(UserDao.class, dataSource);
     * CrudReadOps<User, Long, UserDao> crudDao = DaoUtil.getCrudReadOps(dao);
     * // Successfully casts to CrudReadOps
     * }</pre>
     *
     * @param <T> the entity type managed by this DAO
     * @param <ID> the ID type of the entity
     * @param <TD> the DAO type
     * @param dao the CrudJoinEntityReadOps instance to cast
     * @return the DAO instance cast to CrudReadOps
     * @throws NullPointerException if {@code dao} is {@code null}
     * @throws UnsupportedOperationException if the DAO does not implement CrudReadOps interface.
     */
    static <T, ID, TD extends DaoBase<T, TD>> CrudReadOps<T, ID, TD> getCrudReadOps(final CrudJoinEntityReadOps<T, ID, TD> dao)
            throws NullPointerException, UnsupportedOperationException {
        if (dao instanceof CrudReadOps) {
            return (CrudReadOps<T, ID, TD>) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " does not implement CrudReadOps interface"); //NOSONAR
        }
    }

    /**
     * Casts a {@link JoinEntityBase} join-entity helper to its {@link ReadOps} view.
     * <p>
     * This method is used internally to ensure type safety when working with DAO instances
     * that implement both a join-entity helper interface and ReadOps. It validates that the provided
     * DAO actually extends ReadOps before performing the cast.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Typical usage in internal DAO operations
     * interface ProductDao extends Dao<Product, ProductDao>,
     *                              JoinEntityHelper<Product, ProductDao> {
     * }
     *
     * ProductDao dao = JdbcUtil.createDao(ProductDao.class, dataSource);
     * ReadOps<Product, ProductDao> daoInstance = DaoUtil.getReadOps(dao);
     * // Successfully casts to ReadOps
     * }</pre>
     *
     * @param <T> the entity type managed by this DAO
     * @param <TD> the DAO type
     * @param dao the join-entity helper instance to cast
     * @return the DAO instance cast to ReadOps
     * @throws NullPointerException if {@code dao} is {@code null}
     * @throws UnsupportedOperationException if the DAO does not implement ReadOps interface.
     */
    static <T, TD extends DaoBase<T, TD>> ReadOps<T, TD> getReadOps(final JoinEntityBase<T, TD> dao)
            throws NullPointerException, UnsupportedOperationException {
        if (dao instanceof ReadOps) {
            return (ReadOps<T, TD>) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " does not implement ReadOps interface");
        }
    }

    /**
     * Casts an {@link UncheckedJoinEntityReadOps} to an {@link UncheckedReadOps} instance.
     * <p>
     * This method is used internally to ensure type safety when working with unchecked DAO instances
     * that implement both UncheckedJoinEntityReadOps and UncheckedReadOps interfaces. It validates that
     * the provided DAO actually extends UncheckedReadOps before performing the cast.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Typical usage in internal DAO operations
     * interface ProductDao extends UncheckedDao<Product, ProductDao>,
     *                              UncheckedJoinEntityHelper<Product, ProductDao> {
     * }
     *
     * ProductDao dao = JdbcUtil.createDao(ProductDao.class, dataSource);
     * UncheckedReadOps<Product, ProductDao> daoInstance = DaoUtil.getReadOps(dao);
     * // Successfully casts to UncheckedReadOps
     * }</pre>
     *
     * @param <T> the entity type managed by this DAO
     * @param <TD> the DAO type
     * @param dao the UncheckedJoinEntityReadOps instance to cast
     * @return the DAO instance cast to UncheckedReadOps
     * @throws NullPointerException if {@code dao} is {@code null}
     * @throws UnsupportedOperationException if the DAO does not implement UncheckedReadOps interface.
     */
    static <T, TD extends UncheckedDaoBase<T, TD>> UncheckedReadOps<T, TD> getReadOps(final UncheckedJoinEntityReadOps<T, TD> dao)
            throws NullPointerException, UnsupportedOperationException {
        if (dao instanceof UncheckedReadOps) {
            return (UncheckedReadOps<T, TD>) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " does not implement UncheckedReadOps interface");
        }
    }

    /**
     * Casts an {@link UncheckedCrudJoinEntityReadOps} to an {@link UncheckedCrudReadOps} instance.
     * <p>
     * This method is used internally to ensure type safety when working with unchecked CRUD DAO instances
     * that implement both UncheckedCrudJoinEntityReadOps and UncheckedCrudReadOps interfaces. It validates that
     * the provided DAO actually extends UncheckedCrudReadOps before performing the cast.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Typical usage in internal DAO operations
     * interface UserDao extends UncheckedCrudDao<User, Long, UserDao>,
     *                           UncheckedCrudJoinEntityHelper<User, Long, UserDao> {
     * }
     *
     * UserDao dao = JdbcUtil.createDao(UserDao.class, dataSource);
     * UncheckedCrudReadOps<User, Long, UserDao> crudDao = DaoUtil.getCrudReadOps(dao);
     * // Successfully casts to UncheckedCrudReadOps
     * }</pre>
     *
     * @param <T> the entity type managed by this DAO
     * @param <ID> the ID type of the entity
     * @param <TD> the DAO type
     * @param dao the UncheckedCrudJoinEntityReadOps instance to cast
     * @return the DAO instance cast to UncheckedCrudReadOps
     * @throws NullPointerException if {@code dao} is {@code null}
     * @throws UnsupportedOperationException if the DAO does not implement UncheckedCrudReadOps interface.
     */
    static <T, ID, TD extends UncheckedDaoBase<T, TD>> UncheckedCrudReadOps<T, ID, TD> getCrudReadOps(final UncheckedCrudJoinEntityReadOps<T, ID, TD> dao)
            throws NullPointerException, UnsupportedOperationException {
        if (dao instanceof UncheckedCrudReadOps) {
            return (UncheckedCrudReadOps<T, ID, TD>) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " does not implement UncheckedCrudReadOps interface");
        }
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
     * @throws IllegalArgumentException if an argument is {@code null} or a join annotation is invalid
     * @throws IllegalStateException if generated join SQL lacks a clause required by its query plans
     */
    static Map<String, JoinInfo> getEntityJoinInfo(final Class<?> targetDaoInterface, final Class<?> targetEntityClass, final String targetTableName)
            throws IllegalArgumentException, IllegalStateException {
        return JoinInfo.getEntityJoinInfo(targetDaoInterface, targetEntityClass, targetTableName);
    }

    /**
     * Returns a collection containing the source select property names plus any source property names
     * required by the join-entity property of the specified join entity class, so that the columns
     * needed to load the join entity are always selected.
     *
     * @param dao the join-entity DAO whose join metadata is used.
     * @param sourceSelectPropNames the source property names to select.
     * @param joinEntityClass the join entity class whose required source property names are included.
     * @return {@code sourceSelectPropNames} (possibly unchanged) with the required source join property
     *         names added; null or empty selections are returned unchanged because they select all default properties.
     * @throws NullPointerException if {@code dao} is {@code null} and {@code sourceSelectPropNames} is not empty
     * @throws IllegalArgumentException if metadata needed for a nonempty source selection is invalid, or a requested join entity class is {@code null}
     * @throws IllegalStateException if generated join SQL lacks a clause required by its query plans
     */
    @SuppressWarnings("deprecation")
    static Collection<String> includeSourceJoinPropNames(final JoinEntityBase<?, ?> dao, final Collection<String> sourceSelectPropNames,
            final Class<?> joinEntityClass) throws NullPointerException, IllegalArgumentException, IllegalStateException {
        if (N.isEmpty(sourceSelectPropNames)) {
            return sourceSelectPropNames;
        }

        final Map<String, JoinInfo> entityJoinInfo = getEntityJoinInfo(dao.targetDaoInterface(), dao.targetEntityClass(), dao.targetTableName());
        final List<String> joinPropNames = JoinInfo.getJoinEntityPropNamesByType(dao.targetDaoInterface(), dao.targetEntityClass(), dao.targetTableName(),
                joinEntityClass);

        if (joinPropNames.isEmpty()) {
            return sourceSelectPropNames;
        }

        Collection<String> result = sourceSelectPropNames;

        for (final String joinPropName : joinPropNames) {
            result = includeSourceJoinPropNames(result, entityJoinInfo.get(joinPropName));
        }

        return result;
    }

    /**
     * Returns a collection containing the source select property names plus the source property names
     * required by the join-entity properties of each of the specified join entity classes, so that the
     * columns needed to load those join entities are always selected.
     *
     * @param dao the join-entity DAO whose join metadata is used.
     * @param sourceSelectPropNames the source property names to select.
     * @param joinEntityClasses the join entity classes whose required source property names are included.
     * @return {@code sourceSelectPropNames} (possibly unchanged) with the required source join property
     *         names added; returned unchanged if the selection is null or empty (all default properties),
     *         or {@code joinEntityClasses} is empty.
     * @throws NullPointerException if {@code dao} is {@code null}, {@code sourceSelectPropNames} is not empty and {@code joinEntityClasses}
     *                              is not empty
     * @throws IllegalArgumentException if metadata needed for a nonempty source selection is invalid, or a requested join entity class is {@code null}
     * @throws IllegalStateException if generated join SQL lacks a clause required by its query plans
     */
    static Collection<String> includeSourceJoinPropNames(final JoinEntityBase<?, ?> dao, final Collection<String> sourceSelectPropNames,
            final Collection<Class<?>> joinEntityClasses) throws NullPointerException, IllegalArgumentException, IllegalStateException {
        if (N.isEmpty(sourceSelectPropNames) || N.isEmpty(joinEntityClasses)) {
            return sourceSelectPropNames;
        }

        Collection<String> result = sourceSelectPropNames;

        for (final Class<?> joinEntityClass : joinEntityClasses) {
            result = includeSourceJoinPropNames(dao, result, joinEntityClass);
        }

        return result;
    }

    /**
     * Returns a collection containing the source select property names plus the source property names
     * required by every join-entity property of the DAO's target entity, so that the columns needed to
     * load any join entity are always selected.
     *
     * @param dao the join-entity DAO whose join metadata is used.
     * @param sourceSelectPropNames the source property names to select.
     * @return {@code sourceSelectPropNames} (possibly unchanged) with all required source join property
     *         names added; null or empty selections are returned unchanged because they select all default properties.
     * @throws NullPointerException if {@code dao} is {@code null} and {@code sourceSelectPropNames} is not empty
     * @throws IllegalArgumentException if metadata needed for a nonempty source selection is invalid
     * @throws IllegalStateException if generated join SQL lacks a clause required by its query plans
     */
    @SuppressWarnings("deprecation")
    static Collection<String> includeAllSourceJoinPropNames(final JoinEntityBase<?, ?> dao, final Collection<String> sourceSelectPropNames)
            throws NullPointerException, IllegalArgumentException, IllegalStateException {
        if (N.isEmpty(sourceSelectPropNames)) {
            return sourceSelectPropNames;
        }

        Collection<String> result = sourceSelectPropNames;

        for (final JoinInfo joinInfo : getEntityJoinInfo(dao.targetDaoInterface(), dao.targetEntityClass(), dao.targetTableName()).values()) {
            result = includeSourceJoinPropNames(result, joinInfo);
        }

        return result;
    }

    /**
     * Returns a collection containing the source select property names plus any source property names
     * of the specified {@link JoinInfo} that are not already present.
     *
     * @param sourceSelectPropNames the source property names to select; must not be {@code null}.
     * @param joinInfo the join metadata whose source property names are included.
     * @return {@code sourceSelectPropNames} unchanged if it already contains all required source property
     *         names; otherwise a new collection with the missing names added.
     */
    private static Collection<String> includeSourceJoinPropNames(final Collection<String> sourceSelectPropNames, final JoinInfo joinInfo) {
        List<String> result = null;

        for (final String sourcePropName : joinInfo.sourcePropNames()) {
            if (!sourceSelectPropNames.contains(sourcePropName)) {
                if (result == null) {
                    result = new ArrayList<>(sourceSelectPropNames);
                }

                result.add(sourcePropName);
            }
        }

        return result == null ? sourceSelectPropNames : result;
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
     * @throws IllegalArgumentException if an argument is {@code null} or a join annotation is invalid
     * @throws IllegalStateException if generated join SQL lacks a clause required by its query plans
     */
    static List<String> getJoinEntityPropNamesByType(final Class<?> targetDaoInterface, final Class<?> targetEntityClass, final String targetTableName,
            final Class<?> joinEntityClass) throws IllegalArgumentException, IllegalStateException {
        return JoinInfo.getJoinEntityPropNamesByType(targetDaoInterface, targetEntityClass, targetTableName, joinEntityClass);
    }

    /**
     * A consumer that converts an exception to {@link UncheckedSQLException} (or another runtime exception) and throws it.
     * <p>
     * Used by {@link #uncheckedComplete(List)} and {@link #uncheckedCompleteSum(List)} to surface
     * failures from completed futures. An existing {@link UncheckedSQLException} is rethrown
     * unchanged. A {@link SQLException}, or an exception whose cause is one, is wrapped in an
     * {@code UncheckedSQLException}; otherwise the exception is converted to a runtime exception
     * via {@link ExceptionUtil#toRuntimeException}. Later collected failures remain suppressed.
     * This consumer never returns normally when invoked — it always throws.
     * </p>
     */
    static final Throwables.Consumer<? super Exception, UncheckedSQLException> throwUncheckedSQLException = e -> {
        if (e instanceof UncheckedSQLException) {
            throw (UncheckedSQLException) e;
        } else if (e instanceof SQLException) {
            throw new UncheckedSQLException((SQLException) e);
        } else if (e.getCause() instanceof SQLException) {
            throw new UncheckedSQLException(transferSuppressed(e, (SQLException) e.getCause()));
        } else {
            throw ExceptionUtil.toRuntimeException(e, true);
        }
    };

    /**
     * A consumer that re-throws an exception as a checked {@link SQLException} when possible, or as a runtime exception otherwise.
     * <p>
     * Used by {@link #complete(List)} and {@link #completeSum(List)} to surface failures from
     * completed futures. If the exception is a {@link SQLException} or has a SQLException as its
     * cause, the SQLException is re-thrown. Suppressed failures collected on an enclosing exception
     * are transferred to that SQLException. Otherwise, the exception is converted to a runtime
     * exception via {@link ExceptionUtil#toRuntimeException}. This consumer never returns normally
     * when invoked — it always throws.
     * </p>
     */
    static final Throwables.Consumer<? super Exception, SQLException> throwSQLExceptionAction = e -> {
        if (e instanceof SQLException) {
            throw (SQLException) e;
        } else if (e.getCause() instanceof SQLException) {
            throw transferSuppressed(e, (SQLException) e.getCause());
        } else {
            throw ExceptionUtil.toRuntimeException(e, true);
        }
    };

    /**
     * Completes all futures in the list and propagates the first failure as an unchecked exception.
     * <p>
     * This method waits for all futures to complete and checks for failures. SQL-related failures
     * are translated to {@link UncheckedSQLException}; other failures retain their runtime exception
     * semantics. Later failures are attached as suppressed exceptions to the first failure.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Execute multiple async operations and wait for completion
     * List<ContinuableFuture<Void>> futures = new ArrayList<>();
     * futures.add(ContinuableFuture.run(() -> dao.save(entity1)));
     * futures.add(ContinuableFuture.run(() -> dao.save(entity2)));
     * futures.add(ContinuableFuture.run(() -> dao.save(entity3)));
     *
     * // Wait for all operations to complete
     * DaoUtil.uncheckedComplete(futures);
     * // SQL-related failures are reported as UncheckedSQLException
     * }</pre>
     *
     * @param futures the list of futures to complete. Must not be {@code null}.
     * @throws NullPointerException if {@code futures} is {@code null} or contains a {@code null} future
     * @throws UncheckedSQLException if the first failed future has a SQL-related exception
     * @throws UncheckedInterruptedException if the first collected failure is an interruption while waiting for a future or executing its action
     */
    static void uncheckedComplete(final List<ContinuableFuture<Void>> futures)
            throws NullPointerException, UncheckedSQLException, UncheckedInterruptedException {
        Exception firstException = null;

        for (final ContinuableFuture<Void> f : futures) {
            final Result<Void, Exception> ret = f.getAsResult();

            if (ret.isFailure()) {
                firstException = collectFailure(firstException, ret.getException());
            }
        }

        if (firstException != null) {
            throwUncheckedSQLException.accept(firstException);
        }
    }

    /**
     * Completes all futures in the list, sums their integer results, and propagates the first failure as an unchecked exception.
     * <p>
     * This method waits for all futures to complete, collecting their integer results and summing them.
     * SQL-related failures are translated to {@link UncheckedSQLException}; other failures retain their
     * runtime exception semantics. Later failures are attached as suppressed exceptions to the first failure.
     * This is typically used for batch update/insert/delete operations where the return value indicates
     * the number of affected rows.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Execute multiple async update operations and sum affected rows
     * List<ContinuableFuture<Integer>> futures = new ArrayList<>();
     * futures.add(ContinuableFuture.call(() -> dao.update(entity1)));
     * futures.add(ContinuableFuture.call(() -> dao.update(entity2)));
     * futures.add(ContinuableFuture.call(() -> dao.update(entity3)));
     *
     * // Wait for all operations and get total affected rows
     * int totalAffectedRows = DaoUtil.uncheckedCompleteSum(futures);
     * // totalAffectedRows = sum of all affected rows
     * // SQL-related failures are reported as UncheckedSQLException
     * }</pre>
     *
     * @param futures the list of futures returning integer values to complete and sum. Must not be {@code null}.
     * @return the sum of all integer results from the futures
     * @throws NullPointerException if {@code futures} is {@code null}, contains a {@code null} future, or a successful future returns {@code null}
     * @throws UncheckedSQLException if the first failed future has a SQL-related exception
     * @throws UncheckedInterruptedException if the first collected failure is an interruption while waiting for a future or executing its action
     * @throws ArithmeticException if no future failed and the sum overflows an {@code int}.
     */
    static int uncheckedCompleteSum(final List<ContinuableFuture<Integer>> futures)
            throws NullPointerException, UncheckedSQLException, UncheckedInterruptedException, ArithmeticException {
        long result = 0;
        Result<Integer, Exception> ret = null;
        Exception firstException = null;

        for (final ContinuableFuture<Integer> f : futures) {
            ret = f.getAsResult();

            if (ret.isFailure()) {
                firstException = collectFailure(firstException, ret.getException());
            } else {
                result += ret.orElseIfFailure(0);
            }
        }

        if (firstException != null) {
            throwUncheckedSQLException.accept(firstException);
        }

        return Math.toIntExact(result);
    }

    /**
     * Completes all futures in the list and propagates the first failure.
     * <p>
     * This method waits for all futures to complete and checks for failures. SQL-related failures
     * are propagated as a checked {@link SQLException}; other failures retain their runtime exception
     * semantics. Later failures are attached as suppressed exceptions to the first failure.
     * This is the checked exception variant of {@link #uncheckedComplete(List)}.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Execute multiple async operations and wait for completion (checked exception)
     * List<ContinuableFuture<Void>> futures = new ArrayList<>();
     * futures.add(ContinuableFuture.run(() -> dao.save(entity1)));
     * futures.add(ContinuableFuture.run(() -> dao.save(entity2)));
     * futures.add(ContinuableFuture.run(() -> dao.save(entity3)));
     *
     * // Wait for all operations to complete
     * DaoUtil.complete(futures);
     * // SQL-related failures are reported as SQLException
     * }</pre>
     *
     * @param futures the list of futures to complete. Must not be {@code null}.
     * @throws NullPointerException if {@code futures} is {@code null} or contains a {@code null} future
     * @throws SQLException if the first failed future has a SQL-related exception
     * @throws UncheckedInterruptedException if the first collected failure is an interruption while waiting for a future or executing its action
     */
    static void complete(final List<ContinuableFuture<Void>> futures) throws NullPointerException, SQLException, UncheckedInterruptedException {
        Exception firstException = null;

        for (final ContinuableFuture<Void> f : futures) {
            final Result<Void, Exception> ret = f.getAsResult();

            if (ret.isFailure()) {
                firstException = collectFailure(firstException, ret.getException());
            }
        }

        if (firstException != null) {
            throwSQLExceptionAction.accept(firstException);
        }
    }

    /**
     * Completes all futures in the list, sums their integer results, and propagates the first failure.
     * <p>
     * This method waits for all futures to complete, collecting their integer results and summing them.
     * SQL-related failures are propagated as a checked {@link SQLException}; other failures retain their
     * runtime exception semantics. Later failures are attached as suppressed exceptions to the first failure.
     * This is the checked exception variant of {@link #uncheckedCompleteSum(List)}, typically used for batch operations
     * where the return value indicates the total number of affected rows.
     * </p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Execute multiple async update operations and sum affected rows (checked exception)
     * List<ContinuableFuture<Integer>> futures = new ArrayList<>();
     * futures.add(ContinuableFuture.call(() -> dao.update(entity1)));
     * futures.add(ContinuableFuture.call(() -> dao.update(entity2)));
     * futures.add(ContinuableFuture.call(() -> dao.update(entity3)));
     *
     * // Wait for all operations and get total affected rows
     * int totalAffectedRows = DaoUtil.completeSum(futures);
     * // totalAffectedRows = sum of all affected rows
     * // SQL-related failures are reported as SQLException
     * }</pre>
     *
     * @param futures the list of futures returning integer values to complete and sum. Must not be {@code null}.
     * @return the sum of all integer results from the futures
     * @throws NullPointerException if {@code futures} is {@code null}, contains a {@code null} future, or a successful future returns {@code null}
     * @throws SQLException if the first failed future has a SQL-related exception
     * @throws UncheckedInterruptedException if the first collected failure is an interruption while waiting for a future or executing its action
     * @throws ArithmeticException if no future failed and the sum overflows an {@code int}.
     */
    static int completeSum(final List<ContinuableFuture<Integer>> futures)
            throws NullPointerException, SQLException, UncheckedInterruptedException, ArithmeticException {
        long result = 0;
        Result<Integer, Exception> ret = null;
        Exception firstException = null;

        for (final ContinuableFuture<Integer> f : futures) {
            ret = f.getAsResult();

            if (ret.isFailure()) {
                firstException = collectFailure(firstException, ret.getException());
            } else {
                result += ret.orElseIfFailure(0);
            }
        }

        if (firstException != null) {
            throwSQLExceptionAction.accept(firstException);
        }

        return Math.toIntExact(result);
    }

    /**
     * Collects a failure into the exception being accumulated, keeping the first failure as the one to
     * propagate and attaching any later, distinct failure to it as a suppressed exception.
     *
     * @param firstException the first failure collected so far, or {@code null} if none yet.
     * @param nextException the next failure to collect.
     * @return {@code nextException} if {@code firstException} is {@code null}; otherwise
     *         {@code firstException} with {@code nextException} added as suppressed.
     */
    private static Exception collectFailure(final Exception firstException, final Exception nextException) {
        if (firstException == null) {
            return nextException;
        }

        if (firstException != nextException) {
            addSuppressedIfDifferent(firstException, nextException);
        }

        return firstException;
    }

    /**
     * Copies the suppressed exceptions of {@code source} onto {@code target}, so failures retained on
     * {@code source} are not lost when {@code target} is thrown in its place. {@code target} itself is
     * skipped if it appears among the suppressed exceptions of {@code source}.
     *
     * @param source the exception whose suppressed exceptions are transferred.
     * @param target the exception that receives the suppressed exceptions and is returned.
     * @return {@code target} with the suppressed exceptions of {@code source} added to it.
     */
    private static <E extends Exception> E transferSuppressed(final Exception source, final E target) {
        for (final Throwable suppressed : source.getSuppressed()) {
            if (suppressed != target) {
                addSuppressedIfDifferent(target, suppressed);
            }
        }

        return target;
    }

    /**
     * Attaches {@code secondary} to {@code primary} unless both references identify the same
     * throwable. This identity check is required because {@link Throwable#addSuppressed(Throwable)}
     * rejects self-suppression and would otherwise replace the failure being preserved.
     *
     * @param primary the failure that will be propagated
     * @param secondary the additional failure to retain
     * @throws NullPointerException if exactly one of {@code primary} and {@code secondary} is {@code null}
     */
    static void addSuppressedIfDifferent(final Throwable primary, final Throwable secondary) throws NullPointerException {
        if (primary != secondary) {
            primary.addSuppressed(secondary);
        }
    }

    /**
     * Executes a statement-building action and translates a checked SQL failure for the unchecked DAO hierarchy.
     *
     * @param <R> the result type of {@code action}
     * @param action the action to execute
     * @return the result of {@code action}
     * @throws NullPointerException if {@code action} is {@code null}
     * @throws UncheckedSQLException if the action fails with a SQL-related exception; other runtime failures are propagated
     */
    static <R> R uncheckedSql(final Throwables.Supplier<R, SQLException> action) throws NullPointerException, UncheckedSQLException {
        try {
            return action.get();
        } catch (final Exception e) {
            throwUncheckedSQLException.accept(e);
            throw new AssertionError("Unreachable: throwUncheckedSQLException always throws");
        }
    }
}
