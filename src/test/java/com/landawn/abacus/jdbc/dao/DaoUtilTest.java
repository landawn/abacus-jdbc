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
import com.landawn.abacus.query.SqlParser;
import com.landawn.abacus.util.ContinuableFuture;
import com.landawn.abacus.util.Result;
import com.landawn.abacus.util.Seid;
import com.landawn.abacus.util.function.Function;

public class DaoUtilTest extends TestBase {

    interface TestCrudJoinDao extends CrudDao<Object, Long, TestCrudJoinDao>, CrudJoinEntityHelper<Object, Long, TestCrudJoinDao> {
    }

    interface TestUncheckedCrudJoinDao
            extends UncheckedCrudDao<Object, Long, TestUncheckedCrudJoinDao>, UncheckedCrudJoinEntityHelper<Object, Long, TestUncheckedCrudJoinDao> {
    }

    interface TestJoinHelperOnly extends JoinEntityHelper<Object, TestDao> {
    }

    interface TestUncheckedJoinHelperOnly extends UncheckedJoinEntityHelper<Object, TestUncheckedDao> {
    }

    interface TestDao extends Dao<Object, TestDao> {
    }

    interface TestUncheckedDao extends UncheckedDao<Object, TestUncheckedDao> {
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
        assertTrue(SqlParser.isSelectQuery("  select * from demo"));
        assertFalse(SqlParser.isSelectQuery("update demo set name = 'x'"));
    }

    @Test
    public void testIsInsertQuery() {
        assertTrue(SqlParser.isInsertQuery("insert into demo(id) values (1)"));
        assertFalse(SqlParser.isInsertQuery("delete from demo"));
    }

    // CTE and leading-comment SQL classification exercises the keyword scanner.
    @Test
    public void testIsSelectQuery_WithLeadingCommentsAndCte() {
        final String sql = "  -- leading comment\n/* block comment */\n# shell comment\nWITH cte AS (SELECT 'not final' AS name) SELECT * FROM cte";

        assertTrue(SqlParser.isSelectQuery(sql));
    }

    @Test
    public void testIsInsertQuery_WithRecursiveCte() {
        final String sql = "WITH RECURSIVE cte AS (SELECT 1) INSERT INTO audit_log(id) SELECT id FROM cte";

        assertTrue(SqlParser.isInsertQuery(sql));
    }

    // isReadOnlyQuery / isNoUpdateQuery are the public gates used by the DaoImpl proxy to enforce
    // ReadOnlyDao (SELECT-only) and NoUpdateDao (SELECT/INSERT-only) restrictions.
    @Test
    public void testIsReadOnlyQuery() {
        assertTrue(SqlParser.isReadOnlyQuery("SELECT * FROM demo"));
        assertTrue(SqlParser.isReadOnlyQuery("  select id from demo where name = 'DELETE'")); // 'DELETE' is a literal, not a keyword
        assertFalse(SqlParser.isReadOnlyQuery("INSERT INTO demo(id) VALUES (1)"));
        assertFalse(SqlParser.isReadOnlyQuery("UPDATE demo SET name = 'x'"));
        assertFalse(SqlParser.isReadOnlyQuery("DELETE FROM demo"));
        assertFalse(SqlParser.isReadOnlyQuery("MERGE INTO demo USING src ON (demo.id = src.id) WHEN MATCHED THEN UPDATE SET name = src.name"));
        assertFalse(SqlParser.isReadOnlyQuery("SELECT * INTO demo_copy FROM demo"));
        assertFalse(SqlParser.isReadOnlyQuery("WITH c AS (DELETE FROM demo RETURNING *) SELECT * FROM c")); // mutating CTE
        assertFalse(SqlParser.isReadOnlyQuery(null));
    }

