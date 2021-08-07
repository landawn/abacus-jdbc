package com.landawn.abacus.dao;

import java.sql.SQLException;
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.util.SQLBuilder;

@Beta
public interface NoUpdateCrudDaoL<T, SB extends SQLBuilder, TD extends NoUpdateCrudDaoL<T, SB, TD>>
        extends NoUpdateCrudDao<T, Long, SB, TD>, CrudDaoL<T, SB, TD> {

    /**
     *
     * @param propName
     * @param propValue
     * @param id
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final String propName, final Object propValue, final long id) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param updateProps
     * @param id
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final long id) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * @param id
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteById(final long id) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }
}