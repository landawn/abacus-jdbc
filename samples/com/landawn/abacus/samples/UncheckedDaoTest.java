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

import static com.landawn.abacus.samples.JdbcTest.addressDao;
import static com.landawn.abacus.samples.JdbcTest.dataSource;
import static com.landawn.abacus.samples.JdbcTest.deviceDao;
import static com.landawn.abacus.samples.JdbcTest.employeeDao;
import static com.landawn.abacus.samples.JdbcTest.employeeProjectDao;
import static com.landawn.abacus.samples.JdbcTest.employeeProjectDao2;
import static com.landawn.abacus.samples.JdbcTest.noUpdateUserDao;
import static com.landawn.abacus.samples.JdbcTest.projectDao;
import static com.landawn.abacus.samples.JdbcTest.readOnlyUserDao;
import static com.landawn.abacus.samples.JdbcTest.uncheckedUserDao;
import static com.landawn.abacus.samples.JdbcTest.uncheckedUserDao2;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.condition.ConditionFactory.CF;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.JdbcContext;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.SQLTransaction;
import com.landawn.abacus.samples.entity.Address;
import com.landawn.abacus.samples.entity.Device;
import com.landawn.abacus.samples.entity.Employee;
import com.landawn.abacus.samples.entity.EmployeeProject;
import com.landawn.abacus.samples.entity.Project;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.Fn.Fnn;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Profiler;
import com.landawn.abacus.util.SQLParser;
import com.landawn.abacus.util.stream.IntStream;
import com.landawn.abacus.util.stream.LongStream;
import com.landawn.abacus.util.stream.Stream;

public class UncheckedDaoTest {

    @Test
    public void test_define() throws SQLException {

        final List<User> users = IntStream.range(1, 1000)
                .mapToObj(i -> User.builder().id(i).firstName("Forrest" + i).lastName("Gump" + i).nickName("Forrest").email("123@email.com" + i).build())
                .toList();

        final List<Long> ids = uncheckedUserDao.batchInsertWithId(users);
        assertEquals(users.size(), ids.size());

        assertNotNull(uncheckedUserDao.selectByIdWithDefine("user1", N.asList("last_name", "first_name"), ids.get(0)));
        assertNotNull(uncheckedUserDao.selectByIdWithDefine2("user1", N.asArray("last_name", "first_name"), ids.get(0)));
        assertEquals(ids.size(), uncheckedUserDao.selectByIdWithDefine_2("user1", "id", ids.get(0)).size());

        assertEquals(ids.size(), uncheckedUserDao.selectByIdWithDefine_3("user1", ids.get(0), "id", 1000000001, "xxxyyyyzzz").size());

        assertEquals(ids.size(), uncheckedUserDao.selectByIdWithDefine_4("user1", ids.get(0), "id", 1000000001, "xxxyyyyzzz").size());

        assertTrue(uncheckedUserDao.exists("user1", "last_name", ids.get(0)));
        assertTrue(uncheckedUserDao.isThere("user1", "last_name", ids.get(0)));

        assertEquals(1, uncheckedUserDao.deleteByIdWithDefine("user1", ids.get(0)));
        assertEquals(ids.size() - 1, uncheckedUserDao.deleteByIdsWithDefine("user1", ids));

        assertNull(uncheckedUserDao.selectByIdWithDefine("user1", N.asList("last_name", "first_name"), ids.get(0)));
        assertEquals(0, uncheckedUserDao.selectByIdWithDefine_2("user1", "id", ids.get(0)).size());

        assertFalse(uncheckedUserDao.exists("user1", "last_name", ids.get(0)));
        assertFalse(uncheckedUserDao.isThere("user1", "last_name", ids.get(0)));
    }

    //    @Test
    //    public void test_cacheSql() {
    //        String sql = NSC.selectFrom(User.class).where(CF.eq("id")).sql();
    //        uncheckedUserDao.cacheSql("selectById", sql);
    //
    //        assertEquals(sql, uncheckedUserDao.getCachedSql("selectById"));
    //
    //        uncheckedUserDao.cacheSqls("selectById", N.asList(sql));
    //        assertEquals(N.asList(sql), uncheckedUserDao.getCachedSqls("selectById"));
    //    }

