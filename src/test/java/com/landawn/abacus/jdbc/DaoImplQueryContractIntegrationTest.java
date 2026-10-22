package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.ReadOnly;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.util.EntityId;

@Tag("integration")
public class DaoImplQueryContractIntegrationTest extends TestBase {

    @Table("dao_review_row")
    public static class ReviewRow {
        @Id
        @ReadOnly
        private long id;
        private int score;

        public long getId() {
            return id;
        }

        public void setId(final long id) {
            this.id = id;
        }

        public int getScore() {
            return score;
        }

        public void setScore(final int score) {
            this.score = score;
        }
    }

    interface ScalarBatchDao extends CrudDao<ReviewRow, Long, ScalarBatchDao> {
        @Query(value = "INSERT INTO dao_review_row (score) VALUES (?)", batch = true, collectionAsSingleParameter = true)
        void insertScores(List<Integer> scores, int batchSize) throws SQLException;

        @Query(value = "UPDATE dao_review_row SET score = score + 1 WHERE id = ?", batch = true, collectionAsSingleParameter = true)
        int incrementScores(List<Long> ids, int batchSize) throws SQLException;
    }

    // SELECTs run only for their side effect (row lock, sequence advance): the result must be discarded.
    interface VoidSelectDao extends CrudDao<ReviewRow, Long, VoidSelectDao> {
        @Query("SELECT score FROM dao_review_row WHERE id = ? FOR UPDATE")
        void lockRow(long id) throws SQLException;

        @Query("SELECT NEXT VALUE FOR dao_review_seq")
        void advanceSequence() throws SQLException;

        @Query("SELECT NEXT VALUE FOR dao_review_seq")
        Void advanceSequenceBoxed() throws SQLException;
    }

    interface VoidSelectMockDao extends CrudDao<ReviewRow, Long, VoidSelectMockDao> {
        @Query("SELECT id FROM dao_review_row WHERE score > ? FOR UPDATE")
        void lockRows(int minScore) throws SQLException;

        @Query(value = "SELECT id FROM dao_review_row WHERE id = ? FOR UPDATE", op = QueryOperation.findOnlyOne)
        void lockOnlyRow(long id) throws SQLException;

        @Query("SELECT * INTO dao_review_archive FROM dao_review_row")
        void archiveRows() throws SQLException;
    }

    interface ProcedureMapDao extends CrudDao<ReviewRow, Long, ProcedureMapDao> {
        @Query(value = "{call update_score(?)}", procedure = true, op = QueryOperation.update)
        int updateScore(Map<String, Object> parameters) throws SQLException;
    }

    interface ProcedureTwoNamesMapDao extends CrudDao<ReviewRow, Long, ProcedureTwoNamesMapDao> {
        @Query(value = "{call update_score(?, ?)}", procedure = true, op = QueryOperation.update)
        int updateScore(Map<String, Object> parameters) throws SQLException;
    }

    interface PositionalMapDao extends CrudDao<ReviewRow, Long, PositionalMapDao> {
        @Query(value = "UPDATE dao_review_row SET score = ?")
        int updateScore(Map<String, Object> parameters) throws SQLException;
    }

    interface ProcedureBeanDao extends CrudDao<ReviewRow, Long, ProcedureBeanDao> {
        @Query(value = "{call update_score(?)}", procedure = true, op = QueryOperation.update)
        int updateScore(ReviewRow parameters) throws SQLException;
    }

    interface ProcedureEntityIdDao extends CrudDao<ReviewRow, Long, ProcedureEntityIdDao> {
        @Query(value = "{call update_score(?)}", procedure = true, op = QueryOperation.update)
        int updateScore(EntityId parameters) throws SQLException;
    }

    public record ScoreRecord(int score) {
    }

    interface ProcedureRecordDao extends CrudDao<ReviewRow, Long, ProcedureRecordDao> {
        @Query(value = "{call update_score(?)}", procedure = true, op = QueryOperation.update)
        int updateScore(ScoreRecord parameters) throws SQLException;
    }

