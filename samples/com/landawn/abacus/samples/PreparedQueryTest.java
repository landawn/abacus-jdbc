package com.landawn.abacus.samples;

import static com.landawn.abacus.samples.JdbcTest.dataSource;
import static com.landawn.abacus.samples.JdbcTest.userDao;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.condition.ConditionFactory.CF;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.SQLBuilder.NSC;
import com.landawn.abacus.util.SQLBuilder.PSC;
import com.landawn.abacus.util.stream.IntStream;

import codes.entity.Account.s;

public class PreparedQueryTest {

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_queryForSingleResult() throws SQLException {

        String firstName = N.toJson(N.asList("a", "b", "c"));

        User user = User.builder().firstName(firstName).lastName("Gump").email("123@email.com").build();

        Long id = userDao.insert(user);

        List<String> firstNameV = JdbcUtil.prepareQuery(dataSource, "select first_name from user1 where id = ?") //
                .setLong(1, id)
                .queryForSingleResult(Type.ofList(String.class))
                .orElseNull();

        N.println(firstNameV);

        String sql = PSC.deleteFrom(User.class).where("id >= ?").sql();
        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, id)
                .update();

    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_alias() throws SQLException {

        List<User> users = IntStream.range(1, 9)
                .mapToObj(i -> User.builder().id(i).firstName("Forrest" + i).lastName("Gump").email("123@email.com" + i).build())
                .toList();

        List<Long> ids = userDao.batchInsertWithId(users);
        assertEquals(users.size(), ids.size());

        long minId = N.min(ids);

        String sql = "SELECT acc.id AS \"acc.id\", acc.FIRST_NAME AS \"acc.firstName\", acc.last_name AS \"lastName\", acc.prop1 AS \"nickName\", acc.email AS \"email\", acc.create_time AS \"createTime\" FROM user1 acc";

        JdbcUtil.prepareQuery(dataSource, sql) //
                .query()
                .println();

        JdbcUtil.prepareQuery(dataSource, sql) //
                .list(Jdbc.BiRowMapper.to(User.class, null, it -> it.replaceFirst("acc.", "")))
                .forEach(Fn.println());

        sql = PSC.deleteFrom(User.class).where("id >= ?").sql();
        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, minId)
                .update();
    }

    /**
     *
     *
     * @throws SQLException
     */
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
                .query(Jdbc.ResultExtractor.toMap(rs -> rs.getLong(1), rs -> rs.getString(2)))
                .forEach(Fn.println("="));

        try {
            JdbcUtil.prepareQuery(dataSource, sql) //
                    .setLong(1, minId)
                    .query(Jdbc.ResultExtractor.toMap(rs -> rs.getString(s.lastName), rs -> rs.getLong(1)))
                    .forEach(Fn.println("="));
            fail("Should throw IllegalStateException");
        } catch (IllegalStateException e) {
        }

        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, minId)
                .query(Jdbc.ResultExtractor.toMap(rs -> rs.getString(s.lastName), rs -> rs.getLong(1), Fn.replacingMerger()))
                .forEach(Fn.println("="));

        sql = PSC.deleteFrom(User.class).where("id >= ?").sql();
        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, minId)
                .update();
    }

    /**
     *
     *
     * @throws SQLException
     */
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

        JdbcUtil.prepareNamedQuery(dataSource, NSC.selectFrom(User.class).where(CF.eq(s.firstName)).sql()) //
                .setParameters(User.builder().firstName("Forrest").build(), N.asList(s.firstName))
                .findOnlyOne(User.class)
                .ifPresent(System.out::println);

        JdbcUtil.prepareNamedQuery(dataSource, NSC.selectFrom(User.class).where(CF.eq(s.firstName)).sql()) //
                .settParameters(1, N.asList("Forrest"))
                .findOnlyOne(User.class)
                .ifPresent(System.out::println);

        sql = PSC.selectFrom(User.class).where("id = ?").sql();

        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, 100)
                .findOnlyOne(Jdbc.RowMapper.builder().get(1, ResultSet::getLong).get(3, ResultSet::getString).get(6, ResultSet::getDate).toList())
                .ifPresent(System.out::println);

        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, 100)
                .findOnlyOne(Jdbc.RowMapper.builder().getInt(1).get(3, ResultSet::getString).getTime(6).toArray())
                .ifPresent(System.out::println);

        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, 100)
                .findOnlyOne(Jdbc.BiRowMapper.builder()
                        .get(s.id, ResultSet::getLong)
                        .get(s.firstName, ResultSet::getString)
                        .getTimestamp(s.createTime)
                        .to(User.class))
                .ifPresent(System.out::println);

        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, 100)
                .findOnlyOne(Jdbc.BiRowMapper.builder().getLong(s.id).get(s.firstName, ResultSet::getString).getTime(s.createTime).to(List.class))
                .ifPresent(System.out::println);

        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, 100)
                .findOnlyOne(
                        Jdbc.BiRowMapper.builder().get("id", ResultSet::getLong).get("firstName", ResultSet::getString).getDate("createTime").to(Map.class))
                .ifPresent(System.out::println);

        JdbcUtil.prepareQuery(dataSource, "select id from user1").queryForBigInteger().ifPresent(Fn.println());
        JdbcUtil.prepareQuery(dataSource, "select id from user1").queryForBigDecimal().ifPresent(Fn.println());

        sql = PSC.deleteFrom(User.class).where("id = ?").sql();
        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, 100)
                .update();
    }
}
