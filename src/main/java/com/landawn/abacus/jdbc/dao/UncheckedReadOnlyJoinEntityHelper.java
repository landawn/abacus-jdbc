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
import com.landawn.abacus.query.SQLBuilder;

/**
 * A read-only interface for managing join entity relationships in database operations without checked exceptions.
 * This interface extends both {@link UncheckedJoinEntityHelper} and {@link ReadOnlyJoinEntityHelper} to provide
 * unchecked exception handling for read-only join entity operations.
 * 
 * <p>All mutation operations (delete operations) in this interface are deprecated and will throw
 * {@link UnsupportedOperationException} when called, enforcing the read-only nature of this interface.</p>
 * 
 * <p>This interface is designed for scenarios where you need to query and read join entity relationships
 * but want to prevent any accidental modifications to the data.</p>
 * 
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Assuming we have a UserDao that extends this interface
 * UserDao userDao = daoFactory.getUserDao();
 * // Read operations are allowed
 * User user = userDao.selectJoinEntitiesById(userId, "orders");
 * // Delete operations will throw UnsupportedOperationException
 * // userDao.deleteJoinEntities(user, Order.class); // This will fail
 * }</pre>
 * 
 * @param <T> the entity type managed by this DAO
 * @param <SB> the SQLBuilder type used for query construction
 * @param <TD> the DAO implementation type (self-referencing for method chaining)
 * 
 * @see UncheckedJoinEntityHelper
 * @see ReadOnlyJoinEntityHelper
 * @see UncheckedSQLException
 */
