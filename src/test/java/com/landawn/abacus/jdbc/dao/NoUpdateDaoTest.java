package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.CallableQuery;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.SqlBuilder.PSC;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.Throwables;

public class NoUpdateDaoTest extends TestBase {

    interface TestNoUpdateDao extends NoUpdateDao<TestEntity, PSC, TestNoUpdateDao> {
    }

    static final class TestEntity {
    }

    @Test
    public void testPrepareQuery_SelectAllowed() throws SQLException {
        TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        DatabaseMetaData metaData = Mockito.mock(DatabaseMetaData.class);

        when(dao.dataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(metaData.getDatabaseProductVersion()).thenReturn("8.0");
        when(connection.prepareStatement("SELECT * FROM demo")).thenReturn(stmt);

        PreparedQuery query = dao.prepareQuery("SELECT * FROM demo");

        assertNotNull(query);
        verify(connection).prepareStatement("SELECT * FROM demo");
    }

    @Test
    public void testPrepareQuery_UpdateRejected() {
        TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("UPDATE demo SET name = 'x'"));
    }

    @Test
    public void testPrepareNamedQuery_InsertAllowed() throws SQLException {
        TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        DatabaseMetaData metaData = Mockito.mock(DatabaseMetaData.class);

        when(dao.dataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(metaData.getDatabaseProductVersion()).thenReturn("8.0");
        when(connection.prepareStatement("INSERT INTO demo(id) VALUES (?)")).thenReturn(stmt);

        NamedQuery query = dao.prepareNamedQuery("INSERT INTO demo(id) VALUES (:id)");

        assertNotNull(query);
        verify(connection).prepareStatement("INSERT INTO demo(id) VALUES (?)");
    }

    @Test
    public void testPrepareCallableQuery_UnsupportedOperation() {
        TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareCallableQuery("{call demo_proc()}"));
    }

    @Test
    public void testPrepareQuery_KeyOverloads_InsertAllowed() throws SQLException {
        final TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final PreparedQuery generatedKeysQuery = Mockito.mock(PreparedQuery.class);
        final PreparedQuery indexedKeysQuery = Mockito.mock(PreparedQuery.class);
        final PreparedQuery namedKeysQuery = Mockito.mock(PreparedQuery.class);
        final PreparedQuery largeResultQuery = Mockito.mock(PreparedQuery.class);

        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareQuery(dataSource, "INSERT INTO demo(id) VALUES (?)", true)).thenReturn(generatedKeysQuery);
            jdbcUtil.when(() -> JdbcUtil.prepareQuery(dataSource, "INSERT INTO demo(id) VALUES (?)", new int[] { 1 })).thenReturn(indexedKeysQuery);
            jdbcUtil.when(() -> JdbcUtil.prepareQuery(dataSource, "INSERT INTO demo(id) VALUES (?)", new String[] { "id" })).thenReturn(namedKeysQuery);
            jdbcUtil.when(() -> JdbcUtil.prepareQueryForLargeResult(dataSource, "SELECT * FROM demo")).thenReturn(largeResultQuery);

