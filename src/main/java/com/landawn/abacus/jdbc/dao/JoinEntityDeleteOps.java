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
import java.util.List;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.SqlTransaction;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.stream.Stream;

/**
 * Delete-side view of {@link JoinEntityHelper}: declares the join-entity <i>delete</i> operations
 * ({@code deleteJoinEntities} and {@code deleteAllJoinEntities} families); the internal accessor
 * methods it relies on are inherited from {@link JoinEntityBase}.
 *
 * <p>Mixed together with {@link JoinEntityReadOps} it forms the full {@link JoinEntityHelper}.
 * It is intentionally omitted from read-only DAOs so that join-entity deletion is unavailable at
 * compile time rather than throwing at runtime.</p>
 *
 * <p><b>&#9888; Warning:</b> Parallel delete operations may continue after another task fails and are
 * not automatically part of the caller's thread-bound transaction. Partial database deletion is
 * therefore possible unless the application supplies stronger coordination.</p>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the DAO implementation type (self-referencing for method chaining)
 *
 * @see JoinEntityHelper
 * @see JoinEntityReadOps
 * @see com.landawn.abacus.annotation.JoinedBy
 */
@SuppressWarnings({ "RedundantThrows", "resource" })
sealed interface JoinEntityDeleteOps<T, TD extends Dao<T, TD>> extends JoinEntityBase<T, TD> permits JoinEntityHelper, UncheckedJoinEntityDeleteOps {
    /**
     * Deletes all join entities of the specified type for a single entity.
     * If multiple properties in the entity class are joined to the specified type, all of them are deleted within a single transaction.
     * This deletes the related rows from the database; the in-memory join properties of {@code entity} are left unchanged.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * // Delete all orders associated with the user
     * int deletedCount = userDao.deleteJoinEntities(user, Order.class);
     * }</pre>
     *
     * @param entity the entity for which to delete join entities
     * @param joinEntityClass the class of the join entities to delete
     * @return the total number of deleted records
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws SQLException {
        @SuppressWarnings("deprecation")
        final Class<?> targetEntityClass = targetEntityClass();
        @SuppressWarnings("deprecation")
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
     * Deletes all join entities of the specified type for a collection of entities.
     * If multiple properties in the entity class are joined to the specified type, all of them are deleted within a single transaction.
     * This deletes the related rows from the database; the in-memory join properties of the entities are left unchanged.
     * If {@code entities} is {@code null} or empty, this method returns 0 immediately.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.eq("status", "inactive"));
     * // Delete all orders for inactive users
     * int deletedCount = userDao.deleteJoinEntities(users, Order.class);
     * }</pre>
     *
     * @param entities the collection of entities for which to delete join entities.
     *                 If {@code null} or empty, this method returns 0 immediately
     * @param joinEntityClass the class of the join entities to delete
     * @return the total number of deleted records
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if no join property of the specified type is found in the entity class
     */
    default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException {
        if (N.isEmpty(entities)) {
            return 0;
        }

        @SuppressWarnings("deprecation")
        final Class<?> targetEntityClass = targetEntityClass();
        @SuppressWarnings("deprecation")
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
     * Deletes join entities for a single entity by property name.
     * The property name must correspond to a field annotated with {@code @JoinedBy}.
     * This is an abstract method whose implementation is provided by the generated DAO.
     *
     * <p>This method deletes all related entities for the specified join property. The deletion
     * is based on the foreign key relationship defined in the {@code @JoinedBy} annotation. The
     * method constructs and executes a DELETE statement targeting the join entity table with a
     * WHERE clause matching the foreign key value(s) from the parent entity.</p>
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
     * User user = userDao.get(1L).orElseThrow();
     * // Delete all addresses associated with the user
     * int deletedCount = userDao.deleteJoinEntities(user, "addresses");
     * System.out.println("Deleted " + deletedCount + " addresses");
     * }</pre>
     *
     * @param entity the entity for which to delete join entities. Must not be {@code null}
     * @param joinEntityPropName the property name of the join entities to delete. Must be a valid
     *                           property name that exists in the entity class and is annotated
     *                           with {@code @JoinedBy}
     * @return the total number of deleted records. Returns 0 if no matching records were found
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if the {@code joinEntityPropName} does not exist or is not
     *                                  properly annotated with {@code @JoinedBy}
     */
    int deleteJoinEntities(final T entity, final String joinEntityPropName) throws SQLException;

