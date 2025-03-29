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

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.Jdbc.DaoCache;
import com.landawn.abacus.jdbc.JdbcContext;

// TODO: First of all, it's a bad idea to implement cache in DAL layer?! and how if not?
/**
 * Mostly, it's used for static tables.
 */
@Beta
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.TYPE })
public @interface Cache {

    /**
     *
     *
     * @return
     */
    int capacity() default JdbcContext.DEFAULT_BATCH_SIZE;

    /**
     *
     *
     * @return
     */
    long evictDelay() default JdbcContext.DEFAULT_CACHE_EVICT_DELAY; // unit milliseconds.

    /**
     * The implementation of {@code DaoCache}. it must have a public constructor with type: {@code (int capacity, long evictDelay)}.
     * 
     * @return
     */
    Class<? extends DaoCache> impl() default Jdbc.DefaultDaoCache.class; //NOSONAR
}
