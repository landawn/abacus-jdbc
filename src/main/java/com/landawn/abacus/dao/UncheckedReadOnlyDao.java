package com.landawn.abacus.dao;

import java.util.Collection;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.SQLBuilder;

/**
 * TODO
 *
 * @param <T>
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @param <TD>
 */
@Beta
public interface UncheckedReadOnlyDao<T, SB extends SQLBuilder, TD extends UncheckedReadOnlyDao<T, SB, TD>>
        extends UncheckedNoUpdateDao<T, SB, TD>, ReadOnlyDao<T, SB, TD> {

    /**
     *
     * @param entityToSave
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void save(final T entityToSave) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entityToSave
     * @param propNamesToSave
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void save(final T entityToSave, final Collection<String> propNamesToSave) throws UnsupportedOperationException, UncheckedSQLException {
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
    default void save(final String namedInsertSQL, final T entityToSave) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave
     * @param batchSize
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave
     * @param propNamesToSave
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave)
            throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave
     * @param propNamesToSave
     * @param batchSize
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize)
            throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param namedInsertSQL
     * @param entitiesToSave
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave)
            throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param namedInsertSQL
     * @param entitiesToSave
     * @param batchSize
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize)
            throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }
}