    @Test
    public void test_orderBy() {
        JdbcContext.enableSqlLog();
        final User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        uncheckedUserDao.save(user, N.asList("id", "firstName", "lastName", "email"));

        User userFromDB = uncheckedUserDao.gett(100L);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        uncheckedUserDao.deleteById(100L);

        final long id = uncheckedUserDao.insert(user, N.asList("firstName", "lastName", "email"));
        userFromDB = uncheckedUserDao.gett(id);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        uncheckedUserDao.query(CF.criteria().groupBy("lastName").having(CF.ne("lastName", "aa")).orderBy("firstName")).println();
        uncheckedUserDao.deleteById(id);

        assertFalse(uncheckedUserDao.exists(id));
    }

    @Test
    public void test_cache() {
        final User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        uncheckedUserDao.insert(user, N.asList("id", "firstName", "lastName", "email"));

        final User userFromDB = uncheckedUserDao.gett(100L);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        Profiler.run(1, 10000, 1, () -> uncheckedUserDao.gett(100L)).printResult();

        uncheckedUserDao.delete(userFromDB);

        Profiler.run(1, 10000, 1, () -> uncheckedUserDao.gett(100L)).printResult();

        uncheckedUserDao.delete(userFromDB);
    }

    @Test
    public void test_handler() {
        final User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        uncheckedUserDao.insert(user, N.asList("id", "firstName", "lastName", "email"));

        final User userFromDB = uncheckedUserDao.gett(100L);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        try (SQLTransaction tran = JdbcContext.beginTransaction(dataSource)) {
            uncheckedUserDao.delete_propagation_SUPPORTS(userFromDB.getId());
        }

        assertTrue(uncheckedUserDao.exists(userFromDB.getId()));

        try (SQLTransaction tran = JdbcContext.beginTransaction(dataSource)) {
            uncheckedUserDao.delete_propagation_REQUIRES_NEW(userFromDB.getId());
        }

        assertFalse(uncheckedUserDao.exists(userFromDB.getId()));
    }

    @Test
    public void test_sql_log() {

        LongStream.range(100, 110).parallel(8).forEach(idx -> {
            synchronized (JdbcUtil.class) {
                if (idx % 2 == 0) {
                    System.out.println("###: enable log for Thread: " + Thread.currentThread());
                    JdbcContext.enableSqlLog();
                    JdbcContext.setMinExecutionTimeForSqlPerfLog(0);
                } else {
                    System.out.println("+++: Not enable log for Thread: " + Thread.currentThread());
                }

                final User user = User.builder().id(idx).firstName("Forrest").lastName("Gump").email("123@email.com").build();
                uncheckedUserDao.insert(user, N.asList("id", "firstName", "lastName", "email"));

                assertNotNull(uncheckedUserDao.gett(idx));

                uncheckedUserDao.deleteById(idx);

                if (idx % 2 == 0) {
                    System.out.println("###: disable log for Thread: " + Thread.currentThread());
                    JdbcContext.disableSqlLog();
                    JdbcContext.setMinExecutionTimeForSqlPerfLog(-1);
                }
            }
        });
    }

    @Test
    public void test_operation_log() {

        final User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        uncheckedUserDao.insert(user, N.asList("id", "firstName", "lastName", "email"));

        uncheckedUserDao.delete_propagation_SUPPORTS(100);
    }

    @Test
    public void test_propagation() {
        final User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        uncheckedUserDao.insert(user, N.asList("id", "firstName", "lastName", "email"));

        final User userFromDB = uncheckedUserDao.gett(100L);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        try (SQLTransaction tran = JdbcContext.beginTransaction(dataSource)) {
            uncheckedUserDao.delete_propagation_SUPPORTS(userFromDB.getId());
        }

        assertTrue(uncheckedUserDao.exists(userFromDB.getId()));

        try (SQLTransaction tran = JdbcContext.beginTransaction(dataSource)) {
            uncheckedUserDao.delete_propagation_REQUIRES_NEW(userFromDB.getId());
        }

        assertFalse(uncheckedUserDao.exists(userFromDB.getId()));
    }