            assertSame(generatedKeysQuery, dao.prepareQuery("INSERT INTO demo(id) VALUES (?)", true));
            assertSame(indexedKeysQuery, dao.prepareQuery("INSERT INTO demo(id) VALUES (?)", new int[] { 1 }));
            assertSame(namedKeysQuery, dao.prepareQuery("INSERT INTO demo(id) VALUES (?)", new String[] { "id" }));
            assertSame(largeResultQuery, dao.prepareQueryForLargeResult("SELECT * FROM demo"));
        }
    }

    @Test
    public void testPrepareQuery_KeyOverloads_UpdateRejected() {
        final TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("UPDATE demo SET id = 1", true));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("DELETE FROM demo", new int[] { 1 }));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("UPDATE demo SET id = 1", new String[] { "id" }));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQueryForLargeResult("DELETE FROM demo"));
    }

    @Test
    public void testPrepareNamedQuery_ParsedSqlKeyOverloads() throws SQLException {
        final TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final ParsedSql parsedInsert = Mockito.mock(ParsedSql.class);
        final ParsedSql parsedUpdate = Mockito.mock(ParsedSql.class);
        final NamedQuery generatedKeysQuery = Mockito.mock(NamedQuery.class);
        final NamedQuery indexedKeysQuery = Mockito.mock(NamedQuery.class);
        final NamedQuery namedKeysQuery = Mockito.mock(NamedQuery.class);
        final NamedQuery largeResultQuery = Mockito.mock(NamedQuery.class);

        when(dao.dataSource()).thenReturn(dataSource);
        when(parsedInsert.originalSql()).thenReturn("INSERT INTO demo(id) VALUES (:id)");
        when(parsedUpdate.originalSql()).thenReturn("UPDATE demo SET id = :id");

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(dataSource, parsedInsert, true)).thenReturn(generatedKeysQuery);
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(dataSource, parsedInsert, new int[] { 1 })).thenReturn(indexedKeysQuery);
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(dataSource, parsedInsert, new String[] { "id" })).thenReturn(namedKeysQuery);
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQueryForLargeResult(dataSource, parsedInsert)).thenReturn(largeResultQuery);

            assertSame(generatedKeysQuery, dao.prepareNamedQuery(parsedInsert, true));
            assertSame(indexedKeysQuery, dao.prepareNamedQuery(parsedInsert, new int[] { 1 }));
            assertSame(namedKeysQuery, dao.prepareNamedQuery(parsedInsert, new String[] { "id" }));
            assertSame(largeResultQuery, dao.prepareNamedQueryForLargeResult(parsedInsert));
        }

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery(parsedUpdate, true));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery(parsedUpdate, new int[] { 1 }));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery(parsedUpdate, new String[] { "id" }));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQueryForLargeResult(parsedUpdate));
    }

    @Test
    public void testUnsupportedMutationOverloads() {
        final TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition condition = Mockito.mock(Condition.class);

        assertThrows(UnsupportedOperationException.class, () -> dao.update("name", "value", condition));
        assertThrows(UnsupportedOperationException.class, () -> dao.upsert(new TestEntity(), List.of("id")));
        assertThrows(UnsupportedOperationException.class, () -> dao.upsert(new TestEntity(), condition));
    }

    @Test
    public void testPrepareQuery_WithStmtCreator_AlwaysThrows() {
        final TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);
        @SuppressWarnings("unchecked")
        final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator = Mockito.mock(Throwables.BiFunction.class);

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("SELECT 1", stmtCreator));
    }

    @Test
    public void testPrepareNamedQuery_UpdateRejected() {
        final TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery("UPDATE t SET x = :x"));
    }

    @Test
    public void testPrepareNamedQuery_WithGenerateKeys_AllowedAndRejected() throws SQLException {
        final TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final NamedQuery nq = Mockito.mock(NamedQuery.class);

        when(dao.dataSource()).thenReturn(dataSource);

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery("UPDATE t SET x = :x", true));

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(dataSource, "INSERT INTO t VALUES (:v)", true)).thenReturn(nq);

            assertSame(nq, dao.prepareNamedQuery("INSERT INTO t VALUES (:v)", true));
        }
    }

    @Test
    public void testPrepareNamedQuery_WithReturnColumnIndexes_AllowedAndRejected() throws SQLException {
        final TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final NamedQuery nq = Mockito.mock(NamedQuery.class);
        final int[] indexes = { 1 };

        when(dao.dataSource()).thenReturn(dataSource);

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery("DELETE FROM t WHERE id = :id", indexes));

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(dataSource, "INSERT INTO t VALUES (:v)", indexes)).thenReturn(nq);

            assertSame(nq, dao.prepareNamedQuery("INSERT INTO t VALUES (:v)", indexes));
        }
    }

    @Test
    public void testPrepareNamedQuery_WithReturnColumnNames_AllowedAndRejected() throws SQLException {
        final TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final NamedQuery nq = Mockito.mock(NamedQuery.class);
        final String[] cols = { "id" };

        when(dao.dataSource()).thenReturn(dataSource);

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery("DELETE FROM t WHERE id = :id", cols));

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(dataSource, "INSERT INTO t VALUES (:v)", cols)).thenReturn(nq);

            assertSame(nq, dao.prepareNamedQuery("INSERT INTO t VALUES (:v)", cols));
        }
    }

    @Test
    public void testPrepareNamedQuery_WithStmtCreator_AlwaysThrows() {
        final TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);
        @SuppressWarnings("unchecked")
        final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator = Mockito.mock(Throwables.BiFunction.class);

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery("INSERT INTO t VALUES (:v)", stmtCreator));
    }

    @Test
    public void testPrepareNamedQueryForLargeResult_AllowedAndRejected() throws SQLException {
        final TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final NamedQuery nq = Mockito.mock(NamedQuery.class);

        when(dao.dataSource()).thenReturn(dataSource);

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQueryForLargeResult("UPDATE t SET x = :x"));

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQueryForLargeResult(dataSource, "SELECT * FROM t")).thenReturn(nq);

            assertSame(nq, dao.prepareNamedQueryForLargeResult("SELECT * FROM t"));
        }
    }

    @Test
    public void testPrepareNamedQuery_ParsedSql_AllowedAndRejected() throws SQLException {
        final TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final ParsedSql insertSql = Mockito.mock(ParsedSql.class);
        final ParsedSql updateSql = Mockito.mock(ParsedSql.class);
        final NamedQuery nq = Mockito.mock(NamedQuery.class);

        when(dao.dataSource()).thenReturn(dataSource);
        when(insertSql.originalSql()).thenReturn("INSERT INTO t VALUES (:v)");
        when(updateSql.originalSql()).thenReturn("UPDATE t SET x = :x");

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery(updateSql));

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(dataSource, insertSql)).thenReturn(nq);

            assertSame(nq, dao.prepareNamedQuery(insertSql));
        }
    }

    @Test
    public void testPrepareNamedQuery_ParsedSqlWithStmtCreator_AlwaysThrows() {
        final TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);
        final ParsedSql parsedSql = Mockito.mock(ParsedSql.class);
        @SuppressWarnings("unchecked")
        final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator = Mockito.mock(Throwables.BiFunction.class);

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery(parsedSql, stmtCreator));
    }

    @Test
    public void testPrepareCallableQuery_WithStmtCreator_AlwaysThrows() {
        final TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);
        @SuppressWarnings("unchecked")
        final Throwables.BiFunction<Connection, String, CallableStatement, SQLException> stmtCreator = Mockito.mock(Throwables.BiFunction.class);

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareCallableQuery("{call p()}", stmtCreator));
    }

    @Test
    public void testUpdate_MapAndEntityOverloads_AlwaysThrow() {
        final TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition cond = Mockito.mock(Condition.class);
        final TestEntity entity = new TestEntity();

        assertThrows(UnsupportedOperationException.class, () -> dao.update(Map.of("name", "x"), cond));
        assertThrows(UnsupportedOperationException.class, () -> dao.update(entity, cond));
        assertThrows(UnsupportedOperationException.class, () -> dao.update(entity, List.of("name"), cond));
    }

    @Test
    public void testDelete_AlwaysThrows() {
        final TestNoUpdateDao dao = Mockito.mock(TestNoUpdateDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition cond = Mockito.mock(Condition.class);

        assertThrows(UnsupportedOperationException.class, () -> dao.delete(cond));
    }
}
