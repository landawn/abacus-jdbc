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

import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicatedResultException;
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
 * @see com.landawn.abacus.condition.ConditionFactory
 * @see com.landawn.abacus.condition.ConditionFactory.CF
 */
@Beta
public interface CrudDaoL<T, SB extends SQLBuilder, TD extends CrudDaoL<T, SB, TD>> extends CrudDao<T, Long, SB, TD> {

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     */
    default OptionalBoolean queryForBoolean(final String singleSelectPropName, final long id) throws SQLException {
        return queryForBoolean(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     */
    default OptionalChar queryForChar(final String singleSelectPropName, final long id) throws SQLException {
        return queryForChar(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     */
    default OptionalByte queryForByte(final String singleSelectPropName, final long id) throws SQLException {
        return queryForByte(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     */
    default OptionalShort queryForShort(final String singleSelectPropName, final long id) throws SQLException {
        return queryForShort(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     */
    default OptionalInt queryForInt(final String singleSelectPropName, final long id) throws SQLException {
        return queryForInt(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     */
    default OptionalLong queryForLong(final String singleSelectPropName, final long id) throws SQLException {
        return queryForLong(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     */
    default OptionalFloat queryForFloat(final String singleSelectPropName, final long id) throws SQLException {
        return queryForFloat(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     */
    default OptionalDouble queryForDouble(final String singleSelectPropName, final long id) throws SQLException {
        return queryForDouble(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     */
    default Nullable<String> queryForString(final String singleSelectPropName, final long id) throws SQLException {
        return queryForString(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     */
    default Nullable<java.sql.Date> queryForDate(final String singleSelectPropName, final long id) throws SQLException {
        return queryForDate(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     */
    default Nullable<java.sql.Time> queryForTime(final String singleSelectPropName, final long id) throws SQLException {
        return queryForTime(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     */
    default Nullable<java.sql.Timestamp> queryForTimestamp(final String singleSelectPropName, final long id) throws SQLException {
        return queryForTimestamp(singleSelectPropName, Long.valueOf(id));
    }

    /**
     *
     *
     * @param singleSelectPropName
     * @param id
     * @return
     * @throws SQLException
     */
    default Nullable<byte[]> queryForBytes(final String singleSelectPropName, final long id) throws SQLException {
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
     * @throws SQLException
     */
    default <V> Nullable<V> queryForSingleResult(final Class<? extends V> targetValueClass, final String singleSelectPropName, final long id)
            throws SQLException {
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
     * @throws SQLException
     */
    default <V> Optional<V> queryForSingleNonNull(final Class<? extends V> targetValueClass, final String singleSelectPropName, final long id)
            throws SQLException {
        return queryForSingleNonNull(targetValueClass, singleSelectPropName, Long.valueOf(id));
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
     * @throws SQLException
     */
    default <V> Nullable<V> queryForUniqueResult(final Class<? extends V> targetValueClass, final String singleSelectPropName, final long id)
            throws DuplicatedResultException, SQLException {
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
     * @throws SQLException
     */
    default <V> Optional<V> queryForUniqueNonNull(final Class<? extends V> targetValueClass, final String singleSelectPropName, final long id)
            throws DuplicatedResultException, SQLException {
        return queryForUniqueNonNull(targetValueClass, singleSelectPropName, Long.valueOf(id));
    }

    /**
     * Returns the record found by the specified {@code id} or an empty {@code Optional} if no record is found.
     *
     *
     * @param id
     * @return
     * @throws SQLException
     */
    default Optional<T> get(final long id) throws SQLException {
        return get(Long.valueOf(id));
    }

    /**
     * Returns the record found by the specified {@code id} or an empty {@code Optional} if no record is found.
     *
     *
     * @param id
     * @param selectPropNames
     * @return
     * @throws SQLException
     */
    default Optional<T> get(final long id, final Collection<String> selectPropNames) throws SQLException {
        return get(Long.valueOf(id), selectPropNames);
    }

    /**
     * Returns the record found by the specified {@code id} or {@code null} if no record is found.
     *
     *
     * @param id
     * @return
     * @throws SQLException
     */
    default T gett(final long id) throws SQLException {
        return gett(Long.valueOf(id));
    }

    /**
     * Returns the record found by the specified {@code id} or {@code null} if no record is found.
     *
     *
     * @param id
     * @param selectPropNames
     * @return
     * @throws SQLException
     */
    default T gett(final long id, final Collection<String> selectPropNames) throws SQLException {
        return gett(Long.valueOf(id), selectPropNames);
    }

    /**
     *
     *
     * @param id
     * @return
     * @throws SQLException
     */
    default boolean exists(final long id) throws SQLException {
        return exists(Long.valueOf(id));
    }

    /**
     *
     *
     * @param id
     * @return
     * @throws SQLException
     */
    @Beta
    default boolean notExists(final long id) throws SQLException {
        return !exists(id);
    }

    /**
     *
     *
     * @param propName
     * @param propValue
     * @param id
     * @return
     * @throws SQLException
     */
    default int update(final String propName, final Object propValue, final long id) throws SQLException {
        return update(propName, propValue, Long.valueOf(id));
    }

    /**
     *
     *
     * @param updateProps
     * @param id
     * @return
     * @throws SQLException
     */
    default int update(final Map<String, Object> updateProps, final long id) throws SQLException {
        return update(updateProps, Long.valueOf(id));
    }

    /**
     *
     *
     * @param id
     * @return
     * @throws SQLException
     */
    default int deleteById(final long id) throws SQLException {
        return deleteById(Long.valueOf(id));
    }
}