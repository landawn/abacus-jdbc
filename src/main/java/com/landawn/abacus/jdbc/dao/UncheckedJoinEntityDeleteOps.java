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
import java.util.List;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.SqlTransaction;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.stream.Stream;

/**
 * Delete-side view of {@link UncheckedJoinEntityHelper}: the join-entity <i>delete</i> operations
 * ({@code deleteJoinEntities}/{@code deleteAllJoinEntities} families) that throw
 * {@link com.landawn.abacus.exception.UncheckedSQLException} instead of the checked
 * {@link java.sql.SQLException}.
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
     * Deletes all join entities of the specified class related to the given entity.
     * This deletes the join entities associated with the specified relationship type.
     * If multiple properties in the entity class are joined to the specified type, all of them are deleted within a single transaction.
     * This deletes the related rows from the database; the in-memory join properties of {@code entity} are left unchanged.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Delete all orders for this user
     * int deletedCount = userDao.deleteJoinEntities(user, Order.class);
     * }</pre>
     *
     * @param entity the entity whose join entities should be deleted
     * @param joinEntityClass the class of join entities to delete
     * @return the total count of deleted records
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    @SuppressWarnings("deprecation")
    @Override
    default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws UncheckedSQLException {
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

                    failure.addSuppressed(rollbackFailure);
                }
            }

            return result;
        }
    }

    /**
     * Deletes all join entities of the specified class for multiple entities.
     * This deletes the specified join entities for each of the given entities in a batch operation.
     * If multiple properties in the entity class are joined to the specified type, all of them are deleted within a single transaction.
     * This deletes the related rows from the database; the in-memory join properties of the entities are left unchanged.
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
     * @return the total count of deleted records, or 0 if {@code entities} is empty
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    @SuppressWarnings("deprecation")
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws UncheckedSQLException {
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

                    failure.addSuppressed(rollbackFailure);
                }
            }

            return result;
        }
    }

    /**
     * Deletes join entities for a specific property name of a single entity.
     * This is the core implementation method for deleting join entities in unchecked mode.
     *
     * <p>It deletes all related entities for the specified join property. The deletion is based on
     * the foreign key relationship defined in the {@code @JoinedBy} annotation. The method
     * constructs and executes a DELETE statement targeting the join entity table with a WHERE
     * clause matching the foreign key value(s) from the parent entity.</p>
     *
     * <p>Unlike the checked version in {@link JoinEntityDeleteOps}, this method throws {@link UncheckedSQLException}
     * instead of {@link java.sql.SQLException}, making it suitable for use in functional programming contexts
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
     * User user = userDao.gett(userId);
     * // Delete all addresses for this user
     * int deleted = userDao.deleteJoinEntities(user, "addresses");
     *
     * // Use in functional context without try-catch
     * Optional.ofNullable(user)
     *     .map(u -> userDao.deleteJoinEntities(u, "temporaryData"))
     *     .ifPresent(count -> System.out.println("Deleted " + count + " records"));
     * }</pre>
     *
     * @param entity the entity whose join entities should be deleted. Must not be {@code null}
     * @param joinEntityPropName the property name of the join entities to delete. Must be a valid
     *                           property name that exists in the entity class and is annotated
     *                           with {@code @JoinedBy}
     * @return the total count of deleted records. Returns 0 if no matching records were found
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if the {@code joinEntityPropName} does not exist or is not
     *                                  properly annotated with {@code @JoinedBy}
     */
    @Override
    int deleteJoinEntities(final T entity, final String joinEntityPropName) throws UncheckedSQLException;

    /**
     * Deletes join entities for a specific property name for multiple entities.
     * This is the core batch implementation method for deleting join entities in unchecked mode.
     *
     * <p>It efficiently deletes all related entities for multiple parent entities in a batch operation.
     * The implementation typically uses an IN clause to delete all related records in one or more SQL
     * statements, avoiding the N+1 delete problem. For large collections, the deletion may be
     * automatically batched to prevent SQL statement size limits from being exceeded.</p>
     *
     * <p>Unlike the checked version in {@link JoinEntityDeleteOps}, this method throws {@link UncheckedSQLException}
     * instead of {@link java.sql.SQLException}, making it suitable for use in functional programming contexts
     * such as Stream operations and lambda expressions without requiring explicit exception handling.</p>
     *
     * <p>Performance characteristics:</p>
     * <ul>
     *   <li>For N parent entities, executes O(1) or O(N/batch_size) DELETE statements instead of O(N)</li>
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
     * int deletedOrders = userDao.list(Filters.eq("status", "INACTIVE"))
     *     .stream()
     *     .collect(Collectors.collectingAndThen(
     *         Collectors.toList(),
     *         list -> userDao.deleteJoinEntities(list, "orders")
     *     ));
     * }</pre>
     *
     * @param entities the collection of entities whose join entities should be deleted. Can be empty
     *                 but not {@code null}. If empty, this method returns 0 immediately
     * @param joinEntityPropName the property name of the join entities to delete. Must be a valid
     *                           property name that exists in the entity class and is annotated
     *                           with {@code @JoinedBy}
     * @return the total count of deleted records across all parent entities. Returns 0 if no
     *         matching records were found or if {@code entities} is empty
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if the {@code joinEntityPropName} does not exist or is not
     *                                  properly annotated with {@code @JoinedBy}
     */
    @Override
    int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws UncheckedSQLException;

    /**
     * Deletes join entities for multiple property names of a single entity.
     * This operation is performed within a transaction when multiple properties are specified.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Delete all orders and reviews for this user
     * int deleted = userDao.deleteJoinEntities(
     *     user,
     *     Arrays.asList("orders", "reviews")
     * );
     * }</pre>
     *
     * @param entity the entity whose join entities should be deleted
     * @param joinEntityPropNames the property names of the join entities to delete. If {@code null} or empty, 0 is returned
     * @return the total count of deleted records, or 0 if {@code joinEntityPropNames} is empty
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy}
     */
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
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

                    failure.addSuppressed(rollbackFailure);
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
     * ExecutorService executor = Executors.newFixedThreadPool(4);
     * User user = userDao.gett(userId);
     * // Delete multiple related entities in parallel
     * int deleted = userDao.deleteJoinEntities(
     *     user,
     *     Arrays.asList("orders", "reviews", "wishlistItems"),
     *     executor
     * );
     * }</pre>
     *
     * @param entity the entity whose join entities should be deleted
     * @param joinEntityPropNames the property names of the join entities to delete. If {@code null} or empty, 0 is returned
     * @param executor the {@code Executor} to use for parallel execution
     * @return the total count of deleted records, or 0 if {@code joinEntityPropNames} is empty
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy}
     * @deprecated this operation may not complete in a single transaction when executed in multiple threads;
     *             prefer the sequential {@link #deleteJoinEntities(Object, Collection)} for transactional deletion
     */
    @Beta
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws UncheckedSQLException {
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
     * User user = userDao.gett(userId);
     * // Delete multiple relationships in parallel
     * int deleted = userDao.deleteJoinEntities(
     *     user,
     *     Arrays.asList("orders", "reviews", "notifications"),
     *     true  // parallel deletion
     * );
     * }</pre>
     *
     * @param entity the entity whose join entities should be deleted
     * @param joinEntityPropNames the property names of the join entities to delete
     * @param inParallel if {@code true}, join properties are deleted in parallel; if {@code false}, deleted sequentially
     * @return the total count of deleted records
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy}
     * @deprecated this operation may not complete in a single transaction if {@code inParallel} is {@code true};
     *             prefer the sequential {@link #deleteJoinEntities(Object, Collection)} for transactional deletion
     */
    @Beta
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws UncheckedSQLException {
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
     * @return the total count of deleted records, or 0 if {@code entities} or {@code joinEntityPropNames} is empty
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy}
     */
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws UncheckedSQLException {
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

                    failure.addSuppressed(rollbackFailure);
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
     * @param entities the collection of entities whose join entities should be deleted
     * @param joinEntityPropNames the property names of the join entities to delete
     * @param inParallel if {@code true}, join properties are deleted in parallel; if {@code false}, deleted sequentially
     * @return the total count of deleted records
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy}
     * @deprecated this operation may not complete in a single transaction if {@code inParallel} is {@code true};
     *             prefer the sequential {@link #deleteJoinEntities(Collection, Collection)} for transactional deletion
     */
    @Beta
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws UncheckedSQLException {
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
     * ForkJoinPool cleanupPool = new ForkJoinPool(8);
     * List<User> users = getUsersToCleanup();
     *
     * int deleted = userDao.deleteJoinEntities(
     *     users,
     *     Arrays.asList("logs", "sessions", "tempData"),
     *     cleanupPool
     * );
     * }</pre>
     *
     * @param entities the collection of entities whose join entities should be deleted. If {@code null} or empty, 0 is returned
     * @param joinEntityPropNames the property names of the join entities to delete. If {@code null} or empty, 0 is returned
     * @param executor the {@code Executor} to use for parallel execution
     * @return the total count of deleted records, or 0 if {@code entities} or {@code joinEntityPropNames} is empty
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws UncheckedSQLException if a database access error occurs
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy}
     * @deprecated this operation may not complete in a single transaction when executed in multiple threads;
     *             prefer the sequential {@link #deleteJoinEntities(Collection, Collection)} for transactional deletion
     */
    @Beta
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
            throws UncheckedSQLException {
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
     * This deletes the join entities for every join relationship defined in the entity.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Delete all related entities (orders, addresses, profile, etc.)
     * int totalDeleted = userDao.deleteAllJoinEntities(user);
     * }</pre>
     *
     * @param entity the entity whose all join entities should be deleted
     * @return the total count of deleted records
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Override
    default int deleteAllJoinEntities(final T entity) throws UncheckedSQLException {
        return deleteJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Deletes all join entities for a single entity, optionally in parallel.
     * Note: parallel deletion may not complete in a single transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.gett(userId);
     * // Delete all relationships in parallel
     * int deleted = userDao.deleteAllJoinEntities(user, true);
     * }</pre>
     *
     * @param entity the entity whose all join entities should be deleted
     * @param inParallel if {@code true}, all join properties are deleted in parallel; if {@code false}, deleted sequentially
     * @return the total count of deleted records
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws UncheckedSQLException if a database access error occurs
     * @deprecated this operation may not complete in a single transaction if {@code inParallel} is {@code true};
     *             prefer the sequential {@link #deleteAllJoinEntities(Object)} for transactional deletion
     */
    @Beta
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final boolean inParallel) throws UncheckedSQLException {
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
     * ExecutorService cleanupService = Executors.newCachedThreadPool();
     * User user = userDao.gett(userId);
     *
     * int deleted = userDao.deleteAllJoinEntities(user, cleanupService);
     * }</pre>
     *
     * @param entity the entity whose all join entities should be deleted
     * @param executor the {@code Executor} to use for parallel execution
     * @return the total count of deleted records
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws UncheckedSQLException if a database access error occurs
     * @deprecated this operation may not complete in a single transaction when executed in multiple threads;
     *             prefer the sequential {@link #deleteAllJoinEntities(Object)} for transactional deletion
     */
    @Beta
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final Executor executor) throws UncheckedSQLException {
        return deleteJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     * Deletes all join entities for multiple entities.
     * This deletes the join entities for every join relationship of each of the given entities in a batch operation.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> usersToDelete = getTerminatedUsers();
     * // Clean up all related data
     * int totalDeleted = userDao.deleteAllJoinEntities(usersToDelete);
     * }</pre>
     *
     * @param entities the collection of entities whose all join entities should be deleted. If {@code null} or empty, 0 is returned
     * @return the total count of deleted records, or 0 if {@code entities} is empty
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws UncheckedSQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities) throws UncheckedSQLException {
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
     * @param entities the collection of entities whose all join entities should be deleted
     * @param inParallel if {@code true}, all join properties are deleted in parallel; if {@code false}, deleted sequentially
     * @return the total count of deleted records
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws UncheckedSQLException if a database access error occurs
     * @deprecated this operation may not complete in a single transaction if {@code inParallel} is {@code true};
     *             prefer the sequential {@link #deleteAllJoinEntities(Collection)} for transactional deletion
     */
    @Beta
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws UncheckedSQLException {
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
     * ThreadPoolExecutor massDeleteExecutor = new ThreadPoolExecutor(...);
     * List<User> users = getUsersForMassCleanup();
     *
     * int deleted = userDao.deleteAllJoinEntities(users, massDeleteExecutor);
     * }</pre>
     *
     * @param entities the collection of entities whose all join entities should be deleted. If {@code null} or empty, 0 is returned
     * @param executor the {@code Executor} to use for parallel execution
     * @return the total count of deleted records, or 0 if {@code entities} is empty
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws UncheckedSQLException if a database access error occurs
     * @deprecated this operation may not complete in a single transaction when executed in multiple threads;
     *             prefer the sequential {@link #deleteAllJoinEntities(Collection)} for transactional deletion
     */
    @Beta
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws UncheckedSQLException {
        if (N.isEmpty(entities)) {
            return 0;
        }

        return deleteJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }
}
