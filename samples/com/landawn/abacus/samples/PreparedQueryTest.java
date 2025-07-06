/*
 * Copyright (C) 2024 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.landawn.abacus.samples;

import static com.landawn.abacus.samples.JdbcTest.dataSource;
import static com.landawn.abacus.samples.JdbcTest.userDao;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.condition.ConditionFactory.CF;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.Jdbc.RowMapper;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.samples.entity.s;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.Fn.Factory;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.SQLBuilder.NSC;
import com.landawn.abacus.util.SQLBuilder.PSC;
import com.landawn.abacus.util.stream.IntStream;

public class PreparedQueryTest {

    @Test
    public void test_builder() throws SQLException {

        final User user = User.builder().firstName("abccc").lastName("Gump").email("123@email.com").build();

        final Long id = userDao.insert(user);

        JdbcUtil.prepareQuery(dataSource, "select * from user1 where id = ?").setLong(1, id).list(User.class).forEach(Fn.println());

        JdbcUtil.prepareQuery(dataSource, "select * from user1 where id = ?")
                .setLong(1, id)
                .list(RowMapper.builder().getObject(2, Object.class).toList())
                .forEach(Fn.println());

        JdbcUtil.prepareQuery(dataSource, "select * from user1 where id = ?")
                .setLong(1, id)
                .list(RowMapper.builder().getObject(3, Object.class).toMap())
                .forEach(Fn.println());

        JdbcUtil.prepareQuery(dataSource, "select * from user1 where id = ?")
                .setLong(1, id)
                .list(RowMapper.builder().getObject(3, Object.class).toMap(Factory.ofLinkedHashMap()))
                .forEach(Fn.println());

        JdbcUtil.prepareQuery(dataSource, "delete from user1 where id = ?").setLong(1, id).update();
    }

    @Test
    public void test_queryForSingleResult() throws SQLException {

        final String firstName = N.toJson(N.asList("a", "b", "c"));

        final User user = User.builder().firstName(firstName).lastName("Gump").email("123@email.com").build();

        final Long id = userDao.insert(user);

        final List<String> firstNameV = JdbcUtil.prepareQuery(dataSource, "select first_name from user1 where id = ?") //
                .setLong(1, id)
                .queryForSingleResult(Type.ofList(String.class))
                .orElseNull();

        N.println(firstNameV);

        final String sql = PSC.deleteFrom(User.class).where("id >= ?").sql();
        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, id)
                .update();

    }

    @Test
    public void test_alias() throws SQLException {

        final List<User> users = IntStream.range(1, 9)
                .mapToObj(i -> User.builder().id(i).firstName("Forrest" + i).lastName("Gump").email("123@email.com" + i).build())
                .toList();

        final List<Long> ids = userDao.batchInsertWithId(users);
        assertEquals(users.size(), ids.size());

        final long minId = N.min(ids);

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

    @Test
    public void test_listToMap() throws SQLException {

        final List<User> users = IntStream.range(1, 9)
                .mapToObj(i -> User.builder().id(i).firstName("Forrest" + i).lastName("Gump").email("123@email.com" + i).build())
                .toList();

        final List<Long> ids = userDao.batchInsertWithId(users);
        assertEquals(users.size(), ids.size());

        final long minId = N.min(ids);

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
        } catch (final IllegalStateException e) {
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
                        Jdbc.BiRowMapper.builder().get(s.id, ResultSet::getLong).get(s.firstName, ResultSet::getString).getDate(s.createTime).to(Map.class))
                .ifPresent(System.out::println);

        JdbcUtil.prepareQuery(dataSource, "select id from user1").queryForBigInteger().ifPresent(Fn.println());
        JdbcUtil.prepareQuery(dataSource, "select id from user1").queryForBigDecimal().ifPresent(Fn.println());

        sql = PSC.deleteFrom(User.class).where(CF.eq(s.id)).sql();
        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, 100)
                .update();
    }
}
