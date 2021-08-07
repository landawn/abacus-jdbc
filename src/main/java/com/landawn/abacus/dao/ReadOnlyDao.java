package com.landawn.abacus.dao;

import java.sql.SQLException;
import java.util.Collection;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.dao.annotation.NonDBOperation;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.NamedQuery;
import com.landawn.abacus.util.ParsedSql;
import com.landawn.abacus.util.PreparedQuery;
import com.landawn.abacus.util.SQLBuilder;

/**
 * TODO
 *
 * @param <T>
 * @param <SB>
 * @param <TD>
 * @see com.landawn.abacus.condition.ConditionFactory
 * @see com.landawn.abacus.condition.ConditionFactory.CF
 */
@Beta
public interface ReadOnlyDao<T, SB extends SQLBuilder, TD extends ReadOnlyDao<T, SB, TD>> extends NoUpdateDao<T, SB, TD> {
    /**
    *
    * @param query
    * @return
    * @throws UnsupportedOperationException if the specified {@code query} is not a {@code select} sql statement.
    * @throws SQLException
    */
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query) throws UnsupportedOperationException, SQLException {
        if (!DaoUtil.isSelectQuery(query)) {
            throw new UnsupportedOperationException("Only select query is supported in read-only Dao");
        }

        return JdbcUtil.prepareQuery(dataSource(), query);
    }

    /**
    *
    * @param query
    * @param generateKeys
    * @return
    * @throws UnsupportedOperationException
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final boolean generateKeys) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
    *
    * @param query
    * @param returnColumnIndexes
    * @return
    * @throws UnsupportedOperationException
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final int[] returnColumnIndexes) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
    *
    * @param query
    * @param returnColumnIndexes
    * @return
    * @throws UnsupportedOperationException
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default PreparedQuery prepareQuery(final String query, final String[] returnColumnNames) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
    *
    * @param namedQuery
    * @return
    * @throws UnsupportedOperationException if the specified {@code namedQuery} is not a {@code select} sql statement.
    * @throws SQLException
    */
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery) throws UnsupportedOperationException, SQLException {
        if (!DaoUtil.isSelectQuery(namedQuery)) {
            throw new UnsupportedOperationException("Only select query is supported in read-only Dao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery);
    }

    /**
    *
    * @param namedQuery
    * @param generateKeys
    * @return
    * @throws UnsupportedOperationException
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final boolean generateKeys) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
    *
    * @param namedQuery
    * @param returnColumnIndexes
    * @return
    * @throws UnsupportedOperationException
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final int[] returnColumnIndexes) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
    *
    * @param namedQuery
    * @param returnColumnNames
    * @return
    * @throws UnsupportedOperationException
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final String namedQuery, final String[] returnColumnNames) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
    *
    * @param namedQuery the named query
    * @return
    * @throws UnsupportedOperationException if the specified {@code namedQuery} is not a {@code select} sql statement.
    * @throws SQLException
    */
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery) throws UnsupportedOperationException, SQLException {
        if (!DaoUtil.isSelectQuery(namedQuery.sql())) {
            throw new UnsupportedOperationException("Only select query is supported in read-only Dao");
        }

        return JdbcUtil.prepareNamedQuery(dataSource(), namedQuery);
    }

    /**
    *
    * @param namedQuery the named query
    * @param generateKeys
    * @return
    * @throws UnsupportedOperationException
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final boolean generateKeys) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
    *
    * @param namedQuery
    * @param returnColumnIndexes
    * @return
    * @throws UnsupportedOperationException
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final int[] returnColumnIndexes) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
    *
    * @param namedQuery
    * @param returnColumnNames
    * @return
    * @throws UnsupportedOperationException
    * @throws SQLException
    * @deprecated unsupported Operation
    */
    @Deprecated
    @NonDBOperation
    @Override
    default NamedQuery prepareNamedQuery(final ParsedSql namedQuery, final String[] returnColumnNames) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entityToSave
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void save(final T entityToSave) throws UnsupportedOperationException, UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param entityToSave
     * @param propNamesToSave
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void save(final T entityToSave, final Collection<String> propNamesToSave) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     *
     * @param namedInsertSQL
     * @param entityToSave
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void save(final String namedInsertSQL, final T entityToSave) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave
     * @param batchSize
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final int batchSize) throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param entitiesToSave
     * @param propNamesToSave
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave)
            throws UnsupportedOperationException, SQLException {
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
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final Collection<? extends T> entitiesToSave, final Collection<String> propNamesToSave, final int batchSize)
            throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }

    /**
     * Always throws {@code UnsupportedOperationException}.
     *
     * @param namedInsertSQL
     * @param entitiesToSave
     * @return
     * @throws UnsupportedOperationException
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave) throws UnsupportedOperationException, SQLException {
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
     * @throws SQLException
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default void batchSave(final String namedInsertSQL, final Collection<? extends T> entitiesToSave, final int batchSize)
            throws UnsupportedOperationException, SQLException {
        throw new UnsupportedOperationException();
    }
}