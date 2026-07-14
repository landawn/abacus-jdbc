package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.query.ParsedSql;

/**
 * {@code NonUpdateDao} restricts a DAO to SELECT (read) and INSERT statements. The SQL-kind gate for
 * {@code prepareQuery}/{@code prepareNamedQuery} is enforced centrally by the {@code DaoImpl} proxy
 * (no longer by per-method overrides on the interface), so these tests drive a real {@code createDao}
 * proxy rather than a Mockito mock.
 */
public class NonUpdateDaoTest extends TestBase {

    public static final class TestEntity {
        private long id;
        private String name;

        public long getId() {
            return id;
        }

        public void setId(final long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }

    interface TestNonUpdateDao extends NonUpdateDao<TestEntity, TestNonUpdateDao> {
    }

    private TestNonUpdateDao createDao() throws SQLException {
        final DataSource ds = mock(DataSource.class);
        final Connection conn = mock(Connection.class);
        final DatabaseMetaData meta = mock(DatabaseMetaData.class);
        final PreparedStatement stmt = mock(PreparedStatement.class);

        when(ds.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn("MySQL");
        when(meta.getDatabaseProductVersion()).thenReturn("8.0");
        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(conn.prepareStatement(anyString(), anyInt())).thenReturn(stmt);
        when(conn.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(stmt);
        when(conn.prepareStatement(anyString(), any(int[].class))).thenReturn(stmt);
        when(conn.prepareStatement(anyString(), any(String[].class))).thenReturn(stmt);

        return JdbcUtil.createDao(TestNonUpdateDao.class, ds);
    }

    @Test
    public void testIsInterfaceAndCapabilities() {
        assertTrue(NonUpdateDao.class.isInterface());
        assertEquals(2, NonUpdateDao.class.getTypeParameters().length);
        // read + insert capabilities, but not update/delete.
        assertTrue(InsertOps.class.isAssignableFrom(NonUpdateDao.class));
        assertTrue(ReadOps.class.isAssignableFrom(NonUpdateDao.class));
        assertTrue(DaoUtil.isCacheable(NonUpdateDao.class));
        assertFalse(UpdateOps.class.isAssignableFrom(NonUpdateDao.class));
        assertFalse(DeleteOps.class.isAssignableFrom(NonUpdateDao.class));
    }

    @Test
    public void testPrepareQuery_SelectAndInsertAllowed_UpdateDeleteRejected() throws SQLException {
        final TestNonUpdateDao dao = createDao();

        assertNotNull(dao.prepareQuery("SELECT * FROM test"));
        assertNotNull(dao.prepareQuery("INSERT INTO test(id) VALUES (?)"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("UPDATE test SET name = 'x'"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("DELETE FROM test"));
        // data-changing CTE and SELECT ... INTO are not plain reads/inserts.
        assertThrows(UnsupportedOperationException.class,
                () -> dao.prepareQuery("WITH c AS (UPDATE test SET name = 'x' RETURNING id) INSERT INTO log(id) SELECT id FROM c"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("SELECT * INTO test_copy FROM test"));
    }

    @Test
    public void testPrepareQueryForLargeResult_SelectAllowed_DeleteRejected() throws SQLException {
        final TestNonUpdateDao dao = createDao();

        // The generated-keys prepareQuery overloads (boolean / int[] / String[]) now live on the full
        // Dao only, so they are absent from a NonUpdateDao (calling them would be a compile error).
        assertNotNull(dao.prepareQueryForLargeResult("SELECT * FROM test"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQueryForLargeResult("DELETE FROM test"));
    }

    @Test
    public void testPrepareNamedQuery_StringAndParsedSql() throws SQLException {
        final TestNonUpdateDao dao = createDao();

        assertNotNull(dao.prepareNamedQuery("INSERT INTO test(id) VALUES (:id)"));
        assertNotNull(dao.prepareNamedQuery(ParsedSql.parse("SELECT * FROM test WHERE id = :id")));
        assertNotNull(dao.prepareNamedQueryForLargeResult("SELECT * FROM test"));

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery("UPDATE test SET name = :name"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery(ParsedSql.parse("DELETE FROM test WHERE id = :id")));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQueryForLargeResult("UPDATE test SET name = :name"));
    }

    @Test
    public void testPrepareNamedQuery_NullParsedSql_ThrowsIllegalArgument() throws SQLException {
        final TestNonUpdateDao dao = createDao();

        assertThrows(IllegalArgumentException.class, () -> dao.prepareNamedQuery((ParsedSql) null));
        assertThrows(IllegalArgumentException.class, () -> dao.prepareNamedQueryForLargeResult((ParsedSql) null));
    }
}
