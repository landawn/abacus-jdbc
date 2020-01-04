package com.landawn.abacus.samples.dao.handler;

import java.lang.reflect.Method;

import com.landawn.abacus.samples.dao.UserDao;
import com.landawn.abacus.util.Handler;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Result;
import com.landawn.abacus.util.Tuple.Tuple3;

public class UserDaoHandlerA implements Handler<UserDao> {

    @Override
    public void beforeInvoke(final UserDao userDao, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
        N.println("Calling method: " + methodSignature._1.getName() + " in UserDaoHandlerA.beforeInvoke");
    }

    @Override
    public void afterInvoke(final Result<?, Exception> result, final UserDao userDao, final Object[] args,
            final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
        N.println("Calling method: " + methodSignature._1.getName() + " in UserDaoHandlerA.afterInvoke");
    }
}
