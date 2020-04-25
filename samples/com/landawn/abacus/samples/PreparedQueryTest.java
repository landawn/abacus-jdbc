package com.landawn.abacus.samples;

import static com.landawn.abacus.samples.Jdbc.dataSource;
import static com.landawn.abacus.samples.Jdbc.userDao;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.JdbcUtil.BiRowMapper;
import com.landawn.abacus.util.JdbcUtil.RowMapper;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.SQLBuilder.PSC;
import com.landawn.abacus.util.stream.IntStream;

public class PreparedQueryTest {

    @Test
    public void test_listToMap() throws SQLException {

        List<User> users = IntStream.range(1, 9)
                .mapToObj(i -> User.builder().id(i).firstName("Forrest" + i).lastName("Gump").email("123@email.com" + i).build())
                .toList();

        List<Long> ids = userDao.batchInsertWithId(users);
        assertEquals(users.size(), ids.size());

        long minId = N.min(ids);

        String sql = PSC.selectFrom(User.class).where("id >= ?").sql();

        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, minId)
                .listToMap(rs -> rs.getLong(1), rs -> rs.getString(2))
                .forEach(Fn.println("="));

        try {
            JdbcUtil.prepareQuery(dataSource, sql) //
                    .setLong(1, minId)
                    .listToMap(rs -> rs.getString("lastName"), rs -> rs.getLong(1))
                    .forEach(Fn.println("="));
            fail("Should throw IllegalStateException");
        } catch (IllegalStateException e) {
        }

        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, minId)
                .listToMap(rs -> rs.getString("lastName"), rs -> rs.getLong(1), Fn.replacingMerger())
                .forEach(Fn.println("="));

        sql = PSC.deleteFrom(User.class).where("id >= ?").sql();
        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, minId)
                .update();
    }

    @Test
    public void test_ColumnGetter() throws SQLException {

        String sql = PSC.insertInto(User.class).sql();
        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, 100)
                .setString(2, "Forrest")
                .setString(3, "Gump")
                .setString(4, "Forrest")
                .setString(5, "123@email.com")
                .insert();

        sql = PSC.selectFrom(User.class).where("id = ?").sql();

        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, 100)
                .get(RowMapper.builder()
                        .get(1, (columnIndex, rs) -> rs.getLong(columnIndex))
                        .get(3, (columnIndex, rs) -> rs.getString(columnIndex))
                        .get(6, (columnIndex, rs) -> rs.getDate(columnIndex))
                        .toList())
                .ifPresent(System.out::println);

        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, 100)
                .get(RowMapper.builder().getInt(1).get(3, (columnIndex, rs) -> rs.getString(columnIndex)).getTime(6).toArray())
                .ifPresent(System.out::println);

        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, 100)
                .get(BiRowMapper.builder()
                        .get("id", (columnIndex, rs) -> rs.getLong(columnIndex))
                        .get("firstName", (columnIndex, rs) -> rs.getString(columnIndex))
                        .getTimestamp("createTime")
                        .to(User.class))
                .ifPresent(System.out::println);

        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, 100)
                .get(BiRowMapper.builder().getLong("id").get("firstName", (columnIndex, rs) -> rs.getString(columnIndex)).getTime("createTime").to(List.class))
                .ifPresent(System.out::println);

        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, 100)
                .get(BiRowMapper.builder()
                        .get("id", (columnIndex, rs) -> rs.getLong(columnIndex))
                        .get("firstName", (columnIndex, rs) -> rs.getString(columnIndex))
                        .getDate("createTime")
                        .to(Map.class))
                .ifPresent(System.out::println);

        sql = PSC.deleteFrom(User.class).where("id = ?").sql();
        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, 100)
                .update();
    }
}
