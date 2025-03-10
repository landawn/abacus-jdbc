
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
 * It's only for methods with default implementation in {@code Dao} interfaces. Don't use it for the abstract methods.
 * And the last parameter of the method should be {@code String[]: (param1, param2, ..., String ... sqls)}
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Sqls {

    /**
     * Sql script, or id which is defined in sql mapper or sql table class inside DAO class.
     *
     * @return
     */
    String[] value() default {};
}
