package com.landawn.abacus.dao;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.SQLBuilder;

/**
 * TODO
 *
 * @param <T>
 * @param <ID>
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @param <TD>
 * @see com.landawn.abacus.condition.ConditionFactory
 * @see com.landawn.abacus.condition.ConditionFactory.CF
 */
@Beta
public interface UncheckedNoUpdateCrudDao<T, ID, SB extends SQLBuilder, TD extends UncheckedNoUpdateCrudDao<T, ID, SB, TD>>
        extends UncheckedNoUpdateDao<T, SB, TD>, NoUpdateCrudDao<T, ID, SB, TD>, UncheckedCrudDao<T, ID, SB, TD> {

    /**
     *
     * @param entityToUpdate
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final T entityToUpdate) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entityToUpdate
     * @param propNamesToUpdate
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final T entityToUpdate, final Collection<String> propNamesToUpdate) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param propName
     * @param propValue
     * @param id
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @deprecated unsupported Operation
     */
    @Override
    @Deprecated
    default int update(final String propName, final Object propValue, final ID id) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param updateProps
     * @param id
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final ID id) throws UnsupportedOperationException, UncheckedSQLException {
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
    default int batchUpdate(final Collection<? extends T> entities) throws UnsupportedOperationException, UncheckedSQLException {
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
    default int batchUpdate(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param propNamesToUpdate
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate)
            throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entities
     * @param propNamesToUpdate
     * @param batchSize
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int batchUpdate(final Collection<? extends T> entities, final Collection<String> propNamesToUpdate, final int batchSize)
            throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
     *
     * @param entity
     * @param cond to verify if the record exists or not.
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default T upsert(final T entity, final Condition cond) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Execute {@code add} and return the added entity if the record doesn't, otherwise, {@code update} is executed and updated db record is returned.
     *
     * @param entity
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default T upsert(final T entity) throws UnsupportedOperationException, UncheckedSQLException {
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
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities) throws UnsupportedOperationException, UncheckedSQLException {
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
    @Override
    @Deprecated
    default List<T> batchUpsert(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entity
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int delete(final T entity) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Delete by id.
     *
     * @param id
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteById(final ID id) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    //    /**
    //     *
    //     * @param entity
    //     * @param onDeleteAction
    //     * @return
    //     * @throws UnsupportedOperationException
    //     * @throws UncheckedSQLException
    //     * @deprecated unsupported Operation
    //     */
    //    @Deprecated
    //    @Override
    //    default int delete(final T entity, final OnDeleteAction onDeleteAction) throws UnsupportedOperationException, UncheckedSQLException {
    //        throw new UnsupportedOperationException();
    //    }

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
    default int batchDelete(final Collection<? extends T> entities) throws UnsupportedOperationException, UncheckedSQLException {
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
    default int batchDelete(final Collection<? extends T> entities, final int batchSize) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    //    /**
    //     *
    //     * @param entities
    //     * @param onDeleteAction
    //     * @return
    //     * @throws UnsupportedOperationException
    //     * @throws UncheckedSQLException
    //     * @deprecated unsupported Operation
    //     */
    //    @Deprecated
    //    @Override
    //    default int batchDelete(final Collection<? extends T> entities, final OnDeleteAction onDeleteAction)
    //            throws UnsupportedOperationException, UncheckedSQLException {
    //        throw new UnsupportedOperationException();
    //    }
    //
    //    /**
    //     *
    //     * @param entities
    //     * @param onDeleteAction
    //     * @param batchSize
    //     * @return
    //     * @throws UnsupportedOperationException
    //     * @throws UncheckedSQLException
    //     * @deprecated unsupported Operation
    //     */
    //    @Deprecated
    //    @Override
    //    default int batchDelete(final Collection<? extends T> entities, final OnDeleteAction onDeleteAction, final int batchSize)
    //            throws UnsupportedOperationException, UncheckedSQLException {
    //        throw new UnsupportedOperationException();
    //    }

    /**
     *
     * @param ids
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int batchDeleteByIds(final Collection<? extends ID> ids) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param ids
     * @param batchSize
     * @return
     * @throws UnsupportedOperationException
     * @throws UncheckedSQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int batchDeleteByIds(final Collection<? extends ID> ids, final int batchSize) throws UnsupportedOperationException, UncheckedSQLException {
        throw new UnsupportedOperationException();
    }
}