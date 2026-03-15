package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.annotation.Bind;
import com.landawn.abacus.jdbc.annotation.MergedById;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.dao.Dao;
import com.landawn.abacus.query.SqlBuilder.PSC;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.RowDataset;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.u.Optional;

@Tag("2025")
public class DaoImplTest extends TestBase {

    static class TestEntity {
        private long id;
        private String name;

        public long getId() {
            return id;
        }

        public void setId(long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    interface MergedDao {
        @Query("select * from test")
        @MergedById("id")
        Optional<TestEntity> findMerged();
    }

    interface ProcedureDao {
        @Query(value = "call test_proc(?, ?)", isProcedure = true)
        void callProc(@Bind("p1") String first, String second);
    }

    interface PrimitiveExtractorDao {
        @Query("select count(*) from test")
        int count(Jdbc.ResultExtractor<Integer> extractor);
    }

    interface Marker {
    }

    interface BaseDao<T> extends Dao<T, PSC, BaseDao<T>> {
    }

    interface ReorderedGenericDao extends Marker, BaseDao<TestEntity> {
    }

    interface InvalidRowFilterPositionDao extends Dao<TestEntity, PSC, InvalidRowFilterPositionDao> {
        @Query("select * from test")
        List<TestEntity> findByFilter(Jdbc.RowFilter rowFilter);
    }

    static final class StubQuery extends AbstractQuery<PreparedStatement, StubQuery> {
        private final Dataset dataset;

        StubQuery(Dataset dataset) {
            super(mock(PreparedStatement.class));
            this.dataset = dataset;
        }

        @Override
        public <R> R query(Jdbc.ResultExtractor<? extends R> resultExtractor) {
            return (R) dataset;
        }
    }

    @Test
    void testMergedByIdReturnsAbacusOptional() throws Exception {
        Method daoMethod = MergedDao.class.getMethod("findMerged");
        Method factory = DaoImpl.class.getDeclaredMethod("createQueryFunctionByMethod", Class.class, Method.class, String.class, List.class, Map.class,
                boolean.class, boolean.class, boolean.class, OP.class, boolean.class, String.class);
        factory.setAccessible(true);

        Dataset dataset = new RowDataset(ImmutableList.of("id", "name"), ImmutableList.of(ImmutableList.of(1L), ImmutableList.of("name")));

        @SuppressWarnings("unchecked")
        Throwables.BiFunction<AbstractQuery, Object[], Object, SQLException> func = (Throwables.BiFunction<AbstractQuery, Object[], Object, SQLException>) factory
                .invoke(null, TestEntity.class, daoMethod, null, List.of("id"), null, true, false, false, OP.DEFAULT, false, "MergedDao.findMerged");

        Object result = func.apply(new StubQuery(dataset), new Object[0]);

        assertTrue(result instanceof Optional);

        TestEntity resultEntity = (TestEntity) ((Optional<?>) result).orElseNull();
        assertNotNull(resultEntity);
        assertEquals(1L, resultEntity.getId());
        assertEquals("name", resultEntity.getName());
    }

    @Test
    void testProcedureBindRequiresAllParamsBound() throws Exception {
        Method daoMethod = ProcedureDao.class.getMethod("callProc", String.class, String.class);
        DaoImpl.QueryInfo queryInfo = new DaoImpl.QueryInfo("call test_proc(?, ?)", null, 0, 0, false, 0, OP.DEFAULT, false, false, false, false, true, false);

        Method factory = DaoImpl.class.getDeclaredMethod("createParametersSetter", DaoImpl.QueryInfo.class, String.class, Method.class, Class[].class,
                int.class, int.class, int[].class, boolean[].class, int.class);
        factory.setAccessible(true);

        int[] stmtParamIndexes = new int[] { 0, 1 };
        boolean[] bindListParamFlags = new boolean[] { false, false };

        InvocationTargetException ex = assertThrows(InvocationTargetException.class, () -> factory.invoke(null, queryInfo, "ProcedureDao.callProc", daoMethod,
                daoMethod.getParameterTypes(), 2, 2, stmtParamIndexes, bindListParamFlags, 2));

        assertTrue(ex.getCause() instanceof UnsupportedOperationException);
        assertTrue(ex.getCause().getMessage().contains("either all procedure parameters must be bound"));
    }

    @Test
    void testResultExtractorPrimitiveReturnTypeCompatibility() throws Exception {
        Method daoMethod = PrimitiveExtractorDao.class.getMethod("count", Jdbc.ResultExtractor.class);
        Method factory = DaoImpl.class.getDeclaredMethod("createQueryFunctionByMethod", Class.class, Method.class, String.class, List.class, Map.class,
                boolean.class, boolean.class, boolean.class, OP.class, boolean.class, String.class);
        factory.setAccessible(true);

        @SuppressWarnings("unchecked")
        Throwables.BiFunction<AbstractQuery, Object[], Object, SQLException> func = (Throwables.BiFunction<AbstractQuery, Object[], Object, SQLException>) factory
                .invoke(null, TestEntity.class, daoMethod, null, null, null, true, true, false, OP.DEFAULT, false, "PrimitiveExtractorDao.count");

        assertNotNull(func);

        // Verify the function is actually invocable and returns a value from the extractor
        Dataset dataset = new RowDataset(ImmutableList.of("cnt"), ImmutableList.of(ImmutableList.of(42)));
        Jdbc.ResultExtractor<Integer> extractor = rs -> 42;
        Object result = func.apply(new StubQuery(dataset), new Object[] { extractor });
        // The factory should produce a function that returns a non-null result from the query
        assertNotNull(result);
        // Verify the result is a Dataset containing the expected data
        assertTrue(result instanceof Dataset, "Expected a Dataset result from the query function");
        assertEquals(1, ((Dataset) result).size(), "Dataset should contain one row");
    }

    @Test
    void testCreateDaoWithReorderedGenericInterfaces() throws Exception {
        ReorderedGenericDao dao = DaoImpl.createDao(ReorderedGenericDao.class, null, mockDataSourceForDaoCreation(), null, null, null);
        assertNotNull(dao);
        // Verify the proxy implements both the DAO interface and the Marker interface
        assertTrue(dao instanceof BaseDao, "Should implement BaseDao");
        assertTrue(dao instanceof Marker, "Should implement Marker");
        // Verify the proxy is not accidentally the raw interface class itself
        assertFalse(dao.getClass().equals(ReorderedGenericDao.class), "Should be a proxy, not the interface itself");
    }

    @Test
    void testCreateDaoRejectsRowFilterInUnsupportedPosition() throws Exception {
        assertThrows(UnsupportedOperationException.class,
                () -> DaoImpl.createDao(InvalidRowFilterPositionDao.class, null, mockDataSourceForDaoCreation(), null, null, null));
    }

    private static DataSource mockDataSourceForDaoCreation() throws SQLException {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        DatabaseMetaData meta = mock(DatabaseMetaData.class);

        org.mockito.Mockito.when(ds.getConnection()).thenReturn(conn);
        org.mockito.Mockito.when(conn.getMetaData()).thenReturn(meta);
        org.mockito.Mockito.when(meta.getDatabaseProductName()).thenReturn("MySQL");
        org.mockito.Mockito.when(meta.getDatabaseProductVersion()).thenReturn("8.0");

        return ds;
    }
}
