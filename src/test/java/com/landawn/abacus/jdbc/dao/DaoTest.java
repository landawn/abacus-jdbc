package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Executor;

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
}
