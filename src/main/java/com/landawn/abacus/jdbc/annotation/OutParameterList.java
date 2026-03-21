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

/**
 * Container annotation for repeatable {@link OutParameter} declarations.
 *
 * <p>You normally do not use this annotation directly; it is synthesized by the compiler
 * when multiple {@code @OutParameter} annotations are declared on the same method.</p>
 *
 * @see OutParameter
 * @see Query
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OutParameterList {

    /**
     * The array of {@link OutParameter} annotations contained in this list.
     * Each OutParameter defines a single output parameter for a stored procedure call.
     * 
     * <p>This array is automatically populated when using multiple {@link OutParameter}
     * annotations on a method due to the {@link java.lang.annotation.Repeatable} mechanism.</p>
     * 
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @OutParameterList({
     *     @OutParameter(position = 1, sqlType = Types.VARCHAR),
     *     @OutParameter(position = 2, sqlType = Types.INTEGER),
     *     @OutParameter(position = 3, sqlType = Types.DECIMAL)
     * })
     * }</pre>
     *
     * @return array of OutParameter annotations defining the output parameters
     */
    OutParameter[] value();
}