    @Test
    public void testIsNoUpdateQuery() {
        assertTrue(SqlParser.isNoUpdateQuery("SELECT * FROM demo"));
        assertTrue(SqlParser.isNoUpdateQuery("INSERT INTO demo(id) VALUES (1)"));
        assertTrue(SqlParser.isNoUpdateQuery("INSERT INTO demo(id) VALUES (1) ON CONFLICT DO NOTHING")); // never overwrites
        assertFalse(SqlParser.isNoUpdateQuery("UPDATE demo SET name = 'x'"));
        assertFalse(SqlParser.isNoUpdateQuery("DELETE FROM demo"));
        assertFalse(SqlParser.isNoUpdateQuery("MERGE INTO demo USING src ON (demo.id = src.id) WHEN MATCHED THEN UPDATE SET name = src.name"));
        assertFalse(SqlParser.isNoUpdateQuery("INSERT OR REPLACE INTO demo(id) VALUES (1)"));
        assertFalse(SqlParser.isNoUpdateQuery("INSERT INTO demo(id) VALUES (1) ON DUPLICATE KEY UPDATE name = 'x'"));
        assertFalse(SqlParser.isNoUpdateQuery("INSERT INTO demo(id) VALUES (1) ON CONFLICT(id) DO UPDATE SET name = 'x'"));
        assertFalse(SqlParser.isNoUpdateQuery("INSERT OVERWRITE TABLE demo SELECT * FROM staging"));
        assertFalse(SqlParser.isNoUpdateQuery("SELECT * INTO demo_copy FROM demo"));
        assertFalse(SqlParser.isNoUpdateQuery(null));
    }

