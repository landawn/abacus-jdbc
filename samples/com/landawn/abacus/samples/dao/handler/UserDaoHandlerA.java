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
