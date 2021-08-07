package com.landawn.abacus.dao;

import java.util.Collection;
import java.util.concurrent.Executor;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.SQLBuilder;

public interface UncheckedReadOnlyJoinEntityHelper<T, SB extends SQLBuilder, TD extends UncheckedDao<T, SB, TD>>
        extends UncheckedJoinEntityHelper<T, SB, TD>, ReadOnlyJoinEntityHelper<T, SB, TD> {

    /**
     *
     * @param entity
     * @param joinEntityClass
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param joinEntityClass
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entity
     * @param joinEntityPropName
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final String joinEntityPropName) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @param selectPropNames all properties(columns) will be selected, excluding the properties of joining entities, if the specified {@code selectPropNames} is {@code null}.
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entity
     * @param joinEntityPropNames
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entity
     * @param joinEntityPropNames
     * @param inParallel
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entity
     * @param joinEntityPropNames
     * @param executor
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @param inParallel
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param joinEntityPropName
     * @param executor
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entity
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(T entity) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entity
     * @param inParallel
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final boolean inParallel) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entity
     * @param executor
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final Executor executor) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param inParallel
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param executor
     * @return the total count of updated/deleted records.
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}