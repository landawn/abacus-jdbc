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
package com.landawn.abacus.jdbc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.landawn.abacus.jdbc.IsolationLevel;
import com.landawn.abacus.jdbc.Propagation;

/**
 * Declares transaction settings for a DAO method managed by the Abacus JDBC proxy.
 *
 * <p>The annotation selects a {@link Propagation propagation policy} and an
 * {@link IsolationLevel isolation level} for the method invocation. It is intended for DAO
 * methods; for service-layer transaction orchestration, prefer the transaction mechanism of
 * the surrounding framework.</p>
 *
 * @see Propagation
 * @see IsolationLevel
 * @see org.springframework.transaction.annotation.Transactional
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD) // Should be used on method only, not for ElementType.TYPE/CLASS
public @interface Transactional {

    /**
     * Specifies the transaction propagation behavior.
     * This determines how the method participates in existing transactions.
     * 
     * <p>Common propagation behaviors:</p>
     * <ul>
     *   <li>{@link Propagation#REQUIRED} (default) - Join existing transaction or create new one</li>
     *   <li>{@link Propagation#REQUIRES_NEW} - Always create a new transaction</li>
     *   <li>{@link Propagation#SUPPORTS} - Use transaction if exists, otherwise non-transactional</li>
     *   <li>{@link Propagation#MANDATORY} - Must execute within existing transaction</li>
     *   <li>{@link Propagation#NOT_SUPPORTED} - Execute non-transactionally, suspend existing</li>
     *   <li>{@link Propagation#NEVER} - Execute non-transactionally, fail if transaction exists</li>
     * </ul>
     * 
     * <p>Example scenarios:</p>
     * <pre>{@code
     * // Main business operation - needs transaction
     * @Transactional(propagation = Propagation.REQUIRED)
     * void processOrder(Order order) { ... }
     * 
     * // Audit logging - independent transaction
     * @Transactional(propagation = Propagation.REQUIRES_NEW)
     * void logActivity(String activity) { ... }
     * 
     * // Read operation - works with or without transaction
     * @Transactional(propagation = Propagation.SUPPORTS)
     * User getUser(long id) { ... }
     * }</pre>
     *
     * @return the propagation behavior for this transaction
     * @see Propagation
     */
    Propagation propagation() default Propagation.REQUIRED;

    /**
     * Specifies the transaction isolation level.
     * This controls how the transaction interacts with other concurrent transactions.
     * 
     * <p>Isolation levels (from least to most restrictive):</p>
     * <ul>
     *   <li>{@link IsolationLevel#DEFAULT} - Use database default (usually READ_COMMITTED)</li>
     *   <li>{@link IsolationLevel#READ_UNCOMMITTED} - Lowest isolation, highest performance</li>
     *   <li>{@link IsolationLevel#READ_COMMITTED} - Prevents dirty reads</li>
     *   <li>{@link IsolationLevel#REPEATABLE_READ} - Prevents dirty and non-repeatable reads</li>
     *   <li>{@link IsolationLevel#SERIALIZABLE} - Highest isolation, prevents all phenomena</li>
     * </ul>
     * 
     * <p>Choose isolation level based on your consistency requirements:</p>
     * <pre>{@code
     * // Financial transactions need high isolation
     * @Transactional(isolation = IsolationLevel.SERIALIZABLE)
     * void transferFunds(Account from, Account to, BigDecimal amount) { ... }
     * 
     * // Reporting can tolerate some inconsistency
     * @Transactional(isolation = IsolationLevel.READ_UNCOMMITTED)
     * List<Report> generateReports() { ... }
     * 
     * // Most business operations use default
     * @Transactional(isolation = IsolationLevel.DEFAULT)
     * void updateUserProfile(User user) { ... }
     * }</pre>
     * 
     * <p><strong>Note:</strong> Higher isolation levels may impact performance due to
     * increased locking. Choose the lowest level that meets your consistency requirements.</p>
     *
     * @return the isolation level for this transaction
     * @see IsolationLevel
     */
    IsolationLevel isolation() default IsolationLevel.DEFAULT;
}
