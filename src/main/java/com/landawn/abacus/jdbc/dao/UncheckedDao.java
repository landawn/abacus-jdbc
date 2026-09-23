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

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.CallableQuery;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.jdbc.cs;
import com.landawn.abacus.jdbc.annotation.NonDBOperation;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.QueryUtil;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.Beans;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Throwables;

/**
 * Interface for an unchecked Data Access Object (DAO) that extends the base {@link Dao} interface.
 * Its methods throw {@link UncheckedSQLException} instead of {@link SQLException}, providing a more
 * convenient API for developers who prefer unchecked exceptions.
 *
 * <p>Through its {@code Unchecked*Ops} super-interfaces it redeclares the save operations and the
 * condition-based query, update, and delete operations so callers do not need to handle checked
 * exceptions for those methods; this interface itself redeclares the {@code upsert} operations.
 * Inherited methods that are not redeclared keep the checked-exception contract from {@link Dao}.</p>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * public interface UserDao extends UncheckedDao<User, UserDao> {
 * }
 *
 * UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
 * User user = new User("John", "Doe");
 * userDao.save(user);
 *
 * com.landawn.abacus.util.u.Optional<User> foundUser = userDao.findFirst(Filters.eq("firstName", "John"));
 * }</pre>
 *
 * @param <T> the entity type managed by this DAO
 * @param <TD> the self-type of the DAO for method chaining
 * @see com.landawn.abacus.jdbc.dao.Dao
 * @see com.landawn.abacus.query.Filters
 */