    /**
     * Deletes join entities for a collection of entities by property name.
     * The property name must correspond to a field annotated with {@code @JoinedBy}.
     * This is an abstract method whose implementation is provided by the generated DAO.
     *
     * <p>This method efficiently deletes all related entities for multiple parent entities in a batch operation.
     * The implementation typically uses an IN clause to delete all related records in one or more SQL statements,
     * avoiding the N+1 delete problem. For large collections, the deletion may be automatically batched to
     * prevent SQL statement size limits from being exceeded.</p>
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
     * List<User> users = userDao.list(Filters.in("id", userIdsToClean));
     * // Delete all reviews for these users
     * int deletedCount = userDao.deleteJoinEntities(users, "reviews");
     * System.out.println("Deleted " + deletedCount + " reviews for " + users.size() + " users");
     * }</pre>
     *
     * @param entities the collection of entities for which to delete join entities. Can be empty
     *                 but not {@code null}. If empty, this method returns 0 immediately
     * @param joinEntityPropName the property name of the join entities to delete. Must be a valid
     *                           property name that exists in the entity class and is annotated
     *                           with {@code @JoinedBy}
     * @return the total number of deleted records across all parent entities. Returns 0 if no
     *         matching records were found or if {@code entities} is empty
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if the {@code joinEntityPropName} does not exist or is not
     *                                  properly annotated with {@code @JoinedBy}
     */
    int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws SQLException;

