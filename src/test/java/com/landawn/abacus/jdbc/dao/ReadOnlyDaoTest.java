package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.NamedQuery;
import com.landawn.abacus.jdbc.PreparedQuery;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.query.SqlBuilder.PSC;

public class ReadOnlyDaoTest extends TestBase {

    interface TestReadOnlyDao extends ReadOnlyDao<TestEntity, PSC, TestReadOnlyDao> {
    }

    static final class TestEntity {
    }

    @Test
    public void testIsInterface() {
        assertTrue(ReadOnlyDao.class.isInterface());
    }

    @Test
    public void testExtendsNoUpdateDao() {
        assertTrue(NoUpdateDao.class.isAssignableFrom(ReadOnlyDao.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(3, ReadOnlyDao.class.getTypeParameters().length);
    }

    @Test
    public void testHasDeclaredMethods() {
        assertTrue(ReadOnlyDao.class.getDeclaredMethods().length > 0);
    }

    @Test
    public void testPrepareQuery_SelectOnly() throws SQLException {
        TestReadOnlyDao dao = Mockito.mock(TestReadOnlyDao.class, Mockito.CALLS_REAL_METHODS);
        DataSource dataSource = Mockito.mock(DataSource.class);
        PreparedQuery preparedQuery = Mockito.mock(PreparedQuery.class);
        PreparedQuery largeResultQuery = Mockito.mock(PreparedQuery.class);

        Mockito.when(dao.dataSource()).thenReturn(dataSource);

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareQuery(dataSource, "SELECT * FROM users")).thenReturn(preparedQuery);
            jdbcUtil.when(() -> JdbcUtil.prepareQueryForLargeResult(dataSource, "SELECT * FROM users")).thenReturn(largeResultQuery);

            assertSame(preparedQuery, dao.prepareQuery("SELECT * FROM users"));
            assertSame(largeResultQuery, dao.prepareQueryForLargeResult("SELECT * FROM users"));
        }

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("UPDATE users SET name = 'x'"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQueryForLargeResult("DELETE FROM users"));
    }

    @Test
    public void testPrepareNamedQuery_SelectOnly() throws SQLException {
        TestReadOnlyDao dao = Mockito.mock(TestReadOnlyDao.class, Mockito.CALLS_REAL_METHODS);
        DataSource dataSource = Mockito.mock(DataSource.class);
        NamedQuery namedQuery = Mockito.mock(NamedQuery.class);
        NamedQuery parsedNamedQuery = Mockito.mock(NamedQuery.class);
        NamedQuery largeResultQuery = Mockito.mock(NamedQuery.class);
        NamedQuery parsedLargeResultQuery = Mockito.mock(NamedQuery.class);
        ParsedSql parsedSelect = Mockito.mock(ParsedSql.class);
        ParsedSql parsedUpdate = Mockito.mock(ParsedSql.class);

        Mockito.when(dao.dataSource()).thenReturn(dataSource);
        Mockito.when(parsedSelect.originalSql()).thenReturn("SELECT * FROM users WHERE id = :id");
        Mockito.when(parsedUpdate.originalSql()).thenReturn("UPDATE users SET name = :name");

        try (MockedStatic<JdbcUtil> jdbcUtil = Mockito.mockStatic(JdbcUtil.class)) {
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(dataSource, "SELECT * FROM users WHERE id = :id")).thenReturn(namedQuery);
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQuery(dataSource, parsedSelect)).thenReturn(parsedNamedQuery);
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQueryForLargeResult(dataSource, "SELECT * FROM users WHERE id = :id")).thenReturn(largeResultQuery);
            jdbcUtil.when(() -> JdbcUtil.prepareNamedQueryForLargeResult(dataSource, parsedSelect)).thenReturn(parsedLargeResultQuery);

            assertSame(namedQuery, dao.prepareNamedQuery("SELECT * FROM users WHERE id = :id"));
            assertSame(parsedNamedQuery, dao.prepareNamedQuery(parsedSelect));
            assertSame(largeResultQuery, dao.prepareNamedQueryForLargeResult("SELECT * FROM users WHERE id = :id"));
            assertSame(parsedLargeResultQuery, dao.prepareNamedQueryForLargeResult(parsedSelect));
        }

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery("INSERT INTO users(name) VALUES(:name)"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery(parsedUpdate));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQueryForLargeResult("DELETE FROM users WHERE id = :id"));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQueryForLargeResult(parsedUpdate));
    }

    @Test
    public void testPrepareQuery_KeyGenerationUnsupported() {
        TestReadOnlyDao dao = Mockito.mock(TestReadOnlyDao.class, Mockito.CALLS_REAL_METHODS);

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("SELECT 1", true));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("SELECT 1", new int[] { 1 }));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareQuery("SELECT 1", new String[] { "id" }));
    }

    @Test
    public void testPrepareNamedQuery_KeyGenerationUnsupported() {
        TestReadOnlyDao dao = Mockito.mock(TestReadOnlyDao.class, Mockito.CALLS_REAL_METHODS);
        ParsedSql parsedSelect = Mockito.mock(ParsedSql.class);
        Mockito.when(parsedSelect.originalSql()).thenReturn("SELECT * FROM users WHERE id = :id");

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery("SELECT * FROM users WHERE id = :id", true));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery("SELECT * FROM users WHERE id = :id", new int[] { 1 }));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery("SELECT * FROM users WHERE id = :id", new String[] { "id" }));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery(parsedSelect, true));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery(parsedSelect, new int[] { 1 }));
        assertThrows(UnsupportedOperationException.class, () -> dao.prepareNamedQuery(parsedSelect, new String[] { "id" }));
    }

    @Test
    public void testSave_UnsupportedOperations() {
        TestReadOnlyDao dao = Mockito.mock(TestReadOnlyDao.class, Mockito.CALLS_REAL_METHODS);

        assertThrows(UnsupportedOperationException.class, () -> dao.save(new TestEntity()));
        assertThrows(UnsupportedOperationException.class, () -> dao.save(new TestEntity(), List.of("name")));
        assertThrows(UnsupportedOperationException.class, () -> dao.save("insertUser", new TestEntity()));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchSave(List.of(new TestEntity())));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchSave(List.of(new TestEntity()), 2));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchSave(List.of(new TestEntity()), List.of("name")));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchSave(List.of(new TestEntity()), List.of("name"), 2));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchSave("insertUser", List.of(new TestEntity())));
        assertThrows(UnsupportedOperationException.class, () -> dao.batchSave("insertUser", List.of(new TestEntity()), 2));
    }

    @Test
    public void testPrepareCallableQuery_UnsupportedOperation() {
        TestReadOnlyDao dao = Mockito.mock(TestReadOnlyDao.class, Mockito.CALLS_REAL_METHODS);

        assertThrows(UnsupportedOperationException.class, () -> dao.prepareCallableQuery("{call read_only_proc()}"));
    }
}
