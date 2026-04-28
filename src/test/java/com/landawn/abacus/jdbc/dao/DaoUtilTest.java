package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.query.SqlBuilder.PSC;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.Seid;
import com.landawn.abacus.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertNull;

public class DaoUtilTest extends TestBase {

    interface TestCrudJoinDao extends CrudDao<Object, Long, PSC, TestCrudJoinDao>, CrudJoinEntityHelper<Object, Long, PSC, TestCrudJoinDao> {
    }

    interface TestUncheckedCrudJoinDao
            extends UncheckedCrudDao<Object, Long, PSC, TestUncheckedCrudJoinDao>, UncheckedCrudJoinEntityHelper<Object, Long, PSC, TestUncheckedCrudJoinDao> {
    }

    interface TestJoinHelperOnly extends JoinEntityHelper<Object, PSC, TestDao> {
    }

    interface TestUncheckedJoinHelperOnly extends UncheckedJoinEntityHelper<Object, PSC, TestUncheckedDao> {
    }

    interface TestDao extends Dao<Object, PSC, TestDao> {
    }

    interface TestUncheckedDao extends UncheckedDao<Object, PSC, TestUncheckedDao> {
    }

    @Test
    public void testStmtSetterForBigQueryResult() throws SQLException {
        PreparedStatement stmt = Mockito.mock(PreparedStatement.class);

        DaoUtil.stmtSetterForBigQueryResult.accept(stmt);

        verify(stmt).setFetchDirection(ResultSet.FETCH_FORWARD);
        verify(stmt).setFetchSize(com.landawn.abacus.jdbc.JdbcUtil.DEFAULT_FETCH_SIZE_FOR_BIG_RESULT);
    }

    @Test
    public void testGetCrudDao() {
        TestCrudJoinDao dao = Mockito.mock(TestCrudJoinDao.class);

        assertSame(dao, DaoUtil.getCrudDao(dao));
    }

    @Test
    public void testGetUncheckedCrudDao() {
        TestUncheckedCrudJoinDao dao = Mockito.mock(TestUncheckedCrudJoinDao.class);

        assertSame(dao, DaoUtil.getCrudDao(dao));
    }

    @Test
    public void testGetDao_RejectsJoinHelperWithoutDao() {
        TestJoinHelperOnly helper = Mockito.mock(TestJoinHelperOnly.class);

        assertThrows(UnsupportedOperationException.class, () -> DaoUtil.getDao(helper));
    }

    @Test
    public void testGetUncheckedDao_RejectsJoinHelperWithoutDao() {
        TestUncheckedJoinHelperOnly helper = Mockito.mock(TestUncheckedJoinHelperOnly.class);

        assertThrows(UnsupportedOperationException.class, () -> DaoUtil.getDao(helper));
    }

    @Test
    public void testIsSelectQuery() {
        assertTrue(DaoUtil.isSelectQuery("  select * from demo"));
        assertFalse(DaoUtil.isSelectQuery("update demo set name = 'x'"));
    }

    @Test
    public void testIsInsertQuery() {
        assertTrue(DaoUtil.isInsertQuery("insert into demo(id) values (1)"));
        assertFalse(DaoUtil.isInsertQuery("delete from demo"));
    }

    // CTE and leading-comment SQL classification exercises the keyword scanner.
    @Test
    public void testIsSelectQuery_WithLeadingCommentsAndCte() {
        final String sql = "  -- leading comment\n/* block comment */\n# shell comment\nWITH cte AS (SELECT 'not final' AS name) SELECT * FROM cte";

        assertTrue(DaoUtil.isSelectQuery(sql));
    }

    @Test
    public void testIsInsertQuery_WithRecursiveCte() {
        final String sql = "WITH RECURSIVE cte AS (SELECT 1) INSERT INTO audit_log(id) SELECT id FROM cte";

        assertTrue(DaoUtil.isInsertQuery(sql));
    }

    @Test
    public void testIsSelectQuery_CommentsOnly() {
        assertFalse(DaoUtil.isSelectQuery(" /* block */ -- line\n # shell\n "));
    }

    // Simple entity for extractId / createIdExtractor tests
    public static final class SimpleEntity {
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

    public static final class CompositeKeyEntity {
        private long orderId;
        private int lineNum;

