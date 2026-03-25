package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.CallableQuery;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.SqlBuilder.PSC;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.stream.Stream;
import com.landawn.abacus.util.u.Optional;

public class DaoTest extends TestBase {

    interface TestDao extends Dao<TestEntity, PSC, TestDao> {
    }

    static final class TestEntity {
        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }

    @Test
    public void testPrepareQuery() throws SQLException {
        TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
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
    public void testPrepareNamedQuery() throws SQLException {
        TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        PreparedStatement stmt = Mockito.mock(PreparedStatement.class);
        DatabaseMetaData metaData = Mockito.mock(DatabaseMetaData.class);

        when(dao.dataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(metaData.getDatabaseProductVersion()).thenReturn("8.0");
        when(connection.prepareStatement("SELECT * FROM demo WHERE id = ?")).thenReturn(stmt);

        NamedQuery query = dao.prepareNamedQuery("SELECT * FROM demo WHERE id = :id");

        assertNotNull(query);
        verify(connection).prepareStatement("SELECT * FROM demo WHERE id = ?");
    }

    @Test
    public void testPrepareCallableQuery() throws SQLException {
        TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        DataSource dataSource = Mockito.mock(DataSource.class);
        Connection connection = Mockito.mock(Connection.class);
        CallableStatement stmt = Mockito.mock(CallableStatement.class);

        when(dao.dataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareCall(anyString())).thenReturn(stmt);

        CallableQuery query = dao.prepareCallableQuery("{call demo_proc(?)}");

        assertNotNull(query);
        verify(connection).prepareCall("{call demo_proc(?)}");
    }

    @Test
    public void testAsyncCall_UsesCurrentDao() throws Exception {
        TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        Executor executor = Runnable::run;

        when(dao.executor()).thenReturn(executor);

        TestDao result = dao.asyncCall(it -> it).getNow(null);

        assertSame(dao, result);
    }

    @Test
    public void testPrepareQuery_KeyOverloads() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final PreparedQuery generatedKeysQuery = Mockito.mock(PreparedQuery.class);
        final PreparedQuery indexedKeysQuery = Mockito.mock(PreparedQuery.class);
        final PreparedQuery namedKeysQuery = Mockito.mock(PreparedQuery.class);

        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareQuery(dataSource, "INSERT INTO demo(name) VALUES (?)", true)).thenReturn(generatedKeysQuery);
            jdbcUtil.when(() -> JdbcUtil.prepareQuery(dataSource, "INSERT INTO demo(name) VALUES (?)", new int[] { 1 })).thenReturn(indexedKeysQuery);
            jdbcUtil.when(() -> JdbcUtil.prepareQuery(dataSource, "INSERT INTO demo(name) VALUES (?)", new String[] { "id" })).thenReturn(namedKeysQuery);

            assertSame(generatedKeysQuery, dao.prepareQuery("INSERT INTO demo(name) VALUES (?)", true));
            assertSame(indexedKeysQuery, dao.prepareQuery("INSERT INTO demo(name) VALUES (?)", new int[] { 1 }));
            assertSame(namedKeysQuery, dao.prepareQuery("INSERT INTO demo(name) VALUES (?)", new String[] { "id" }));
        }
    }

    @Test
    public void testPrepareNamedQuery_ParsedSqlKeyOverloads() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final ParsedSql parsedSql = Mockito.mock(ParsedSql.class);
        final NamedQuery generatedKeysQuery = Mockito.mock(NamedQuery.class);
        final NamedQuery indexedKeysQuery = Mockito.mock(NamedQuery.class);
        final NamedQuery namedKeysQuery = Mockito.mock(NamedQuery.class);

        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(dataSource, parsedSql, true)).thenReturn(generatedKeysQuery);
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(dataSource, parsedSql, new int[] { 1 })).thenReturn(indexedKeysQuery);
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(dataSource, parsedSql, new String[] { "id" })).thenReturn(namedKeysQuery);

            assertSame(generatedKeysQuery, dao.prepareNamedQuery(parsedSql, true));
            assertSame(indexedKeysQuery, dao.prepareNamedQuery(parsedSql, new int[] { 1 }));
            assertSame(namedKeysQuery, dao.prepareNamedQuery(parsedSql, new String[] { "id" }));
        }
    }

    @Test
    public void testBatchSave_DefaultOverloads() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final List<TestEntity> entities = List.of(new TestEntity());

        Mockito.doNothing().when(dao).batchSave(entities, JdbcUtil.DEFAULT_BATCH_SIZE);
        Mockito.doNothing().when(dao).batchSave(entities, List.of("name"), JdbcUtil.DEFAULT_BATCH_SIZE);
        Mockito.doNothing().when(dao).batchSave("insertUser", entities, JdbcUtil.DEFAULT_BATCH_SIZE);

        assertDoesNotThrow(() -> dao.batchSave(entities));
        assertDoesNotThrow(() -> dao.batchSave(entities, List.of("name")));
        assertDoesNotThrow(() -> dao.batchSave("insertUser", entities));
    }

    @Test
    public void testUpsert_UpdatePath_IgnoresIdProperty() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition condition = Mockito.mock(Condition.class);
        final TestEntity entity = new TestEntity();
        entity.setId(99L);
        entity.setName("updated");
        final TestEntity dbEntity = new TestEntity();
        dbEntity.setId(1L);
        dbEntity.setName("original");

        when(dao.findOnlyOne(condition)).thenReturn(Optional.of(dbEntity));
        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        when(dao.update(Mockito.same(dbEntity), Mockito.anyCollection(), Mockito.same(condition))).thenReturn(1);

        final TestEntity result = dao.upsert(entity, condition);

        assertSame(dbEntity, result);
        assertEquals(1L, dbEntity.getId());
        assertEquals("updated", dbEntity.getName());
    }

    @Test
    public void testAsyncRun_WithExplicitExecutor() {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final Executor executor = Runnable::run;
        final Object[] observed = new Object[1];

        assertDoesNotThrow(() -> dao.asyncRun(it -> observed[0] = it, executor).get());
        assertSame(dao, observed[0]);
    }

    @Test
    public void testPrepareQuery_WithStmtCreator() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final PreparedQuery pq = Mockito.mock(PreparedQuery.class);
        @SuppressWarnings("unchecked")
        final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator = Mockito.mock(Throwables.BiFunction.class);

        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareQuery(dataSource, "SELECT 1", stmtCreator)).thenReturn(pq);

            assertSame(pq, dao.prepareQuery("SELECT 1", stmtCreator));
        }
    }

    @Test
    public void testPrepareQuery_ConditionDelegatesToSelectPropNamesCondition() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition cond = Mockito.mock(Condition.class);
        final PreparedQuery pq = Mockito.mock(PreparedQuery.class);

        Mockito.doReturn(pq).when(dao).prepareQuery((Collection<String>) null, cond);

        assertSame(pq, dao.prepareQuery(cond));
        verify(dao).prepareQuery((Collection<String>) null, cond);
    }

    @Test
    public void testPrepareQueryForLargeResult_SqlString() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final PreparedQuery pq = Mockito.mock(PreparedQuery.class);

        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareQueryForLargeResult(dataSource, "SELECT * FROM demo")).thenReturn(pq);

            assertSame(pq, dao.prepareQueryForLargeResult("SELECT * FROM demo"));
        }
    }

    @Test
    public void testPrepareQueryForLargeResult_ConditionDelegatesToNullProps() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition cond = Mockito.mock(Condition.class);
        final PreparedQuery pq = Mockito.mock(PreparedQuery.class);

        Mockito.doReturn(pq).when(dao).prepareQueryForLargeResult((Collection<String>) null, cond);

        assertSame(pq, dao.prepareQueryForLargeResult(cond));
        verify(dao).prepareQueryForLargeResult((Collection<String>) null, cond);
    }

    @Test
    public void testPrepareQueryForLargeResult_WithPropsConfigsStmt() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition cond = Mockito.mock(Condition.class);
        final PreparedQuery pq = Mockito.mock(PreparedQuery.class);
        final List<String> props = List.of("id", "name");

        Mockito.doReturn(pq).when(dao).prepareQuery(props, cond);
        when(pq.configStmt(Mockito.<Throwables.Consumer<? super PreparedStatement, ? extends SQLException>> any())).thenReturn(pq);

        assertSame(pq, dao.prepareQueryForLargeResult(props, cond));
        verify(pq).configStmt((Throwables.Consumer<? super PreparedStatement, ? extends SQLException>) DaoUtil.stmtSetterForBigQueryResult);
    }

    @Test
    public void testPrepareNamedQuery_WithGenerateKeys() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final NamedQuery nq = Mockito.mock(NamedQuery.class);

        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(dataSource, "INSERT INTO t VALUES (:v)", true)).thenReturn(nq);

            assertSame(nq, dao.prepareNamedQuery("INSERT INTO t VALUES (:v)", true));
        }
    }

    @Test
    public void testPrepareNamedQuery_WithReturnColumnIndexes() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final NamedQuery nq = Mockito.mock(NamedQuery.class);
        final int[] indexes = { 1 };

        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(dataSource, "INSERT INTO t VALUES (:v)", indexes)).thenReturn(nq);

            assertSame(nq, dao.prepareNamedQuery("INSERT INTO t VALUES (:v)", indexes));
        }
    }

    @Test
    public void testPrepareNamedQuery_WithReturnColumnNames() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final NamedQuery nq = Mockito.mock(NamedQuery.class);
        final String[] cols = { "id" };

        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(dataSource, "INSERT INTO t VALUES (:v)", cols)).thenReturn(nq);

            assertSame(nq, dao.prepareNamedQuery("INSERT INTO t VALUES (:v)", cols));
        }
    }

    @Test
    public void testPrepareNamedQuery_WithStmtCreator() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final NamedQuery nq = Mockito.mock(NamedQuery.class);
        @SuppressWarnings("unchecked")
        final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator = Mockito.mock(Throwables.BiFunction.class);

        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(dataSource, "SELECT * FROM t WHERE id = :id", stmtCreator)).thenReturn(nq);

            assertSame(nq, dao.prepareNamedQuery("SELECT * FROM t WHERE id = :id", stmtCreator));
        }
    }

    @Test
    public void testPrepareNamedQuery_FromParsedSql() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final ParsedSql parsedSql = Mockito.mock(ParsedSql.class);
        final NamedQuery nq = Mockito.mock(NamedQuery.class);

        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(dataSource, parsedSql)).thenReturn(nq);

            assertSame(nq, dao.prepareNamedQuery(parsedSql));
        }
    }

    @Test
    public void testPrepareNamedQuery_FromParsedSqlWithStmtCreator() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final ParsedSql parsedSql = Mockito.mock(ParsedSql.class);
        final NamedQuery nq = Mockito.mock(NamedQuery.class);
        @SuppressWarnings("unchecked")
        final Throwables.BiFunction<Connection, String, PreparedStatement, SQLException> stmtCreator = Mockito.mock(Throwables.BiFunction.class);

        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(dataSource, parsedSql, stmtCreator)).thenReturn(nq);

            assertSame(nq, dao.prepareNamedQuery(parsedSql, stmtCreator));
        }
    }

    @Test
    public void testPrepareNamedQuery_ConditionDelegatesToNullProps() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition cond = Mockito.mock(Condition.class);
        final NamedQuery nq = Mockito.mock(NamedQuery.class);

        Mockito.doReturn(nq).when(dao).prepareNamedQuery((Collection<String>) null, cond);

        assertSame(nq, dao.prepareNamedQuery(cond));
        verify(dao).prepareNamedQuery((Collection<String>) null, cond);
    }

    @Test
    public void testPrepareNamedQueryForLargeResult_SqlString() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final NamedQuery nq = Mockito.mock(NamedQuery.class);

        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQueryForLargeResult(dataSource, "SELECT * FROM t")).thenReturn(nq);

            assertSame(nq, dao.prepareNamedQueryForLargeResult("SELECT * FROM t"));
        }
    }

    @Test
    public void testPrepareNamedQueryForLargeResult_ParsedSql() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final DataSource dataSource = Mockito.mock(DataSource.class);
        final ParsedSql parsedSql = Mockito.mock(ParsedSql.class);
        final NamedQuery nq = Mockito.mock(NamedQuery.class);

        when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQueryForLargeResult(dataSource, parsedSql)).thenReturn(nq);

            assertSame(nq, dao.prepareNamedQueryForLargeResult(parsedSql));
        }
    }

    @Test
    public void testPrepareNamedQueryForLargeResult_ConditionDelegatesToNullProps() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition cond = Mockito.mock(Condition.class);
        final NamedQuery nq = Mockito.mock(NamedQuery.class);

        Mockito.doReturn(nq).when(dao).prepareNamedQueryForLargeResult((Collection<String>) null, cond);

        assertSame(nq, dao.prepareNamedQueryForLargeResult(cond));
        verify(dao).prepareNamedQueryForLargeResult((Collection<String>) null, cond);
    }

    @Test
    public void testPrepareNamedQueryForLargeResult_WithPropsConfigsStmt() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition cond = Mockito.mock(Condition.class);
        final NamedQuery nq = Mockito.mock(NamedQuery.class);
        final List<String> props = List.of("id");

        Mockito.doReturn(nq).when(dao).prepareNamedQuery(props, cond);
        when(nq.configStmt(Mockito.<Throwables.Consumer<? super PreparedStatement, ? extends SQLException>> any())).thenReturn(nq);

        assertSame(nq, dao.prepareNamedQueryForLargeResult(props, cond));
        verify(nq).configStmt((Throwables.Consumer<? super PreparedStatement, ? extends SQLException>) DaoUtil.stmtSetterForBigQueryResult);
    }

    @Test
    public void testUpdate_SinglePropDelegatesToUpdateMap() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition cond = Mockito.mock(Condition.class);

        when(dao.update(Mockito.anyMap(), Mockito.same(cond))).thenReturn(5);

        assertEquals(5, dao.update("name", "Alice", cond));
        verify(dao).update(Mockito.anyMap(), Mockito.same(cond));
    }

    @Test
    public void testUpsert_WithUniquePropNames() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final TestEntity entity = new TestEntity();
        entity.setName("Bob");

        Mockito.doReturn(entity).when(dao).upsert(Mockito.same(entity), Mockito.any(Condition.class));

        final TestEntity result = dao.upsert(entity, List.of("name"));

        assertSame(entity, result);
        verify(dao).upsert(Mockito.same(entity), Mockito.any(Condition.class));
    }

    @Test
    public void testAsyncRun_UsesDefaultExecutor() throws Exception {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);

        when(dao.executor()).thenReturn(Runnable::run);

        final boolean[] ran = { false };
        dao.asyncRun(d -> ran[0] = true).get();

        assertTrue(ran[0]);
    }

    @Test
    public void testList_SinglePropNameDelegatesToCollection() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition cond = Mockito.mock(Condition.class);
        final List<String> expected = List.of("Alice");

        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        Mockito.doReturn(expected).when(dao).list(Mockito.anyList(), Mockito.same(cond), Mockito.<Jdbc.RowMapper<? extends Object>> any());

        final List<String> result = dao.list("name", cond);

        assertSame(expected, result);
    }

    @Test
    public void testList_SinglePropWithRowMapper() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition cond = Mockito.mock(Condition.class);
        final Jdbc.RowMapper<String> rowMapper = rs -> rs.getString(1);
        final List<String> expected = List.of("X");

        when(dao.list(Mockito.anyList(), Mockito.same(cond), Mockito.same(rowMapper))).thenReturn(expected);

        assertSame(expected, dao.list("name", cond, rowMapper));
    }

    @Test
    public void testList_SinglePropWithRowFilterAndMapper() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition cond = Mockito.mock(Condition.class);
        final Jdbc.RowFilter rowFilter = rs -> true;
        final Jdbc.RowMapper<String> rowMapper = rs -> rs.getString(1);
        final List<String> expected = List.of("Y");

        when(dao.list(Mockito.anyList(), Mockito.same(cond), Mockito.same(rowFilter), Mockito.same(rowMapper))).thenReturn(expected);

        assertSame(expected, dao.list("name", cond, rowFilter, rowMapper));
    }

    @Test
    public void testStream_SinglePropName_DelegatesToCollectionWithRowMapper() {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition cond = Mockito.mock(Condition.class);
        @SuppressWarnings("unchecked")
        final Stream<String> expected = Mockito.mock(Stream.class);

        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        Mockito.doReturn(expected).when(dao).stream(Mockito.anyList(), Mockito.same(cond), Mockito.<Jdbc.RowMapper<? extends Object>> any());

        assertSame(expected, dao.stream("name", cond));
    }

    @Test
    public void testStream_SinglePropWithRowMapper_DelegatesToCollection() {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition cond = Mockito.mock(Condition.class);
        final Jdbc.RowMapper<String> rowMapper = rs -> rs.getString(1);
        @SuppressWarnings("unchecked")
        final Stream<String> expected = Mockito.mock(Stream.class);

        Mockito.doReturn(expected).when(dao).stream(Mockito.anyList(), Mockito.same(cond), Mockito.same(rowMapper));

        assertSame(expected, dao.stream("name", cond, rowMapper));
    }

    @Test
    public void testStream_SinglePropWithRowFilterAndMapper_DelegatesToCollection() {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition cond = Mockito.mock(Condition.class);
        final Jdbc.RowFilter rowFilter = rs -> true;
        final Jdbc.RowMapper<String> rowMapper = rs -> rs.getString(1);
        @SuppressWarnings("unchecked")
        final Stream<String> expected = Mockito.mock(Stream.class);

        Mockito.doReturn(expected).when(dao).stream(Mockito.anyList(), Mockito.same(cond), Mockito.same(rowFilter), Mockito.same(rowMapper));

        assertSame(expected, dao.stream("name", cond, rowFilter, rowMapper));
    }

    @Test
    public void testForeach_WithSelectPropsAndRowConsumer_DelegatesToForEach() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition cond = Mockito.mock(Condition.class);
        final List<String> props = List.of("id", "name");
        final java.util.function.Consumer<DisposableObjArray> rowConsumer = row -> {
        };

        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        Mockito.doNothing().when(dao).forEach(Mockito.same(props), Mockito.same(cond), Mockito.any(Jdbc.RowConsumer.class));

        dao.foreach(props, cond, rowConsumer);

        verify(dao).forEach(Mockito.same(props), Mockito.same(cond), Mockito.any(Jdbc.RowConsumer.class));
    }

    @Test
    public void testForeach_WithCondAndRowConsumer_DelegatesToForEach() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final Condition cond = Mockito.mock(Condition.class);
        final java.util.function.Consumer<DisposableObjArray> rowConsumer = row -> {
        };

        when(dao.targetEntityClass()).thenReturn(TestEntity.class);
        Mockito.doNothing().when(dao).forEach(Mockito.same(cond), Mockito.any(Jdbc.RowConsumer.class));

        dao.foreach(cond, rowConsumer);

        verify(dao).forEach(Mockito.same(cond), Mockito.any(Jdbc.RowConsumer.class));
    }

    @Test
    public void testUpsert_InsertPath_WhenEntityNotFound() throws SQLException {
        final TestDao dao = Mockito.mock(TestDao.class, Mockito.CALLS_REAL_METHODS);
        final TestEntity entity = new TestEntity();
        entity.setName("new");

        when(dao.findOnlyOne(Mockito.any())).thenReturn(Optional.empty());

        final TestEntity result = dao.upsert(entity, Mockito.mock(Condition.class));

        assertSame(entity, result);
        verify(dao).save(entity);
    }

}
