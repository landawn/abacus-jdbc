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
package com.landawn.abacus.jdbc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * For example:
 * <p>
 * <code>
 *
 *  @Select("SELECT id, first_name as \"firstName\", last_name as \"lastName\", devices.id as \"devices.id\", device.model as \"devices.model\" FROM user left join device on user.id =  device.user_id WHERE id in ({ids}")
 *  <br />
 *  MergedById
 *  <br />
 * List<User> listUser(@BindList("ids") List<Integer> ids) throws SQLException;
 *
 * </code>
 * </p>
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD })
public @interface MergedById {

    /**
     *
     * @return
     * @deprecated using ids="id1, id2" for explicit call.
     */
    @Deprecated
    String value() default "";

    String ids() default "";

    String prefixFieldMapping() default "";
}