    /**
     * Deletes multiple join entities for a single entity by property names.
     * This operation is performed within a transaction when multiple properties are specified.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * // Delete orders and addresses in a single transaction
     * int deletedCount = userDao.deleteJoinEntities(user, Arrays.asList("orders", "addresses"));
     * }</pre>
     *
     * @param entity the entity for which to delete join entities
     * @param joinEntityPropNames the property names of the join entities to delete. If {@code null} or empty, 0 is returned
     * @return the total number of deleted records, or 0 if {@code joinEntityPropNames} is empty
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy}
     */
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws SQLException {
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
     * Deletes multiple join entities for a single entity with optional parallel execution.
     * Note: when {@code inParallel} is {@code true}, the deletions are dispatched to the default executor
     * and therefore are not executed within a single transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * // Delete multiple join entity types in parallel (not transactional)
     * int deletedCount = userDao.deleteJoinEntities(user, Arrays.asList("orders", "addresses", "reviews"), true);
     * }</pre>
     *
     * @param entity the entity for which to delete join entities
     * @param joinEntityPropNames the property names of the join entities to delete. If {@code null} or empty, 0 is returned
     * @param inParallel if {@code true}, join entities will be deleted in parallel
     * @return the total number of deleted records, or 0 if {@code joinEntityPropNames} is empty
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy}
     * @deprecated when {@code inParallel} is {@code true} the deletions are not performed within a single
     *             transaction; prefer {@link #deleteJoinEntities(Object, Collection)} for transactional behavior
     */
    @Deprecated
    @Beta
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel) throws SQLException {
        if (inParallel) {
            return deleteJoinEntities(entity, joinEntityPropNames, executor());
        } else {
            return deleteJoinEntities(entity, joinEntityPropNames);
        }
    }

    /**
     * Deletes multiple join entities for a single entity using a custom executor for parallel execution.
     * Note: this operation cannot be completed within a single transaction when executed across multiple threads.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService deleteExecutor = Executors.newFixedThreadPool(3);
     * User user = userDao.get(1L).orElseThrow();
     * int deletedCount = userDao.deleteJoinEntities(user, Arrays.asList("orders", "addresses"), deleteExecutor);
     * }</pre>
     *
     * @param entity the entity for which to delete join entities
     * @param joinEntityPropNames the property names of the join entities to delete. If {@code null} or empty, 0 is returned
     * @param executor the executor to use for parallel deletion
     * @return the total number of deleted records, or 0 if {@code joinEntityPropNames} is empty
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy}
     * @deprecated parallel deletion cannot be performed within a single transaction; prefer
     *             {@link #deleteJoinEntities(Object, Collection)} for transactional behavior
     */
    @Deprecated
    @Beta
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
        if (N.isEmpty(joinEntityPropNames)) {
            return 0;
        }

        final List<ContinuableFuture<Integer>> futures = Stream.of(joinEntityPropNames)
                .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entity, joinEntityPropName), executor))
                .toList();

        return DaoUtil.completeSum(futures);
    }

    /**
     * Deletes multiple join entities for a collection of entities by property names.
     * This operation is performed within a transaction when multiple properties are specified.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.eq("accountStatus", "terminated"));
     * // Delete all related data for terminated accounts
     * int deletedCount = userDao.deleteJoinEntities(users, Arrays.asList("orders", "addresses", "paymentMethods"));
     * }</pre>
     *
     * @param entities the collection of entities for which to delete join entities. If {@code null} or empty, 0 is returned
     * @param joinEntityPropNames the property names of the join entities to delete. If {@code null} or empty, 0 is returned
     * @return the total number of deleted records, or 0 if {@code entities} or {@code joinEntityPropNames} is empty
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy}
     */
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames) throws SQLException {
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
     * Deletes multiple join entities for a collection of entities with optional parallel execution.
     * Note: Parallel execution may not complete within a single transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getBulkUsersForDeletion();
     * // Delete join entities in parallel for performance (not transactional)
     * int deletedCount = userDao.deleteJoinEntities(users, Arrays.asList("orders", "addresses"), true);
     * }</pre>
     *
     * @param entities the collection of entities for which to delete join entities. If {@code null} or empty, 0 is returned
     * @param joinEntityPropNames the property names of the join entities to delete. If {@code null} or empty, 0 is returned
     * @param inParallel if {@code true}, join entities will be deleted in parallel
     * @return the total number of deleted records, or 0 if {@code entities} or {@code joinEntityPropNames} is empty
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy}
     * @deprecated when {@code inParallel} is {@code true} the deletions are not performed within a single
     *             transaction; prefer {@link #deleteJoinEntities(Collection, Collection)} for transactional behavior
     */
    @Deprecated
    @Beta
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel) throws SQLException {
        if (inParallel) {
            return deleteJoinEntities(entities, joinEntityPropNames, executor());
        } else {
            return deleteJoinEntities(entities, joinEntityPropNames);
        }
    }

    /**
     * Deletes multiple join entities for a collection of entities using a custom executor for parallel execution.
     * Note: This operation cannot be completed within a single transaction when executed in multiple threads.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService bulkDeleteExecutor = Executors.newWorkStealingPool();
     * List<User> users = getBulkUsersForDeletion();
     * int deletedCount = userDao.deleteJoinEntities(users, Arrays.asList("orders", "addresses"), bulkDeleteExecutor);
     * }</pre>
     *
     * @param entities the collection of entities for which to delete join entities. If {@code null} or empty, 0 is returned
     * @param joinEntityPropNames the property names of the join entities to delete. If {@code null} or empty, 0 is returned
     * @param executor the executor to use for parallel deletion
     * @return the total number of deleted records, or 0 if {@code entities} or {@code joinEntityPropNames} is empty
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws SQLException if a database access error occurs
     * @throws IllegalArgumentException if any property name in {@code joinEntityPropNames} does not exist or is not annotated with {@code @JoinedBy}
     * @deprecated parallel deletion cannot be performed within a single transaction; prefer
     *             {@link #deleteJoinEntities(Collection, Collection)} for transactional behavior
     */
    @Deprecated
    @Beta
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor) throws SQLException {
        if (N.isEmpty(entities) || N.isEmpty(joinEntityPropNames)) {
            return 0;
        }

        final List<ContinuableFuture<Integer>> futures = Stream.of(joinEntityPropNames)
                .map(joinEntityPropName -> ContinuableFuture.call(() -> deleteJoinEntities(entities, joinEntityPropName), executor))
                .toList();

        return DaoUtil.completeSum(futures);
    }

    /**
     * Deletes all join entities for a single entity.
     * This deletes the rows referenced by every property annotated with {@code @JoinedBy};
     * the in-memory join properties of {@code entity} are left unchanged.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * // Delete all related data (orders, addresses, reviews, etc.)
     * int deletedCount = userDao.deleteAllJoinEntities(user);
     * }</pre>
     *
     * @param entity the entity for which to delete all join entities
     * @return the total number of deleted records
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    default int deleteAllJoinEntities(final T entity) throws SQLException {
        return deleteJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Deletes all join entities for a single entity with optional parallel execution.
     * Note: Parallel execution may not complete within a single transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = userDao.get(1L).orElseThrow();
     * // Delete all join entities in parallel (not transactional)
     * int deletedCount = userDao.deleteAllJoinEntities(user, true);
     * }</pre>
     *
     * @param entity the entity for which to delete all join entities
     * @param inParallel if {@code true}, join entities will be deleted in parallel
     * @return the total number of deleted records
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws SQLException if a database access error occurs
     * @deprecated parallel deletion cannot be performed within a single transaction; prefer
     *             {@link #deleteAllJoinEntities(Object)} for transactional behavior
     */
    @Deprecated
    @Beta
    default int deleteAllJoinEntities(final T entity, final boolean inParallel) throws SQLException {
        if (inParallel) {
            return deleteAllJoinEntities(entity, executor());
        } else {
            return deleteAllJoinEntities(entity);
        }
    }

    /**
     * Deletes all join entities for a single entity using a custom executor for parallel execution.
     * Note: This operation cannot be completed within a single transaction when executed in multiple threads.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService cleanupExecutor = Executors.newCachedThreadPool();
     * User user = userDao.get(1L).orElseThrow();
     * int deletedCount = userDao.deleteAllJoinEntities(user, cleanupExecutor);
     * }</pre>
     *
     * @param entity the entity for which to delete all join entities
     * @param executor the executor to use for parallel deletion
     * @return the total number of deleted records
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws SQLException if a database access error occurs
     * @deprecated parallel deletion cannot be performed within a single transaction; prefer
     *             {@link #deleteAllJoinEntities(Object)} for transactional behavior
     */
    @Deprecated
    @Beta
    default int deleteAllJoinEntities(final T entity, final Executor executor) throws SQLException {
        return deleteJoinEntities(entity, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }

    /**
     * Deletes all join entities for a collection of entities.
     * This deletes the rows referenced by every property annotated with {@code @JoinedBy} for each entity;
     * the in-memory join properties of the entities are left unchanged.
     * If {@code entities} is {@code null} or empty, this method returns 0 immediately.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = userDao.list(Filters.eq("markedForDeletion", true));
     * // Delete all related data for users marked for deletion
     * int deletedCount = userDao.deleteAllJoinEntities(users);
     * }</pre>
     *
     * @param entities the collection of entities for which to delete all join entities.
     *                 If {@code null} or empty, this method returns 0 immediately
     * @return the total number of deleted records
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws SQLException if a database access error occurs
     */
    @SuppressWarnings("deprecation")
    default int deleteAllJoinEntities(final Collection<T> entities) throws SQLException {
        if (N.isEmpty(entities)) {
            return 0;
        }

        return deleteJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet());
    }

    /**
     * Deletes all join entities for a collection of entities with optional parallel execution.
     * Note: Parallel execution may not complete within a single transaction.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * List<User> users = getInactiveUsers();
     * // Delete all join entities in parallel for performance (not transactional)
     * int deletedCount = userDao.deleteAllJoinEntities(users, true);
     * }</pre>
     *
     * @param entities the collection of entities for which to delete all join entities. If {@code null} or empty, 0 is returned
     * @param inParallel if {@code true}, join entities will be deleted in parallel
     * @return the total number of deleted records, or 0 if {@code entities} is empty
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws SQLException if a database access error occurs
     * @deprecated parallel deletion cannot be performed within a single transaction; prefer
     *             {@link #deleteAllJoinEntities(Collection)} for transactional behavior
     */
    @Deprecated
    @Beta
    default int deleteAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws SQLException {
        if (inParallel) {
            return deleteAllJoinEntities(entities, executor());
        } else {
            return deleteAllJoinEntities(entities);
        }
    }

    /**
     * Deletes all join entities for a collection of entities using a custom executor for parallel execution.
     * Note: This operation cannot be completed within a single transaction when executed in multiple threads.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ExecutorService massCleanupExecutor = Executors.newWorkStealingPool(8);
     * List<User> users = getAllUsersForPurge();
     * int deletedCount = userDao.deleteAllJoinEntities(users, massCleanupExecutor);
     * }</pre>
     *
     * @param entities the collection of entities for which to delete all join entities. If {@code null} or empty, 0 is returned
     * @param executor the executor to use for parallel deletion
     * @return the total number of deleted records, or 0 if {@code entities} is empty
     * @throws ArithmeticException if the total deleted-row count overflows an {@code int}
     * @throws SQLException if a database access error occurs
     * @deprecated parallel deletion cannot be performed within a single transaction; prefer
     *             {@link #deleteAllJoinEntities(Collection)} for transactional behavior
     */
    @Deprecated
    @Beta
    default int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws SQLException {
        if (N.isEmpty(entities)) {
            return 0;
        }

        return deleteJoinEntities(entities, DaoUtil.getEntityJoinInfo(targetDaoInterface(), targetEntityClass(), targetTableName()).keySet(), executor);
    }
}
