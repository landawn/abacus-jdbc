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

import java.util.Collection;
import java.util.concurrent.Executor;

import com.landawn.abacus.query.SqlBuilder;

/**
 * Read-only helper for join entity operations in DAOs.
 * This interface extends {@link JoinEntityHelper} but overrides all mutation methods to throw
 * {@link UnsupportedOperationException}, enforcing read-only behavior for join entity operations.
 *
 * <p>This interface provides read-only access to join entity operations,
 * preventing any modifications to entity relationships while still allowing
 * load operations inherited from the parent interface.</p>
 *
 * <p><b>Supported Join Entity Read Operations:</b></p>
 * <ul>
 *   <li>{@code loadJoinEntities(entity, Class)} - Load associated entities for a specific join type</li>
 *   <li>{@code loadJoinEntities(entity, String)} - Load associated entities by property name</li>
 *   <li>{@code loadJoinEntities(entity, Collection)} - Load multiple join entities by property names</li>
 *   <li>{@code loadAllJoinEntities(entity)} - Load all defined join entities for an entity</li>
 *   <li>{@code loadJoinEntities(Collection, Class)} - Batch load join entities for multiple entities</li>
 *   <li>{@code loadAllJoinEntities(Collection)} - Batch load all join entities for multiple entities</li>
 * </ul>
 *
 * <p>All {@code deleteJoinEntities} and {@code deleteAllJoinEntities} operations throw
 * {@link UnsupportedOperationException}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a read-only DAO with join entity support
 * public interface UserReadOnlyDao extends ReadOnlyJoinEntityHelper<User, SqlBuilder.PSC, UserReadOnlyDao> {
 *     // All load operations work normally
 *     // All delete operations throw UnsupportedOperationException
 * }
 *
 * UserReadOnlyDao userDao = JdbcUtil.createDao(UserReadOnlyDao.class, dataSource);
 *
 * // Supported operations - all work fine:
 *
 * // Load join entities for a single user
 * User user = userDao.gett(1L);
 * userDao.loadJoinEntities(user, Order.class);   // Loads associated orders
 * userDao.loadJoinEntities(user, "addresses");   // Loads addresses by property name
 * userDao.loadAllJoinEntities(user);   // Loads all defined join entities
 *
 * // Load join entities for multiple users (batch loading)
 * List<User> users = userDao.list(Filters.alwaysTrue());
 * userDao.loadJoinEntities(users, Order.class);   // Batch loads orders for all users
 * userDao.loadAllJoinEntities(users);   // Batch loads all join entities
 *
 * // Load specific join entities by property names
 * userDao.loadJoinEntities(user, Arrays.asList("orders", "addresses"));
 *
 * // Unsupported operations - all throw UnsupportedOperationException:
 * userDao.deleteJoinEntities(user, Order.class);   // Throws exception
 * userDao.deleteJoinEntities(user, "orders");   // Throws exception
 * userDao.deleteAllJoinEntities(user);   // Throws exception
 * userDao.deleteJoinEntities(users, Order.class);   // Throws exception
 * }</pre>
 *
 * @param <T> the entity type that this helper manages
 * @param <SB> the {@link SqlBuilder} type used to generate SQL statements; must be one of
 *             {@code SqlBuilder.PSC}, {@code SqlBuilder.PAC}, or {@code SqlBuilder.PLC}
 * @param <TD> the concrete DAO type itself (self-referencing generic for fluent method chaining)
 * @see JoinEntityHelper
 * @see Dao
 * @see com.landawn.abacus.annotation.JoinedBy
 */
@SuppressWarnings("RedundantThrows")
public interface ReadOnlyJoinEntityHelper<T, SB extends SqlBuilder, TD extends Dao<T, SB, TD>> extends JoinEntityHelper<T, SB, TD> {

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity whose join entities would be deleted
     * @param joinEntityClass the class of the join entity to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code ReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities whose join entities would be deleted
     * @param joinEntityClass the class of the join entity to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code ReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity whose join entities would be deleted
     * @param joinEntityPropName the property name of the join entity to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code ReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final String joinEntityPropName) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities whose join entities would be deleted
     * @param joinEntityPropName the property name of the join entity to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code ReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity whose join entities would be deleted
     * @param joinEntityPropNames the collection of property names identifying the join entities to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code ReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity whose join entities would be deleted
     * @param joinEntityPropNames the collection of property names identifying the join entities to delete
     * @param inParallel {@code true} for parallel execution; {@code false} for sequential
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code ReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity whose join entities would be deleted
     * @param joinEntityPropNames the collection of property names identifying the join entities to delete
     * @param executor the {@link Executor} for parallel execution
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code ReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities whose join entities would be deleted
     * @param joinEntityPropNames the collection of property names identifying the join entities to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code ReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities whose join entities would be deleted
     * @param joinEntityPropNames the collection of property names identifying the join entities to delete
     * @param inParallel {@code true} for parallel execution; {@code false} for sequential
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code ReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities whose join entities would be deleted
     * @param joinEntityPropNames the collection of property names identifying the join entities to delete
     * @param executor the {@link Executor} for parallel execution
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code ReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity whose join entities would all be deleted
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code ReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity whose join entities would all be deleted
     * @param inParallel {@code true} for parallel execution; {@code false} for sequential
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code ReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final boolean inParallel) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity whose join entities would all be deleted
     * @param executor the {@link Executor} for parallel execution
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code ReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final Executor executor) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities whose join entities would all be deleted
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code ReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities whose join entities would all be deleted
     * @param inParallel {@code true} for parallel execution; {@code false} for sequential
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code ReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported operation that always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities whose join entities would all be deleted
     * @param executor the {@link Executor} for parallel execution
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code ReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