        public long getOrderId() {
            return orderId;
        }

        public void setOrderId(long orderId) {
            this.orderId = orderId;
        }

        public int getLineNum() {
            return lineNum;
        }

        public void setLineNum(int lineNum) {
            this.lineNum = lineNum;
        }
    }

    @Test
    public void testExtractId_SingleId() {
        BeanInfo beanInfo = ParserUtil.getBeanInfo(SimpleEntity.class);
        SimpleEntity entity = new SimpleEntity();
        entity.setId(42L);

        Long id = DaoUtil.extractId(entity, List.of("id"), beanInfo);
        assertEquals(42L, id);
    }

    @Test
    public void testExtractId_CompositeId() {
        BeanInfo beanInfo = ParserUtil.getBeanInfo(CompositeKeyEntity.class);
        CompositeKeyEntity entity = new CompositeKeyEntity();
        entity.setOrderId(10L);
        entity.setLineNum(2);

        Seid id = DaoUtil.extractId(entity, Arrays.asList("orderId", "lineNum"), beanInfo);
        assertNotNull(id);
        assertEquals((Object) 10L, id.get("orderId"));
        assertEquals((Object) 2, id.get("lineNum"));
    }

    @Test
    public void testCreateIdExtractor_SingleId() {
        BeanInfo beanInfo = ParserUtil.getBeanInfo(SimpleEntity.class);
        Function<SimpleEntity, Long> extractor = DaoUtil.createIdExtractor(List.of("id"), beanInfo);

        SimpleEntity entity = new SimpleEntity();
        entity.setId(99L);

        assertEquals(99L, extractor.apply(entity));
    }

    @Test
    public void testCreateIdExtractor_CompositeId() {
        BeanInfo beanInfo = ParserUtil.getBeanInfo(CompositeKeyEntity.class);
        Function<CompositeKeyEntity, Seid> extractor = DaoUtil.createIdExtractor(Arrays.asList("orderId", "lineNum"), beanInfo);

        CompositeKeyEntity entity = new CompositeKeyEntity();
        entity.setOrderId(5L);
        entity.setLineNum(3);

        Seid id = extractor.apply(entity);
        assertNotNull(id);
        assertEquals((Object) 5L, id.get("orderId"));
    }

    @Test
    public void testGetRefreshSelectPropNames_ContainsAllIds() {
        Collection<String> propsToRefresh = Arrays.asList("id", "name");
        Collection<String> result = DaoUtil.getRefreshSelectPropNames(propsToRefresh, List.of("id"));
        assertSame(propsToRefresh, result);
    }

    @Test
    public void testGetRefreshSelectPropNames_MissingId() {
        Collection<String> propsToRefresh = Arrays.asList("name");
        Collection<String> result = DaoUtil.getRefreshSelectPropNames(propsToRefresh, List.of("id"));
        assertTrue(result.contains("id"));
        assertTrue(result.contains("name"));
    }

    @Test
    public void testUncheckedComplete_Success() {
        List<ContinuableFuture<Void>> futures = List.of(ContinuableFuture.completed(null));
        DaoUtil.uncheckedComplete(futures); // should not throw
    }

    @Test
    public void testUncheckedCompleteSum_Success() {
        List<ContinuableFuture<Integer>> futures = Arrays.asList(ContinuableFuture.completed(3), ContinuableFuture.completed(5));
        int sum = DaoUtil.uncheckedCompleteSum(futures);
        assertEquals(8, sum);
    }

    @Test
    public void testComplete_Success() throws SQLException {
        List<ContinuableFuture<Void>> futures = List.of(ContinuableFuture.completed(null));
        DaoUtil.complete(futures); // should not throw
    }

    @Test
    public void testCompleteSum_Success() throws SQLException {
        List<ContinuableFuture<Integer>> futures = Arrays.asList(ContinuableFuture.completed(2), ContinuableFuture.completed(4));
        int sum = DaoUtil.completeSum(futures);
        assertEquals(6, sum);
    }

    @Test
    public void testUncheckedComplete_Failure() {
        ContinuableFuture<Void> failed = ContinuableFuture.run(() -> {
            throw new UncheckedSQLException(new SQLException("test"));
        });
        assertThrows(UncheckedSQLException.class, () -> DaoUtil.uncheckedComplete(List.of(failed)));
    }

