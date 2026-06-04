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

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.query.SqlBuilder;

/**
 * A read-only interface for managing join entity relationships in database operations without checked exceptions.
 * This interface extends both {@link UncheckedJoinEntityHelper} and {@link ReadOnlyJoinEntityHelper} to provide
 * unchecked exception handling for read-only join entity operations.
 *
 * <p>Load operations (e.g., {@code loadJoinEntities}, {@code loadAllJoinEntities}) inherited from
 * {@link UncheckedJoinEntityHelper} throw {@link UncheckedSQLException} instead of the checked
 * {@link java.sql.SQLException}.</p>
 *
 * <p>All mutation operations (the {@code deleteJoinEntities} and {@code deleteAllJoinEntities} families)
 * in this interface are deprecated and throw {@link UnsupportedOperationException} when called,
 * enforcing the read-only nature of this interface.</p>
 *
 * <p>This interface is designed for scenarios where you need to query and read join entity relationships
 * but want to prevent any accidental modifications to the data while avoiding checked exception handling.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Define a read-only DAO with unchecked exceptions
 * public interface UserReadOnlyDao extends UncheckedReadOnlyJoinEntityHelper<User, SqlBuilder.PSC, UserReadOnlyDao> {
 *     // All load operations work normally with unchecked exceptions
 *     // All delete operations will throw UnsupportedOperationException
 * }
 *
 * UserReadOnlyDao userDao = JdbcUtil.createDao(UserReadOnlyDao.class, dataSource);
 *
 * // Read operations work fine - no checked exceptions
 * User user = userDao.gett(1L);
 * userDao.loadJoinEntities(user, "orders");   // Loads successfully
 *
 * List<User> users = userDao.list(Filters.eq("status", "active"));
 * userDao.loadAllJoinEntities(users);   // Loads all join entities
 *
 * // Delete operations are blocked
 * try {
 *     userDao.deleteJoinEntities(user, Order.class);
 *     // Will throw UnsupportedOperationException
 * } catch (UnsupportedOperationException e) {
 *     // Expected - this is a read-only interface
 * }
 * }</pre>
 *
 * @param <T> the entity type that this helper manages
 * @param <SB> the SqlBuilder type used to generate SQL scripts (must be one of SqlBuilder.PSC/PAC/PLC/PSB)
 * @param <TD> the DAO type that hosts this helper, bound to {@link UncheckedDao}
 *
 * @see UncheckedJoinEntityHelper
 * @see ReadOnlyJoinEntityHelper
 * @see UncheckedSQLException
 */
public interface UncheckedReadOnlyJoinEntityHelper<T, SB extends SqlBuilder, TD extends UncheckedDao<T, SB, TD>>
        extends UncheckedJoinEntityHelper<T, SB, TD>, ReadOnlyJoinEntityHelper<T, SB, TD> {

    /**
     * Unsupported delete operation that always throws {@link UnsupportedOperationException} in read-only mode.
     *
     * @param entity the entity whose join entities would be deleted
     * @param joinEntityClass the class of the join entity to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported delete operation that always throws {@link UnsupportedOperationException} in read-only mode.
     *
     * @param entities the collection of entities whose join entities would be deleted
     * @param joinEntityClass the class of the join entity to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported delete operation that always throws {@link UnsupportedOperationException} in read-only mode.
     *
     * @param entity the entity whose join entities would be deleted
     * @param joinEntityPropName the property name of the join entity to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final String joinEntityPropName) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported delete operation that always throws {@link UnsupportedOperationException} in read-only mode.
     *
     * @param entities the collection of entities whose join entities would be deleted
     * @param joinEntityPropName the property name of the join entity to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported delete operation that always throws {@link UnsupportedOperationException} in read-only mode.
     *
     * @param entity the entity whose join entities would be deleted
     * @param joinEntityPropNames the collection of property names identifying the join entities to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported delete operation that always throws {@link UnsupportedOperationException} in read-only mode.
     *
     * @param entity the entity whose join entities would be deleted
     * @param joinEntityPropNames the collection of property names identifying the join entities to delete
     * @param inParallel {@code true} for parallel execution; {@code false} for sequential
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported delete operation that always throws {@link UnsupportedOperationException} in read-only mode.
     *
     * @param entity the entity whose join entities would be deleted
     * @param joinEntityPropNames the collection of property names identifying the join entities to delete
     * @param executor the {@link Executor} for parallel execution
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported delete operation that always throws {@link UnsupportedOperationException} in read-only mode.
     *
     * @param entities the collection of entities whose join entities would be deleted
     * @param joinEntityPropNames the collection of property names identifying the join entities to delete
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported delete operation that always throws {@link UnsupportedOperationException} in read-only mode.
     *
     * @param entities the collection of entities whose join entities would be deleted
     * @param joinEntityPropNames the collection of property names identifying the join entities to delete
     * @param inParallel {@code true} for parallel execution; {@code false} for sequential
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported delete operation that always throws {@link UnsupportedOperationException} in read-only mode.
     *
     * @param entities the collection of entities whose join entities would be deleted
     * @param joinEntityPropNames the collection of property names identifying the join entities to delete
     * @param executor the {@link Executor} for parallel execution
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
            throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported delete operation that always throws {@link UnsupportedOperationException} in read-only mode.
     *
     * @param entity the entity whose join entities would all be deleted
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported delete operation that always throws {@link UnsupportedOperationException} in read-only mode.
     *
     * @param entity the entity whose join entities would all be deleted
     * @param inParallel {@code true} for parallel execution; {@code false} for sequential
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final boolean inParallel) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported delete operation that always throws {@link UnsupportedOperationException} in read-only mode.
     *
     * @param entity the entity whose join entities would all be deleted
     * @param executor the {@link Executor} for parallel execution
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final Executor executor) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported delete operation that always throws {@link UnsupportedOperationException} in read-only mode.
     *
     * @param entities the collection of entities whose join entities would all be deleted
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported delete operation that always throws {@link UnsupportedOperationException} in read-only mode.
     *
     * @param entities the collection of entities whose join entities would all be deleted
     * @param inParallel {@code true} for parallel execution; {@code false} for sequential
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Unsupported delete operation that always throws {@link UnsupportedOperationException} in read-only mode.
     *
     * @param entities the collection of entities whose join entities would all be deleted
     * @param executor the {@link Executor} for parallel execution
     * @return never returns normally
     * @throws UnsupportedOperationException always, since deletions are not permitted in read-only mode
     * @deprecated Unsupported in {@code UncheckedReadOnlyJoinEntityHelper}. Deletions are prohibited.
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