public interface UncheckedReadOnlyJoinEntityHelper<T, SB extends SQLBuilder, TD extends UncheckedDao<T, SB, TD>>
        extends UncheckedJoinEntityHelper<T, SB, TD>, ReadOnlyJoinEntityHelper<T, SB, TD> {

    /**
     * Attempts to delete join entities of a specific class related to the given entity.
     * This operation is not supported in a read-only interface.
     * 
     * <p>Example that will fail:
     * <pre>{@code
     * User user = userDao.selectById(123L);
     * // This will throw UnsupportedOperationException
     * userDao.deleteJoinEntities(user, Order.class);
     * }</pre>
     *
     * @param entity the entity whose related join entities should be deleted
     * @param joinEntityClass the class of the join entities to delete
     * @return the total count of updated/deleted records (never returned, always throws exception)
     * @throws UncheckedSQLException if a database error occurs (wrapped SQLException)
     * @throws UnsupportedOperationException always thrown as this is a read-only operation
     * @deprecated unsupported Operation - this interface is read-only
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to delete join entities of a specific class related to the given collection of entities.
     * This operation is not supported in a read-only interface.
     * 
     * <p>Example that will fail:
     * <pre>{@code
     * List<User> users = userDao.selectByIds(Arrays.asList(123L, 456L));
     * // This will throw UnsupportedOperationException
     * userDao.deleteJoinEntities(users, Order.class);
     * }</pre>
     *
     * @param entities the collection of entities whose related join entities should be deleted
     * @param joinEntityClass the class of the join entities to delete
     * @return the total count of updated/deleted records (never returned, always throws exception)
     * @throws UncheckedSQLException if a database error occurs (wrapped SQLException)
     * @throws UnsupportedOperationException always thrown as this is a read-only operation
     * @deprecated unsupported Operation - this interface is read-only
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to delete join entities identified by property name related to the given entity.
     * This operation is not supported in a read-only interface.
     * 
     * <p>Example that will fail:
     * <pre>{@code
     * User user = userDao.selectById(123L);
     * // This will throw UnsupportedOperationException
     * userDao.deleteJoinEntities(user, "orders");
     * }</pre>
     *
     * @param entity the entity whose related join entities should be deleted
     * @param joinEntityPropName the property name identifying the join entities to delete
     * @return the total count of updated/deleted records (never returned, always throws exception)
     * @throws UncheckedSQLException if a database error occurs (wrapped SQLException)
     * @throws UnsupportedOperationException always thrown as this is a read-only operation
     * @deprecated unsupported Operation - this interface is read-only
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final String joinEntityPropName) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to delete join entities identified by property name related to the given collection of entities.
     * This operation is not supported in a read-only interface.
     * 
     * <p>Example that will fail:
     * <pre>{@code
     * List<User> users = userDao.selectByIds(Arrays.asList(123L, 456L));
     * // This will throw UnsupportedOperationException
     * userDao.deleteJoinEntities(users, "orders");
     * }</pre>
     *
     * @param entities the collection of entities whose related join entities should be deleted
     * @param joinEntityPropName the property name identifying the join entities to delete
     * @return the total count of updated/deleted records (never returned, always throws exception)
     * @throws UncheckedSQLException if a database error occurs (wrapped SQLException)
     * @throws UnsupportedOperationException always thrown as this is a read-only operation
     * @deprecated unsupported Operation - this interface is read-only
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to delete multiple types of join entities identified by property names related to the given entity.
     * This operation is not supported in a read-only interface.
     * 
     * <p>Example that will fail:
     * <pre>{@code
     * User user = userDao.selectById(123L);
     * // This will throw UnsupportedOperationException
     * userDao.deleteJoinEntities(user, Arrays.asList("orders", "addresses"));
     * }</pre>
     *
     * @param entity the entity whose related join entities should be deleted
     * @param joinEntityPropNames the collection of property names identifying the join entities to delete
     * @return the total count of updated/deleted records (never returned, always throws exception)
     * @throws UncheckedSQLException if a database error occurs (wrapped SQLException)
     * @throws UnsupportedOperationException always thrown as this is a read-only operation
     * @deprecated unsupported Operation - this interface is read-only
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to delete multiple types of join entities with optional parallel execution.
     * This operation is not supported in a read-only interface.
     * 
     * <p>Example that will fail:
     * <pre>{@code
     * User user = userDao.selectById(123L);
     * // This will throw UnsupportedOperationException
     * userDao.deleteJoinEntities(user, Arrays.asList("orders", "addresses"), true);
     * }</pre>
     *
     * @param entity the entity whose related join entities should be deleted
     * @param joinEntityPropNames the collection of property names identifying the join entities to delete
     * @param inParallel whether to execute the deletions in parallel
     * @return the total count of updated/deleted records (never returned, always throws exception)
     * @throws UncheckedSQLException if a database error occurs (wrapped SQLException)
     * @throws UnsupportedOperationException always thrown as this is a read-only operation
     * @deprecated unsupported Operation - this interface is read-only
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to delete multiple types of join entities using a custom executor.
     * This operation is not supported in a read-only interface.
     * 
     * <p>Example that will fail:
     * <pre>{@code
     * User user = userDao.selectById(123L);
     * ExecutorService executor = Executors.newFixedThreadPool(4);
     * // This will throw UnsupportedOperationException
     * userDao.deleteJoinEntities(user, Arrays.asList("orders", "addresses"), executor);
     * }</pre>
     *
     * @param entity the entity whose related join entities should be deleted
     * @param joinEntityPropNames the collection of property names identifying the join entities to delete
     * @param executor the {@code Executor} to use for parallel execution
     * @return the total count of updated/deleted records (never returned, always throws exception)
     * @throws UncheckedSQLException if a database error occurs (wrapped SQLException)
     * @throws UnsupportedOperationException always thrown as this is a read-only operation
     * @deprecated unsupported Operation - this interface is read-only
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to delete multiple types of join entities for a collection of entities.
     * This operation is not supported in a read-only interface.
     * 
     * <p>Example that will fail:
     * <pre>{@code
     * List<User> users = userDao.selectByIds(Arrays.asList(123L, 456L));
     * // This will throw UnsupportedOperationException
     * userDao.deleteJoinEntities(users, Arrays.asList("orders", "addresses"));
     * }</pre>
     *
     * @param entities the collection of entities whose related join entities should be deleted
     * @param joinEntityPropNames the collection of property names identifying the join entities to delete
     * @return the total count of updated/deleted records (never returned, always throws exception)
     * @throws UncheckedSQLException if a database error occurs (wrapped SQLException)
     * @throws UnsupportedOperationException always thrown as this is a read-only operation
     * @deprecated unsupported Operation - this interface is read-only
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to delete multiple types of join entities for a collection of entities with optional parallel execution.
     * This operation is not supported in a read-only interface.
     * 
     * <p>Example that will fail:
     * <pre>{@code
     * List<User> users = userDao.selectByIds(Arrays.asList(123L, 456L));
     * // This will throw UnsupportedOperationException
     * userDao.deleteJoinEntities(users, Arrays.asList("orders", "addresses"), true);
     * }</pre>
     *
     * @param entities the collection of entities whose related join entities should be deleted
     * @param joinEntityPropNames the collection of property names identifying the join entities to delete
     * @param inParallel whether to execute the deletions in parallel
     * @return the total count of updated/deleted records (never returned, always throws exception)
     * @throws UncheckedSQLException if a database error occurs (wrapped SQLException)
     * @throws UnsupportedOperationException always thrown as this is a read-only operation
     * @deprecated unsupported Operation - this interface is read-only
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to delete multiple types of join entities for a collection of entities using a custom executor.
     * This operation is not supported in a read-only interface.
     * 
     * <p>Example that will fail:
     * <pre>{@code
     * List<User> users = userDao.selectByIds(Arrays.asList(123L, 456L));
     * ExecutorService executor = Executors.newFixedThreadPool(4);
     * // This will throw UnsupportedOperationException
     * userDao.deleteJoinEntities(users, Arrays.asList("orders", "addresses"), executor);
     * }</pre>
     *
     * @param entities the collection of entities whose related join entities should be deleted
     * @param joinEntityPropNames the collection of property names identifying the join entities to delete
     * @param executor the {@code Executor} to use for parallel execution
     * @return the total count of updated/deleted records (never returned, always throws exception)
     * @throws UncheckedSQLException if a database error occurs (wrapped SQLException)
     * @throws UnsupportedOperationException always thrown as this is a read-only operation
     * @deprecated unsupported Operation - this interface is read-only
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to delete all join entities related to the given entity.
     * This operation is not supported in a read-only interface.
     * 
     * <p>Example that will fail:
     * <pre>{@code
     * User user = userDao.selectById(123L);
     * // This will throw UnsupportedOperationException
     * userDao.deleteAllJoinEntities(user);
     * }</pre>
     *
     * @param entity the entity whose all related join entities should be deleted
     * @return the total count of updated/deleted records (never returned, always throws exception)
     * @throws UncheckedSQLException if a database error occurs (wrapped SQLException)
     * @throws UnsupportedOperationException always thrown as this is a read-only operation
     * @deprecated unsupported Operation - this interface is read-only
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to delete all join entities related to the given entity with optional parallel execution.
     * This operation is not supported in a read-only interface.
     * 
     * <p>Example that will fail:
     * <pre>{@code
     * User user = userDao.selectById(123L);
     * // This will throw UnsupportedOperationException
     * userDao.deleteAllJoinEntities(user, true);
     * }</pre>
     *
     * @param entity the entity whose all related join entities should be deleted
     * @param inParallel whether to execute the deletions in parallel
     * @return the total count of updated/deleted records (never returned, always throws exception)
     * @throws UncheckedSQLException if a database error occurs (wrapped SQLException)
     * @throws UnsupportedOperationException always thrown as this is a read-only operation
     * @deprecated unsupported Operation - this interface is read-only
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final boolean inParallel) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to delete all join entities related to the given entity using a custom executor.
     * This operation is not supported in a read-only interface.
     * 
     * <p>Example that will fail:
     * <pre>{@code
     * User user = userDao.selectById(123L);
     * ExecutorService executor = Executors.newFixedThreadPool(4);
     * // This will throw UnsupportedOperationException
     * userDao.deleteAllJoinEntities(user, executor);
     * }</pre>
     *
     * @param entity the entity whose all related join entities should be deleted
     * @param executor the {@code Executor} to use for parallel execution
     * @return the total count of updated/deleted records (never returned, always throws exception)
     * @throws UncheckedSQLException if a database error occurs (wrapped SQLException)
     * @throws UnsupportedOperationException always thrown as this is a read-only operation
     * @deprecated unsupported Operation - this interface is read-only
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final Executor executor) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to delete all join entities related to the given collection of entities.
     * This operation is not supported in a read-only interface.
     * 
     * <p>Example that will fail:
     * <pre>{@code
     * List<User> users = userDao.selectByIds(Arrays.asList(123L, 456L));
     * // This will throw UnsupportedOperationException
     * userDao.deleteAllJoinEntities(users);
     * }</pre>
     *
     * @param entities the collection of entities whose all related join entities should be deleted
     * @return the total count of updated/deleted records (never returned, always throws exception)
     * @throws UncheckedSQLException if a database error occurs (wrapped SQLException)
     * @throws UnsupportedOperationException always thrown as this is a read-only operation
     * @deprecated unsupported Operation - this interface is read-only
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to delete all join entities related to the given collection of entities with optional parallel execution.
     * This operation is not supported in a read-only interface.
     * 
     * <p>Example that will fail:
     * <pre>{@code
     * List<User> users = userDao.selectByIds(Arrays.asList(123L, 456L));
     * // This will throw UnsupportedOperationException
     * userDao.deleteAllJoinEntities(users, true);
     * }</pre>
     *
     * @param entities the collection of entities whose all related join entities should be deleted
     * @param inParallel whether to execute the deletions in parallel
     * @return the total count of updated/deleted records (never returned, always throws exception)
     * @throws UncheckedSQLException if a database error occurs (wrapped SQLException)
     * @throws UnsupportedOperationException always thrown as this is a read-only operation
     * @deprecated unsupported Operation - this interface is read-only
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to delete all join entities related to the given collection of entities using a custom executor.
     * This operation is not supported in a read-only interface.
     * 
     * <p>Example that will fail:
     * <pre>{@code
     * List<User> users = userDao.selectByIds(Arrays.asList(123L, 456L));
     * ExecutorService executor = Executors.newFixedThreadPool(4);
     * // This will throw UnsupportedOperationException
     * userDao.deleteAllJoinEntities(users, executor);
     * }</pre>
     *
     * @param entities the collection of entities whose all related join entities should be deleted
     * @param executor the {@code Executor} to use for parallel execution
     * @return the total count of updated/deleted records (never returned, always throws exception)
     * @throws UncheckedSQLException if a database error occurs (wrapped SQLException)
     * @throws UnsupportedOperationException always thrown as this is a read-only operation
     * @deprecated unsupported Operation - this interface is read-only
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}