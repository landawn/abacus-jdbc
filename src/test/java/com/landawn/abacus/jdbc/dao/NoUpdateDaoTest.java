package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.query.SqlBuilder.PSC;

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
}