    @Test
    void scalarBatchAcceptsAnExplicitBatchSizeForInsertAndUpdate() throws SQLException {
        final JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:dao_review_scalar_batch");

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE dao_review_row (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, score INTEGER)");
            final ScalarBatchDao dao = JdbcUtil.createDao(ScalarBatchDao.class, dataSource);

            dao.insertScores(List.of(10, 20, 30), 2);
            assertEquals(3, dao.count(com.landawn.abacus.query.Filters.alwaysTrue()));
            assertEquals(3, dao.incrementScores(List.of(1L, 2L, 3L), 2));
            assertEquals(11, dao.getOrNull(1L).getScore());
            assertEquals(31, dao.getOrNull(3L).getScore());

            dao.insertScores(List.of(40), 0);
            assertEquals(1, dao.incrementScores(List.of(4L), 10));
            assertEquals(41, dao.getOrNull(4L).getScore());
            assertThrows(IllegalArgumentException.class, () -> dao.incrementScores(List.of(1L), -1));
        }
    }

    // A void SELECT used to be read as a single value and failed every call with
    // "IllegalArgumentException: Conversion returned java.lang.Long for void".
    @Test
    void voidSelectExecutesTheStatementAndDiscardsTheResult() throws SQLException {
        final JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:dao_review_void_select");

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE dao_review_row (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, score INTEGER)");
            statement.execute("INSERT INTO dao_review_row (score) VALUES (10)");
            statement.execute("CREATE SEQUENCE dao_review_seq START WITH 1");
            final VoidSelectDao dao = JdbcUtil.createDao(VoidSelectDao.class, dataSource);

            dao.advanceSequence();
            dao.advanceSequence();
            assertNull(dao.advanceSequenceBoxed());

            try (ResultSet rs = statement.executeQuery("SELECT NEXT VALUE FOR dao_review_seq")) {
                rs.next();
                assertEquals(4L, rs.getLong(1)); // each void call really executed its SELECT
            }

            final SqlTransaction tran = JdbcUtil.beginTransaction(dataSource);

            try {
                dao.lockRow(1L);

                // The row lock taken by the void SELECT ... FOR UPDATE is held until the transaction ends.
                statement.execute("SET LOCK_TIMEOUT 100");
                assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE dao_review_row SET score = 20 WHERE id = 1"));
            } finally {
                tran.rollback();
            }

            assertEquals(1, statement.executeUpdate("UPDATE dao_review_row SET score = 20 WHERE id = 1"));
        }
    }

    // The rows of a void SELECT are read to the end, not only executed: a driver fetching through a cursor (e.g. PostgreSQL
    // with a fetch size in a transaction) runs the query, and locks its rows, only as far as rows are fetched.
    @Test
    void voidSelectReadsAllRowsBeforeClosingTheStatement() throws SQLException {
        final PreparedStatement statement = mock(PreparedStatement.class);
        final ResultSet resultSet = mock(ResultSet.class);
        final VoidSelectMockDao dao = JdbcUtil.createDao(VoidSelectMockDao.class, mockDataSourceFor(statement));
        when(statement.execute()).thenReturn(true);
        when(statement.getResultSet()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, true, false);

        dao.lockRows(5);

        verify(statement).setInt(1, 5);
        verify(statement).execute();
        verify(resultSet, times(4)).next();
        verify(resultSet).close();
        verify(statement).close();
    }

    // A SELECT that returns no result set (e.g. SELECT ... INTO a table) is only executed.
    @Test
    void voidSelectWithoutResultSetIsOnlyExecuted() throws SQLException {
        final PreparedStatement statement = mock(PreparedStatement.class);
        final VoidSelectMockDao dao = JdbcUtil.createDao(VoidSelectMockDao.class, mockDataSourceFor(statement));
        when(statement.execute()).thenReturn(false);

        dao.archiveRows();

        verify(statement).execute();
        verify(statement, never()).getResultSet();
        verify(statement).close();
    }

    // A void SELECT declared with findOnlyOne (or queryForUnique) still rejects more than one row, like every other
    // single-result dispatch, instead of silently ignoring the declared uniqueness contract.
    @Test
    void voidFindOnlyOneSelectRejectsMoreThanOneRow() throws SQLException {
        final PreparedStatement statement = mock(PreparedStatement.class);
        final ResultSet resultSet = mock(ResultSet.class);
        final VoidSelectMockDao dao = JdbcUtil.createDao(VoidSelectMockDao.class, mockDataSourceFor(statement));
        when(statement.execute()).thenReturn(true);
        when(statement.getResultSet()).thenReturn(resultSet);

        when(resultSet.next()).thenReturn(true, false);
        dao.lockOnlyRow(1L);

        when(resultSet.next()).thenReturn(true, true, false);
        assertThrows(DuplicateResultException.class, () -> dao.lockOnlyRow(1L));

        verify(statement, times(2)).execute();
        verify(statement, times(2)).close();
    }

    private static DataSource mockDataSourceFor(final PreparedStatement statement) throws SQLException {
        final DataSource dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        when(metadata.getDatabaseProductVersion()).thenReturn("2");
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(connection.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(statement);
        when(statement.getConnection()).thenReturn(connection);

        return dataSource;
    }

    @Test
    void procedureMapsBindByCallableParameterName() throws SQLException {
        final DataSource dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        final CallableStatement statement = mock(CallableStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        when(metadata.getDatabaseProductVersion()).thenReturn("2");
        when(connection.prepareCall("{call update_score(?)}")).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        final ProcedureMapDao dao = JdbcUtil.createDao(ProcedureMapDao.class, dataSource);
        assertEquals(1, dao.updateScore(Map.of("score", 42)));
        verify(statement).setInt("score", 42);
        verify(statement).close();

        assertThrows(UnsupportedOperationException.class, () -> JdbcUtil.createDao(PositionalMapDao.class, dataSource));
        assertThrows(UnsupportedOperationException.class, () -> JdbcUtil.createDao(ProcedureBeanDao.class, dataSource));
        assertThrows(UnsupportedOperationException.class, () -> JdbcUtil.createDao(ProcedureEntityIdDao.class, dataSource));
        assertThrows(UnsupportedOperationException.class, () -> JdbcUtil.createDao(ProcedureRecordDao.class, dataSource));
    }

    @Test
    void aSingleProcedureMapBindsMultipleDriverParameterNames() throws SQLException {
        final DataSource dataSource = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        final CallableStatement statement = mock(CallableStatement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("H2");
        when(metadata.getDatabaseProductVersion()).thenReturn("2");
        when(connection.prepareCall("{call update_score(?, ?)}")).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1);

        final ProcedureTwoNamesMapDao dao = JdbcUtil.createDao(ProcedureTwoNamesMapDao.class, dataSource);
        assertEquals(1, dao.updateScore(Map.of("score", 42, "employee_id", 7L)));
        verify(statement).setInt("score", 42);
        verify(statement).setLong("employee_id", 7L);
        verify(statement).close();
    }
}