    @Test
    public void testComplete_Failure() {
        ContinuableFuture<Void> failed = ContinuableFuture.run(() -> {
            throw new SQLException("test");
        });
        assertThrows(SQLException.class, () -> DaoUtil.complete(List.of(failed)));
    }

    @Test
    public void testThrowUncheckedSQLException_WithSQLExceptionCause() {
        final Exception ex = new RuntimeException(new SQLException("inner"));
        assertThrows(UncheckedSQLException.class, () -> DaoUtil.throwUncheckedSQLException.accept(ex));
    }

    @Test
    public void testThrowUncheckedSQLException_WithRuntimeException() {
        final Exception ex = new IllegalArgumentException("not sql");
        assertThrows(RuntimeException.class, () -> DaoUtil.throwUncheckedSQLException.accept(ex));
    }

    @Test
    public void testThrowSQLExceptionAction_WithSQLExceptionCause() {
        final Exception ex = new RuntimeException(new SQLException("inner"));
        assertThrows(SQLException.class, () -> DaoUtil.throwSQLExceptionAction.accept(ex));
    }

    @Test
    public void testThrowSQLExceptionAction_WithRuntimeException() {
        final Exception ex = new IllegalArgumentException("not sql");
        assertThrows(RuntimeException.class, () -> DaoUtil.throwSQLExceptionAction.accept(ex));
    }

    @Test
    public void testUncheckedComplete_WithSQLExceptionCause() {
        final ContinuableFuture<Void> failed = ContinuableFuture.run(() -> {
            throw new RuntimeException(new SQLException("cause"));
        });
        assertThrows(UncheckedSQLException.class, () -> DaoUtil.uncheckedComplete(List.of(failed)));
    }

    @Test
    public void testComplete_WithSQLExceptionCause() {
        final ContinuableFuture<Void> failed = ContinuableFuture.run(() -> {
            throw new RuntimeException(new SQLException("cause"));
        });
        assertThrows(SQLException.class, () -> DaoUtil.complete(List.of(failed)));
    }

    // CrudJoinEntityHelper without CrudDao — exercises the throw branch (line 277-278).
    interface OnlyCrudJoinHelper extends CrudJoinEntityHelper<Object, Long, PSC, TestCrudJoinDao> {
    }

    @Test
    public void testGetCrudDao_NotCrudDao_Throws() {
        final OnlyCrudJoinHelper helper = Mockito.mock(OnlyCrudJoinHelper.class);
        assertThrows(UnsupportedOperationException.class, () -> DaoUtil.getCrudDao(helper));
    }

    // UncheckedCrudJoinEntityHelper without UncheckedCrudDao — exercises throw branch (line 386-387).
    interface OnlyUncheckedCrudJoinHelper
            extends UncheckedCrudJoinEntityHelper<Object, Long, PSC, TestUncheckedCrudJoinDao> {
    }

    @Test
    public void testGetCrudDao_NotUncheckedCrudDao_Throws() {
        final OnlyUncheckedCrudJoinHelper helper = Mockito.mock(OnlyUncheckedCrudJoinHelper.class);
        assertThrows(UnsupportedOperationException.class, () -> DaoUtil.getCrudDao(helper));
    }

    // uncheckedCompleteSum throws UncheckedSQLException when a future fails (line 493).
    @Test
    public void testUncheckedCompleteSum_FailureWraps() {
        final ContinuableFuture<Integer> failed = ContinuableFuture.call(() -> {
            throw new SQLException("boom");
        });
        assertThrows(UncheckedSQLException.class, () -> DaoUtil.uncheckedCompleteSum(List.of(failed)));
    }

    // completeSum throws SQLException when a future fails (line 567).
    @Test
    public void testCompleteSum_FailureWraps() {
        final ContinuableFuture<Integer> failed = ContinuableFuture.call(() -> {
            throw new SQLException("boom");
        });
        assertThrows(SQLException.class, () -> DaoUtil.completeSum(List.of(failed)));
    }

    // throwUncheckedSQLException with a direct SQLException — exercises line 402.
    @Test
    public void testThrowUncheckedSQLException_DirectSQLException() {
        final SQLException sql = new SQLException("direct");
        assertThrows(UncheckedSQLException.class, () -> DaoUtil.throwUncheckedSQLException.accept(sql));
    }

