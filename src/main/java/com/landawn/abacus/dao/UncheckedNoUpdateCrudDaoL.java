package com.landawn.abacus.dao;

import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.SQLBuilder;

@Beta
public interface UncheckedNoUpdateCrudDaoL<T, SB extends SQLBuilder, TD extends UncheckedNoUpdateCrudDaoL<T, SB, TD>>
        extends UncheckedNoUpdateCrudDao<T, Long, SB, TD>, UncheckedCrudDaoL<T, SB, TD> {

    /**
     *
     * @param propName
     * @param propValue
     * @param id
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final String propName, final Object propValue, final long id) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param updateProps
     * @param id
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final long id) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * @param id
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteById(final long id) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }
}