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
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.landawn.abacus.annotation.Internal;
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
 *       {@link ReadOps}/{@link CrudReadOps} (and unchecked/{@code long}-ID) views</li>
 *   <li>Asynchronous operation completion and result aggregation — joining batches of futures and
 *       surfacing the first failure as a checked or unchecked SQL exception</li>
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
    private DaoUtil() {
        // utility class - prevent instantiation.
    }

    /**
     * Returns whether the specified DAO interface supports DAO result caching.
     *
     * @param daoInterface the DAO interface to inspect.
     * @return {@code true} if {@code daoInterface} extends {@link NoUpdateDao} or {@link ReadOnlyDao}
     *         (and therefore cannot perform update/delete operations that would invalidate cached rows);
     *         otherwise {@code false}.
     */
    public static boolean isCacheable(final Class<?> daoInterface) {
        return NoUpdateDao.class.isAssignableFrom(daoInterface) || ReadOnlyDao.class.isAssignableFrom(daoInterface);
    }

    /**
     * Returns whether the specified DAO interface exposes readable CRUD operations.
     *
     * @param daoInterface the DAO interface to inspect.
     * @return {@code true} if {@code daoInterface} extends {@link CrudReadOps}; otherwise {@code false}.
     */
    public static boolean isCrudReadOps(final Class<?> daoInterface) {
        return CrudReadOps.class.isAssignableFrom(daoInterface);
    }

    /**
     * Returns whether the specified DAO interface exposes readable CRUD operations with a {@code long} ID type.
     *
     * @param daoInterface the DAO interface to inspect.
     * @return {@code true} if {@code daoInterface} extends {@link CrudLReadOps}; otherwise {@code false}.
     */
    public static boolean isCrudLReadOps(final Class<?> daoInterface) {
        return CrudLReadOps.class.isAssignableFrom(daoInterface);
    }

    /**
     * Returns the {@code idExtractor()} declared by the given DAO when it is a CRUD-read-capable DAO
     * (any {@link CrudReadOps} variant, including the no-update and read-only composites), otherwise {@code null}.
     *
     * @param dao the DAO instance to inspect.
     * @return the DAO's declared id extractor, or {@code null} if the DAO has none.
     */
    @SuppressWarnings("rawtypes")
    public static Jdbc.BiRowMapper getDeclaredIdExtractor(final DaoBase dao) {
        return dao instanceof CrudReadOps ? ((CrudReadOps) dao).idExtractor() : null;
    }

    /**
     * Returns whether the specified DAO interface exposes readable CRUD join-entity helper operations.
     *
     * @param daoInterface the DAO interface to inspect.
     * @return {@code true} if {@code daoInterface} extends {@link CrudJoinEntityReadOps}; otherwise {@code false}.
     */
    public static boolean isCrudJoinEntityReadOps(final Class<?> daoInterface) {
        return CrudJoinEntityReadOps.class.isAssignableFrom(daoInterface);
    }

    /**
     * Returns whether the specified DAO interface exposes readable join-entity helper operations.
     *
     * @param daoInterface the DAO interface to inspect.
     * @return {@code true} if {@code daoInterface} extends {@link JoinEntityReadOps}; otherwise {@code false}.
     */
    public static boolean isJoinEntityReadOps(final Class<?> daoInterface) {
        return JoinEntityReadOps.class.isAssignableFrom(daoInterface);
    }

    /**
     * Returns whether the specified DAO interface exposes unchecked readable operations.
     *
     * @param daoInterface the DAO interface to inspect.
     * @return {@code true} if {@code daoInterface} extends {@link UncheckedReadOps}; otherwise {@code false}.
     */
    public static boolean isUncheckedReadOps(final Class<?> daoInterface) {
        return UncheckedReadOps.class.isAssignableFrom(daoInterface);
    }

    /**
     * Returns whether methods declared by the specified class are handled as base DAO operations.
     *
     * @param declaringClass the declaring class of a DAO method.
     * @return {@code true} if methods declared by {@code declaringClass} are base DAO operations; otherwise {@code false}.
     */
    public static boolean isDaoOperationDeclaringClass(final Class<?> declaringClass) {
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
     */
    public static boolean isCrudDaoOperationDeclaringClass(final Class<?> declaringClass) {
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
     */
    public static boolean isJoinEntityHelperDeclaringClass(final Class<?> declaringClass) {
        return declaringClass.equals(JoinEntityReadOps.class) || declaringClass.equals(JoinEntityDeleteOps.class)
                || declaringClass.equals(UncheckedJoinEntityReadOps.class) || declaringClass.equals(UncheckedJoinEntityDeleteOps.class)
                || declaringClass.equals(JoinEntityHelper.class) || declaringClass.equals(UncheckedJoinEntityHelper.class);
    }

    /**
     * Generates a new ID for entity insertion by delegating to {@link CrudReadOps#generateId()}.
     * <p>
     * The default {@code generateId()} implementation throws {@link UnsupportedOperationException};
     * a value is only produced when the DAO overrides it with a client-side ID generation strategy
     * (for example a UUID or sequence). ID generation is normally handled by the database, so this
     * path is rarely used.
     * </p>
     *
     * @param dao the DAO used to generate the identifier; must implement {@link CrudReadOps}.
     * @return the generated identifier.
     * @throws SQLException if a database access error occurs while generating the identifier.
     * @throws UnsupportedOperationException if {@code dao} does not override {@link CrudReadOps#generateId()}.
     * @throws ClassCastException if {@code dao} does not implement {@link CrudReadOps}.
     */
    @SuppressWarnings({ "rawtypes", "unchecked", "deprecation" })
    public static Object generateId(final DaoBase dao) throws SQLException {
        return ((CrudReadOps) dao).generateId();
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
     * Builds the {@code WHERE} condition selecting the rows whose IDs are contained in the given (sub-)collection,
     * dispatching on the shape of the IDs: {@link EntityId}s, {@link Map}s, a single-column id (rendered as an
     * {@code IN} clause), or multi-column ids. Shared by {@link CrudReadOps#batchGet(Collection, Collection, int)} and
     * {@link CrudReadOps#count(Collection)}; declared {@code static} so it is not treated as a DAO operation by the proxy.
     *
     * @param ids the (batch of) IDs to match
     * @param idPropNameList the id property names of the entity
     * @param isEntityId whether the IDs are {@link EntityId} instances
     * @param isMap whether the IDs are {@link Map} instances
     * @return a condition matching any row whose id is in {@code ids}
     */
    @SuppressWarnings("unchecked")
    static Condition idsToCondition(final Collection<?> ids, final List<String> idPropNameList, final boolean isEntityId, final boolean isMap) {
        if (isEntityId) {
            return Filters.idToCond((Collection<? extends EntityId>) ids);
        } else if (isMap) {
            return Filters.anyOfAllEqual(ids);
        } else if (idPropNameList.size() == 1) {
            return Filters.in(idPropNameList.get(0), ids);
        } else {
            return Filters.anyOfAllEqual(ids, idPropNameList);
        }
    }

    /**
     * A consumer that configures a {@link PreparedStatement} for handling large query results efficiently.
     * Sets the fetch direction to {@link ResultSet#FETCH_FORWARD} and raises the fetch size to
     * {@link JdbcUtil#DEFAULT_FETCH_SIZE_FOR_BIG_RESULT} (a larger pre-configured size is preserved,
     * matching {@code JdbcUtil.stmtSetterForBigQueryResult}).
     */
    static final Throwables.Consumer<PreparedStatement, SQLException> stmtSetterForBigQueryResult = stmt -> {
        stmt.setFetchDirection(ResultSet.FETCH_FORWARD);

        if (stmt.getFetchSize() < JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT) {
            stmt.setFetchSize(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT);
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
     * class UserDaoImpl implements CrudDao<User, Long, UserDaoImpl>,
     *                              CrudJoinEntityHelper<User, Long, UserDaoImpl> {
     *     // ... implementation
     * }
     *
     * UserDaoImpl dao = new UserDaoImpl();
     * CrudReadOps<User, Long, UserDaoImpl> crudDao = DaoUtil.getCrudReadOps(dao);
     * // Successfully casts to CrudReadOps
     * }</pre>
     *
     * @param <T> the entity type managed by this DAO
     * @param <ID> the ID type of the entity
     * @param <TD> the DAO type
     * @param dao the CrudJoinEntityReadOps instance to cast
     * @return the DAO instance cast to CrudReadOps
     * @throws UnsupportedOperationException if the DAO does not implement CrudReadOps interface
     */
    static <T, ID, TD extends DaoBase<T, TD>> CrudReadOps<T, ID, TD> getCrudReadOps(final CrudJoinEntityReadOps<T, ID, TD> dao) {
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
     * class ProductDaoImpl implements Dao<Product, ProductDaoImpl>,
     *                                 JoinEntityHelper<Product, ProductDaoImpl> {
     *     // ... implementation
     * }
     *
     * ProductDaoImpl dao = new ProductDaoImpl();
     * ReadOps<Product, ProductDaoImpl> daoInstance = DaoUtil.getReadOps(dao);
     * // Successfully casts to ReadOps
     * }</pre>
     *
     * @param <T> the entity type managed by this DAO
     * @param <TD> the DAO type
     * @param dao the join-entity helper instance to cast
     * @return the DAO instance cast to ReadOps
     * @throws UnsupportedOperationException if the DAO does not implement ReadOps interface
     */
    static <T, TD extends DaoBase<T, TD>> ReadOps<T, TD> getReadOps(final JoinEntityBase<T, TD> dao) {
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
     * class ProductDaoImpl implements UncheckedDao<Product, ProductDaoImpl>,
     *                                 UncheckedJoinEntityHelper<Product, ProductDaoImpl> {
     *     // ... implementation
     * }
     *
     * ProductDaoImpl dao = new ProductDaoImpl();
     * UncheckedReadOps<Product, ProductDaoImpl> daoInstance = DaoUtil.getReadOps(dao);
     * // Successfully casts to UncheckedReadOps
     * }</pre>
     *
     * @param <T> the entity type managed by this DAO
     * @param <TD> the DAO type
     * @param dao the UncheckedJoinEntityReadOps instance to cast
     * @return the DAO instance cast to UncheckedReadOps
     * @throws UnsupportedOperationException if the DAO does not implement UncheckedReadOps interface
     */
    static <T, TD extends UncheckedDaoBase<T, TD>> UncheckedReadOps<T, TD> getReadOps(final UncheckedJoinEntityReadOps<T, TD> dao) {
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
     * class UserDaoImpl implements UncheckedCrudDao<User, Long, UserDaoImpl>,
     *                              UncheckedCrudJoinEntityHelper<User, Long, UserDaoImpl> {
     *     // ... implementation
     * }
     *
     * UserDaoImpl dao = new UserDaoImpl();
     * UncheckedCrudReadOps<User, Long, UserDaoImpl> crudDao = DaoUtil.getCrudReadOps(dao);
     * // Successfully casts to UncheckedCrudReadOps
     * }</pre>
     *
     * @param <T> the entity type managed by this DAO
     * @param <ID> the ID type of the entity
     * @param <TD> the DAO type
     * @param dao the UncheckedCrudJoinEntityReadOps instance to cast
     * @return the DAO instance cast to UncheckedCrudReadOps
     * @throws UnsupportedOperationException if the DAO does not implement UncheckedCrudReadOps interface
     */
    static <T, ID, TD extends UncheckedDaoBase<T, TD>> UncheckedCrudReadOps<T, ID, TD> getCrudReadOps(final UncheckedCrudJoinEntityReadOps<T, ID, TD> dao) {
        if (dao instanceof UncheckedCrudReadOps) {
            return (UncheckedCrudReadOps<T, ID, TD>) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " does not implement UncheckedCrudReadOps interface");
        }
    }

    /**
     * Casts a {@link CrudLJoinEntityHelper} to a {@link CrudLReadOps} instance.
     * <p>
     * This is the {@code long}-ID counterpart of {@link #getCrudReadOps(CrudJoinEntityReadOps)}. It returns the
     * {@link CrudLReadOps} view so the helper's primitive {@code long}-ID {@code gett} paths resolve to the
     * primitive {@code gett(long)} overloads (keeping dispatch on the {@code long}-ID view) rather than the boxed {@code gett(Long)} ones.
     *
     * @param <T> the entity type managed by this DAO
     * @param <TD> the DAO type
     * @param dao the CrudLJoinEntityHelper instance to cast
     * @return the DAO instance cast to CrudLReadOps
     * @throws UnsupportedOperationException if the DAO does not implement CrudLReadOps interface
     */
    static <T, TD extends CrudLDao<T, TD>> CrudLReadOps<T, TD> getCrudReadOps(final CrudLJoinEntityHelper<T, TD> dao) {
        if (dao instanceof CrudLReadOps) {
            return (CrudLReadOps<T, TD>) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " does not implement CrudLReadOps interface"); //NOSONAR
        }
    }

    /**
     * Casts an {@link UncheckedCrudLJoinEntityHelper} to an {@link UncheckedCrudLReadOps} instance.
     * <p>
     * This is the {@code long}-ID counterpart of {@link #getCrudReadOps(UncheckedCrudJoinEntityReadOps)}. It
     * returns the {@link UncheckedCrudLReadOps} view so the helper's primitive {@code long}-ID {@code gett}
     * paths resolve to the primitive {@code gett(long)} overloads (keeping dispatch on the {@code long}-ID view).
     *
     * @param <T> the entity type managed by this DAO
     * @param <TD> the DAO type
     * @param dao the UncheckedCrudLJoinEntityHelper instance to cast
     * @return the DAO instance cast to UncheckedCrudLReadOps
     * @throws UnsupportedOperationException if the DAO does not implement UncheckedCrudLReadOps interface
     */
    static <T, TD extends UncheckedCrudLDao<T, TD>> UncheckedCrudLReadOps<T, TD> getCrudReadOps(final UncheckedCrudLJoinEntityHelper<T, TD> dao) {
        if (dao instanceof UncheckedCrudLReadOps) {
            return (UncheckedCrudLReadOps<T, TD>) dao;
        } else {
            throw new UnsupportedOperationException(ClassUtil.getCanonicalClassName(dao.getClass()) + " does not implement UncheckedCrudLReadOps interface"); //NOSONAR
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
        Exception firstException = null;

        for (final ContinuableFuture<Void> f : futures) {
            final Result<Void, Exception> ret = f.getAsResult();

            if (firstException == null && ret.isFailure()) {
                firstException = ret.getException();
            }
        }

        if (firstException != null) {
            throwUncheckedSQLException.accept(firstException);
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
     * @throws ArithmeticException if the sum overflows an {@code int}
     */
    static int uncheckedCompleteSum(final List<ContinuableFuture<Integer>> futures) throws UncheckedSQLException {
        long result = 0;
        Result<Integer, Exception> ret = null;
        Exception firstException = null;

        for (final ContinuableFuture<Integer> f : futures) {
            ret = f.getAsResult();

            if (ret.isFailure()) {
                if (firstException == null) {
                    firstException = ret.getException();
                }
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
        Exception firstException = null;

        for (final ContinuableFuture<Void> f : futures) {
            final Result<Void, Exception> ret = f.getAsResult();

            if (firstException == null && ret.isFailure()) {
                firstException = ret.getException();
            }
        }

        if (firstException != null) {
            throwSQLExceptionAction.accept(firstException);
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
     * @throws ArithmeticException if the sum overflows an {@code int}
     */
    static int completeSum(final List<ContinuableFuture<Integer>> futures) throws SQLException {
        long result = 0;
        Result<Integer, Exception> ret = null;
        Exception firstException = null;

        for (final ContinuableFuture<Integer> f : futures) {
            ret = f.getAsResult();

            if (ret.isFailure()) {
                if (firstException == null) {
                    firstException = ret.getException();
                }
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
