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

/**
 * Defines how DAO results are copied when stored in or retrieved from a cache.
 *
 * <p>The copying strategies bypass serialization for values implementing
 * {@link com.landawn.abacus.util.Immutable}, except populated optional/nullable wrappers whose
 * contents may need copying. Other values are copied even if their Java types are immutable.</p>
 *
 * @see CacheResult#serialization()
 */
public enum CacheSerialization {
    /** Stores and returns direct object references without serialization. */
    NONE,

    /**
     * Uses Kryo to deep-copy cached values that require copying. Requires Kryo on the classpath;
     * without it, DAO creation still succeeds and the failure surfaces at invocation time &mdash; the
     * first attempt to cache a value that needs copying throws {@code UnsupportedOperationException}.
     */
    KRYO,

    /** Uses JSON to deep-copy cached values that require copying. */
    JSON
}
