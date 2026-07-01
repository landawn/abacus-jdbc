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

import java.util.concurrent.Executor;

import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;

/**
 * Sealed accessor root shared by the join-entity read and delete capabilities
 * ({@link JoinEntityReadOps} and {@link JoinEntityDeleteOps}). It declares only the internal accessor
 * methods ({@code targetDaoInterface()}, {@code targetEntityClass()}, {@code targetTableName()},
 * {@code executor()}) that those op-interfaces rely on; it contains no join-load or join-delete
 * operation itself.
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the DAO implementation type (self-referencing for method chaining)
 *
 * @see JoinEntityReadOps
 * @see JoinEntityDeleteOps
 * @see JoinEntityHelper
 */
public sealed interface JoinEntityBase<T, TD extends Dao<T, TD>> permits JoinEntityReadOps, JoinEntityDeleteOps {
    /**
     * Retrieves the class type of the target DAO interface.
     * Internal use only.
     *
     * @return the class type of the target DAO interface
     * @deprecated Internal use only.
     */
    @Deprecated
    @NonDBOperation
    @Internal
    Class<TD> targetDaoInterface();

    /**
     * Retrieves the class type of the target entity.
     * Internal use only.
     *
     * @return the class type of the target entity
     * @deprecated Internal use only.
     */
    @Deprecated
    @NonDBOperation
    @Internal
    Class<T> targetEntityClass();

    /**
     * Retrieves the name of the target table.
     * Internal use only.
     *
     * @return the name of the target table
     * @deprecated Internal use only.
     */
    @Deprecated
    @NonDBOperation
    @Internal
    String targetTableName();

    /**
     * Retrieves the executor for executing tasks in parallel.
     * Internal use only.
     *
     * @return the executor for executing parallel tasks
     * @deprecated Internal use only.
     */
    @Deprecated
    @NonDBOperation
    @Internal
    Executor executor();

}