    //    @Test
    //    public void test_includingJoinEntities()   {
    //
    //        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
    //        uncheckedUserDao.save(user, N.asList("id", "firstName", "lastName", "email"));
    //
    //        User userFromDB = uncheckedUserDao.gett(100L, true);
    //        System.out.println(userFromDB);
    //        assertNotNull(userFromDB);
    //
    //        uncheckedUserDao.deleteById(100L);
    //
    //        long id = uncheckedUserDao.insert(user, N.asList("firstName", "lastName", "email"));
    //        userFromDB = uncheckedUserDao.gett(id);
    //        System.out.println(userFromDB);
    //        assertNotNull(userFromDB);
    //
    //        uncheckedUserDao.delete(userFromDB, OnDeleteAction.CASCADE);
    //        uncheckedUserDao.delete(userFromDB, OnDeleteAction.NO_ACTION);
    //        uncheckedUserDao.batchDelete(N.asList(userFromDB), OnDeleteAction.CASCADE);
    //        uncheckedUserDao.batchDelete(N.asList(userFromDB), OnDeleteAction.NO_ACTION);
    //
    //        assertFalse(uncheckedUserDao.exists(id));
    //    }

    @Test
    public void test_batch() throws SQLException {

        final List<User> users = IntStream.range(1, 1000)
                .mapToObj(i -> User.builder().id(i).firstName("Forrest" + i).lastName("Gump" + i).nickName("Forrest").email("123@email.com" + i).build())
                .toList();

        List<Long> ids = uncheckedUserDao.batchInsertWithId(users);
        assertEquals(users.size(), ids.size());

        assertEquals(users.size(), uncheckedUserDao.batchUpdate(users));

        assertEquals(users.size(), uncheckedUserDao.batchDelete(users));

        ids = uncheckedUserDao.batchInsertWithoutId(users);
        assertEquals(users.size(), ids.size());

        users.forEach(user -> user.setFirstName("updated-" + user.getFirstName()));

        assertEquals(users.size(), uncheckedUserDao.batchUpdate(users));

        assertEquals(users.size(), uncheckedUserDao.batchDeleteByIds(ids));

        assertEquals(0, N.sum(uncheckedUserDao.batchDeleteByIds_2(ids)));
    }

    @Test
    public void test_save_insert() {
        final User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        uncheckedUserDao.save(user, N.asList("id", "firstName", "lastName", "email"));

        User userFromDB = uncheckedUserDao.gett(100L);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        uncheckedUserDao.deleteById(100L);

        final long id = uncheckedUserDao.insert(user, N.asList("firstName", "lastName", "email"));
        userFromDB = uncheckedUserDao.gett(id);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);
        uncheckedUserDao.deleteById(id);