    // getDaoPreparedQueryFunc — PSC, PAC, PLC, PSB code paths (lines 672-723).

    static final class DemoBean {
        private long id;
        private String name;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    interface PscDao extends Dao<DemoBean, com.landawn.abacus.query.SqlBuilder.PSC, PscDao> {
    }
    interface PacDao extends Dao<DemoBean, com.landawn.abacus.query.SqlBuilder.PAC, PacDao> {
    }
    interface PlcDao extends Dao<DemoBean, com.landawn.abacus.query.SqlBuilder.PLC, PlcDao> {
    }
    interface PsbDao extends Dao<DemoBean, com.landawn.abacus.query.SqlBuilder.PSB, PsbDao> {
    }

    @Test
    public void testGetDaoPreparedQueryFunc_Psc() {
        final PscDao dao = Mockito.mock(PscDao.class);
        Mockito.when(dao.targetEntityClass()).thenReturn((Class) DemoBean.class);

        final var pair = DaoUtil.getDaoPreparedQueryFunc(dao);
        assertNotNull(pair._1);
        assertNotNull(pair._2);
    }

    @Test
    public void testGetDaoPreparedQueryFunc_Pac() {
        final PacDao dao = Mockito.mock(PacDao.class);
        Mockito.when(dao.targetEntityClass()).thenReturn((Class) DemoBean.class);

        final var pair = DaoUtil.getDaoPreparedQueryFunc(dao);
        assertNotNull(pair._1);
        assertNotNull(pair._2);
    }

    @Test
    public void testGetDaoPreparedQueryFunc_Plc() {
        final PlcDao dao = Mockito.mock(PlcDao.class);
        Mockito.when(dao.targetEntityClass()).thenReturn((Class) DemoBean.class);

        final var pair = DaoUtil.getDaoPreparedQueryFunc(dao);
        assertNotNull(pair._1);
        assertNotNull(pair._2);
    }

    @Test
    public void testGetDaoPreparedQueryFunc_Psb() {
        final PsbDao dao = Mockito.mock(PsbDao.class);
        Mockito.when(dao.targetEntityClass()).thenReturn((Class) DemoBean.class);

        final var pair = DaoUtil.getDaoPreparedQueryFunc(dao);
        assertNotNull(pair._1);
        assertNotNull(pair._2);
    }

    // PSC builder lambdas — apply() actually executes prepareQueryFunc / prepareNamedQueryFunc
    // (lines 632-650, 652-670, 673-683).
    @Test
    public void testGetDaoPreparedQueryFunc_PscApply_BuildsRealSql() throws SQLException {
        final PscDao dao = Mockito.mock(PscDao.class);
        Mockito.when(dao.targetEntityClass()).thenReturn((Class) DemoBean.class);
        final javax.sql.DataSource ds = Mockito.mock(javax.sql.DataSource.class);
        final java.sql.Connection conn = Mockito.mock(java.sql.Connection.class);
        final java.sql.PreparedStatement stmt = Mockito.mock(java.sql.PreparedStatement.class);
        final java.sql.DatabaseMetaData md = Mockito.mock(java.sql.DatabaseMetaData.class);

        Mockito.when(dao.dataSource()).thenReturn(ds);
        Mockito.when(ds.getConnection()).thenReturn(conn);
        Mockito.when(conn.getMetaData()).thenReturn(md);
        Mockito.when(md.getDatabaseProductName()).thenReturn("MySQL");
        Mockito.when(md.getDatabaseProductVersion()).thenReturn("8.0");
        Mockito.when(conn.prepareStatement(Mockito.anyString())).thenReturn(stmt);

        final var pair = DaoUtil.getDaoPreparedQueryFunc(dao);
        final com.landawn.abacus.query.condition.Condition cond = com.landawn.abacus.query.Filters.eq("id", 1L);

        final com.landawn.abacus.jdbc.PreparedQuery pq = pair._1.apply(null, cond);
        assertNotNull(pq);

        final com.landawn.abacus.jdbc.NamedQuery nq = pair._2.apply(null, cond);
        assertNotNull(nq);
    }
}
