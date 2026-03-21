/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.landawn.abacus.jdbc;

/**
 * Transaction participation policies used by {@link com.landawn.abacus.jdbc.annotation.Transactional}.
 *
 * <p>Each constant describes how a DAO method should behave when a transaction already exists:
 * join it, require it, suspend it, or refuse it.</p>
 *
 * @see com.landawn.abacus.jdbc.annotation.Transactional
 */
public enum Propagation {

    /**
     * Support a current transaction, create a new one if none exists.
     * 
     * <p>This is the default and most commonly used propagation behavior.
     * If a transaction is already active, the method will participate in it.
     * If no transaction exists, a new one will be created for the method execution.</p>
     * 
     * <p>Use this when you want transactional behavior but don't care whether
     * the transaction is new or existing.</p>
     */
    REQUIRED,

    /**
     * Support a current transaction, execute non-transactionally if none exists.
     * 
     * <p>This behavior is flexible - the method can work with or without a transaction.
     * If a transaction exists, the method will participate in it. If no transaction
     * exists, the method will execute without transactional behavior.</p>
     * 
     * <p>Use this for methods that can benefit from transactions when available
     * but don't require them, such as read-only operations.</p>
     */
    SUPPORTS,

    /**
     * Support a current transaction, throw an exception if none exists.
     * 
     * <p>This behavior requires that a transaction already be active when the method
     * is called. If no transaction exists, an exception will be thrown. The method
     * will never create a new transaction.</p>
     * 
     * <p>Use this for methods that must be part of a larger transactional operation
     * and should never execute outside a transaction context.</p>
     */
    MANDATORY,

    /**
     * Create a new transaction, and suspend the current transaction if one exists.
     * 
     * <p>This behavior always creates a new transaction. If a transaction is already
     * active, it will be suspended until the new transaction completes. This ensures
     * that the method executes in complete isolation from any outer transaction.</p>
     * 
     * <p>Use this for operations that should be committed or rolled back independently
     * of the calling transaction, such as audit logging or error reporting.</p>
     */
    REQUIRES_NEW,

    /**
     * Execute non-transactionally, suspend the current transaction if one exists.
     * 
     * <p>This behavior ensures that the method never executes within a transaction.
     * If a transaction is active when the method is called, it will be suspended
     * until the method completes.</p>
     * 
     * <p>Use this for operations that should not be part of any transaction,
     * such as sending notifications or performing operations that should not
     * be rolled back.</p>
     */
    NOT_SUPPORTED,

    /**
     * Execute non-transactionally, throw an exception if a transaction exists.
     * 
     * <p>This is the most restrictive behavior - the method will only execute
     * if no transaction is currently active. If a transaction exists, an exception
     * will be thrown.</p>
     * 
     * <p>Use this for operations that must never execute within a transaction
     * and should fail fast if called from within a transactional context.</p>
     */
    NEVER
}
