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
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.condition.Condition;

/**
 * Unchecked-exception infrastructure root: marker base that the unchecked capability interfaces
 * ({@link UncheckedReadOps}, {@link UncheckedInsertOps}, {@link UncheckedUpdateOps},
 * {@link UncheckedDeleteOps}) extend. The shared accessors are inherited from {@link DaoBase}; its
 * {@code prepare*} builders are re-declared here without checked exceptions and translate database
 * failures to {@link UncheckedSQLException}.
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-referencing DAO type
 * @see DaoBase
 * @see UncheckedReadOps
 */
@Beta
sealed interface UncheckedDaoBase<T, TD extends UncheckedDaoBase<T, TD>> extends DaoBase<T, TD>
        permits UncheckedReadOps, UncheckedInsertOps, UncheckedUpdateOps, UncheckedDeleteOps {

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code sql} is {@code null} or empty
     * @throws UnsupportedOperationException if invoked on a read-only DAO with non-SELECT SQL,
     *                                       or on a non-update DAO with SQL other than SELECT/INSERT
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String sql) throws UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> DaoBase.super.prepareQuery(sql));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code cond} is {@code null}
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final Condition cond) throws UncheckedSQLException {
        return prepareQuery(null, cond);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code cond} is {@code null}
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    PreparedQuery prepareQuery(final Collection<String> selectPropNames, final Condition cond) throws UncheckedSQLException;

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code sql} is {@code null} or empty
     * @throws UnsupportedOperationException if invoked on a read-only DAO with non-SELECT SQL,
     *                                       or on a non-update DAO with SQL other than SELECT/INSERT
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQueryForLargeResult(final String sql) throws UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> DaoBase.super.prepareQueryForLargeResult(sql));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code cond} is {@code null}
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQueryForLargeResult(final Condition cond) throws UncheckedSQLException {
        return prepareQueryForLargeResult(null, cond);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code cond} is {@code null}
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQueryForLargeResult(final Collection<String> selectPropNames, final Condition cond) throws UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> prepareQuery(selectPropNames, cond).configureStatement(DaoUtil.stmtSetterForBigQueryResult));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code namedSql} is {@code null} or empty,
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UnsupportedOperationException if invoked on a read-only DAO with non-SELECT SQL,
     *                                       or on a non-update DAO with SQL other than SELECT/INSERT
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedSql) throws UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> DaoBase.super.prepareNamedQuery(namedSql));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code namedSql} is {@code null},
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UnsupportedOperationException if invoked on a read-only DAO with non-SELECT SQL,
     *                                       or on a non-update DAO with SQL other than SELECT/INSERT
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedSql) throws UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> DaoBase.super.prepareNamedQuery(namedSql));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code cond} is {@code null}
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final Condition cond) throws UncheckedSQLException {
        return prepareNamedQuery(null, cond);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code cond} is {@code null}
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    NamedQuery prepareNamedQuery(final Collection<String> selectPropNames, final Condition cond) throws UncheckedSQLException;

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code namedSql} is {@code null} or empty,
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UnsupportedOperationException if invoked on a read-only DAO with non-SELECT SQL,
     *                                       or on a non-update DAO with SQL other than SELECT/INSERT
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQueryForLargeResult(final String namedSql) throws UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> DaoBase.super.prepareNamedQueryForLargeResult(namedSql));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code namedSql} is {@code null},
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UnsupportedOperationException if invoked on a read-only DAO with non-SELECT SQL,
     *                                       or on a non-update DAO with SQL other than SELECT/INSERT
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQueryForLargeResult(final ParsedSql namedSql) throws UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> DaoBase.super.prepareNamedQueryForLargeResult(namedSql));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code cond} is {@code null}
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQueryForLargeResult(final Condition cond) throws UncheckedSQLException {
        return prepareNamedQueryForLargeResult(null, cond);
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code cond} is {@code null}
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQueryForLargeResult(final Collection<String> selectPropNames, final Condition cond) throws UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> prepareNamedQuery(selectPropNames, cond).configureStatement(DaoUtil.stmtSetterForBigQueryResult));
    }
}
