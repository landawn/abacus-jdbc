/*
 * Copyright (c) 2021, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.landawn.abacus.jdbc.dao;

import java.util.Collection;
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicatedResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.u.OptionalBoolean;
import com.landawn.abacus.util.u.OptionalByte;
import com.landawn.abacus.util.u.OptionalChar;
import com.landawn.abacus.util.u.OptionalDouble;
import com.landawn.abacus.util.u.OptionalFloat;
import com.landawn.abacus.util.u.OptionalInt;
import com.landawn.abacus.util.u.OptionalLong;
import com.landawn.abacus.util.u.OptionalShort;

/**
 *
 *
 * @param <T>
 * @param <SB> {@code SQLBuilder} used to generate sql scripts. Only can be {@code SQLBuilder.PSC/PAC/PLC}
 * @param <TD>
 */
@Beta
public interface UncheckedCrudDaoL<T, SB extends SQLBuilder, TD extends UncheckedCrudDaoL<T, SB, TD>>
        extends UncheckedCrudDao<T, Long, SB, TD>, CrudDaoL<T, SB, TD> {

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default OptionalBoolean queryForBoolean(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForBoolean(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default OptionalChar queryForChar(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForChar(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default OptionalByte queryForByte(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForByte(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default OptionalShort queryForShort(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForShort(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default OptionalInt queryForInt(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForInt(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default OptionalLong queryForLong(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForLong(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default OptionalFloat queryForFloat(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForFloat(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default OptionalDouble queryForDouble(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForDouble(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default Nullable<String> queryForString(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForString(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForDate(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForTime(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForTimestamp(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default Nullable<byte[]> queryForBytes(final String singleSelectPropName, final long id) throws UncheckedSQLException {
        return queryForBytes(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param <V>
     * @param targetValueClass
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default <V> Nullable<V> queryForSingleResult(final Class<? extends V> targetValueClass, final String singleSelectPropName, final long id)
            throws UncheckedSQLException {
        return queryForSingleResult(targetValueClass, singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param <V>
     * @param targetValueClass
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default <V> Optional<V> queryForSingleNonNull(final Class<? extends V> targetValueClass, final String singleSelectPropName, final long id)
            throws UncheckedSQLException {
        return queryForSingleNonNull(targetValueClass, singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     * @param <V>
     * @param singleSelectPropName
     * @param id
     * @param rowMapper
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default <V> Optional<V> queryForSingleNonNull(final String singleSelectPropName, final long id, final Jdbc.RowMapper<? extends V> rowMapper)
            throws UncheckedSQLException {
        return queryForSingleNonNull(singleSelectPropName, Long.valueOf(id), rowMapper);
    }

    /**
     *
     *
     * @param <V>
     * @param targetValueClass
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws DuplicatedResultException
     * @throws UncheckedSQLException
     */
    @Override
    default <V> Nullable<V> queryForUniqueResult(final Class<? extends V> targetValueClass, final String singleSelectPropName, final long id)
            throws DuplicatedResultException, UncheckedSQLException {
        return queryForUniqueResult(targetValueClass, singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param <V>
     * @param targetValueClass
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws DuplicatedResultException
     * @throws UncheckedSQLException
     */
    @Override
    default <V> Optional<V> queryForUniqueNonNull(final Class<? extends V> targetValueClass, final String singleSelectPropName, final long id)
            throws DuplicatedResultException, UncheckedSQLException {
        return queryForUniqueNonNull(targetValueClass, singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     * @param <V>
     * @param singleSelectPropName
     * @param id
     * @param rowMapper
     * @return
     * @throws DuplicatedResultException
     * @throws UncheckedSQLException
     */
    @Override
    default <V> Optional<V> queryForUniqueNonNull(final String singleSelectPropName, final long id, final Jdbc.RowMapper<? extends V> rowMapper)
            throws DuplicatedResultException, UncheckedSQLException {
        return queryForUniqueNonNull(singleSelectPropName, Long.valueOf(id), rowMapper);
    }

    /**
     *
     *
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default Optional<T> get(final long id) throws UncheckedSQLException {
        return get(Long.valueOf(id));
    }

    /**
     *
     *
     * @param id
     * @param selectPropNames
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default Optional<T> get(final long id, final Collection<String> selectPropNames) throws UncheckedSQLException {
        return get(Long.valueOf(id), selectPropNames);
    }

    /**
     *
     *
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default T gett(final long id) throws UncheckedSQLException {
        return gett(Long.valueOf(id));
    }

    /**
     *
     *
     * @param id
     * @param selectPropNames
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default T gett(final long id, final Collection<String> selectPropNames) throws UncheckedSQLException {
        return gett(Long.valueOf(id), selectPropNames);
    }

    /**
     *
     *
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default boolean exists(final long id) throws UncheckedSQLException {
        return exists(Long.valueOf(id));
    }

    /**
     *
     *
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Beta
    @Override
    default boolean notExists(final long id) throws UncheckedSQLException {
        return !exists(id);
    }

    /**
     *
     *
     * @param propName
     * @param propValue
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default int update(final String propName, final Object propValue, final long id) throws UncheckedSQLException {
        return update(propName, propValue, Long.valueOf(id));
    }

    /**
     *
     *
     * @param updateProps
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default int update(final Map<String, Object> updateProps, final long id) throws UncheckedSQLException {
        return update(updateProps, Long.valueOf(id));
    }

    /**
     *
     *
     * @param id
     * @return
     * @throws UncheckedSQLException
     */
    @Override
    default int deleteById(final long id) throws UncheckedSQLException {
        return deleteById(Long.valueOf(id));
    }
}
