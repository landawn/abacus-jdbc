/*
 * Copyright (c) 2021, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.landawn.abacus.jdbc.dao;

import java.util.Collection;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.query.SQLBuilder;

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
     *
     * @param entityToSave
     * @throws UncheckedSQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void save(final T entityToSave) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     *
     * @param entityToSave
     * @param propNamesToSave
     * @throws UncheckedSQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void save(final T entityToSave, final Collection<String> propNamesToSave) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     *
     * @param namedInsertSQL
     * @param entityToSave
     * @throws UncheckedSQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void save(final String namedInsertSQL, final T entityToSave) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave
     * @throws UncheckedSQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave
     * @param batchSize
     * @throws UncheckedSQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave
     * @param propNamesToSave
     * @throws UncheckedSQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave
     * @param propNamesToSave
     * @param batchSize
     * @throws UncheckedSQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param namedInsertSQL
     * @param entitiesToSave
     * @throws UncheckedSQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param namedInsertSQL
     * @param entitiesToSave
     * @param batchSize
     * @throws UncheckedSQLException
     * @throws UnsupportedOperationException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize)
            throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
