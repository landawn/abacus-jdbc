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

public enum Propagation {

    /**
     * Support a current transaction, create a new one if none exists.
     * <p>This is the default setting of a transaction annotation.
     */
    REQUIRED,

    /**
     * Support a current transaction, execute non-transactionally if none exists.
     */
    SUPPORTS,

    /**
     * Support a current transaction, throw an exception if none exists.
     */
    MANDATORY,

    /**
     * Create a new transaction, and suspend the current transaction if one exists.
     */
    REQUIRES_NEW,

    /**
     * Execute non-transactionally, suspend the current transaction if one exists.
     */
    NOT_SUPPORTED,

    /**
     * Execute non-transactionally, throw an exception if a transaction exists.
     */
    NEVER
}
