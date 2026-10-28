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
import java.util.Arrays;
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
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.Beans;
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
     * A value to be bound with a given type (that of the entity property it is written to or compared with) instead of the
     * type of its runtime class, e.g. an enum id stored as its ordinal. It is passed to the query builder as an ordinary
     * value, so that it keeps its position among the built statement's parameters, and the DAO implementation binds it with
     * that type. Deliberately not a bean or record: the builder must not read it as one.
     */
    @Internal
    public static final class TypedParameter {
        private final Object value;
        // transient: a DAO cache key serialized with Kryo holds a condition's values, not the types they are bound with.
        private final transient com.landawn.abacus.type.Type<Object> type;

        private TypedParameter(final Object value, final com.landawn.abacus.type.Type<Object> type) {
            this.value = value;
            this.type = type;
        }

        /**
         * Returns the value wrapped to be bound with the property's type, or the value itself (bound by its runtime class) when
         * it is not an instance of the property's class: the property type would reject it, e.g. a {@code Long} given for an
         * {@code int} property.
         *
         * @param value the value
         * @param propInfo the property the value is written to or compared with
         * @return the wrapped value, or {@code value}
         */
        public static Object of(final Object value, final PropInfo propInfo) {
            return value == null || ClassUtil.wrap(propInfo.clazz).isInstance(value) ? new TypedParameter(value, propInfo.dbType) : value;
        }

        /**
         * Returns the wrapped value (not named as a getter: this must not read as a bean property).
         *
         * @return the value
         */
        Object value() {
            return value;
        }

        /**
         * Binds the value to the statement parameter with the property's type.
         *
         * @param stmt the statement
         * @param parameterIndex the 1-based parameter index
         * @throws SQLException if the driver fails to bind the value
         */
        public void setTo(final PreparedStatement stmt, final int parameterIndex) throws SQLException {
            type.set(stmt, parameterIndex, value);
        }

        @Override
        public String toString() {
            return String.valueOf(value);
        }
    }

    /**
     * Returns the value an id property is compared with: wrapped to be bound with the property's type (see
     * {@link TypedParameter}) when {@code entityInfo} is given and declares the property, otherwise the value itself.
     */
    private static Object idValue(final Object value, final String idPropName, final BeanInfo entityInfo) {
        final PropInfo propInfo = entityInfo == null ? null : entityInfo.getPropInfo(idPropName);

        return propInfo == null ? value : TypedParameter.of(value, propInfo);
    }

    /**
     * Builds the condition the upsert family matches a row with: {@code name = ?} for each of {@code matchPropNames}, with the
     * entity's value bound with the property's type (see {@link TypedParameter}), and {@code name IS NULL} for a {@code null}
     * value, the documented upsert match of a null key. Each name must be a property of the entity's class: a nested property
     * path (such as {@code "address.street"}) is rejected, since it is not a column of the entity's table and would be rendered
     * into the SQL as a qualified name.
     *
     * <p>WHY: {@code Filters.allEqual} binds each value by its runtime class, so an ID or match property with a custom
     * {@code @Type} (an enum stored as its ordinal) failed to convert, or on a lenient database compared with another value.</p>
     *
     * @param entity the entity holding the values to match
     * @param matchPropNames the properties to match, as the SQL builder renders them (canonical names)
     * @return the condition
     * @throws IllegalArgumentException if {@code entity} is {@code null}, {@code matchPropNames} is {@code null} or empty, or a
     *                                  name is not a property of the entity's class (including a nested property path)
     */
    static Condition typedAllEqual(final Object entity, final Collection<String> matchPropNames) throws IllegalArgumentException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotEmpty(matchPropNames, cs.matchPropNames);

        final BeanInfo entityInfo = ParserUtil.getBeanInfo(entity.getClass());
        final List<Condition> conditions = new ArrayList<>(matchPropNames.size());

        for (final String propName : matchPropNames) {
            final PropInfo propInfo = entityInfo.getPropInfo(propName);

            // WHY no nested paths: Filters.allEqual read "address.street" through the nested bean but rendered it as a
            // qualified column, so the lookup failed in the database ("Column ADDRESS.STREET not found").
            N.checkArgument(propInfo != null, "No property found with name: %s in class: %s", propName, ClassUtil.getCanonicalClassName(entity.getClass()));

            conditions.add(typedEqual(propName, propInfo.getPropValue(entity), propInfo));
        }

        return Filters.and(conditions);
    }

    /**
     * Builds the condition {@code batchUpsert} looks up the rows of a chunk of composite match keys with: an {@code OR} over the
     * keys of {@code AND}ed equalities, typed and {@code null}-rendered as by {@link #typedAllEqual(Object, Collection)}. Every
     * key carries every match property, so unlike {@link #entityIdToCondition(EntityId, List, BeanInfo)} a key is never
     * incomplete.
     *
     * @param ids the match keys, each holding every match property (by canonical name) and its value
     * @param entityInfo the bean info of the entity, whose property types the values are bound with
     * @return the condition
     * @throws IllegalArgumentException if {@code ids} is {@code null} or empty, or holds a {@code null} or empty key
     */
    static Condition typedAnyOfAllEqual(final Collection<? extends EntityId> ids, final BeanInfo entityInfo) throws IllegalArgumentException {
        N.checkArgNotEmpty(ids, cs.ids);

        final List<Condition> keyConditions = new ArrayList<>(ids.size());

        for (final EntityId id : ids) {
            N.checkArgument(id != null && !id.isEmpty(), "An element of 'ids' is null or has no keys");

            final List<Condition> conditions = new ArrayList<>(id.size());

            for (final Map.Entry<String, Object> entry : id.entrySet()) {
                conditions.add(typedEqual(entry.getKey(), entry.getValue(), entityInfo == null ? null : entityInfo.getPropInfo(entry.getKey())));
            }

            keyConditions.add(Filters.and(conditions));
        }

        return Filters.or(keyConditions);
    }

    /**
     * Returns {@code name = ?} with the value bound with the property's type, or {@code name IS NULL} for a {@code null} value:
     * a {@code null} is left unwrapped, since a wrapped one would render {@code = ?} and bind SQL {@code NULL}, which matches no
     * row.
     */
    private static Condition typedEqual(final String propName, final Object value, final PropInfo propInfo) {
        return Filters.eq(propName, value == null || propInfo == null ? value : TypedParameter.of(value, propInfo));
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
     * Equality key over the property values of a composite id, used to de-duplicate ids by value. Array values (e.g. a
     * {@code byte[]} binary UUID component) are compared by content, as an id class's own {@code equals} would compare them;
     * a {@code List} key would compare them by reference and keep equal ids apart.
     *
     * @param values the id property values, in id property order
     */
    record IdValuesKey(Object[] values) {
        @Override
        public boolean equals(final Object obj) {
            return obj instanceof final IdValuesKey other && Arrays.deepEquals(values, other.values);
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(values);
        }

        @Override
        public String toString() {
            return Arrays.deepToString(values);
        }
    }

    /**
     * Returns a hash key that compares a single key value by content. An array value (e.g. a {@code byte[]} binary UUID
     * id or unique key) has identity {@code equals}/{@code hashCode}, so equal values read from different rows never match
     * in a {@code HashMap}; it is wrapped in an {@link IdValuesKey}. Any other value, including a composite id's
     * {@code EntityId} (which already compares its values by content), is returned unchanged.
     *
     * @param value the key value; may be {@code null}
     * @return a key with content-based {@code equals}/{@code hashCode} for {@code value}
     */
    static Object valueKey(final Object value) {
        return value != null && value.getClass().isArray() ? new IdValuesKey(new Object[] { value }) : value;
    }

    /**
     * Builds the {@code WHERE} condition selecting the rows whose IDs are contained in the given (sub-)collection,
     * dispatching on the shape of the IDs: a single-column id (rendered as an {@code IN} clause with an {@code IS NULL}
     * branch when needed), or composite ids given as {@link EntityId}s, {@link Map}s or id beans/records (each matched
     * on every declared id property, see {@link #entityIdToCondition(EntityId, List, BeanInfo)} and
     * {@link #compositeIdToCondition(Object, List, BeanInfo)}). Shared by
     * {@link CrudReadOps#batchGet(Collection, Collection, int)} and
     * {@link CrudReadOps#count(Collection)}.
     *
     * @param ids the (batch of) IDs to match
     * @param idPropNameList the id property names of the entity
     * @param isEntityId whether the IDs are {@link EntityId} instances
     * @param isMap whether the IDs are {@link Map} instances
     * @param entityInfo the bean info of the entity, whose id property types the id values are bound with (WHY: bound by
     *        their runtime class, an id with a custom {@code @Type}, e.g. an enum stored as its ordinal, matched no row or
     *        failed to convert); {@code null} to bind them by their runtime class
     * @return a condition matching any row whose id is in {@code ids}, or {@code null} if no row can match because, for
     *         composite ids, no element holds a non-null value for every name of {@code idPropNameList} (the callers skip
     *         such a batch without querying)
     * @throws IllegalArgumentException if {@code ids} or {@code idPropNameList} is {@code null} or empty; if {@code isEntityId} and an element
     *                                  is {@code null}, is not an {@link EntityId} or is an {@link EntityId} with no keys; if
     *                                  {@code isMap} and a non-null element is not a {@link Map}; if {@code idPropNameList}
     *                                  holds more than one name and a non-null element is a {@link Map} while {@code isMap} is
     *                                  {@code false}, is not a bean or record, or lacks a declared id property, or every
     *                                  element of {@code ids} is {@code null}
     */
    static Condition idsToCondition(final Collection<?> ids, final List<String> idPropNameList, final boolean isEntityId, final boolean isMap,
            final BeanInfo entityInfo) throws IllegalArgumentException {
        N.checkArgNotEmpty(ids, cs.ids);
        N.checkArgNotEmpty(idPropNameList, cs.idPropNameList);

        if (!isEntityId && !isMap && idPropNameList.size() == 1) {
            return singlePropValuesToCondition(idPropNameList.get(0), ids, entityInfo);
        }

        // WHY: Filters.idToCond(Collection) matched each EntityId on the keys it CONTAINS, so an id lacking a declared id
        // property matched every row agreeing on the present ones (batchGet returned, count counted, foreign rows), and
        // Filters.anyOfAllEqual matched a Map on the keys it contains and rendered a null id property of a Map or bean id as
        // IS NULL, which matches every row holding NULL in a nullable key column, while getOrNull/exists/deleteById bind
        // NULL for it and match none. Each id is now matched on every declared id property; one that can match no row is
        // left out, and a batch of only such ids yields null, which the callers skip without a query (Filters.alwaysFalse()
        // is deprecated in abacus-query).
        final List<Condition> conditions = new ArrayList<>(ids.size());
        boolean hasNonNullId = isEntityId; // a null EntityId element is rejected by entityIdToCondition, not skipped.

        for (final Object id : ids) {
            final Condition idCond;

            if (isEntityId) {
                // WHY: a Map or bean among EntityIds was rejected with a ClassCastException by the cast below, while the reverse
                // order (an EntityId among Maps/beans) is an IllegalArgumentException; every mixed batch is now an IAE.
                N.checkArgNotNull(id, cs.id);
                N.checkArgument(id instanceof EntityId, "All elements of 'ids' must be EntityIds when the first non-null one is, but found: %s", id.getClass());

                idCond = entityIdToCondition((EntityId) id, idPropNameList, entityInfo);
            } else if (id == null) {
                // batchGet/count drop null Map/bean ids before chunking (Filters.anyOfAllEqual skipped them as well).
                continue;
            } else {
                N.checkArgument(id instanceof Map == isMap,
                        isMap ? "All non-null elements of 'ids' must be Maps when the first non-null one is a Map, but found: %s"
                                : "All non-null elements of 'ids' must be beans or records when the first non-null one is, but found: %s",
                        id.getClass());

                idCond = compositeIdToCondition(id, idPropNameList, entityInfo);
                hasNonNullId = true;
            }

            if (idCond != null) {
                conditions.add(idCond);
            }
        }

        N.checkArgument(hasNonNullId, "All specified ids are null");

        return conditions.isEmpty() ? null : Filters.or(conditions);
    }

    /**
     * Validates composite ids before {@link CrudReadOps#batchGet(Collection, Collection, int)} and
     * {@link CrudReadOps#count(Collection)} de-duplicate them, with the rules {@link #idsToCondition(Collection, List, boolean, boolean, BeanInfo)}
     * applies per chunk. WHY: an invalid id (an empty {@link EntityId}, a bean whose class lacks a declared id property) can
     * share its de-duplication key with a valid one and was then dropped silently, so whether it was rejected depended on
     * the order of {@code ids}.
     *
     * @param ids the ids to validate
     * @param idPropNameList the declared id property names of the entity (more than one)
     * @param isEntityId whether the IDs are {@link EntityId} instances
     * @param isMap whether the IDs are {@link Map} instances
     * @throws IllegalArgumentException if {@code ids} or {@code idPropNameList} is empty; if {@code isEntityId} and an element
     *                                  is {@code null}, is not an {@link EntityId} or is an {@link EntityId} with no keys; if
     *                                  {@code isMap} and a non-null element is not a {@link Map}; if neither and a non-null
     *                                  element is a {@link Map}, is not a bean or record, or its class lacks a declared id
     *                                  property; or if not {@code isEntityId} and every element is {@code null}
     */
    static void checkCompositeIds(final Collection<?> ids, final List<String> idPropNameList, final boolean isEntityId, final boolean isMap)
            throws IllegalArgumentException {
        N.checkArgNotEmpty(ids, cs.ids);
        N.checkArgNotEmpty(idPropNameList, cs.idPropNameList);

        boolean hasNonNullId = false;
        Class<?> checkedIdClass = null;

        for (final Object id : ids) {
            if (isEntityId) {
                // The element of 'ids' is named 'id' (cs.id), like the element checks of the Map/bean branches below.
                N.checkArgNotNull(id, cs.id);
                // WHY: a Map or bean among EntityIds was rejected with a ClassCastException by the cast below, while the reverse
                // order (an EntityId among Maps/beans) is an IllegalArgumentException; every mixed batch is now an IAE.
                N.checkArgument(id instanceof EntityId, "All elements of 'ids' must be EntityIds when the first non-null one is, but found: %s", id.getClass());
                N.checkArgument(!((EntityId) id).isEmpty(), "An element of 'ids' is an EntityId with no keys");
            } else if (id == null) {
                continue;
            } else if (isMap) {
                N.checkArgument(id instanceof Map, "All non-null elements of 'ids' must be Maps when the first non-null one is a Map, but found: %s",
                        id.getClass());
            } else {
                final Class<?> idClass = id.getClass();

                if (idClass != checkedIdClass) {
                    N.checkArgument(!(id instanceof Map),
                            "All non-null elements of 'ids' must be beans or records when the first non-null one is, but found: %s", idClass);
                    N.checkArgument(Beans.isBeanClass(idClass) || Beans.isRecordClass(idClass), "Composite id %s is not a Map, bean or record", idClass);

                    final BeanInfo idBeanInfo = ParserUtil.getBeanInfo(idClass);

                    for (final String idPropName : idPropNameList) {
                        N.checkArgument(idBeanInfo.getPropInfo(idPropName) != null, "Id property '%s' is not readable from composite id class %s", idPropName,
                                idClass);
                    }

                    checkedIdClass = idClass;
                }
            }

            hasNonNullId = true;
        }

        N.checkArgument(isEntityId || hasNonNullId, "All specified ids are null");
    }

    /**
     * Builds the condition matching the row a composite id given as a {@link Map} (keyed by id property name) or as an id
     * bean/record identifies, under the rule of {@link #entityIdToCondition(EntityId, List, BeanInfo)}: every DECLARED id property
     * is compared ({@code name = ?}), an id that holds {@code null} for one, or a {@code Map} that lacks one, matches no
     * row ({@code null} is returned), and {@code Map} keys or bean properties beyond the declared id properties are
     * ignored, as the single-id lookups ignore them.
     *
     * <p>WHY: {@code Filters.anyOfAllEqual} rendered a {@code null} id property as {@code IS NULL}, which matches every
     * row holding {@code NULL} in a nullable key column, and matched a {@code Map} on the keys it contains, so
     * {@code batchGet}/{@code count(ids)} returned or counted rows that {@code getOrNull}/{@code exists} (binding
     * {@code NULL}, which {@code =} never matches) do not find.</p>
     *
     * @param id the composite id: a {@link Map} or a bean/record exposing the declared id properties
     * @param idPropNameList the declared id property names of the entity
     * @param entityInfo the bean info of the entity, whose id property types the id values are bound with; {@code null} to
     *        bind them by their runtime class
     * @return the condition, or {@code null} if {@code id} matches no row
     * @throws IllegalArgumentException if {@code id} is {@code null}, or is neither a {@link Map} nor a bean or record, or
     *                                  is a bean or record that does not expose a declared id property; or if
     *                                  {@code idPropNameList} is {@code null} or empty
     */
    static Condition compositeIdToCondition(final Object id, final List<String> idPropNameList, final BeanInfo entityInfo) throws IllegalArgumentException {
        N.checkArgNotNull(id, cs.id);
        N.checkArgNotEmpty(idPropNameList, cs.idPropNameList);

        final List<Condition> conditions = new ArrayList<>(idPropNameList.size());

        if (id instanceof final Map<?, ?> map) {
            for (final String idPropName : idPropNameList) {
                final Object idPropValue = map.get(idPropName);

                if (idPropValue == null) {
                    return null;
                }

                conditions.add(Filters.eq(idPropName, idValue(idPropValue, idPropName, entityInfo)));
            }
        } else {
            final Class<?> idClass = id.getClass();
            N.checkArgument(Beans.isBeanClass(idClass) || Beans.isRecordClass(idClass), "Composite id %s is not a Map, bean or record", idClass);
            final BeanInfo idBeanInfo = ParserUtil.getBeanInfo(idClass);

            for (final String idPropName : idPropNameList) {
                final PropInfo idPropInfo = idBeanInfo.getPropInfo(idPropName);
                N.checkArgument(idPropInfo != null, "Id property '%s' is not readable from composite id class %s", idPropName, idClass);

                final Object idPropValue = idPropInfo.getPropValue(id);

                if (idPropValue == null) {
                    return null;
                }

                conditions.add(Filters.eq(idPropName, idValue(idPropValue, idPropName, entityInfo)));
            }
        }

        return Filters.and(conditions);
    }

    /**
     * Builds the condition matching the row an {@link EntityId} composite id identifies, comparing every DECLARED id
     * property of the entity ({@code name = ?} for each name of {@code idPropNameList}, with the id's value for it), as the
     * single-id lookups ({@code getOrNull}, {@code exists}, {@code deleteById}, {@code update(Map, id)}) bind it. An id that
     * lacks a declared id property, or holds {@code null} for one, matches no row: those lookups bind SQL {@code NULL} for
     * it, which {@code =} never matches, so {@code null} is returned instead of a condition ({@code eq(name, null)} would
     * render {@code IS NULL}, which does match a nullable key column). Keys the id carries beyond the declared id
     * properties are ignored, as those lookups ignore them.
     *
     * <p>WHY: {@code Filters.idToCond(EntityId)} builds one equality per key the id CONTAINS, so an incomplete id matched
     * every row agreeing on the present components: {@code batchGet}/{@code count(ids)} returned or counted foreign rows
     * and {@code refresh} raised {@code DuplicateResultException}, while the single-id lookups matched nothing.</p>
     *
     * @param entityId the composite id
     * @param idPropNameList the declared id property names of the entity
     * @param entityInfo the bean info of the entity, whose id property types the id values are bound with; {@code null} to
     *        bind them by their runtime class
     * @return the condition, or {@code null} if {@code entityId} matches no row
     * @throws IllegalArgumentException if {@code entityId} is {@code null} or has no keys, or {@code idPropNameList} is
     *                                  {@code null} or empty
     */
    static Condition entityIdToCondition(final EntityId entityId, final List<String> idPropNameList, final BeanInfo entityInfo)
            throws IllegalArgumentException {
        N.checkArgNotNull(entityId, cs.entityId);
        N.checkArgument(!entityId.isEmpty(), "The specified 'entityId' has no keys");
        N.checkArgNotEmpty(idPropNameList, cs.idPropNameList);

        final List<Condition> conditions = new ArrayList<>(idPropNameList.size());

        for (final String idPropName : idPropNameList) {
            final Object idPropValue = entityId.get(idPropName);

            if (idPropValue == null) {
                return null;
            }

            conditions.add(Filters.eq(idPropName, idValue(idPropValue, idPropName, entityInfo)));
        }

        return Filters.and(conditions);
    }

    /**
     * Builds a condition matching any supplied value for one property while preserving SQL
     * {@code NULL} equality semantics. A plain {@code IN (..., NULL)} predicate never matches a
     * {@code null} column, so a {@code null} value is expressed as a separate {@link Filters#isNull(String)} branch.
     *
     * @param propName the property to match
     * @param values the non-empty values to match
     * @param entityInfo the bean info of the entity, whose property type the values are bound with; {@code null} to bind them
     *        by their runtime class
     * @return an {@code IN}, {@code IS NULL}, or combined {@code OR} condition as appropriate
     * @throws IllegalArgumentException if {@code propName} or {@code values} is {@code null} or empty.
     */
    static Condition singlePropValuesToCondition(final String propName, final Collection<?> values, final BeanInfo entityInfo) throws IllegalArgumentException {
        N.checkArgNotEmpty(propName, cs.propName);
        N.checkArgNotEmpty(values, cs.values);

        final List<Object> nonNullValues = new ArrayList<>(values.size());
        boolean containsNull = false;

        for (final Object value : values) {
            if (value == null) {
                containsNull = true;
            } else {
                nonNullValues.add(idValue(value, propName, entityInfo));
            }
        }

        if (!containsNull) {
            return Filters.in(propName, nonNullValues);
        } else if (nonNullValues.isEmpty()) {
            return Filters.isNull(propName);
        } else {
            return Filters.in(propName, nonNullValues).or(Filters.isNull(propName));
        }
    }

    /**
     * Resolves each name to the canonical property name of {@code entity}'s class, so that a spelling accepted by the
     * bean-info lookup (for example {@code "FIRSTNAME"} or {@code "first_name"} for {@code firstName}) renders as a real
     * column in SQL. A name that does not resolve to a property is kept unchanged, so the caller's own validation still
     * reports it.
     *
     * @param entity the non-null entity whose class defines the properties
     * @param propNames the non-empty property names to resolve
     * @return the resolved property names, in the same order
     */
    static List<String> toCanonicalPropNames(final Object entity, final Collection<String> propNames) {
        final BeanInfo entityInfo = ParserUtil.getBeanInfo(entity.getClass());
        final List<String> result = new ArrayList<>(propNames.size());

        for (final String propName : propNames) {
            final PropInfo propInfo = propName == null ? null : entityInfo.getPropInfo(propName);
            result.add(propInfo == null ? propName : propInfo.name);
        }

        return result;
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
     */
    static Map<String, JoinInfo> getEntityJoinInfo(final Class<?> targetDaoInterface, final Class<?> targetEntityClass, final String targetTableName)
            throws IllegalArgumentException {
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
     *         names added; {@code null} or empty selections are returned unchanged because they select all default properties.
     * @throws NullPointerException if {@code dao} is {@code null} and {@code sourceSelectPropNames} is not empty
     * @throws IllegalArgumentException if {@code sourceSelectPropNames} is not empty and the join metadata it needs is invalid or
     *                                  {@code joinEntityClass} is {@code null}
     */
    @SuppressWarnings("deprecation")
    static Collection<String> includeSourceJoinPropNames(final JoinEntityBase<?, ?> dao, final Collection<String> sourceSelectPropNames,
            final Class<?> joinEntityClass) throws NullPointerException, IllegalArgumentException {
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
     *         names added; returned unchanged if the selection is {@code null} or empty (all default properties),
     *         or {@code joinEntityClasses} is empty.
     * @throws NullPointerException if {@code dao} is {@code null} while both {@code sourceSelectPropNames} and {@code joinEntityClasses}
     *                              are not empty
     * @throws IllegalArgumentException if both {@code sourceSelectPropNames} and {@code joinEntityClasses} are not empty and the join
     *                                  metadata they need is invalid or {@code joinEntityClasses} contains a {@code null} element
     */
    static Collection<String> includeSourceJoinPropNames(final JoinEntityBase<?, ?> dao, final Collection<String> sourceSelectPropNames,
            final Collection<Class<?>> joinEntityClasses) throws NullPointerException, IllegalArgumentException {
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
     *         names added; {@code null} or empty selections are returned unchanged because they select all default properties.
     * @throws NullPointerException if {@code dao} is {@code null} and {@code sourceSelectPropNames} is not empty
     * @throws IllegalArgumentException if metadata needed for a nonempty source selection is invalid
     */
    @SuppressWarnings("deprecation")
    static Collection<String> includeAllSourceJoinPropNames(final JoinEntityBase<?, ?> dao, final Collection<String> sourceSelectPropNames)
            throws NullPointerException, IllegalArgumentException {
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
     */
    static List<String> getJoinEntityPropNamesByType(final Class<?> targetDaoInterface, final Class<?> targetEntityClass, final String targetTableName,
            final Class<?> joinEntityClass) throws IllegalArgumentException {
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
     * This method waits for all futures to complete and checks for failures. The wait is not cut short by an interrupt
     * of the calling thread: the interruption is collected as a failure and the interrupt status is restored once every
     * future has completed. SQL-related failures
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
        final Exception firstException = awaitAll(futures, null);

        if (firstException != null) {
            throwUncheckedSQLException.accept(firstException);
        }
    }

    /**
     * Completes all futures in the list, sums their integer results, and propagates the first failure as an unchecked exception.
     * <p>
     * This method waits for all futures to complete, collecting their integer results and summing them. The wait is
     * not cut short by an interrupt of the calling thread: the interruption is collected as a failure and the interrupt
     * status is restored once every future has completed.
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
     * @throws NullPointerException if {@code futures} is {@code null} or contains a {@code null} future, or if no future failed and a successful
     *                              future returns {@code null}
     * @throws UncheckedSQLException if the first failed future has a SQL-related exception
     * @throws UncheckedInterruptedException if the first collected failure is an interruption while waiting for a future or executing its action
     * @throws ArithmeticException if no future failed and the sum overflows an {@code int}.
     */
    static int uncheckedCompleteSum(final List<ContinuableFuture<Integer>> futures)
            throws NullPointerException, UncheckedSQLException, UncheckedInterruptedException, ArithmeticException {
        final List<Integer> results = new ArrayList<>(futures.size());
        final Exception firstException = awaitAll(futures, results);

        if (firstException != null) {
            throwUncheckedSQLException.accept(firstException);
        }

        return sum(results);
    }

    /**
     * Completes all futures in the list and propagates the first failure.
     * <p>
     * This method waits for all futures to complete and checks for failures. The wait is not cut short by an interrupt
     * of the calling thread: the interruption is collected as a failure and the interrupt status is restored once every
     * future has completed. SQL-related failures
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
        final Exception firstException = awaitAll(futures, null);

        if (firstException != null) {
            throwSQLExceptionAction.accept(firstException);
        }
    }

    /**
     * Completes all futures in the list, sums their integer results, and propagates the first failure.
     * <p>
     * This method waits for all futures to complete, collecting their integer results and summing them. The wait is
     * not cut short by an interrupt of the calling thread: the interruption is collected as a failure and the interrupt
     * status is restored once every future has completed.
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
     * @throws NullPointerException if {@code futures} is {@code null} or contains a {@code null} future, or if no future failed and a successful
     *                              future returns {@code null}
     * @throws SQLException if the first failed future has a SQL-related exception
     * @throws UncheckedInterruptedException if the first collected failure is an interruption while waiting for a future or executing its action
     * @throws ArithmeticException if no future failed and the sum overflows an {@code int}.
     */
    static int completeSum(final List<ContinuableFuture<Integer>> futures)
            throws NullPointerException, SQLException, UncheckedInterruptedException, ArithmeticException {
        final List<Integer> results = new ArrayList<>(futures.size());
        final Exception firstException = awaitAll(futures, results);

        if (firstException != null) {
            throwSQLExceptionAction.accept(firstException);
        }

        return sum(results);
    }

    /**
     * Waits for every future to complete and collects the failures: the first one is returned, and later, distinct
     * ones are attached to it as suppressed exceptions.
     * <p>
     * The wait is not cut short by an interrupt of the calling thread. {@link ContinuableFuture#getAsResult()} returns at
     * once when the thread is interrupted (and restores its interrupt status, so every later wait would return at once as
     * well); surfacing the failure then would let a join task that is still running keep writing the caller's entities
     * (or deleting rows) after the caller has seen it, as {@code JoinEntityReadOps.submitJoinTasks} also guards against.
     * The interruption is therefore collected as an {@link InterruptedException} failure of the future being awaited when
     * it happened, the interrupt status is cleared to keep waiting, and it is restored once every future has completed.
     * </p>
     *
     * @param <R> the result type of the futures
     * @param futures the futures to wait for
     * @param results receives the result of each successful future, in order; may be {@code null}
     * @return the first collected failure, or {@code null} if no future failed and the thread was not interrupted
     * @throws NullPointerException if {@code futures} is {@code null} or contains a {@code null} future
     */
    private static <R> Exception awaitAll(final List<ContinuableFuture<R>> futures, final List<? super R> results) throws NullPointerException {
        Exception firstException = null;
        boolean interrupted = false;

        try {
            for (final ContinuableFuture<R> f : futures) {
                Result<R, Exception> ret = f.getAsResult();

                // Thread.interrupted() tells a wait interrupted on this thread (getAsResult() restored the flag) from an
                // InterruptedException the task itself failed with, and clears the flag so that the future is awaited again.
                while (ret.isFailure() && ret.getException() instanceof InterruptedException && Thread.interrupted()) {
                    if (!interrupted) {
                        interrupted = true;
                        firstException = collectFailure(firstException, ret.getException());
                    }

                    ret = f.getAsResult();
                }

                if (ret.isFailure()) {
                    firstException = collectFailure(firstException, ret.getException());
                } else if (results != null) {
                    results.add(ret.orElseIfFailure(null));
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        return firstException;
    }

    /**
     * Sums the results of the completed futures.
     *
     * @param results the future results
     * @return the sum of {@code results}
     * @throws NullPointerException if a result is {@code null}
     * @throws ArithmeticException if the sum overflows an {@code int}
     */
    private static int sum(final List<Integer> results) throws NullPointerException, ArithmeticException {
        long result = 0;

        for (final Integer value : results) {
            result += value;
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
