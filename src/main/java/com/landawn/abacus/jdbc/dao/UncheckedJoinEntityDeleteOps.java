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

import java.sql.SQLException;
import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.jdbc.CannotGetJdbcConnectionException;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.UncheckedInterruptedException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.SqlTransaction;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.stream.Stream;

/**
 * Delete-side view of {@link UncheckedJoinEntityHelper}: the join-entity <i>delete</i> operations
 * ({@code deleteJoinEntities}/{@code deleteAllJoinEntities} families) that throw
 * {@link UncheckedSQLException} instead of the checked
 * {@link SQLException}.
 *
 * <p>Unlike its checked counterpart {@link JoinEntityDeleteOps} (which is read-independent), this
 * interface also extends {@link UncheckedJoinEntityReadOps} for hierarchy plumbing, so it already
 * carries the unchecked read side; {@link UncheckedJoinEntityHelper} completes the picture by adding
 * {@link JoinEntityHelper}. It is intentionally omitted from read-only unchecked DAOs.</p>
 *
 * <p><b>&#9888; Warning:</b> Parallel delete operations may continue after another task fails and are
 * not automatically part of the caller's thread-bound transaction. Partial database deletion is
 * therefore possible unless the application supplies stronger coordination.</p>
 *
 * @param <T> the entity type that this helper manages
 * @param <TD> the companion {@link UncheckedDao} type that owns this helper
 * @see JoinEntityDeleteOps
 * @see UncheckedJoinEntityHelper
 * @see com.landawn.abacus.annotation.JoinedBy
 */
