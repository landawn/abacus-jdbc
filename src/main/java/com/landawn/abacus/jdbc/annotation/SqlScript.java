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
 * Marks a {@code static final String} field as a named SQL script.
 *
 * <p>Annotated constants can be resolved by {@link Query#id()} and are useful for moving
 * large SQL definitions out of method annotations while keeping them close to the DAO type.</p>
 *
 * @see Query
 * @see SqlSource
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface SqlScript {

    /**
     * Supplies an optional identifier that overrides the annotated field name when the SQL is
     * registered. When left empty, the declaration name (for example {@code sql_listUserWithBiggerId})
     * becomes the key. Setting a custom id makes it possible to share the same SQL across
     * differently named constants or to shorten the token referenced from {@link Query}.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * @SqlScript(id = "sql_listUserWithBiggerId")
     * static final String listUserWithBiggerId =
     *         PSC.selectFrom(User.class).where(Filters.gt("id")).sql();
     * }</pre>
     *
     * @return the identifier used by {@link Query#id()}; empty means the annotated field name is used
     */
    String id() default ""; // default will be field name.
}
