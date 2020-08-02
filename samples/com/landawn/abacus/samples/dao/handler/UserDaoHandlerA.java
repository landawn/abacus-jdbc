package com.landawn.abacus.samples.dao.handler;

import java.lang.reflect.Method;

import com.landawn.abacus.samples.dao.UserDao;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Tuple.Tuple3;

public class UserDaoHandlerA implements JdbcUtil.Handler<UserDao> {

    @Override
    public void beforeInvoke(final UserDao userDao, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
        N.println("Calling method: " + methodSignature._1.getName() + " in UserDaoHandlerA.beforeInvoke");
    }

    @Override
    public void afterInvoke(final Object result, final UserDao userDao, final Object[] args,
            final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
        N.println("Calling method: " + methodSignature._1.getName() + " in UserDaoHandlerA.afterInvoke");
    }
}