    @Test
    public void testIsSelectQuery_CommentsOnly() {
        assertFalse(SqlParser.isSelectQuery(" /* block */ -- line\n # shell\n "));
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
        assertEquals(10L, (Long) id.get("orderId"));
        assertEquals(2, (Integer) id.get("lineNum"));
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
        assertEquals(5L, (Long) id.get("orderId"));
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

    @SuppressWarnings("unchecked")
    @Test
    public void testUncheckedComplete_DrainsAllFuturesBeforeThrowing() {
        final ContinuableFuture<Void> failed = Mockito.mock(ContinuableFuture.class);
        final ContinuableFuture<Void> second = Mockito.mock(ContinuableFuture.class);
        final Result<Void, Exception> failedResult = ContinuableFuture.<Void> run(() -> {
            throw new SQLException("first");
        }).getAsResult();
        final Result<Void, Exception> secondResult = ContinuableFuture.completed((Void) null).getAsResult();
        Mockito.when(failed.getAsResult()).thenReturn(failedResult);
        Mockito.when(second.getAsResult()).thenReturn(secondResult);

        assertThrows(UncheckedSQLException.class, () -> DaoUtil.uncheckedComplete(Arrays.asList(failed, second)));

        verify(second).getAsResult();
    }

    @Test
    public void testUncheckedCompleteSum_ThrowsOnOverflow() {
        final List<ContinuableFuture<Integer>> futures = Arrays.asList(ContinuableFuture.completed(Integer.MAX_VALUE), ContinuableFuture.completed(1));

        assertThrows(ArithmeticException.class, () -> DaoUtil.uncheckedCompleteSum(futures));
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

    @SuppressWarnings("unchecked")
    @Test
    public void testComplete_DrainsAllFuturesBeforeThrowing() {
        final ContinuableFuture<Void> failed = Mockito.mock(ContinuableFuture.class);
        final ContinuableFuture<Void> second = Mockito.mock(ContinuableFuture.class);
        final Result<Void, Exception> failedResult = ContinuableFuture.<Void> run(() -> {
            throw new SQLException("first");
        }).getAsResult();
        final Result<Void, Exception> secondResult = ContinuableFuture.completed((Void) null).getAsResult();
        Mockito.when(failed.getAsResult()).thenReturn(failedResult);
        Mockito.when(second.getAsResult()).thenReturn(secondResult);

        assertThrows(SQLException.class, () -> DaoUtil.complete(Arrays.asList(failed, second)));

        verify(second).getAsResult();
    }

    @Test
    public void testCompleteSum_ThrowsOnOverflow() {
        final List<ContinuableFuture<Integer>> futures = Arrays.asList(ContinuableFuture.completed(Integer.MAX_VALUE), ContinuableFuture.completed(1));

        assertThrows(ArithmeticException.class, () -> DaoUtil.completeSum(futures));
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
    interface OnlyCrudJoinHelper extends CrudJoinEntityHelper<Object, Long, TestCrudJoinDao> {
    }

    @Test
    public void testGetCrudDao_NotCrudDao_Throws() {
        final OnlyCrudJoinHelper helper = Mockito.mock(OnlyCrudJoinHelper.class);
        assertThrows(UnsupportedOperationException.class, () -> DaoUtil.getCrudDao(helper));
    }

    // UncheckedCrudJoinEntityHelper without UncheckedCrudDao — exercises throw branch (line 386-387).
    interface OnlyUncheckedCrudJoinHelper extends UncheckedCrudJoinEntityHelper<Object, Long, TestUncheckedCrudJoinDao> {
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

    interface PscDao extends Dao<DemoBean, PscDao> {
    }

    interface PacDao extends Dao<DemoBean, PacDao> {
    }

    interface PlcDao extends Dao<DemoBean, PlcDao> {
    }

    interface PsbDao extends Dao<DemoBean, PsbDao> {
    }

    //    @Test
    //    public void testGetDaoPreparedQueryFunc_Psc() {
    //        final PscDao dao = Mockito.mock(PscDao.class);
    //        Mockito.when(dao.targetEntityClass()).thenReturn((Class) DemoBean.class);
    //        Mockito.when(dao.dsl()).thenReturn(PSC);
    //
    //        final var pair = DaoUtil.getDaoPreparedQueryFunc(dao);
    //        assertNotNull(pair._1);
    //        assertNotNull(pair._2);
    //    }
    //
    //    @Test
    //    public void testGetDaoPreparedQueryFunc_Pac() {
    //        final PacDao dao = Mockito.mock(PacDao.class);
    //        Mockito.when(dao.targetEntityClass()).thenReturn((Class) DemoBean.class);
    //        Mockito.when(dao.dsl()).thenReturn(PAC);
    //
    //        final var pair = DaoUtil.getDaoPreparedQueryFunc(dao);
    //        assertNotNull(pair._1);
    //        assertNotNull(pair._2);
    //    }
    //
    //    @Test
    //    public void testGetDaoPreparedQueryFunc_Plc() {
    //        final PlcDao dao = Mockito.mock(PlcDao.class);
    //        Mockito.when(dao.targetEntityClass()).thenReturn((Class) DemoBean.class);
    //        Mockito.when(dao.dsl()).thenReturn(PLC);
    //
    //        final var pair = DaoUtil.getDaoPreparedQueryFunc(dao);
    //        assertNotNull(pair._1);
    //        assertNotNull(pair._2);
    //    }
    //
    //    @Test
    //    public void testGetDaoPreparedQueryFunc_Psb() {
    //        final PsbDao dao = Mockito.mock(PsbDao.class);
    //        Mockito.when(dao.targetEntityClass()).thenReturn((Class) DemoBean.class);
    //        Mockito.when(dao.dsl()).thenReturn(PSB);
    //
    //        final var pair = DaoUtil.getDaoPreparedQueryFunc(dao);
    //        assertNotNull(pair._1);
    //        assertNotNull(pair._2);
    //    }
    //
    //    // PSC builder lambdas — apply() actually executes prepareQueryFunc / prepareNamedQueryFunc
    //    // (lines 632-650, 652-670, 673-683).
    //    @Test
    //    public void testGetDaoPreparedQueryFunc_PscApply_BuildsRealSql() throws SQLException {
    //        final PscDao dao = Mockito.mock(PscDao.class);
    //        Mockito.when(dao.targetEntityClass()).thenReturn((Class) DemoBean.class);
    //        Mockito.when(dao.dsl()).thenReturn(PSC);
    //        final javax.sql.DataSource ds = Mockito.mock(javax.sql.DataSource.class);
    //        final java.sql.Connection conn = Mockito.mock(java.sql.Connection.class);
    //        final java.sql.PreparedStatement stmt = Mockito.mock(java.sql.PreparedStatement.class);
    //        final java.sql.DatabaseMetaData md = Mockito.mock(java.sql.DatabaseMetaData.class);
    //
    //        Mockito.when(dao.dataSource()).thenReturn(ds);
    //        Mockito.when(ds.getConnection()).thenReturn(conn);
    //        Mockito.when(conn.getMetaData()).thenReturn(md);
    //        Mockito.when(md.getDatabaseProductName()).thenReturn("MySQL");
    //        Mockito.when(md.getDatabaseProductVersion()).thenReturn("8.0");
    //        Mockito.when(conn.prepareStatement(Mockito.anyString())).thenReturn(stmt);
    //
    //        final var pair = DaoUtil.getDaoPreparedQueryFunc(dao);
    //        final com.landawn.abacus.query.condition.Condition cond = com.landawn.abacus.query.Filters.eq("id", 1L);
    //
    //        final com.landawn.abacus.jdbc.PreparedQuery pq = pair._1.apply(null, cond);
    //        assertNotNull(pq);
    //
    //        final com.landawn.abacus.jdbc.NamedQuery nq = pair._2.apply(null, cond);
    //        assertNotNull(nq);
    //    }

    @Test
    public void testGetRefreshSelectPropNamesNullInput() {
        final Collection<String> result = DaoUtil.getRefreshSelectPropNames(null, Arrays.asList("id", "version"));
        assertNotNull(result);
        assertTrue(result.contains("id"));
        assertTrue(result.contains("version"));
    }

    @Test
    public void testIsSelectQueryDoesNotThrow() {
        assertFalse(SqlParser.isSelectQuery(null));
        assertFalse(SqlParser.isSelectQuery(""));
        assertTrue(SqlParser.isSelectQuery("SELECT * FROM t"));
        assertFalse(SqlParser.isSelectQuery("INSERT INTO t VALUES(1)"));
    }

    @Test
    public void testIsInsertQueryDoesNotThrow() {
        assertFalse(SqlParser.isInsertQuery(null));
        assertFalse(SqlParser.isInsertQuery(""));
        assertTrue(SqlParser.isInsertQuery("INSERT INTO t VALUES(1)"));
        assertFalse(SqlParser.isInsertQuery("SELECT * FROM t"));
    }

    // SQL keyword parsing edge cases: non-letter leading characters (line 910)
    @Test
    public void testIsSelectQuery_NonLetterSql() {
        assertFalse(SqlParser.isSelectQuery("123 SELECT * FROM t"));
        assertFalse(SqlParser.isSelectQuery("_abc SELECT * FROM t"));
        assertFalse(SqlParser.isSelectQuery("-- comment\n123"));
    }

    @Test
    public void testIsInsertQuery_NonLetterSql() {
        assertFalse(SqlParser.isInsertQuery("123 INSERT INTO t VALUES(1)"));
    }

    // WITH clause with no final DML keyword (lines 936, 975)
    @Test
    public void testIsSelectQuery_CteNoFinalKeyword() {
        assertFalse(SqlParser.isSelectQuery("WITH cte AS (SELECT 1)"));
        assertFalse(SqlParser.isInsertQuery("WITH cte AS (SELECT 1)"));
    }

    @Test
    public void testIsSelectQuery_CteNoFinalKeyword_WithRecursive() {
        assertFalse(SqlParser.isSelectQuery("WITH RECURSIVE cte AS (SELECT 1)"));
        assertFalse(SqlParser.isInsertQuery("WITH RECURSIVE cte AS (SELECT 1)"));
    }

    // WITH clause containing DML keywords in CTE body — exercises isQueryKeyword branches (line 979) and depth tracking (line 953)
    @Test
    public void testIsSelectQuery_CteWithNestedUpdate() {
        assertTrue(SqlParser.isSelectQuery("WITH cte AS (UPDATE t SET x=1 RETURNING *) SELECT * FROM cte"));
    }

    @Test
    public void testIsSelectQuery_CteWithNestedDelete() {
        assertTrue(SqlParser.isSelectQuery("WITH cte AS (DELETE FROM t WHERE id=1 RETURNING *) SELECT * FROM cte"));
    }

    @Test
    public void testIsInsertQuery_CteWithNestedMerge() {
        assertTrue(
                SqlParser.isInsertQuery("WITH cte AS (MERGE INTO t USING s ON t.id=s.id WHEN MATCHED THEN UPDATE SET x=1) INSERT INTO t2 SELECT * FROM cte"));
    }

    @Test
    public void testIsNoUpdateQuery_AllowsPlainInsertAndDoNothing() {
        assertTrue(SqlParser.isNoUpdateQuery("INSERT INTO audit_log(id, message) VALUES (1, 'created')"));
        assertTrue(SqlParser.isNoUpdateQuery("INSERT INTO users(id, name) VALUES (1, 'a') ON CONFLICT (id) DO NOTHING"));
    }

    @Test
    public void testIsNoUpdateQuery_RejectsInsertConflictUpdateClauses() {
        assertFalse(SqlParser.isNoUpdateQuery("INSERT INTO users(id, name) VALUES (1, 'a') ON DUPLICATE KEY UPDATE name = VALUES(name)"));
        assertFalse(SqlParser.isNoUpdateQuery("INSERT INTO users(id, name) VALUES (1, 'a') ON CONFLICT (id) DO UPDATE SET name = excluded.name"));
        assertFalse(SqlParser.isNoUpdateQuery("INSERT OR REPLACE INTO users(id, name) VALUES (1, 'a')"));
    }

    @Test
    public void testIsNoUpdateQuery_IgnoresQuotedConflictUpdateText() {
        assertTrue(SqlParser.isNoUpdateQuery("INSERT INTO audit_log(message) VALUES ('ON DUPLICATE KEY UPDATE name = VALUES(name)')"));
        assertTrue(SqlParser.isNoUpdateQuery("INSERT INTO audit_log(message) VALUES ('ON CONFLICT DO UPDATE')"));
    }

    @Test
    public void testIsReadOnlyQuery_RejectsSelectInto() {
        assertFalse(SqlParser.isReadOnlyQuery("SELECT * INTO user_copy FROM users"));
        assertFalse(SqlParser.isReadOnlyQuery("WITH src AS (SELECT * FROM users) SELECT * INTO user_copy FROM src"));
        assertTrue(SqlParser.isReadOnlyQuery("SELECT 'INTO' AS keyword_text FROM users"));
        assertTrue(SqlParser.isReadOnlyQuery("SELECT [INTO] FROM users"));
        assertTrue(SqlParser.isReadOnlyQuery("SELECT into_column FROM users"));
    }

    @Test
    public void testIsNoUpdateQuery_RejectsSelectIntoAndInsertOverwrite() {
        assertFalse(SqlParser.isNoUpdateQuery("SELECT * INTO user_copy FROM users"));
        assertFalse(SqlParser.isNoUpdateQuery("INSERT OVERWRITE TABLE users SELECT * FROM staging_users"));
        assertTrue(SqlParser.isNoUpdateQuery("INSERT INTO audit_log(message) VALUES ('INSERT OVERWRITE TABLE users')"));
        assertTrue(SqlParser.isNoUpdateQuery("INSERT INTO audit_log([INSERT OVERWRITE]) VALUES (1)"));
    }

    @Test
    public void testIsReadOnlyQuery_IgnoresBracketQuotedMutationKeywords() {
        assertTrue(SqlParser.isReadOnlyQuery("SELECT [DELETE], [UPDATE], [MERGE] FROM [INSERT]"));
        assertTrue(SqlParser.isReadOnlyQuery("WITH [DELETE] AS (SELECT 1) SELECT * FROM [DELETE]"));
        assertFalse(SqlParser.isReadOnlyQuery("SELECT [DELETE] FROM audit_log; DELETE FROM audit_log"));
    }

    @Test
    public void testIsNoUpdateQuery_IgnoresBracketQuotedConflictUpdateText() {
        assertTrue(SqlParser.isNoUpdateQuery("INSERT INTO audit_log([DO], [UPDATE]) VALUES (1, 2)"));
        assertTrue(SqlParser.isNoUpdateQuery("INSERT INTO audit_log([DO UPDATE]) VALUES (1)"));
        assertFalse(SqlParser.isNoUpdateQuery("INSERT INTO users(id, name) VALUES (1, 'a') ON CONFLICT (id) DO UPDATE SET name = excluded.name"));
    }

    // Backtick-quoted identifiers in WITH clause exercises quote-type branch (line 941)
    @Test
    public void testIsSelectQuery_CteWithBacktickQuotes() {
        assertTrue(SqlParser.isSelectQuery("WITH cte AS (SELECT `col` FROM `tbl`) SELECT * FROM cte"));
    }

    @Test
    public void testIsInsertQuery_CteWithBacktickQuotes() {
        assertTrue(SqlParser.isInsertQuery("WITH cte AS (SELECT `col` FROM `tbl`) INSERT INTO t2 SELECT * FROM cte"));
    }

    // CTE with parenthesized sub-expressions at different depths
    @Test
    public void testIsSelectQuery_CteWithNestedParens() {
        assertTrue(SqlParser.isSelectQuery("WITH cte AS (SELECT x FROM (SELECT 1 AS x) sub WHERE x > 0) SELECT * FROM cte"));
    }

    // A query wrapped in leading parentheses must still be classified by its leading verb.
    // Regression for getLeadingQueryKeyword treating a leading '(' as "no keyword".
    @Test
    public void testIsSelectQuery_LeadingParenthesis() {
        assertTrue(SqlParser.isSelectQuery("(SELECT 1)"));
        assertTrue(SqlParser.isSelectQuery("(SELECT a FROM t1) UNION ALL (SELECT a FROM t2)"));
        assertTrue(SqlParser.isSelectQuery("((SELECT 1))"));
        assertTrue(SqlParser.isSelectQuery("  ( SELECT 1 )"));
        assertTrue(SqlParser.isSelectQuery("-- comment\n(SELECT 1)"));
        assertTrue(SqlParser.isSelectQuery("/* block */ (SELECT id FROM users)"));
        // Still not a SELECT when the wrapped verb is something else.
        assertFalse(SqlParser.isSelectQuery("(UPDATE t SET x = 1)"));
    }

    @Test
    public void testIsReadOnlyQuery_LeadingParenthesis() {
        assertTrue(SqlParser.isNoUpdateQuery("(SELECT 1)"));
        assertTrue(SqlParser.isReadOnlyQuery("(SELECT a FROM t1) UNION ALL (SELECT a FROM t2)"));
    }

    @Test
    public void testIsNoUpdateQuery_LeadingParenthesis() {
        assertTrue(SqlParser.isNoUpdateQuery("(SELECT 1)"));
        assertTrue(SqlParser.isNoUpdateQuery("(SELECT a FROM t1) UNION ALL (SELECT a FROM t2)"));
    }

    @Test
    public void testIsInsertQuery_LeadingParenthesis() {
        assertTrue(SqlParser.isInsertQuery("(INSERT INTO t VALUES (1))"));
        assertFalse(SqlParser.isInsertQuery("(SELECT 1)"));
    }

    // getEntityJoinInfo delegation (line 1097)
    @Test
    public void testGetEntityJoinInfo() {
        final var result = DaoUtil.getEntityJoinInfo(SimpleEntity.class, SimpleEntity.class, "simple_entity");
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    // getJoinEntityPropNamesByType delegation (line 1138)
    @Test
    public void testGetJoinEntityPropNamesByType() {
        final var result = DaoUtil.getJoinEntityPropNamesByType(SimpleEntity.class, SimpleEntity.class, "simple_entity", String.class);
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    interface CustomDao extends Dao<DemoBean, CustomDao> {
    }

    // Shell-comment (#) in skipLeadingWhitespaceAndComments exercises the continue at line 1019.
    @Test
    public void testIsSelectQuery_ShellComment() {
        assertTrue(SqlParser.isSelectQuery("# comment\nSELECT * FROM t"));
    }

    @Test
    public void testIsInsertQuery_ShellComment() {
        assertTrue(SqlParser.isInsertQuery("# comment\nINSERT INTO t VALUES(1)"));
    }

    // Backslash-escaped quote inside a CTE quoted literal exercises skipQuotedLiteral lines 1036-1038.
    @Test
    public void testIsSelectQuery_CteWithBackslashEscapedQuote() {
        assertTrue(SqlParser.isSelectQuery("WITH cte AS (SELECT 'it\\'s' AS name) SELECT * FROM cte"));
    }

    @Test
    public void testIsInsertQuery_CteWithBackslashEscapedQuote() {
        assertTrue(SqlParser.isInsertQuery("WITH cte AS (SELECT 'it\\'s' AS name) INSERT INTO t SELECT * FROM cte"));
    }

    // Backslash at end of quoted literal exercises the break at line 1038.
    @Test
    public void testIsSelectQuery_CteWithBackslashAtEndOfQuote() {
        assertFalse(SqlParser.isSelectQuery("WITH cte AS (SELECT 'test\\' AS name) SELECT * FROM cte"));
    }

    // Doubled-quote escape (SQL standard) inside a CTE exercises skipQuotedLiteral line 1043.
    @Test
    public void testIsSelectQuery_CteWithDoubledQuoteEscape() {
        assertTrue(SqlParser.isSelectQuery("WITH cte AS (SELECT 'it''s' AS name) SELECT * FROM cte"));
    }

    @Test
    public void testIsInsertQuery_CteWithDoubledQuoteEscape() {
        assertTrue(SqlParser.isInsertQuery("WITH cte AS (SELECT 'it''s' AS name) INSERT INTO t SELECT * FROM cte"));
    }
}