@SuppressWarnings("resource")
sealed interface UncheckedJoinEntityDeleteOps<T, TD extends UncheckedDao<T, TD>> extends JoinEntityDeleteOps<T, TD>, UncheckedJoinEntityReadOps<T, TD>
        permits UncheckedJoinEntityHelper {
    /**
     * Deletes all join entities of the specified type for a single entity.
     * If multiple properties in the entity class are joined to the specified type, all of them are deleted within a single transaction.
     * This deletes the related rows from the database; the in-memory join properties of {@code entity} are left unchanged.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.getOrNull(userId);
     * // Delete all orders for this user
     * int deletedCount = userDao.deleteJoinEntities(user, Order.class);
     * }</pre>
     *
     * @param entity the entity whose join entities should be deleted
     * @param joinEntityClass the class of join entities to delete
     * @return the total number of deleted records
     * @throws IllegalArgumentException if {@code entity} or {@code joinEntityClass} is {@code null},
     *                                  or if no join property of the specified type is found in the entity class,
     *                                  or a join being deleted has a disallowed null/default key
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans, or an existing transaction on the current thread
     *         is no longer active and cannot accept the internally required transaction scope
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing, binding,
     *         or executing a DELETE statement fails
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     */
    @SuppressWarnings("deprecation")
    @Override
    default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass)
            throws IllegalArgumentException, IllegalStateException, CannotGetJdbcConnectionException, UncheckedSQLException, ArithmeticException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotNull(joinEntityClass, cs.joinEntityClass);

        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property of type {} found in class {}", joinEntityClass, targetEntityClass);

        if (joinEntityPropNames.size() == 1) {
            return deleteJoinEntities(entity, joinEntityPropNames.get(0));
        } else {
            int result = 0;
            final DataSource ds = DaoUtil.getReadOps(this).dataSource();
            final SqlTransaction tran = JdbcUtil.beginTransaction(ds);
            Throwable failure = null;

            try {
                for (final String joinEntityPropName : joinEntityPropNames) {
                    result = Math.addExact(result, deleteJoinEntities(entity, joinEntityPropName));
                }
                tran.commit();
            } catch (final Throwable e) { //NOSONAR
                failure = e;
                throw e;
            } finally {
                try {
                    tran.rollbackIfNotCommitted();
                } catch (final RuntimeException | Error rollbackFailure) {
                    if (failure == null) {
                        throw rollbackFailure;
                    }

                    DaoUtil.addSuppressedIfDifferent(failure, rollbackFailure);
                }
            }

            return result;
        }
    }

    /**
     * Deletes all join entities of the specified type for a collection of entities.
     * If multiple properties in the entity class are joined to the specified type, all of them are deleted within a single transaction.
     * This deletes the related rows from the database; the in-memory join properties of the entities are left unchanged.
     * If {@code entities} is {@code null} or empty, this method returns 0 without executing a DELETE statement.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> usersToClean = getInactiveUsers();
     * // Delete all orders for these users
     * int totalDeleted = userDao.deleteJoinEntities(usersToClean, Order.class);
     * }</pre>
     *
     * @param entities the collection of entities whose join entities should be deleted. If {@code null} or empty, 0 is returned
     * @param joinEntityClass the class of join entities to delete
     * @return the total number of deleted records, or 0 if {@code entities} is empty
     * @throws IllegalArgumentException if {@code joinEntityClass} is {@code null},
     *                                  or if {@code entities} is not empty and no join property of the specified type is found in the entity class,
     *                                  or a join being deleted has a disallowed null/default key,
     *         or if a {@code null} entity is encountered while reading its join keys or properties
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans, or an existing transaction on the current thread
     *         is no longer active and cannot accept the internally required transaction scope
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing, binding,
     *         or executing a DELETE statement fails
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     */
    @SuppressWarnings("deprecation")
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass)
            throws IllegalArgumentException, IllegalStateException, CannotGetJdbcConnectionException, UncheckedSQLException, ArithmeticException {
        N.checkArgNotNull(joinEntityClass, cs.joinEntityClass);

        if (N.isEmpty(entities)) {
            return 0;
        }

        final Class<?> targetEntityClass = targetEntityClass();
        final List<String> joinEntityPropNames = DaoUtil.getJoinEntityPropNamesByType(targetDaoInterface(), targetEntityClass, targetTableName(),
                joinEntityClass);
        N.checkArgument(N.notEmpty(joinEntityPropNames), "No joined property of type {} found in class {}", joinEntityClass, targetEntityClass);

        if (joinEntityPropNames.size() == 1) {
            return deleteJoinEntities(entities, joinEntityPropNames.get(0));
        } else {
            int result = 0;
            final DataSource ds = DaoUtil.getReadOps(this).dataSource();
            final SqlTransaction tran = JdbcUtil.beginTransaction(ds);
            Throwable failure = null;

            try {
                for (final String joinEntityPropName : joinEntityPropNames) {
                    result = Math.addExact(result, deleteJoinEntities(entities, joinEntityPropName));
                }
                tran.commit();
            } catch (final Throwable e) { //NOSONAR
                failure = e;
                throw e;
            } finally {
                try {
                    tran.rollbackIfNotCommitted();
                } catch (final RuntimeException | Error rollbackFailure) {
                    if (failure == null) {
                        throw rollbackFailure;
                    }

                    DaoUtil.addSuppressedIfDifferent(failure, rollbackFailure);
                }
            }

            return result;
        }
    }

    /**
     * Deletes join entities for a single entity by property name.
     * The property name must correspond to a field annotated with {@code @JoinedBy}.
     *
     * <p>It deletes all related entities for the specified join property. The deletion is based on
     * the foreign key relationship defined in the {@code @JoinedBy} annotation. The method
     * constructs and executes a DELETE statement targeting the join entity table with a WHERE
     * clause matching the foreign key value(s) from the parent entity.</p>
     *
     * <p>Unlike the checked version in {@link JoinEntityDeleteOps}, this method throws {@link UncheckedSQLException}
     * instead of {@link SQLException}, making it suitable for use in functional programming contexts
     * and lambda expressions without requiring explicit exception handling.</p>
     *
     * <p>Important notes:</p>
     * <ul>
     *   <li>This operation does NOT modify the in-memory join property of the entity</li>
     *   <li>The deletion is permanent and cannot be rolled back unless within a transaction</li>
     *   <li>Cascade deletion of further nested entities depends on database constraints</li>
     *   <li>For transactional deletion of multiple properties, use {@link #deleteJoinEntities(Object, Collection)}</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.getOrNull(userId);
     * // Delete all addresses for this user
     * int deleted = userDao.deleteJoinEntities(user, "addresses");
     *
     * // Use in functional context without try-catch
     * java.util.Optional.ofNullable(user)
     *     .map(u -> userDao.deleteJoinEntities(u, "temporaryData"))
     *     .ifPresent(count -> System.out.println("Deleted " + count + " records"));
     * }</pre>
     *
     * @param entity the entity whose join entities should be deleted. Must not be {@code null}
     * @param joinEntityPropName the property name of the join entities to delete. Must be a valid
     *                           property name that exists in the entity class and is annotated
     *                           with {@code @JoinedBy}
     * @return the total number of deleted records. Returns 0 if no matching records were found
     * @throws IllegalArgumentException if {@code entity} is {@code null}, or if {@code joinEntityPropName} is {@code null} or empty,
     *                                  or does not exist or is not properly annotated with {@code @JoinedBy},
     *                                  or a join being deleted has a disallowed null/default key
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans, or an existing transaction on the current thread
     *         is no longer active and cannot accept the internally required transaction scope
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing, binding,
     *         or executing a DELETE statement fails
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     */
    @Override
    int deleteJoinEntities(final T entity, final String joinEntityPropName)
            throws IllegalArgumentException, IllegalStateException, CannotGetJdbcConnectionException, UncheckedSQLException, ArithmeticException;

    /**
     * Deletes join entities for a collection of entities by property name.
     * The property name must correspond to a field annotated with {@code @JoinedBy}.
     *
     * <p>It efficiently deletes all related entities for multiple parent entities in a batch operation.
     * The implementation typically uses an IN clause to delete all related records in one or more SQL
     * statements, avoiding the N+1 delete problem. For large collections, the deletion may be
     * automatically batched to prevent SQL statement size limits from being exceeded.</p>
     *
     * <p>Unlike the checked version in {@link JoinEntityDeleteOps}, this method throws {@link UncheckedSQLException}
     * instead of {@link SQLException}, making it suitable for use in functional programming contexts
     * such as Stream operations and lambda expressions without requiring explicit exception handling.</p>
     *
     * <p>Performance characteristics:</p>
     * <ul>
     *   <li>For N parent entities, executes O(1) or O(N / batch size) DELETE statements instead of O(N)</li>
     *   <li>Much more efficient than deleting join entities one parent at a time</li>
     *   <li>The actual number of deleted records may be less than or greater than the number of parent entities</li>
     * </ul>
     *
     * <p>Important notes:</p>
     * <ul>
     *   <li>This operation does NOT modify the in-memory join properties of the entities</li>
     *   <li>All deletions are permanent unless executed within a transaction</li>
     *   <li>For transactional deletion of multiple properties, use {@link #deleteJoinEntities(Collection, Collection)}</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getDeactivatedUsers();
     * // Delete all payment methods for these users
     * int totalDeleted = userDao.deleteJoinEntities(users, "paymentMethods");
     *
     * // Use in stream context
     * List<User> inactiveUsers = userDao.list(Filters.eq("status", "INACTIVE"))
     *     .stream()
     *     .toList();
     * int deletedOrders = userDao.deleteJoinEntities(inactiveUsers, "orders");
     * }</pre>
     *
     * @param entities the collection of entities whose join entities should be deleted.
     *                 If {@code null} or empty, this method returns 0 without executing a DELETE statement
     * @param joinEntityPropName the property name of the join entities to delete. Must be a valid
     *                           property name that exists in the entity class and is annotated
     *                           with {@code @JoinedBy}
     * @return the total number of deleted records across all parent entities. Returns 0 if no
     *         matching records were found or if {@code entities} is {@code null} or empty
     * @throws IllegalArgumentException if {@code joinEntityPropName} is {@code null} or empty,
     *                                  or does not exist or is not properly annotated with {@code @JoinedBy},
     *                                  or a join being deleted has a disallowed null/default key,
     *         or if a {@code null} entity is encountered while reading its join keys or properties
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans, or an existing transaction on the current thread
     *         is no longer active and cannot accept the internally required transaction scope
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing, binding,
     *         or executing a DELETE statement fails
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     */
    @Override
    int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName)
            throws IllegalArgumentException, IllegalStateException, CannotGetJdbcConnectionException, UncheckedSQLException, ArithmeticException;

    /**
     * Deletes join entities for multiple property names of a single entity.
     * This operation is performed within a transaction when multiple properties are specified.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.getOrNull(userId);
     * // Delete all orders and reviews for this user
     * int deleted = userDao.deleteJoinEntities(
     *     user,
     *     Arrays.asList("orders", "reviews")
     * );
     * }</pre>
     *
     * @param entity the entity whose join entities should be deleted
     * @param joinEntityPropNames the property names of the join entities to delete. If {@code null} or empty, 0 is returned
     * @return the total number of deleted records, or 0 if {@code joinEntityPropNames} is empty
     * @throws IllegalArgumentException if {@code entity} is {@code null},
     *                                  or any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy},
     *                                  or a join being deleted has a disallowed null/default key
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans, or an existing transaction on the current thread
     *         is no longer active and cannot accept the internally required transaction scope
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing, binding,
     *         or executing a DELETE statement fails
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     */
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames)
            throws IllegalArgumentException, IllegalStateException, CannotGetJdbcConnectionException, UncheckedSQLException, ArithmeticException {
        N.checkArgNotNull(entity, cs.entity);

        if (N.isEmpty(joinEntityPropNames)) {
            return 0;
        }

        if (joinEntityPropNames.size() == 1) {
            return deleteJoinEntities(entity, N.firstOrNullIfEmpty(joinEntityPropNames));
        } else {
            int result = 0;
            final DataSource ds = DaoUtil.getReadOps(this).dataSource();
            final SqlTransaction tran = JdbcUtil.beginTransaction(ds);
            Throwable failure = null;

            try {
                for (final String joinEntityPropName : joinEntityPropNames) {
                    result = Math.addExact(result, deleteJoinEntities(entity, joinEntityPropName));
                }
                tran.commit();
            } catch (final Throwable e) { //NOSONAR
                failure = e;
                throw e;
            } finally {
                try {
                    tran.rollbackIfNotCommitted();
                } catch (final RuntimeException | Error rollbackFailure) {
                    if (failure == null) {
                        throw rollbackFailure;
                    }

                    DaoUtil.addSuppressedIfDifferent(failure, rollbackFailure);
                }
            }

            return result;
        }
    }

    /**
     * Deletes join entities for multiple property names of a single entity using a custom executor.
     * Note: Operations executed in multiple threads will not be completed in a single transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.getOrNull(userId);
     * java.util.concurrent.ExecutorService executor =
     *     java.util.concurrent.Executors.newFixedThreadPool(4);
     * try {
     *     // Delete multiple related entities in parallel
     *     int deleted = userDao.deleteJoinEntities(
     *         user,
     *         Arrays.asList("orders", "reviews", "wishlistItems"),
     *         executor
     *     );
     * } finally {
     *     executor.shutdown();
     * }
     * }</pre>
     *
     * @param entity the entity whose join entities should be deleted
     * @param joinEntityPropNames the property names of the join entities to delete. If {@code null} or empty, 0 is returned
     * @param executor the {@code Executor} to use for parallel execution
     * @return the total number of deleted records, or 0 if {@code joinEntityPropNames} is empty
     * @throws IllegalArgumentException if {@code entity} or {@code executor} is {@code null}, or if any property name in
     *                                  {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy},
     *                                  or a join being deleted has a disallowed null/default key
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans, or an existing transaction on the current thread
     *         is no longer active and cannot accept the internally required transaction scope
     * @throws RejectedExecutionException if parallel execution is requested and the executor rejects a join task
     * @throws UncheckedInterruptedException if the calling thread is interrupted while waiting for the parallel delete tasks to finish
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and the data source returns
     *         {@code null} or throws an {@code IllegalStateException}
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing, binding,
     *         or executing a DELETE statement fails
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @deprecated parallel deletion cannot be performed within a single transaction; prefer
     *             {@link #deleteJoinEntities(Object, Collection)} for transactional behavior
     */
    @Beta
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor)
            throws IllegalArgumentException, IllegalStateException, RejectedExecutionException, UncheckedInterruptedException, CannotGetJdbcConnectionException,
            UncheckedSQLException, ArithmeticException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotNull(executor, cs.executor);

        if (N.isEmpty(joinEntityPropNames)) {
            return 0;
        }

        final List<ContinuableFuture<Integer>> futures = Stream.of(joinEntityPropNames)
                .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entity, joinEntityPropName), executor))
                .toList();

        return DaoUtil.uncheckedCompleteSum(futures);
    }

    /**
     * Deletes join entities for multiple property names of a single entity, optionally in parallel.
     * Note: parallel deletion may not complete in a single transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.getOrNull(userId);
     * // Delete multiple relationships in parallel
     * int deleted = userDao.deleteJoinEntities(
     *     user,
     *     Arrays.asList("orders", "reviews", "notifications"),
     *     true  // parallel deletion
     * );
     * }</pre>
     *
     * @param entity the entity whose join entities should be deleted
     * @param joinEntityPropNames the property names of the join entities to delete. If {@code null} or empty, 0 is returned
     * @param inParallel if {@code true}, join properties are deleted in parallel; if {@code false}, deleted sequentially
     * @return the total number of deleted records, or 0 if {@code joinEntityPropNames} is empty
     * @throws IllegalArgumentException if {@code entity} is {@code null},
     *                                  or any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy},
     *                                  or a join being deleted has a disallowed null/default key
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans, or an existing transaction on the current thread
     *         is no longer active and cannot accept the internally required transaction scope
     * @throws RejectedExecutionException if parallel execution is requested and the executor rejects a join task
     * @throws UncheckedInterruptedException if the calling thread is interrupted while waiting for the parallel delete tasks to finish
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing, binding,
     *         or executing a DELETE statement fails
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @deprecated when {@code inParallel} is {@code true} the deletions are not performed within a single
     *             transaction; prefer {@link #deleteJoinEntities(Object, Collection)} for transactional behavior
     */
    @Beta
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws IllegalArgumentException, IllegalStateException, RejectedExecutionException, UncheckedInterruptedException, CannotGetJdbcConnectionException,
            UncheckedSQLException, ArithmeticException {
        if (inParallel) {
            return deleteJoinEntities(entity, joinEntityPropNames, executor());
        } else {
            return deleteJoinEntities(entity, joinEntityPropNames);
        }
    }

    /**
     * Deletes join entities for multiple property names for multiple entities.
     * This operation is performed within a transaction when multiple properties are specified.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getDeletedUsers();
     * // Clean up multiple relationships
     * int deleted = userDao.deleteJoinEntities(
     *     users,
     *     Arrays.asList("orders", "addresses", "preferences")
     * );
     * }</pre>
     *
     * @param entities the collection of entities whose join entities should be deleted. If {@code null} or empty, 0 is returned
     * @param joinEntityPropNames the property names of the join entities to delete. If {@code null} or empty, 0 is returned
     * @return the total number of deleted records, or 0 if {@code entities} or {@code joinEntityPropNames} is empty
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy},
     *                                  or a join being deleted has a disallowed null/default key,
     *         or if a {@code null} entity is encountered while reading its join keys or properties
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans, or an existing transaction on the current thread
     *         is no longer active and cannot accept the internally required transaction scope
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing, binding,
     *         or executing a DELETE statement fails
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     */
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames)
            throws IllegalArgumentException, IllegalStateException, CannotGetJdbcConnectionException, UncheckedSQLException, ArithmeticException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return 0;
        }

        if (joinEntityPropNames.size() == 1) {
            return deleteJoinEntities(entities, N.firstOrNullIfEmpty(joinEntityPropNames));
        } else {
            int result = 0;
            final DataSource ds = DaoUtil.getReadOps(this).dataSource();
            final SqlTransaction tran = JdbcUtil.beginTransaction(ds);
            Throwable failure = null;

            try {
                for (final String joinEntityPropName : joinEntityPropNames) {
                    result = Math.addExact(result, deleteJoinEntities(entities, joinEntityPropName));
                }
                tran.commit();
            } catch (final Throwable e) { //NOSONAR
                failure = e;
                throw e;
            } finally {
                try {
                    tran.rollbackIfNotCommitted();
                } catch (final RuntimeException | Error rollbackFailure) {
                    if (failure == null) {
                        throw rollbackFailure;
                    }

                    DaoUtil.addSuppressedIfDifferent(failure, rollbackFailure);
                }
            }

            return result;
        }
    }

    /**
     * Deletes join entities for multiple property names for multiple entities, optionally in parallel.
     * Note: parallel deletion may not complete in a single transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getObsoleteUsers();
     * int deleted = userDao.deleteJoinEntities(
     *     users,
     *     Arrays.asList("orders", "transactions"),
     *     true  // parallel deletion
     * );
     * }</pre>
     *
     * @param entities the collection of entities whose join entities should be deleted. If {@code null} or empty, 0 is returned
     * @param joinEntityPropNames the property names of the join entities to delete. If {@code null} or empty, 0 is returned
     * @param inParallel if {@code true}, join properties are deleted in parallel; if {@code false}, deleted sequentially
     * @return the total number of deleted records, or 0 if {@code entities} or {@code joinEntityPropNames} is empty
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy},
     *                                  or a join being deleted has a disallowed null/default key,
     *         or if a {@code null} entity is encountered while reading its join keys or properties
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans, or an existing transaction on the current thread
     *         is no longer active and cannot accept the internally required transaction scope
     * @throws RejectedExecutionException if parallel execution is requested and the executor rejects a join task
     * @throws UncheckedInterruptedException if the calling thread is interrupted while waiting for the parallel delete tasks to finish
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing, binding,
     *         or executing a DELETE statement fails
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @deprecated when {@code inParallel} is {@code true} the deletions are not performed within a single
     *             transaction; prefer {@link #deleteJoinEntities(Collection, Collection)} for transactional behavior
     */
    @Beta
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws IllegalArgumentException, IllegalStateException, RejectedExecutionException, UncheckedInterruptedException, CannotGetJdbcConnectionException,
            UncheckedSQLException, ArithmeticException {
        if (inParallel) {
            return deleteJoinEntities(entities, joinEntityPropNames, executor());
        } else {
            return deleteJoinEntities(entities, joinEntityPropNames);
        }
    }

    /**
     * Deletes join entities for multiple property names for multiple entities using a custom executor.
     * Note: operations executed in multiple threads will not be completed in a single transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getUsersToCleanup();
     * java.util.concurrent.ExecutorService cleanupPool =
     *     new java.util.concurrent.ForkJoinPool(8);
     * try {
     *     int deleted = userDao.deleteJoinEntities(
     *         users,
     *         Arrays.asList("logs", "sessions", "tempData"),
     *         cleanupPool
     *     );
     * } finally {
     *     cleanupPool.shutdown();
     * }
     * }</pre>
     *
     * @param entities the collection of entities whose join entities should be deleted. If {@code null} or empty, 0 is returned
     * @param joinEntityPropNames the property names of the join entities to delete. If {@code null} or empty, 0 is returned
     * @param executor the {@code Executor} to use for parallel execution
     * @return the total number of deleted records, or 0 if {@code entities} or {@code joinEntityPropNames} is empty
     * @throws IllegalArgumentException if {@code executor} is {@code null}, or if any property name in
     *                                  {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy},
     *                                  or a join being deleted has a disallowed null/default key,
     *         or if a {@code null} entity is encountered while reading its join keys or properties
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans, or an existing transaction on the current thread
     *         is no longer active and cannot accept the internally required transaction scope
     * @throws RejectedExecutionException if parallel execution is requested and the executor rejects a join task
     * @throws UncheckedInterruptedException if the calling thread is interrupted while waiting for the parallel delete tasks to finish
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and the data source returns
     *         {@code null} or throws an {@code IllegalStateException}
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing, binding,
     *         or executing a DELETE statement fails
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @deprecated parallel deletion cannot be performed within a single transaction; prefer
     *             {@link #deleteJoinEntities(Collection, Collection)} for transactional behavior
     */
    @Beta
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
            throws IllegalArgumentException, IllegalStateException, RejectedExecutionException, UncheckedInterruptedException, CannotGetJdbcConnectionException,
            UncheckedSQLException, ArithmeticException {
        N.checkArgNotNull(executor, cs.executor);

        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return 0;
        }

        final List<ContinuableFuture<Integer>> futures = Stream.of(joinEntityPropNames)
                .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entities, joinEntityPropName), executor))
                .toList();

        return DaoUtil.uncheckedCompleteSum(futures);
    }

    /**
     * Deletes all join entities for a single entity.
     * This deletes the rows referenced by every property annotated with {@code @JoinedBy};
     * the in-memory join properties of {@code entity} are left unchanged.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.getOrNull(userId);
     * // Delete all related entities (orders, addresses, profile, etc.)
     * int totalDeleted = userDao.deleteAllJoinEntities(user);
     * }</pre>
     *
     * @param entity the entity whose all join entities should be deleted
     * @return the total number of deleted records
     * @throws IllegalArgumentException if {@code entity} is {@code null},
     *                                  or a join being deleted has a disallowed null/default key
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans, or an existing transaction on the current thread
     *         is no longer active and cannot accept the internally required transaction scope
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing, binding,
     *         or executing a DELETE statement fails
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     */
    @SuppressWarnings("deprecation")
    @Override
    default int deleteAllJoinEntities(final T entity)
            throws IllegalArgumentException, IllegalStateException, CannotGetJdbcConnectionException, UncheckedSQLException, ArithmeticException {
        N.checkArgNotNull(entity, cs.entity);

        return deleteJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Deletes all join entities for a single entity, optionally in parallel.
     * Note: parallel deletion may not complete in a single transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.getOrNull(userId);
     * // Delete all relationships in parallel
     * int deleted = userDao.deleteAllJoinEntities(user, true);
     * }</pre>
     *
     * @param entity the entity whose all join entities should be deleted
     * @param inParallel if {@code true}, all join properties are deleted in parallel; if {@code false}, deleted sequentially
     * @return the total number of deleted records
     * @throws IllegalArgumentException if {@code entity} is {@code null},
     *                                  or a join being deleted has a disallowed null/default key
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans, or an existing transaction on the current thread
     *         is no longer active and cannot accept the internally required transaction scope
     * @throws RejectedExecutionException if parallel execution is requested and the executor rejects a join task
     * @throws UncheckedInterruptedException if the calling thread is interrupted while waiting for the parallel delete tasks to finish
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing, binding,
     *         or executing a DELETE statement fails
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @deprecated parallel deletion cannot be performed within a single transaction; prefer
     *             {@link #deleteAllJoinEntities(Object)} for transactional behavior
     */
    @Beta
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final boolean inParallel) throws IllegalArgumentException, IllegalStateException,
            RejectedExecutionException, UncheckedInterruptedException, CannotGetJdbcConnectionException, UncheckedSQLException, ArithmeticException {
        if (inParallel) {
            return deleteAllJoinEntities(entity, executor());
        } else {
            return deleteAllJoinEntities(entity);
        }
    }

    /**
     * Deletes all join entities for a single entity using a custom executor.
     * Note: operations executed in multiple threads will not be completed in a single transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.getOrNull(userId);
     * java.util.concurrent.ExecutorService cleanupService =
     *     java.util.concurrent.Executors.newCachedThreadPool();
     * try {
     *     int deleted = userDao.deleteAllJoinEntities(user, cleanupService);
     * } finally {
     *     cleanupService.shutdown();
     * }
     * }</pre>
     *
     * @param entity the entity whose all join entities should be deleted
     * @param executor the {@code Executor} to use for parallel execution
     * @return the total number of deleted records
     * @throws IllegalArgumentException if {@code entity} or {@code executor} is {@code null},
     *                                  or a join being deleted has a disallowed null/default key
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans, or an existing transaction on the current thread
     *         is no longer active and cannot accept the internally required transaction scope
     * @throws RejectedExecutionException if parallel execution is requested and the executor rejects a join task
     * @throws UncheckedInterruptedException if the calling thread is interrupted while waiting for the parallel delete tasks to finish
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and the data source returns
     *         {@code null} or throws an {@code IllegalStateException}
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing, binding,
     *         or executing a DELETE statement fails
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @deprecated parallel deletion cannot be performed within a single transaction; prefer
     *             {@link #deleteAllJoinEntities(Object)} for transactional behavior
     */
    @Beta
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final Executor executor) throws IllegalArgumentException, IllegalStateException,
            RejectedExecutionException, UncheckedInterruptedException, CannotGetJdbcConnectionException, UncheckedSQLException, ArithmeticException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotNull(executor, cs.executor);

        return deleteJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     * Deletes all join entities for multiple entities.
     * This deletes the rows referenced by every property annotated with {@code @JoinedBy} for each entity;
     * the in-memory join properties of the entities are left unchanged.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> usersToDelete = getTerminatedUsers();
     * // Clean up all related data
     * int totalDeleted = userDao.deleteAllJoinEntities(usersToDelete);
     * }</pre>
     *
     * @param entities the collection of entities whose all join entities should be deleted. If {@code null} or empty, 0 is returned
     * @return the total number of deleted records, or 0 if {@code entities} is empty
     * @throws IllegalArgumentException if a join being deleted has a disallowed null/default key,
     *         or if a {@code null} entity is encountered while reading its join keys or properties
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans, or an existing transaction on the current thread
     *         is no longer active and cannot accept the internally required transaction scope
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing, binding,
     *         or executing a DELETE statement fails
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     */
    @SuppressWarnings("deprecation")
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities)
            throws IllegalArgumentException, IllegalStateException, CannotGetJdbcConnectionException, UncheckedSQLException, ArithmeticException {
        if (N.isEmpty(entities)) {
            return 0;
        }

        return deleteJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Deletes all join entities for multiple entities, optionally in parallel.
     * Note: parallel deletion may not complete in a single transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getExpiredUsers();
     * // Delete all relationships in parallel
     * int deleted = userDao.deleteAllJoinEntities(users, true);
     * }</pre>
     *
     * @param entities the collection of entities whose all join entities should be deleted. If {@code null} or empty, 0 is returned
     * @param inParallel if {@code true}, all join properties are deleted in parallel; if {@code false}, deleted sequentially
     * @return the total number of deleted records, or 0 if {@code entities} is empty
     * @throws IllegalArgumentException if a join being deleted has a disallowed null/default key,
     *         or if a {@code null} entity is encountered while reading its join keys or properties
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans, or an existing transaction on the current thread
     *         is no longer active and cannot accept the internally required transaction scope
     * @throws RejectedExecutionException if parallel execution is requested and the executor rejects a join task
     * @throws UncheckedInterruptedException if the calling thread is interrupted while waiting for the parallel delete tasks to finish
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and cannot obtain a required database connection
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing, binding,
     *         or executing a DELETE statement fails
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @deprecated parallel deletion cannot be performed within a single transaction; prefer
     *             {@link #deleteAllJoinEntities(Collection)} for transactional behavior
     */
    @Beta
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws IllegalArgumentException, IllegalStateException,
            RejectedExecutionException, UncheckedInterruptedException, CannotGetJdbcConnectionException, UncheckedSQLException, ArithmeticException {
        if (inParallel) {
            return deleteAllJoinEntities(entities, executor());
        } else {
            return deleteAllJoinEntities(entities);
        }
    }

    /**
     * Deletes all join entities for multiple entities using a custom executor.
     * Note: operations executed in multiple threads will not be completed in a single transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getUsersForMassCleanup();
     * java.util.concurrent.ExecutorService massDeleteExecutor =
     *     java.util.concurrent.Executors.newFixedThreadPool(8);
     * try {
     *     int deleted = userDao.deleteAllJoinEntities(users, massDeleteExecutor);
     * } finally {
     *     massDeleteExecutor.shutdown();
     * }
     * }</pre>
     *
     * @param entities the collection of entities whose all join entities should be deleted. If {@code null} or empty, 0 is returned
     * @param executor the {@code Executor} to use for parallel execution
     * @return the total number of deleted records, or 0 if {@code entities} is empty
     * @throws IllegalArgumentException if {@code executor} is {@code null},
     *                                  or a join being deleted has a disallowed null/default key,
     *         or if a {@code null} entity is encountered while reading its join keys or properties
     * @throws IllegalStateException if required join metadata cannot be converted into SQL query plans, or an existing transaction on the current thread
     *         is no longer active and cannot accept the internally required transaction scope
     * @throws RejectedExecutionException if parallel execution is requested and the executor rejects a join task
     * @throws UncheckedInterruptedException if the calling thread is interrupted while waiting for the parallel delete tasks to finish
     * @throws CannotGetJdbcConnectionException if Spring connection acquisition is enabled and the data source returns
     *         {@code null} or throws an {@code IllegalStateException}
     * @throws UncheckedSQLException if acquiring a connection fails, starting or completing an internally required transaction fails, or preparing, binding,
     *         or executing a DELETE statement fails
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @deprecated parallel deletion cannot be performed within a single transaction; prefer
     *             {@link #deleteAllJoinEntities(Collection)} for transactional behavior
     */
    @Beta
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws IllegalArgumentException, IllegalStateException,
            RejectedExecutionException, UncheckedInterruptedException, CannotGetJdbcConnectionException, UncheckedSQLException, ArithmeticException {
        N.checkArgNotNull(executor, cs.executor);

        if (N.isEmpty(entities)) {
            return 0;
        }

        return deleteJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }
}