@Beta
public non-sealed interface UncheckedDao<T, TD extends UncheckedDao<T, TD>>
        extends UncheckedReadOps<T, TD>, UncheckedInsertOps<T, TD>, UncheckedUpdateOps<T, TD>, UncheckedDeleteOps<T, TD>, Dao<T, TD> {

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code sql} is {@code null} or empty
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String sql, final boolean generateKeys) throws IllegalArgumentException, UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> Dao.super.prepareQuery(sql, generateKeys));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code sql} is {@code null} or empty, or if {@code generatedKeyColumnIndexes} is {@code null} or empty
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String sql, final int[] generatedKeyColumnIndexes) throws IllegalArgumentException, UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> Dao.super.prepareQuery(sql, generatedKeyColumnIndexes));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code sql} is {@code null} or empty, or if {@code generatedKeyColumnNames} is {@code null} or empty
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String sql, final String[] generatedKeyColumnNames) throws IllegalArgumentException, UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> Dao.super.prepareQuery(sql, generatedKeyColumnNames));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code sql} is {@code null} or empty, or {@code stmtCreator} is {@code null}
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails, or a supplied JDBC callback
     *         throws {@link SQLException}
     */
    @Override
    @Beta
    @NonDBOperation
    default PreparedQuery prepareQuery(final String sql, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws IllegalArgumentException, UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> Dao.super.prepareQuery(sql, stmtCreator));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code namedSql} is {@code null} or empty,
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedSql, final boolean generateKeys) throws IllegalArgumentException, UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> Dao.super.prepareNamedQuery(namedSql, generateKeys));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code namedSql} is {@code null} or empty, or if {@code generatedKeyColumnIndexes} is {@code null} or empty,
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedSql, final int[] generatedKeyColumnIndexes) throws IllegalArgumentException, UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> Dao.super.prepareNamedQuery(namedSql, generatedKeyColumnIndexes));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code namedSql} is {@code null} or empty, or if {@code generatedKeyColumnNames} is {@code null} or empty,
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedSql, final String[] generatedKeyColumnNames) throws IllegalArgumentException, UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> Dao.super.prepareNamedQuery(namedSql, generatedKeyColumnNames));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code namedSql} is {@code null},
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedSql, final boolean generateKeys) throws IllegalArgumentException, UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> Dao.super.prepareNamedQuery(namedSql, generateKeys));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code namedSql} is {@code null}, or if {@code generatedKeyColumnIndexes} is {@code null} or empty,
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedSql, final int[] generatedKeyColumnIndexes)
            throws IllegalArgumentException, UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> Dao.super.prepareNamedQuery(namedSql, generatedKeyColumnIndexes));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code namedSql} is {@code null}, or if {@code generatedKeyColumnNames} is {@code null} or empty,
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedSql, final String[] generatedKeyColumnNames)
            throws IllegalArgumentException, UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> Dao.super.prepareNamedQuery(namedSql, generatedKeyColumnNames));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code namedSql} is {@code null} or empty, or {@code stmtCreator} is {@code null},
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails, or a supplied JDBC callback
     *         throws {@link SQLException}
     */
    @Override
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final String namedSql, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws IllegalArgumentException, UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> Dao.super.prepareNamedQuery(namedSql, stmtCreator));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code namedSql} is {@code null}, or {@code stmtCreator} is {@code null},
     *                                  or if {@code namedSql} contains positional (unnamed) parameters
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails, or a supplied JDBC callback
     *         throws {@link SQLException}
     */
    @Override
    @Beta
    @NonDBOperation
    default NamedQuery prepareNamedQuery(final ParsedSql namedSql, final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator)
            throws IllegalArgumentException, UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> Dao.super.prepareNamedQuery(namedSql, stmtCreator));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code sql} is {@code null} or empty
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails
     */
    @Override
    @Beta
    @NonDBOperation
    default CallableQuery prepareCallableQuery(final String sql) throws IllegalArgumentException, UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> Dao.super.prepareCallableQuery(sql));
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalArgumentException if {@code sql} is {@code null} or empty, or {@code stmtCreator} is {@code null}
     * @throws UncheckedSQLException if acquiring a connection fails, or preparing or configuring the SQL statement fails, or a supplied JDBC callback
     *         throws {@link SQLException}
     */
    @Override
    @Beta
    @NonDBOperation
    default CallableQuery prepareCallableQuery(final String sql, final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator)
            throws IllegalArgumentException, UncheckedSQLException {
        return DaoUtil.uncheckedSql(() -> Dao.super.prepareCallableQuery(sql, stmtCreator));
    }

    /**
     * Executes an upsert operation: inserts the entity if no record matches the unique properties,
     * otherwise updates the existing record.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User("john@example.com", "John", "Doe");
     * user.setLastLogin(new java.util.Date());
     *
     * // Upsert based on email being unique
     * User result = userDao.upsert(user, Arrays.asList("email"));
     * }</pre>
     *
     * @param entity the entity to insert or update
     * @param matchPropNames the list of property names that uniquely identify the record
     * @return the saved entity (the input entity if it was newly inserted; otherwise the merged existing entity that was updated)
     * @throws IllegalArgumentException if {@code entity} is {@code null} or {@code matchPropNames} is {@code null} or empty,
     *                                  or if any name in {@code matchPropNames} is not a readable property of the entity class,
     *                                  or an existing row is updated and {@code entity} has a property the loaded
     *                                  entity does not
     * @throws UncheckedSQLException if acquiring a connection fails, or looking up an existing row or executing the required INSERT or UPDATE statement fails
     * @throws DuplicateResultException if more than one record matches the specified {@code matchPropNames}
     * @throws UnsupportedOperationException if an existing row is updated and the loaded class is an immutable bean
     * @see #upsert(Object, Condition)
     */
    @Override
    default T upsert(final T entity, final Collection<String> matchPropNames)
            throws IllegalArgumentException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotEmpty(matchPropNames, cs.matchPropNames);

        final Condition cond = Filters.allEqual(entity, matchPropNames);

        return upsert(entity, cond);
    }

    /**
     * Executes an upsert operation: inserts the entity if no record matches the condition,
     * otherwise updates the existing record.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * User user = new User();
     * user.setEmail("john@example.com");
     * user.setScore(100);
     *
     * // Custom condition for upsert
     * User result = userDao.upsert(user, Filters.and(
     *     Filters.eq("email", user.getEmail()),
     *     Filters.eq("accountType", "PREMIUM")
     * ));
     * }</pre>
     *
     * @param entity the entity to insert or update
     * @param cond the condition to verify if the record exists
     * @return the saved entity (the input entity if it was newly inserted; otherwise the merged existing entity that was updated)
     * @throws IllegalArgumentException if {@code entity} or {@code cond} is {@code null}, or an existing row is updated
     *                                  and {@code entity} has a property the loaded entity does not
     * @throws UncheckedSQLException if acquiring a connection fails, or looking up an existing row or executing the required INSERT or UPDATE statement fails
     * @throws DuplicateResultException if more than one record matches the specified condition
     * @throws UnsupportedOperationException if an existing row is updated and the loaded class is an immutable bean
     */
    @Override
    default T upsert(final T entity, final Condition cond)
            throws IllegalArgumentException, UncheckedSQLException, DuplicateResultException, UnsupportedOperationException {
        N.checkArgNotNull(entity, cs.entity);
        N.checkArgNotNull(cond, cs.cond);

        final T dbEntity = findOnlyOne(cond).orElseNull();

        if (dbEntity == null) {
            save(entity);
            return entity;
        } else {
            final Class<?> cls = entity.getClass();
            final List<String> idPropNameList = QueryUtil.idPropNames(cls);

            if (N.isEmpty(idPropNameList)) {
                Beans.mergeInto(entity, dbEntity);
                update(dbEntity, cond);
            } else {
                Beans.mergeInto(entity, dbEntity, false, N.newHashSet(idPropNameList));
                final Condition idCond = Filters.allEqual(dbEntity, idPropNameList);
                update(dbEntity, idCond);
            }

            return dbEntity;
        }
    }

}
