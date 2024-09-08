/*
 * Copyright (C) 2024 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.landawn.abacus.samples.dao.handler;

import java.lang.reflect.Method;

import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.samples.dao.UserDao;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Tuple.Tuple3;

public class UserDaoHandlerA implements Jdbc.Handler<UserDao> {

    /**
     *
     *
     * @param userDao
     * @param args
     * @param methodSignature
     */
    @Override
    public void beforeInvoke(final UserDao userDao, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
        N.println(">>>UserDaoHandlerA.beforeInvoke: method: " + methodSignature._1.getName());
    }

    /**
     *
     *
     * @param result
     * @param userDao
     * @param args
     * @param methodSignature
     */
    @Override
    public void afterInvoke(final Object result, final UserDao userDao, final Object[] args,
            final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
        N.println("<<<UserDaoHandlerA.afterInvoke: method: " + methodSignature._1.getName() + ". result: " + result);
    }
}
