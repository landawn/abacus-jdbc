package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * {@code ReadOnlyDao} restricts a DAO to SELECT statements only. The SQL-kind gate for
 * {@code prepareQuery}/{@code prepareNamedQuery} is enforced centrally by the {@code DaoImpl} proxy
 * (no longer by per-method overrides on the interface), so these tests drive a real {@code createDao}
 * proxy rather than a Mockito mock.
 */
public class ReadOnlyDaoTest extends TestBase {

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

    interface TestReadOnlyDao extends ReadOnlyDao<TestEntity, TestReadOnlyDao> {
    }

    private TestReadOnlyDao createDao() throws SQLException {
        final DataSource ds = mock(DataSource.class);
        final Connection conn = mock(Connection.class);
        final DatabaseMetaData meta = mock(DatabaseMetaData.class);
        final PreparedStatement stmt = mock(PreparedStatement.class);

        when(ds.getConnection()).thenReturn(conn);
        when(conn.getMetaData()).thenReturn(meta);
        when(meta.getDatabaseProductName()).thenReturn("MySQL");
        when(meta.getDatabaseProductVersion()).thenReturn("8.0");
        when(conn.prepareStatement(anyString())).thenReturn(stmt);
        when(conn.prepareStatement(anyString(), anyInt(), anyInt())).thenReturn(stmt);

        return JdbcUtil.createDao(TestReadOnlyDao.class, ds);
    }

    @Test
    public void testIsInterfaceAndCapabilities() {
        assertTrue(ReadOnlyDao.class.isInterface());
        assertEquals(2, ReadOnlyDao.class.getTypeParameters().length);
        // read capability + Cacheable only; not insert/update/delete, and no longer a NoUpdateDao.
        assertTrue(ReadOps.class.isAssignableFrom(ReadOnlyDao.class));
        assertTrue(Cacheable.class.isAssignableFrom(ReadOnlyDao.class));
        assertFalse(InsertOps.class.isAssignableFrom(ReadOnlyDao.class));
        assertFalse(UpdateOps.class.isAssignableFrom(ReadOnlyDao.class));
        assertFalse(DeleteOps.class.isAssignableFrom(ReadOnlyDao.class));
        assertFalse(NoUpdateDao.class.isAssignableFrom(ReadOnlyDao.class));
    }

    @Test
    public void testPrepareQuery_SelectOnly() throws SQLException {
        final TestReadOnlyDao dao = createDao();

        assertNotNull(dao.prepareQuery("SELECT * FROM test"));
        assertNotNull(dao.prepareQueryForLargeResult("SELECT * FROM test"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("INSERT INTO test(id) VALUES (?)"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("UPDATE test SET name = 'x'"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("DELETE FROM test"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("SELECT * INTO test_copy FROM test"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQueryForLargeResult("DELETE FROM test"));
        // mutating CTE is not read-only even though it starts with WITH ... SELECT.
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("WITH d AS (DELETE FROM test WHERE id = ? RETURNING *) SELECT * FROM d"));
    }

    @Test
    public void testPrepareNamedQuery_SelectOnly() throws SQLException {
        final TestReadOnlyDao dao = createDao();

        assertNotNull(dao.prepareNamedQuery("SELECT * FROM test WHERE id = :id"));
        assertNotNull(dao.prepareNamedQuery(ParsedSql.parse("SELECT * FROM test WHERE id = :id")));
        assertNotNull(dao.prepareNamedQueryForLargeResult("SELECT * FROM test WHERE id = :id"));
        assertNotNull(dao.prepareNamedQueryForLargeResult(ParsedSql.parse("SELECT * FROM test WHERE id = :id")));

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery("INSERT INTO test(name) VALUES (:name)"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery(ParsedSql.parse("UPDATE test SET name = :name")));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQueryForLargeResult("DELETE FROM test WHERE id = :id"));
    }

    @Test
    public void testPrepareNamedQuery_NullParsedSql_ThrowsIllegalArgument() throws SQLException {
        final TestReadOnlyDao dao = createDao();

        assertThrows(IllegalArgumentException.class, () -> dao.prepareNamedQuery((ParsedSql) null));
        assertThrows(IllegalArgumentException.class, () -> dao.prepareNamedQueryForLargeResult((ParsedSql) null));
    }
}
