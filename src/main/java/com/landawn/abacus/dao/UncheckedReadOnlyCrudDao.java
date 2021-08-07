package com.landawn.abacus.dao;

import java.util.Collection;
import java.util.List;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.SQLBuilder;

/**
 * TODO
 *
 * @param <T>
 * @param <ID>
 * @param <SB>
 * @param <TD>
 */
@Beta
public interface UncheckedReadOnlyCrudDao<T, ID, SB extends SQLBuilder, TD extends UncheckedReadOnlyCrudDao<T, ID, SB, TD>>
        extends UncheckedReadOnlyDao<T, SB, TD>, UncheckedNoUpdateCrudDao<T, ID, SB, TD>, ReadOnlyCrudDao<T, ID, SB, TD> {

    /**
     *
     * @param entityToInsert
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default ID insert(final T entityToInsert) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entityToInsert
     * @param propNamesToInsert
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default ID insert(final T entityToInsert, final Collection<String> propNamesToInsert) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param namedInsertSQL
     * @param entityToSave
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default ID insert(final String namedInsertSQL, final T entityToSave) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param batchSize
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param propNamesToInsert
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert)
            throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param propNamesToInsert
     * @param batchSize
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final Collection<? extends T> entities, final Collection<String> propNamesToInsert, final int batchSize)
            throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param namedInsertSQL
     * @param entities
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities)
            throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param namedInsertSQL
     * @param entities
     * @param batchSize
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default List<ID> batchInsert(final String namedInsertSQL, final Collection<? extends T> entities, final int batchSize)
            throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }
}