/*
 * Copyright (c) 2021, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.landawn.abacus.dao.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.landawn.abacus.dao.OP;
import com.landawn.abacus.util.JdbcUtil;

/**
 * The Interface Delete.
 *
 * @see <a href="https://stackoverflow.com/questions/1820908/how-to-turn-off-the-eclipse-code-formatter-for-certain-sections-of-java-code">How to turn off the Eclipse code formatter for certain sections of Java code?</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Delete {

    /**
     *
     * @return
     * @deprecated using sql="SELECT ... FROM ..." for explicit call.
     */
    @Deprecated
    String value() default "";

    /**
     *
     * @return
     */
    String id() default ""; // id defined SqlMapper

    /**
     *
     * @return
     */
    String sql() default "";

    /**
     *
     * @return
     */
    boolean isBatch() default false;

    /**
     *
     * @return
     */
    int batchSize() default JdbcUtil.DEFAULT_BATCH_SIZE;

    /**
     * Unit is seconds.
     *
     * @return
     */
    int queryTimeout() default -1;

    /**
     * Set it to true if there is only one input parameter and the type is Collection/Object Array, and the target db column type is Collection/Object Array.
     *
     * @return
     */
    boolean isSingleParameter() default false;

    OP op() default OP.DEFAULT;
}