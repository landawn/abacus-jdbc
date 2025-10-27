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
package com.landawn.abacus.jdbc;

import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.jdbc.dao.Dao;

/**
 * A no-operation implementation of {@link Jdbc.Handler} for DAO instances.
 * 
 * <p>This class serves as a placeholder handler that performs no operations.
 * It is marked as {@code @Internal} indicating it is intended for framework
 * internal use only and should not be used directly by application code.</p>
 * 
 * <p>The EmptyHandler is typically used in scenarios where a handler interface
 * must be provided but no actual handling logic is required, following the
 * Null Object pattern.</p>
 * 
 * @see Jdbc.Handler
 * @see Dao
 * @since 1.0
 */
@Internal
@SuppressWarnings("rawtypes")
public final class EmptyHandler implements Jdbc.Handler<Dao> {

    /**
     * Constructs a new EmptyHandler instance.
     *
     * <p>This no-operation handler is used internally by the framework
     * as a placeholder when no actual handling logic is required.</p>
     */
    public EmptyHandler() {
        // No-op constructor for empty handler
    }
}