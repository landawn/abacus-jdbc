/*
 * Copyright (C) 2026 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.landawn.abacus.jdbc;

import java.io.Serial;

/**
 * Thrown by the outermost {@link SqlTransaction#commit()} of a transaction that a nested scope marked rollback-only
 * ({@link SqlTransaction.Status#MARKED_ROLLBACK}), after the transaction has been rolled back: none of its work was committed.
 * For a transaction that participates in a Spring-managed transaction, that rollback is the rollback-only mark of the Spring
 * transaction, which rolls back the work when it completes.
 *
 * <p>A nested scope marks the transaction rollback-only when it ends without a successful commit, typically because its
 * work threw, and so does a nested scope that is never completed (the enclosing scope's completion exits it), or a nested
 * scope whose exit cannot restore the enclosing isolation level. When code around the nested scope catches that failure and
 * carries on, the outermost {@code commit()} cannot commit the transaction's work; it rolls the transaction back and throws
 * this exception, so the caller does not take the rolled-back work for committed. If that rollback fails (for a transaction
 * participating in a Spring-managed transaction: marking the Spring transaction rollback-only, which may then still commit
 * the work), the failure of the rollback is thrown instead, with the reason attached as a suppressed
 * {@link IllegalStateException}; an unchecked failure of the connection cleanup after a successful rollback is thrown with
 * this exception attached. It plays the role of Spring's {@code UnexpectedRollbackException}.
 * {@link JdbcUtil#callInTransaction(javax.sql.DataSource, com.landawn.abacus.util.Throwables.Callable) callInTransaction},
 * {@link JdbcUtil#runInTransaction(javax.sql.DataSource, com.landawn.abacus.util.Throwables.Runnable) runInTransaction} and
 * DAO methods annotated with {@link com.landawn.abacus.jdbc.annotation.Transactional @Transactional} propagate it.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * try (SqlTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
 *     orderDao.insert(order);
 *     try {
 *         auditDao.writeAudit(order); // a @Transactional DAO method: its failure marks the transaction rollback-only
 *     } catch (SQLException e) {
 *         logger.warn("audit failed", e);
 *     }
 *     tran.commit(); // rolls back and throws RollbackOnlyException: the order was not inserted
 * }
 * }</pre>
 *
 * @see SqlTransaction#commit()
 * @see SqlTransaction.Status#MARKED_ROLLBACK
 */
public class RollbackOnlyException extends IllegalStateException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a {@code RollbackOnlyException} with the specified detail message.
     *
     * @param message the detail message
     */
    public RollbackOnlyException(final String message) {
        super(message);
    }
}