        assertFalse(uncheckedUserDao.exists(id));
    }

    @Test
    public void test_save_insert_2() {
        final User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        uncheckedUserDao2.save(user, N.asList("id", "firstName", "lastName", "email"));

        User userFromDB = uncheckedUserDao2.gett(100L);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        uncheckedUserDao2.deleteById(100);

        final long id = uncheckedUserDao2.insert(user, N.asList("firstName", "lastName", "email"));
        userFromDB = uncheckedUserDao2.gett(id);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);
        uncheckedUserDao2.deleteById(id);

        assertFalse(uncheckedUserDao2.exists(id));

    }

    /**
     *
     *
     * @throws SQLException
     */
    @SuppressWarnings("deprecation")
    @Test
    public void test_readOnlyDao() throws SQLException {
        final User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();

        try {
            readOnlyUserDao.save(user);
            fail("Should throw UnsupportedOperationException");
        } catch (final UnsupportedOperationException e) {
            //
        } catch (final Exception e) {
            //
            e.printStackTrace();
        }

        try {
            readOnlyUserDao.batchSave(N.asList(user));
            fail("Should throw UnsupportedOperationException");
        } catch (final UnsupportedOperationException e) {
            //
        } catch (final Exception e) {
            //
            e.printStackTrace();
        }

        try {
            uncheckedUserDao2.deleteJoinEntities(user, Device.class);
            fail("Should throw UnsupportedOperationException");
        } catch (final UnsupportedOperationException e) {
            //
        }

        noUpdateUserDao.save(user);

        final User userFromDB = readOnlyUserDao.gett(100L);
        System.out.println(userFromDB);

        try {
            readOnlyUserDao.delete(user);
            fail("Should throw UnsupportedOperationException");
        } catch (final UnsupportedOperationException e) {
            //
        }

        try {
            noUpdateUserDao.delete(user);
            fail("Should throw UnsupportedOperationException");
        } catch (final UnsupportedOperationException e) {
            //
        }

        uncheckedUserDao.delete(user);
    }

    @Test
    public void test_batchGet() {
        final User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        uncheckedUserDao.insertWithId(user);

        final User userFromDB = uncheckedUserDao.gett(100L);
        System.out.println(userFromDB);

        for (int i = 0; i < 100; i++) {
            uncheckedUserDao.batchGet(N.repeat(100L, 1)).forEach(Fn.println());
            uncheckedUserDao.batchGet(N.repeat(100L, 99)).forEach(Fn.println());
            uncheckedUserDao.batchGet(N.repeat(100L, 199)).forEach(Fn.println());
            uncheckedUserDao.batchGet(N.repeat(100L, 299)).forEach(Fn.println());
            uncheckedUserDao.batchGet(N.repeat(100L, 399)).forEach(Fn.println());
            uncheckedUserDao.batchGet(N.repeat(100L, 999)).forEach(Fn.println());

            uncheckedUserDao.batchGet(N.repeat(100L, 1), N.asList("firstName", "lastName")).forEach(Fn.println());
            uncheckedUserDao.batchGet(N.repeat(100L, 99), N.asList("firstName", "lastName")).forEach(Fn.println());
            uncheckedUserDao.batchGet(N.repeat(100L, 199), N.asList("firstName", "lastName")).forEach(Fn.println());
            uncheckedUserDao.batchGet(N.repeat(100L, 299), N.asList("firstName", "lastName")).forEach(Fn.println());
            uncheckedUserDao.batchGet(N.repeat(100L, 399), N.asList("firstName", "lastName")).forEach(Fn.println());
            uncheckedUserDao.batchGet(N.repeat(100L, 999), N.asList("firstName", "lastName")).forEach(Fn.println());
        }

        uncheckedUserDao.updateFirstAndLastName("Tom", "Hanks", 100);

        uncheckedUserDao.allUsers().map(e -> e.getFirstName() + " " + e.getLastName()).forEach(Fn.println());

        uncheckedUserDao.deleteById(100L);
    }

    @Test
    public void test_batchDelete() {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        uncheckedUserDao.insertWithId(user);

        User userFromDB = uncheckedUserDao.gett(100L);
        System.out.println(userFromDB);

        assertEquals(1, uncheckedUserDao.batchDeleteByIds(N.repeat(100L, 1)));
        assertEquals(0, uncheckedUserDao.batchDeleteByIds(N.repeat(100L, 99)));
        assertEquals(0, uncheckedUserDao.batchDeleteByIds_1(N.repeat(100L, 199)));
        assertEquals(0, uncheckedUserDao.batchDeleteByIds(N.repeat(100L, 299)));
        assertEquals(0, uncheckedUserDao.batchDeleteByIds_1(N.repeat(100L, 399)));
        assertEquals(0, uncheckedUserDao.batchDeleteByIds(N.repeat(100L, 999)));

        user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        uncheckedUserDao.insertWithId(user);

        userFromDB = uncheckedUserDao.gett(100L);
        System.out.println(userFromDB);

        assertEquals(1, uncheckedUserDao.batchDelete(N.repeat(userFromDB, 1)));
        assertEquals(0, uncheckedUserDao.batchDelete(N.repeat(userFromDB, 99)));
        assertEquals(0, uncheckedUserDao.batchDelete(N.repeat(userFromDB, 199)));
        assertEquals(0, uncheckedUserDao.batchDelete(N.repeat(userFromDB, 299)));
        assertEquals(0, uncheckedUserDao.batchDelete(N.repeat(userFromDB, 399)));
        assertEquals(0, uncheckedUserDao.batchDelete(N.repeat(userFromDB, 999)));

        uncheckedUserDao.allUsers().map(e -> e.getFirstName() + " " + e.getLastName()).forEach(Fn.println());

        uncheckedUserDao.deleteById(100L);
    }

    @Test
    public void test_findFirst() {
        final User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        uncheckedUserDao.insertWithId(user);

        final User userFromDB = uncheckedUserDao.gett(100L);
        System.out.println(userFromDB);

        for (int i = 0; i < 1000; i++) {
            uncheckedUserDao.findFirst(CF.eq("firstName", "Forrest")).ifPresent(Fn.println());

            uncheckedUserDao.findFirst(CF.eq("firstName", "Forrest"), rs -> rs.getString("firstName")).ifPresent(Fn.println());

            uncheckedUserDao.findFirst(CF.eq("firstName", "Forrest"), (rs, cnl) -> rs.getString("firstName")).ifPresent(Fn.println());

            uncheckedUserDao.findFirst(N.asList("firstName", "lastName"), CF.eq("firstName", "Forrest")).ifPresent(Fn.println());

            uncheckedUserDao.findFirst(N.asList("firstName", "lastName"), CF.eq("firstName", "Forrest"), rs -> rs.getString(1)).ifPresent(Fn.println());

            uncheckedUserDao.findFirst(N.asList("firstName", "lastName"), CF.eq("firstName", "Forrest"), (rs, cnl) -> rs.getString(1)).ifPresent(Fn.println());
        }

        uncheckedUserDao.updateFirstAndLastName("Tom", "Hanks", 100);

        uncheckedUserDao.allUsers().map(e -> e.getFirstName() + " " + e.getLastName()).forEach(Fn.println());

        uncheckedUserDao.deleteById(100L);
    }

    @Test
    public void test_list() {
        final User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        uncheckedUserDao.insertWithId(user);

        final User userFromDB = uncheckedUserDao.gett(100L);
        System.out.println(userFromDB);

        for (int i = 0; i < 1000; i++) {
            uncheckedUserDao.list(CF.eq("firstName", "Forrest")).forEach(Fn.println());

            uncheckedUserDao.list(CF.eq("firstName", "Forrest"), rs -> rs.getString("firstName")).forEach(Fn.println());

            uncheckedUserDao.list(CF.eq("firstName", "Forrest"), (rs, cnl) -> rs.getString("firstName")).forEach(Fn.println());

            uncheckedUserDao.list(N.asList("firstName", "lastName"), CF.eq("firstName", "Forrest")).forEach(Fn.println());

            uncheckedUserDao.list(N.asList("firstName", "lastName"), CF.eq("firstName", "Forrest"), rs -> rs.getString(1)).forEach(Fn.println());

            uncheckedUserDao.list(N.asList("firstName", "lastName"), CF.eq("firstName", "Forrest"), (rs, cnl) -> rs.getString(1)).forEach(Fn.println());

            uncheckedUserDao.list("firstName", CF.eq("firstName", "Forrest")).forEach(Fn.println());

            uncheckedUserDao.stream("firstName", CF.alwaysTrue()).forEach(Fn.println());
        }

        uncheckedUserDao.listUserByAnnoSql(0).forEach(Fn.println());

        assertEquals(1, uncheckedUserDao.listUserByAnnoSql(0).size());

        assertEquals(1, uncheckedUserDao.listUserByAnnoSql2("newFirstName", 0).size());

        uncheckedUserDao.updateFirstAndLastName("Tom", "Hanks", 100);

        uncheckedUserDao.allUsers().map(e -> e.getFirstName() + " " + e.getLastName()).forEach(Fn.println());

        uncheckedUserDao.deleteById(100L);
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_stream() throws SQLException {
        final User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        uncheckedUserDao.insertWithId(user);

        user.setId(101);
        uncheckedUserDao.insertWithId(user);

        final User userFromDB = uncheckedUserDao.gett(100L);
        System.out.println(userFromDB);

        for (int i = 0; i < 1000; i++) {
            uncheckedUserDao.stream(CF.eq("firstName", "Forrest")).forEach(Fnn.println());

            uncheckedUserDao.stream(CF.eq("firstName", "Forrest"), rs -> rs.getString("firstName")).forEach(Fnn.println());

            uncheckedUserDao.stream(CF.eq("firstName", "Forrest"), (rs, cnl) -> rs.getString("firstName")).forEach(Fnn.println());

            uncheckedUserDao.stream(N.asList("firstName", "lastName"), CF.eq("firstName", "Forrest")).forEach(Fnn.println());

            uncheckedUserDao.stream(N.asList("firstName", "lastName"), CF.eq("firstName", "Forrest"), rs -> rs.getString(1)).forEach(Fnn.println());

            uncheckedUserDao.stream(N.asList("firstName", "lastName"), CF.eq("firstName", "Forrest"), (rs, cnl) -> rs.getString(1)).forEach(Fnn.println());
        }

        uncheckedUserDao.list(CF.gt("id", 0), rs -> rs.getString(1) != null, Jdbc.RowMapper.builder().get(1, ResultSet::getString).toList())
                .forEach(Fn.println());

        uncheckedUserDao
                .list(CF.gt("id", 0), (rs, cnl) -> rs.getString(1) != null, Jdbc.BiRowMapper.builder().get("firstName", ResultSet::getString).to(List.class))
                .forEach(Fn.println());

        uncheckedUserDao.list(CF.gt("id", 0), (rs, cnl) -> rs.getString(1) != null, Jdbc.BiRowMapper.builder().getString("firstName").to(LinkedHashMap.class))
                .forEach(Fn.println());

        uncheckedUserDao
                .list(CF.gt("id", 0), (rs, cnl) -> rs.getString(1) != null, Jdbc.BiRowMapper.builder().get("firstName", ResultSet::getString).to(User.class))
                .forEach(Fn.println());

        uncheckedUserDao.list(CF.gt("id", 0), (rs, cnl) -> rs.getString(1) != null, Jdbc.BiRowMapper.to(User.class)).forEach(Fn.println());

        uncheckedUserDao.updateFirstAndLastName("Tom", "Hanks", 100);

        uncheckedUserDao.allUsers().map(e -> e.getFirstName() + " " + e.getLastName()).forEach(Fn.println());

        uncheckedUserDao.deleteById(100L);

        assertEquals(1, JdbcUtil.executeUpdate(dataSource, "delete from user1 where id = ? ", 101));
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    @SuppressWarnings("deprecation")
    public void crud_joinedBy() throws SQLException {
        N.println(uncheckedUserDao.targetEntityClass());
        N.println(uncheckedUserDao.targetDaoInterface());
        N.println(uncheckedUserDao.executor());

        final User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        uncheckedUserDao.insertWithId(user);

        User userFromDB = uncheckedUserDao.gett(100L);
        System.out.println(userFromDB);

        final Device device = Device.builder().userId(userFromDB.getId()).manufacture("Apple").model("iPhone 11").build();
        deviceDao.insert(device);

        final Address address = Address.builder().userId(userFromDB.getId()).street("infinite loop 1").city("Cupertino").build();
        addressDao.insert(address);

        N.copy(userFromDB);
        uncheckedUserDao.loadAllJoinEntities(userFromDB);
        System.out.println(userFromDB);

        userFromDB = uncheckedUserDao.gett(100L);
        N.copy(userFromDB);
        uncheckedUserDao.loadJoinEntitiesIfNull(userFromDB);
        System.out.println(userFromDB);

        userFromDB = uncheckedUserDao.gett(100L);
        N.copy(userFromDB);
        uncheckedUserDao.loadJoinEntities(userFromDB, Device.class);
        System.out.println(userFromDB);

        userFromDB = uncheckedUserDao.gett(100L);
        N.copy(userFromDB);
        uncheckedUserDao.loadJoinEntitiesIfNull(userFromDB, Address.class);
        System.out.println(userFromDB);

        userFromDB = uncheckedUserDao.gett(100L);
        N.copy(userFromDB);
        uncheckedUserDao.loadAllJoinEntities(userFromDB, true);
        System.out.println(userFromDB);

        uncheckedUserDao.deleteJoinEntities(userFromDB, Address.class);
        uncheckedUserDao.deleteJoinEntities(N.asList(userFromDB, userFromDB, userFromDB), Device.class);

        uncheckedUserDao.deleteById(100L);
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void crud_joinedBy_2() throws SQLException {
        final List<User> users = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            final User user = User.builder().id(100 + i).firstName("Forrest").lastName("Gump").email("123@email.com").build();
            uncheckedUserDao.insertWithId(user);

            final User userFromDB = uncheckedUserDao.gett(100L + i);
            System.out.println(userFromDB);
            users.add(userFromDB);

            final Device device = Device.builder().userId(userFromDB.getId()).manufacture("Apple").model("iPhone 11").build();
            deviceDao.insert(device);

            final Address address = Address.builder().userId(userFromDB.getId()).street("infinite loop 1").city("Cupertino").build();
            addressDao.insert(address);
        }

        List<User> users2 = Stream.of(users).map(N::copy).toList();
        List<User> users3 = Stream.of(users).map(N::copy).toList();

        uncheckedUserDao.loadAllJoinEntities(users2);
        System.out.println(users2);

        users2 = Stream.of(users).map(N::copy).toList();
        users3 = Stream.of(users).map(N::copy).toList();

        uncheckedUserDao.loadJoinEntitiesIfNull(users2);
        System.out.println(users2);

        users2 = Stream.of(users).map(N::copy).toList();
        users3 = Stream.of(users).map(N::copy).toList();

        uncheckedUserDao.loadJoinEntities(users2, Device.class);
        System.out.println(users2);

        users2 = Stream.of(users).map(N::copy).toList();
        users3 = Stream.of(users).map(N::copy).toList();

        uncheckedUserDao.loadJoinEntitiesIfNull(users2, Address.class);
        System.out.println(users2);

        users2 = Stream.of(users).map(N::copy).toList();
        users3 = Stream.of(users).map(N::copy).toList();

        uncheckedUserDao.loadAllJoinEntities(users2, true);
        System.out.println(users2);

        uncheckedUserDao.deleteJoinEntities(users3, Address.class);
        uncheckedUserDao.deleteJoinEntities(users, Device.class);

        uncheckedUserDao.batchDelete(users);
    }

    @Test
    public void test_SQLParser() {
        final String sql = "SELECT employee_id AS \"employeeId\", first_name AS \"firstName\", last_name AS \"lastName\" FROM employee WHERE 1 < 2";
        SQLParser.parse(sql).forEach(Fn.println());
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void crud_many_to_many() throws SQLException {

        Employee employee = Employee.builder().employeeId(100).firstName("Forrest").lastName("Gump").build();
        employeeDao.insert(employee);

        final Employee employeeFromDB = employeeDao.gett(employee.getEmployeeId());
        employeeDao.loadAllJoinEntities(employeeFromDB);
        System.out.println(employeeFromDB);

        Project project = Project.builder().projectId(1000).title("Project X").build();
        projectDao.insert(project);

        final Project projectFromDB = projectDao.gett(project.getProjectId());
        projectDao.loadAllJoinEntities(projectFromDB);
        System.out.println(projectFromDB);

        EmployeeProject employeeProject = EmployeeProject.builder().employeeId(employeeFromDB.getEmployeeId()).projectId(projectFromDB.getProjectId()).build();
        EntityId entityId = employeeProjectDao.insert(employeeProject);
        N.println(entityId);

        employeeDao.loadAllJoinEntities(employeeFromDB);
        System.out.println(employeeFromDB);

        employeeDao.loadJoinEntities(employeeFromDB, Project.class, N.asList("title"));
        System.out.println(employeeFromDB);

        projectDao.loadAllJoinEntities(projectFromDB);
        System.out.println(projectFromDB);

        projectDao.loadJoinEntities(projectFromDB, Employee.class, N.asList("firstName"));
        System.out.println(projectFromDB);

        employee = Employee.builder().employeeId(101).firstName("Forrest").lastName("Gump").build();
        employeeDao.insert(employee);

        project = Project.builder().projectId(1001).title("Project X").build();
        projectDao.insert(project);

        employeeProject = EmployeeProject.builder().employeeId(employee.getEmployeeId()).projectId(project.getProjectId()).build();
        entityId = employeeProjectDao.insert(employeeProject);
        N.println(entityId);

        employeeProject = EmployeeProject.builder().employeeId(100).projectId(project.getProjectId()).build();
        final EmployeeProject entityId2 = employeeProjectDao2.insert(employeeProject);
        N.println(entityId2);

        final List<Employee> employees = employeeDao.list(CF.alwaysTrue());
        employeeDao.loadAllJoinEntities(employees);
        System.out.println(employees);

        employeeDao.loadJoinEntities(employees, Project.class, N.asList("title"));
        System.out.println(employees);

        final List<Project> projects = projectDao.list(CF.alwaysTrue());
        projectDao.loadAllJoinEntities(projects);
        System.out.println(projects);

        projectDao.loadJoinEntities(projects, Employee.class, N.asList("firstName"));
        System.out.println(projects);

        assertTrue(employeeProjectDao.exists(entityId));
        assertNotNull(employeeProjectDao.gett(entityId));

        assertTrue(employeeProjectDao2.exists(entityId2));
        assertNotNull(employeeProjectDao2.gett(entityId2));

        employeeDao.delete(CF.alwaysTrue());
        projectDao.delete(CF.alwaysTrue());
        employeeProjectDao.deleteById(entityId);
        employeeProjectDao2.deleteById(entityId2);

        assertFalse(employeeProjectDao.exists(entityId));
        assertNull(employeeProjectDao.gett(entityId));
    }
}
