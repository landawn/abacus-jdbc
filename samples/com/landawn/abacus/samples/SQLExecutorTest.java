package com.landawn.abacus.samples;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.sql.SQLException;
import java.util.List;

import org.junit.Test;

import com.landawn.abacus.condition.ConditionFactory.CF;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.SQLBuilder.NSC;
import com.landawn.abacus.util.SQLBuilder.SP;
import com.landawn.abacus.util.SQLExecutor.JdbcSettings;

/**
 * All I want to do is: insert -> read -> update -> delete a record in DB table.
 */
public class SQLExecutorTest extends Jdbc {

    @Test
    public void batch_stream() throws SQLException {
        List<User> users = N.fill(User.class, 99);
        String sql_insert = NSC.insertInto(User.class, N.asSet("id")).sql();
        List<Long> ids = sqlExecutor.batchInsert(sql_insert, users);
        String sql = NSC.selectFrom(User.class).where(CF.in("id", ids)).sql();

        for (int i = 0; i < 1000; i++) {
            assertEquals(99, sqlExecutor.stream(User.class, sql, ids).count());
            assertEquals(99, userMapper.stream(CF.in("id", ids)).count());
        }

        for (int i = 0; i < 1000; i++) {
            try {
                sqlExecutor.stream(User.class, "select * from user where idd > 1").count();
                fail("Should throw UncheckedSQLException");
            } catch (UncheckedSQLException e) {

            }
        }

        userDao.delete(CF.alwaysTrue());
    }

    @Test
    public void batch_01() throws SQLException {

        List<User> users = N.fill(User.class, 99);
        String sql_insert = NSC.insertInto(User.class, N.asSet("id")).sql();
        JdbcSettings jdbcSettings = JdbcSettings.create().setBatchSize(1000);
        // insert 99 users, less the specified batch size.
        List<Long> ids = sqlExecutor.batchInsert(sql_insert, jdbcSettings, users);
        N.println(ids);

        assertEquals(users.size(), ids.size());

        SP sp = NSC.selectFrom(User.class).where(CF.in("id", ids)).pair();
        sqlExecutor.query(sp.sql, sp.parameters).println();

        // insert 3001 users, bigger the specified batch size.
        users = N.fill(User.class, 3001);
        ids = sqlExecutor.batchInsert(sql_insert, jdbcSettings, users);
        N.println(ids);

        assertEquals(users.size(), ids.size());

        sp = NSC.selectFrom(User.class).where(CF.in("id", ids)).pair();
        sqlExecutor.query(sp.sql, sp.parameters).println();

        userDao.delete(CF.alwaysTrue());
    }

    @Test
    public void batch_25() throws SQLException {
        List<User> users = N.fill(User.class, 19764);
        String sql_insert = NSC.insertInto(User.class, N.asSet("id")).sql();
        JdbcSettings jdbcSettings = JdbcSettings.create().setBatchSize(5000);
        // insert 99 users, less the specified batch size.
        List<Long> ids = sqlExecutor.batchInsert(sql_insert, jdbcSettings, users);

        assertEquals(users.size(), ids.size());

        SP sp = NSC.selectFrom(User.class).where(CF.in("id", ids)).pair();
        assertEquals(users.size(), sqlExecutor.query(sp.sql, sp.parameters).size());

        userDao.delete(CF.alwaysTrue());
    }
}
