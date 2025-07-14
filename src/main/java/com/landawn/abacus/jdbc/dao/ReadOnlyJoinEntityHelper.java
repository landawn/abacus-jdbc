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

import java.sql.SQLException;
import java.util.Collection;
import java.util.concurrent.Executor;

import com.landawn.abacus.query.SQLBuilder;

/**
 * A read-only interface for handling join entity operations in a Data Access Object (DAO) pattern.
 * This interface extends {@link JoinEntityHelper} but overrides all mutation methods to throw
 * {@link UnsupportedOperationException}, enforcing read-only behavior for join entity operations.
 * 
 * <p>This interface is useful when you want to provide read-only access to join entity operations,
 * preventing any modifications to the relationships between entities while still allowing
 * read operations inherited from the parent interface.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * public class UserReadOnlyDao implements ReadOnlyJoinEntityHelper<User, SQLBuilder, UserDao> {
 *     // All delete operations will throw UnsupportedOperationException
 *     // Only read operations from JoinEntityHelper are available
 * }
 * }</pre>
 * 
 * @param <T> the type of the entity
 * @param <SB> the type of SQLBuilder used for query construction
 * @param <TD> the type of the DAO implementation
 * @see JoinEntityHelper
 * @see Dao
 */
@SuppressWarnings("RedundantThrows")
public interface ReadOnlyJoinEntityHelper<T, SB extends SQLBuilder, TD extends Dao<T, SB, TD>> extends JoinEntityHelper<T, SB, TD> {

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity whose join entities should be deleted
     * @param joinEntityClass the class of the join entity to delete
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only interface
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Class<?> joinEntityClass) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities whose join entities should be deleted
     * @param joinEntityClass the class of the join entity to delete
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only interface
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Class<?> joinEntityClass) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity whose join entities should be deleted
     * @param joinEntityPropName the property name of the join entity to delete
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only interface
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final String joinEntityPropName) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities whose join entities should be deleted
     * @param joinEntityPropName the property name of the join entity to delete
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only interface
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final String joinEntityPropName) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity whose join entities should be deleted
     * @param joinEntityPropNames the collection of property names of join entities to delete
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only interface
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity whose join entities should be deleted
     * @param joinEntityPropNames the collection of property names of join entities to delete
     * @param inParallel whether to execute the deletions in parallel
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only interface
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity whose join entities should be deleted
     * @param joinEntityPropNames the collection of property names of join entities to delete
     * @param executor the executor to use for parallel execution
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only interface
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final T entity, final Collection<String> joinEntityPropNames, final Executor executor)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities whose join entities should be deleted
     * @param joinEntityPropNames the collection of property names of join entities to delete
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only interface
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities whose join entities should be deleted
     * @param joinEntityPropNames the collection of property names of join entities to delete
     * @param inParallel whether to execute the deletions in parallel
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only interface
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final boolean inParallel)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities whose join entities should be deleted
     * @param joinEntityPropNames the collection of property names of join entities to delete
     * @param executor the executor to use for parallel execution
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only interface
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default int deleteJoinEntities(final Collection<T> entities, final Collection<String> joinEntityPropNames, final Executor executor)
            throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity whose all join entities should be deleted
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only interface
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity whose all join entities should be deleted
     * @param inParallel whether to execute the deletions in parallel
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only interface
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final boolean inParallel) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entity the entity whose all join entities should be deleted
     * @param executor the executor to use for parallel execution
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only interface
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final T entity, final Executor executor) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities whose all join entities should be deleted
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only interface
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities whose all join entities should be deleted
     * @param inParallel whether to execute the deletions in parallel
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only interface
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities, final boolean inParallel) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * This operation is not supported in read-only mode.
     * Always throws {@link UnsupportedOperationException}.
     *
     * @param entities the collection of entities whose all join entities should be deleted
     * @param executor the executor to use for parallel execution
     * @return never returns normally
     * @throws SQLException never thrown due to UnsupportedOperationException
     * @throws UnsupportedOperationException always thrown as this is a read-only interface
     * @deprecated This operation is not supported in read-only mode
     */
    @Deprecated
    @Override
    default int deleteAllJoinEntities(final Collection<T> entities, final Executor executor) throws SQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}