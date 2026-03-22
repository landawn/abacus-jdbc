package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.SqlBuilder.PSC;

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
}
