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
import com.landawn.abacus.jdbc.JdbcUtil;

@Beta
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD, ElementType.TYPE })
public @interface CacheResult {
    /**
     * Flag to identity if {@code CacheResult} is disabled.
     * @return
     */
    boolean disabled() default false;

    /**
     *
     * @return
     */
    long liveTime() default JdbcUtil.DEFAULT_CACHE_LIVE_TIME; // unit milliseconds.

    /**
     *
     * @return
     */
    long maxIdleTime() default JdbcUtil.DEFAULT_CACHE_MAX_IDLE_TIME; // unit milliseconds.

    /**
     * Minimum required size to cache a query result if the return type is {@code Collection} or {@code DataSet}.
     * This setting will be ignored if the return types are not {@code Collection} or {@code DataSet}.
     *
     * @return
     */
    int minSize() default 0; // for list/DataSet.

    /**
     * If the query result isn't cached if it's size is bigger than {@code maxSize} if the return type is {@code Collection} or {@code DataSet}.
     * This setting will be ignored if the return types are not {@code Collection} or {@code DataSet}.
     *
     * @return
     */
    int maxSize() default Integer.MAX_VALUE; // for list/DataSet.

    /**
     * It's used to copy/clone the result when save result to cache or fetch result from cache.
     * It can be set to {@code "none", "kryo" or "json"}.
     *
     * @return
     * @see <a href="https://github.com/EsotericSoftware/kryo">kryo</a>
     */
    String transfer() default "none";

    //    /**
    //     * If it's set to true, the cached result won't be removed by method annotated by {@code RefreshCache}.
    //     *
    //     * @return
    //     */
    //    boolean isStaticData() default false;

    /**
     * Those conditions (by contains ignore case or regular expression match) will be joined by {@code OR}, not {@code AND}.
     * It's only applied if target of annotation {@code CacheResult} is {@code Type}, and will be ignored if target is method.
     *
     * @return
     */
    String[] filter() default { "query", "queryFor", "list", "get", "batchGet", "find", "findFirst", "findOnlyOne", "exist", "notExist", "count" };

    // TODO: second, what will key be like?: {methodName=[args]} -> JSON or kryo?
    // KeyGenerator keyGenerator() default KeyGenerator.JSON; KeyGenerator.JSON/KRYO;
}
