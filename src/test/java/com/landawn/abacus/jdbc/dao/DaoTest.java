package com.landawn.abacus.jdbc.dao;

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
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.CallableQuery;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.query.SqlBuilder.PSC;

public class DaoTest extends TestBase {

    interface TestDao extends Dao<TestEntity, PSC, TestDao> {
    }

    static final class TestEntity {
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
}
