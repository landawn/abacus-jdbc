package com.landawn.abacus.samples;

import static com.landawn.abacus.samples.JdbcTest.addressDao;
import static com.landawn.abacus.samples.JdbcTest.dataSource;
import static com.landawn.abacus.samples.JdbcTest.deviceDao;
import static com.landawn.abacus.samples.JdbcTest.employeeDao;
import static com.landawn.abacus.samples.JdbcTest.employeeProjectDao;
import static com.landawn.abacus.samples.JdbcTest.employeeProjectDao2;
import static com.landawn.abacus.samples.JdbcTest.myUserDaoA;
import static com.landawn.abacus.samples.JdbcTest.noUpdateUserDao;
import static com.landawn.abacus.samples.JdbcTest.projectDao;
import static com.landawn.abacus.samples.JdbcTest.readOnlyUserDao;
import static com.landawn.abacus.samples.JdbcTest.userDao;
import static com.landawn.abacus.samples.JdbcTest.userDao2;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.condition.ConditionFactory.CB;
import com.landawn.abacus.condition.ConditionFactory.CF;
import com.landawn.abacus.jdbc.Jdbc;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.JdbcUtils;
import com.landawn.abacus.jdbc.SQLTransaction;
import com.landawn.abacus.samples.entity.Address;
import com.landawn.abacus.samples.entity.Device;
import com.landawn.abacus.samples.entity.Employee;
import com.landawn.abacus.samples.entity.EmployeeProject;
import com.landawn.abacus.samples.entity.ImmutableUser;
import com.landawn.abacus.samples.entity.Project;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.Array;
import com.landawn.abacus.util.DateUtil;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.Fn.Fnn;
import com.landawn.abacus.util.IOUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Profiler;
import com.landawn.abacus.util.SQLBuilder.PSC;
import com.landawn.abacus.util.SQLParser;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.stream.IntStream;
import com.landawn.abacus.util.stream.LongStream;
import com.landawn.abacus.util.stream.Stream;

public class DaoTest {

    /**
     *
     *
     * @throws Exception
     */
    @Test
    public void test_paginate() throws Exception {

        List<User> users = IntStream.range(0, 20)
                .mapToObj(i -> User.builder().id(i).firstName("Forrest" + i).lastName("Gump" + i).nickName("Forrest").email("123@email.com" + i).build())
                .toList();

        userDao.batchInsertWithId(users);

        userDao.paginate(CB.where(CF.gt("id", 0)).orderBy("id"), 9, (q, r) -> {
            if (r == null) {
                q.setLong(1, -1);
            } else {
                q.setLong(1, (Long) N.lastOrNullIfEmpty(r.getColumn("id")));
            }
        }).forEach(N::println);

        userDao.paginate(CB.where(CF.gt("id", 0).and(CF.ne("firstName", "aaaa"))).orderBy("id"), 9, (q, r) -> {
            if (r == null) {
                q.setLong(1, -1);
            } else {
                q.setLong(1, (Long) N.lastOrNullIfEmpty(r.getColumn("id")));
            }
        }).forEach(N::println);

        userDao.paginate(CF.expr("id > ? and firstName != 'aaaa' order by id"), 9, (q, r) -> {
            if (r == null) {
                q.setLong(1, -1);
            } else {
                q.setLong(1, (Long) N.lastOrNullIfEmpty(r.getColumn("id")));
            }
        }).forEach(N::println);

        userDao.delete(CF.ge("id", 0));
    }

    /**
     *
     *
     * @throws Exception
     */
    @Test
    public void test_setStringForMultiPositions() throws Exception {

    }

    //    @Test
    //    public void test_match() throws SQLException {
    //        final List<User> users = new ArrayList<>();
    //
    //        for (int i = 0; i < 789; i++) {
    //            users.add(User.builder().id(100 + i).firstName("Forrest").lastName("Gump").email("123@email.com").build());
    //        }
    //
    //        List<Long> ids = userDao.batchInsert(users);
    //
    //        assertTrue(userDao.anyMatch(N.asList("id"), CF.ge("id", 10), rs -> rs.getLong(1) > 10));
    //        assertFalse(userDao.anyMatch(N.asList("id"), CF.ge("id", 10), rs -> rs.getLong(1) < 10));
    //
    //        assertTrue(userDao.allMatch(N.asList("id"), CF.ge("id", 10), rs -> rs.getLong(1) > 10));
    //        assertFalse(userDao.allMatch(N.asList("id"), CF.ge("id", 10), rs -> rs.getLong(1) < 200));
    //
    //        assertTrue(userDao.noneMatch(N.asList("id"), CF.ge("id", 10), rs -> rs.getLong(1) > 10000));
    //        assertTrue(userDao.noneMatch(N.asList("id"), CF.ge("id", 10), rs -> rs.getLong(1) < 10));
    //
    //        assertEquals(789, userDao.batchDeleteByIds(ids));
    //
    //        assertEquals(0, userDao.count(ids));
    //    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_distinct() throws SQLException {
        final List<User> users = new ArrayList<>();

        for (int i = 0; i < 37; i++) {
            users.add(User.builder().id(100 + i).firstName("Forrest").lastName("Gump").email("123@email.com").build());
        }

        List<Long> ids = userDao.batchInsert(users);

        assertEquals(users.size(), userDao.count(ids));
        assertEquals(users.size(), userDao.list(CF.criteria().distinct()).size());

        assertEquals(users.size(), userDao.list(CF.criteria().where(CF.notEqual("firstName", "aaaaaa")).orderBy("firstName", "lastName").distinct()).size());

        assertEquals(users.size(),
                userDao.prepareNamedQueryForBigResult(CF.criteria().where(CF.notEqual("firstName", "aaaaaa")).orderBy("firstName", "lastName").distinct())
                        .list()
                        .size());

        assertEquals(users.size(), userDao.batchDelete(users));

        assertEquals(0, userDao.batchDeleteByIds(ids));

        assertEquals(0, userDao.count(ids));
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_count() throws SQLException {
        final List<User> users = new ArrayList<>();

        for (int i = 0; i < 789; i++) {
            users.add(User.builder().id(100 + i).firstName("Forrest").lastName("Gump").email("123@email.com").build());
        }

        List<Long> ids = userDao.batchInsert(users);

        assertEquals(users.size(), userDao.count(ids));
        assertEquals(users.size(), userDao.count(N.repeatCollectionToSize(ids, ids.size() * 3)));

        assertEquals(users.size(), userDao.batchDelete(users));

        assertEquals(0, userDao.batchDeleteByIds(ids));

        assertEquals(0, userDao.count(ids));
    }

    //    @Test
    //    public void test_preparedQuery() throws Exception {
    //
    //        List<User> users = IntStream.range(1, 1000)
    //                .mapToObj(i -> User.builder().id(i).firstName("Forrest" + i).lastName("Gump" + i).nickName("Forrest").email("123@email.com" + i).build())
    //                .toList();
    //
    //        userDao.batchInsertWithId(users);
    //
    //        List<User> dbUsers = userDao.prepareQueryForBigResult(CF.ge("id", users.get(0).getId()))
    //                .stream(User.class)
    //                .onEach(it -> it.setCreateTime(null))
    //                .sortedBy(it -> it.getId())
    //                .toList();
    //
    //        assertEquals(users.get(0), dbUsers.get(0));
    //        assertTrue(N.equals(users, dbUsers));
    //
    //        dbUsers = userDao.prepareNamedQueryForBigResult(CF.ge("id", users.get(0).getId()))
    //                .stream(User.class)
    //                .onEach(it -> it.setCreateTime(null))
    //                .sortedBy(it -> it.getId())
    //                .toList();
    //
    //        assertEquals(users.get(0), dbUsers.get(0));
    //        assertTrue(N.equals(users, dbUsers));
    //
    //        PSC.deleteFrom(User.class).where(CF.ge("id", users.get(0).getId())).toPreparedQuery(userDao.dataSource()).update();
    //        NSC.deleteFrom(User.class).where(CF.ge("id", users.get(0).getId())).toNamedQuery(userDao.dataSource()).update();
    //        NSC.deleteFrom(User.class).where(CF.ge("id", users.get(0).getId())).toNamedQuery(userDao.dataSource()).setIntForMultiPositions(0, 1).update();
    //
    //        NSC.deleteFrom(User.class)
    //                .where(CF.ge("id", users.get(0).getId()))
    //                .accept(sp -> JdbcUtil.executeUpdate(userDao.dataSource(), sp.sql, sp.parameters.toArray()));
    //    }

    /**
     *
     *
     * @throws Exception
     */
    @Test
    public void test_parallel() throws Exception {

        List<User> users = IntStream.range(1, 1000)
                .mapToObj(i -> User.builder().id(i).firstName("Forrest" + i).lastName("Gump" + i).nickName("Forrest").email("123@email.com" + i).build())
                .toList();

        userDao.batchInsertWithId(users);

        List<User> dbUsers = LongStream.range(1, 1000)
                .boxed()
                .parallel()
                .map(Fn.ff(it -> userDao.gett(it)))
                .onEach(it -> it.setCreateTime(null))
                .sortedBy(User::getId)
                .toList();

        assertEquals(users.get(0), dbUsers.get(0));
        assertTrue(N.equals(users, dbUsers));

        userDao.delete(CF.alwaysTrue());
    }

    /**
     *
     *
     * @throws Exception
     */
    @Test
    public void test_batchUpsert() throws Exception {

        List<User> users = IntStream.range(1, 1000)
                .mapToObj(i -> User.builder().id(i).firstName("Forrest" + i).lastName("Gump" + i).nickName("Forrest").email("123@email.com" + i).build())
                .toList();

        userDao.batchInsertWithId(users.subList(0, 499));

        users.forEach(it -> it.setFirstName(Strings.uuid().substring(0, 32)));

        userDao.batchUpsert(users);

        final List<User> dbUsers = userDao.list(CF.gt("id", 0));

        // assertEquals(users.size(), StreamEx.of(users).innerJoin(dbUsers, it -> it.getFirstName(), Pair::of).count());

        dbUsers.forEach(Fn.println());

        userDao.batchDelete(dbUsers);
    }

    /**
     *
     *
     * @throws Exception
     */
    @Test
    public void test_exportCSV() throws Exception {
        List<User> users = IntStream.range(1, 30)
                .mapToObj(i -> User.builder().id(i).firstName("Forrest" + i).lastName("Gump" + i).nickName("Forrest").email("123@email.com" + i).build())
                .toList();

        userDao.batchInsertWithId(users);

        try (Connection conn = JdbcTest.dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement("select * from user1");
                ResultSet rs = stmt.executeQuery()) {
            JdbcUtils.exportCSV(System.out, rs);
        }

        N.println(IOUtil.LINE_SEPARATOR);
        N.println(Strings.repeat("=", 80));

        try (Connection conn = JdbcTest.dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement("select * from user1");
                ResultSet rs = stmt.executeQuery()) {
            JdbcUtils.exportCSV(System.out, rs, 0, 10, true, false);
        }

        userDao.batchDelete(users);
    }

    /**
     *
     *
     * @throws Exception
     */
    @Test
    public void test_refresh() throws Exception {

        List<User> users = IntStream.range(1, 1000)
                .mapToObj(i -> User.builder().id(i).firstName("Forrest" + i).lastName("Gump" + i).nickName("Forrest").email("123@email.com" + i).build())
                .toList();

        List<Long> ids = userDao.batchInsertWithId(users);
        assertEquals(users.size(), ids.size());

        List<User> dbUsers = userDao.batchGet(ids).stream().map(N::copy).collect(Collectors.toList());

        dbUsers.forEach(it -> it.setFirstName(Strings.uuid()));

        userDao.batchRefresh(dbUsers, N.asList("lastName"));
        assertFalse(N.equals(userDao.batchGet(ids), dbUsers));

        userDao.batchRefresh(dbUsers, N.asList("firstName"));
        assertTrue(N.equals(userDao.batchGet(ids), dbUsers));

        dbUsers.forEach(it -> it.setFirstName(Strings.uuid()));

        userDao.batchRefresh(dbUsers);
        assertTrue(N.equals(userDao.batchGet(ids), dbUsers));

        userDao.batchDelete(dbUsers);
    }

    /**
     *
     *
     * @throws Exception
     */
    @Test
    public void test_define() throws Exception {

        List<User> users = IntStream.range(1, 1000)
                .mapToObj(i -> User.builder().id(i).firstName("Forrest" + i).lastName("Gump" + i).nickName("Forrest").email("123@email.com" + i).build())
                .toList();

        List<Long> ids = userDao.batchInsertWithId(users);
        assertEquals(users.size(), ids.size());

        assertNotNull(userDao.selectByIdWithDefine("user1", "last_name", ids.get(0)));
        assertEquals(ids.size(), userDao.selectByIdWithDefine_2("user1", "id", ids.get(0)).size());

        assertEquals(ids.size(), userDao.selectByIdWithDefine_3("user1", ids.get(0), "id", 1000000001, "xxxyyyyzzz").size());

        assertEquals(ids.size(), userDao.selectByIdWithDefine_4("user1", ids.get(0), "id", 1000000001, "xxxyyyyzzz").size());

        assertTrue(userDao.exists("user1", "last_name", ids.get(0)));
        assertTrue(userDao.isThere("user1", "last_name", ids.get(0)));

        assertEquals(1, userDao.deleteByIdWithDefine("user1", ids.get(0)));
        assertEquals(ids.size() - 1, userDao.deleteByIdsWithDefine("user1", ids));

        assertNull(userDao.selectByIdWithDefine("user1", "last_name", ids.get(0)));
        assertEquals(0, userDao.selectByIdWithDefine_2("user1", "id", ids.get(0)).size());

        assertFalse(userDao.exists("user1", "last_name", ids.get(0)));
        assertFalse(userDao.isThere("user1", "last_name", ids.get(0)));
    }

    /**
     *
     *
     * @throws Exception
     */
    @Test
    public void test_BindList() throws Exception {

        List<User> users = IntStream.range(1, 1000)
                .mapToObj(i -> User.builder().id(i).firstName("Forrest" + i).lastName("Gump" + i).nickName("Forrest").email("123@email.com" + i).build())
                .toList();

        List<Long> ids = userDao.batchInsertWithId(users);
        assertEquals(users.size(), ids.size());

        int[] intIds = Stream.of(ids).mapToInt(Long::intValue).toArray();
        long[] longIds = N.toLongArray(ids);
        assertEquals(ids.size(), userDao.listByIds(intIds).size());

        assertEquals(ids.size(), userDao.listByIds_01("xxx", longIds, "xxx").size());

        assertEquals(ids.size(), userDao.listByIds_02(Array.box(longIds), "xxx").size());

        assertEquals(ids.size(), userDao.listByIds_03(ids, N.asList("xxx")).size());

        assertEquals(ids.size(), userDao.listByIds_04(ids, N.asList("xxx")).size());

        assertEquals(ids.size(), userDao.listByIds_04(N.emptyList(), N.asList("xxx")).size());

        assertEquals(1, userDao.deleteByIdWithDefine("user1", ids.get(0)));
        assertEquals(ids.size() - 1, userDao.deleteByIdsWithDefine("user1", ids));

        userDao.delete(CB.where(CF.ge("id", 0)).limit(10000));
    }

    //    @Test
    //    public void test_cacheSql() throws SQLException {
    //        String sql = NSC.selectFrom(User.class).where(CF.eq("id")).sql();
    //        userDao.cacheSql("selectById", sql);
    //
    //        assertEquals(sql, userDao.getCachedSql("selectById"));
    //
    //        userDao.cacheSqls("selectById", N.asList(sql));
    //        assertEquals(N.asList(sql), userDao.getCachedSqls("selectById"));
    //    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_orderBy() throws SQLException {
        JdbcUtil.enableSqlLog();
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.save(user, N.asList("id", "firstName", "lastName", "email"));

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        userDao.deleteById(100L);

        long id = userDao.insert(user, N.asList("firstName", "lastName", "email"));
        userFromDB = userDao.gett(id);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        userDao.query(CF.criteria().groupBy("lastName").having(CF.ne("lastName", "aa")).orderBy("firstName")).println();
        userDao.deleteById(id);

        assertFalse(userDao.exists(id));
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_cache() throws SQLException {
        User user = User.builder().firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insert(user, N.asList("id", "firstName", "lastName", "email"));

        long id = user.getId();
        User userFromDB = noUpdateUserDao.gett(id);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        Profiler.run(1, 10000, 1, () -> noUpdateUserDao.gett(id)).printResult();

        userDao.delete(userFromDB);

        Profiler.run(1, 10000, 1, () -> noUpdateUserDao.gett(id)).printResult();

        userDao.delete(userFromDB);
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_handler() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insert(user, N.asList("id", "firstName", "lastName", "email"));

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        try (SQLTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
            userDao.delete_propagation_SUPPORTS(userFromDB.getId());
        }

        assertTrue(userDao.exists(userFromDB.getId()));

        try (SQLTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
            userDao.delete_propagation_REQUIRES_NEW(userFromDB.getId());
        }

        assertFalse(userDao.exists(userFromDB.getId()));
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_sql_log() throws SQLException {

        LongStream.range(100, 110).parallel(8).forEach(idx -> {
            synchronized (JdbcUtil.class) {
                if (idx % 2 == 0) {
                    System.out.println("###: enable log for Thread: " + Thread.currentThread());
                    JdbcUtil.enableSqlLog();
                    JdbcUtil.setMinExecutionTimeForSqlPerfLog(0);
                } else {
                    System.out.println("+++: Not enable log for Thread: " + Thread.currentThread());
                }

                User user = User.builder().id(idx).firstName("Forrest").lastName("Gump").email("123@email.com").build();
                userDao.insert(user, N.asList("id", "firstName", "lastName", "email"));

                assertNotNull(userDao.gett(idx));

                userDao.deleteById(idx);

                if (idx % 2 == 0) {
                    System.out.println("###: disable log for Thread: " + Thread.currentThread());
                    JdbcUtil.disableSqlLog();
                    JdbcUtil.setMinExecutionTimeForSqlPerfLog(-1);
                }
            }
        });
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_operation_log() throws SQLException {

        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insert(user, N.asList("id", "firstName", "lastName", "email"));

        userDao.delete_propagation_SUPPORTS(100);
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_propagation() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insert(user, N.asList("id", "firstName", "lastName", "email"));

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        try (SQLTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
            userDao.delete_propagation_SUPPORTS(userFromDB.getId());
        }

        assertTrue(userDao.exists(userFromDB.getId()));

        try (SQLTransaction tran = JdbcUtil.beginTransaction(dataSource)) {
            userDao.delete_propagation_REQUIRES_NEW(userFromDB.getId());
        }

        assertFalse(userDao.exists(userFromDB.getId()));
    }

    //    @Test
    //    public void test_includingJoinEntities() throws SQLException {
    //
    //        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
    //        userDao.save(user, N.asList("id", "firstName", "lastName", "email"));
    //
    //        User userFromDB = userDao.gett(100L, true);
    //        System.out.println(userFromDB);
    //        assertNotNull(userFromDB);
    //
    //        userDao.deleteById(100L);
    //
    //        long id = userDao.insert(user, N.asList("firstName", "lastName", "email"));
    //        userFromDB = userDao.gett(id);
    //        System.out.println(userFromDB);
    //        assertNotNull(userFromDB);
    //
    //        userDao.delete(userFromDB, OnDeleteAction.CASCADE);
    //        userDao.delete(userFromDB, OnDeleteAction.NO_ACTION);
    //        userDao.batchDelete(N.asList(userFromDB), OnDeleteAction.CASCADE);
    //        userDao.batchDelete(N.asList(userFromDB), OnDeleteAction.NO_ACTION);
    //
    //        assertFalse(userDao.exists(id));
    //    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_batch() throws SQLException {

        List<User> users = IntStream.range(1, 1000)
                .mapToObj(i -> User.builder().id(i).firstName("Forrest" + i).lastName("Gump" + i).nickName("Forrest").email("123@email.com" + i).build())
                .toList();

        List<Long> ids = userDao.batchInsertWithId(users);
        assertEquals(users.size(), ids.size());

        assertEquals(users.size(), userDao.batchUpdate(users));

        assertEquals(users.size(), userDao.batchDelete(users));

        ids = userDao.batchInsertWithoutId(users);
        assertEquals(users.size(), ids.size());

        users.forEach(user -> user.setFirstName("updated-" + user.getFirstName()));

        assertEquals(users.size(), userDao.batchUpdate(users));

        assertEquals(users.size(), userDao.batchDeleteByIds(ids));

        assertEquals(0, N.sum(userDao.batchDeleteByIds_2(ids)));
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_save_insert() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.save(user, N.asList("id", "firstName", "lastName", "email"));

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        userDao.findFirst(CF.eq("id", 100L), Jdbc.BiRowMapper.TO_MAP).ifPresent(Fn.println());

        userDao.findFirst(CF.eq("id", 100L), Jdbc.BiRowMapper.toMap(Fn.toUpperCaseWithUnderscore())).ifPresent(Fn.println());

        userDao.deleteById(100L);

        long id = userDao.insert(user, N.asList("firstName", "lastName", "email"));
        userFromDB = userDao.gett(id);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);
        userDao.deleteById(id);

        assertFalse(userDao.exists(id));
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_save_insert_2() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao2.save(user, N.asList("id", "firstName", "lastName", "email"));

        User userFromDB = userDao2.gett(100L);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        userDao2.deleteById(100);

        long id = userDao2.insert(user, N.asList("firstName", "lastName", "email"));
        userFromDB = userDao2.gett(id);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);
        userDao2.deleteById(id);

        assertFalse(userDao2.exists(id));
    }

    /**
     *
     *
     * @throws SQLException
     */
    @SuppressWarnings("deprecation")
    @Test
    public void test_readOnlyDao() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();

        try {
            readOnlyUserDao.save(user);
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            //
        } catch (Exception e) {
            //
            e.printStackTrace();
        }

        try {
            readOnlyUserDao.batchSave(N.asList(user));
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            //
        } catch (Exception e) {
            //
            e.printStackTrace();
        }

        noUpdateUserDao.save(user);

        User userFromDB = readOnlyUserDao.gett(100L);
        System.out.println(userFromDB);

        try {
            readOnlyUserDao.delete(user);
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            //
        }

        try {
            noUpdateUserDao.delete(user);
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            //
        }

        readOnlyUserDao.prepareQuery("select * from user1").stream(List.class).forEach(Fn.println());

        try {
            readOnlyUserDao.prepareQuery("delete from user").execute();
            fail("Should throw UnsupportedOperationException");
        } catch (UnsupportedOperationException e) {
            //
        }

        userDao.delete(user);
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_update() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.save(user, N.asList("id", "firstName", "lastName", "email"));

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        userFromDB.setFirstName("updatedFN");
        userDao.update(userFromDB, CF.eq("firstName", "Forrest"));

        userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);
        assertEquals("updatedFN", userFromDB.getFirstName());

        userFromDB.setFirstName("updatedFN2");
        userDao.update(userFromDB, CF.eq("lastName", "Gump").and(CF.eq("id", userFromDB.getId())));

        userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);
        assertEquals("updatedFN2", userFromDB.getFirstName());

        userDao.update(N.asMap("firstName", "updatedFN3"), userFromDB.getId());

        userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);
        assertEquals("updatedFN3", userFromDB.getFirstName());

        userDao.deleteById(100L);

        long id = userDao.insert(user, N.asList("firstName", "lastName", "email"));
        userFromDB = userDao.gett(id);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);
        userDao.deleteById(id);

        assertFalse(userDao.exists(id));
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_queryForSingle() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.save(user, N.asList("id", "firstName", "lastName", "email"));

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        assertEquals(100, userDao.queryForLong("id", CF.eq("firstName", "Forrest")).orElseZero());
        assertEquals(100, userDao.queryForLong("id", 100L).orElseZero());

        assertEquals("Forrest", userDao.queryForString("firstName", CF.eq("firstName", "Forrest")).orElseNull());
        assertEquals("Forrest", userDao.queryForString("firstName", 100L).orElseNull());

        assertTrue(userDao.queryForTimestamp("createTime", CF.eq("firstName", "Forrest")).orElseNull().before(DateUtil.currentTimestamp()));
        assertTrue(userDao.queryForTimestamp("createTime", 100L).orElseNull().before(DateUtil.currentTimestamp()));

        userDao.deleteById(100L);

        long id = userDao.insert(user, N.asList("firstName", "lastName", "email"));
        userFromDB = userDao.gett(id);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);
        userDao.deleteById(id);

        assertFalse(userDao.exists(id));
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_batchGet() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insertWithId(user);

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);

        for (int i = 0; i < 100; i++) {
            userDao.batchGet(N.repeat(100L, 1)).forEach(Fn.println());
            userDao.batchGet(N.repeat(100L, 99)).forEach(Fn.println());
            userDao.batchGet(N.repeat(100L, 199)).forEach(Fn.println());
            userDao.batchGet(N.repeat(100L, 299)).forEach(Fn.println());
            userDao.batchGet(N.repeat(100L, 399)).forEach(Fn.println());
            userDao.batchGet(N.repeat(100L, 999)).forEach(Fn.println());

            userDao.batchGet(N.repeat(100L, 1), N.asList("firstName", "lastName")).forEach(Fn.println());
            userDao.batchGet(N.repeat(100L, 99), N.asList("firstName", "lastName")).forEach(Fn.println());
            userDao.batchGet(N.repeat(100L, 199), N.asList("firstName", "lastName")).forEach(Fn.println());
            userDao.batchGet(N.repeat(100L, 299), N.asList("firstName", "lastName")).forEach(Fn.println());
            userDao.batchGet(N.repeat(100L, 399), N.asList("firstName", "lastName")).forEach(Fn.println());
            userDao.batchGet(N.repeat(100L, 999), N.asList("firstName", "lastName")).forEach(Fn.println());
        }

        userDao.updateFirstAndLastName("Tom", "Hanks", 100);

        userDao.allUsers().map(e -> e.getFirstName() + " " + e.getLastName()).forEach(Fn.println());

        userDao.deleteById(100L);
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_batchDelete() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insertWithId(user);

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);

        assertEquals(1, userDao.batchDeleteByIds(N.repeat(100L, 1)));
        assertEquals(0, userDao.batchDeleteByIds(N.repeat(100L, 99)));
        assertEquals(0, userDao.batchDeleteByIds_1(N.repeat(100L, 199)));
        assertEquals(0, userDao.batchDeleteByIds(N.repeat(100L, 299)));
        assertEquals(0, userDao.batchDeleteByIds_1(N.repeat(100L, 399)));
        assertEquals(0, userDao.batchDeleteByIds(N.repeat(100L, 999)));

        user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insertWithId(user);

        userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);

        assertEquals(1, userDao.batchDelete(N.repeat(userFromDB, 1)));
        assertEquals(0, userDao.batchDelete(N.repeat(userFromDB, 99)));
        assertEquals(0, userDao.batchDelete(N.repeat(userFromDB, 199)));
        assertEquals(0, userDao.batchDelete(N.repeat(userFromDB, 299)));
        assertEquals(0, userDao.batchDelete(N.repeat(userFromDB, 399)));
        assertEquals(0, userDao.batchDelete(N.repeat(userFromDB, 999)));

        userDao.allUsers().map(e -> e.getFirstName() + " " + e.getLastName()).forEach(Fn.println());

        userDao.deleteById(100L);
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_findFirst() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insertWithId(user);

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);

        for (int i = 0; i < 1000; i++) {
            userDao.findFirst(CF.eq("firstName", "Forrest")).ifPresent(Fn.println());

            userDao.findFirst(CF.eq("firstName", "Forrest"), rs -> rs.getString("firstName")).ifPresent(Fn.println());

            userDao.findFirst(CF.eq("firstName", "Forrest"), (rs, cnl) -> rs.getString("firstName")).ifPresent(Fn.println());

            userDao.findFirst(N.asList("firstName", "lastName"), CF.eq("firstName", "Forrest")).ifPresent(Fn.println());

            userDao.findFirst(N.asList("firstName", "lastName"), CF.eq("firstName", "Forrest"), rs -> rs.getString(1)).ifPresent(Fn.println());

            userDao.findFirst(N.asList("firstName", "lastName"), CF.eq("firstName", "Forrest"), (rs, cnl) -> rs.getString(1)).ifPresent(Fn.println());
        }

        userDao.prepareQuery("select * from user1").list(ImmutableUser.class).forEach(Fn.println());
        userDao.prepareQuery("select * from user1").list(Jdbc.BiRowMapper.to(ImmutableUser.class)).forEach(Fn.println());

        userDao.getOne(0).ifPresent(Fn.println());

        userDao.updateFirstAndLastName("Tom", "Hanks", 100);

        userDao.allUsers().map(e -> e.getFirstName() + " " + e.getLastName()).forEach(Fn.println());

        userDao.deleteById(100L);
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_list() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insertWithId(user);

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);

        for (int i = 0; i < 1000; i++) {
            userDao.list(CF.eq("firstName", "Forrest")).forEach(Fn.println());

            userDao.list(CF.eq("firstName", "Forrest"), rs -> rs.getString("firstName")).forEach(Fn.println());

            userDao.list(CF.eq("firstName", "Forrest"), (rs, cnl) -> rs.getString("firstName")).forEach(Fn.println());

            userDao.list(N.asList("firstName", "lastName"), CF.eq("firstName", "Forrest")).forEach(Fn.println());

            userDao.list(N.asList("firstName", "lastName"), CF.eq("firstName", "Forrest"), rs -> rs.getString(1)).forEach(Fn.println());

            userDao.list(N.asList("firstName", "lastName"), CF.eq("firstName", "Forrest"), (rs, cnl) -> rs.getString(1)).forEach(Fn.println());

            userDao.list("firstName", CF.eq("firstName", "Forrest")).forEach(Fn.println());

            userDao.stream("firstName", CF.alwaysTrue()).forEach(Fn.println());
        }

        Map<?, ?> map = userDao.mappedById(0);
        N.println(map);
        assertEquals(1, map.size());

        userDao.listUserByAnnoSql(0).forEach(Fn.println());

        assertEquals(1, userDao.listUserByAnnoSql(0).size());

        assertEquals(1, userDao.listUserByAnnoSql2("newFirstName", 0).size());

        userDao.listToSet(0).forEach(Fn.println());

        userDao.listToSet2(0).forEach(Fn.println());

        assertEquals(1, userDao.listToSet(0).size());

        userDao.listToCollection(0).forEach(Fn.println());

        assertEquals(1, userDao.listToCollection(0).size());

        //    userDao.list(0, RowFilter.ALWAYS_TRUE, RowMapper.builder().toList()).forEach(Fn.println());
        //
        //    assertEquals(1, userDao.list(0, RowFilter.ALWAYS_TRUE, RowMapper.builder().toList()).size());

        userDao.updateFirstAndLastName("Tom", "Hanks", 100);

        userDao.allUsers().map(e -> e.getFirstName() + " " + e.getLastName()).forEach(Fn.println());

        userDao.delete(CB.where(CF.ge("id", 0)).limit(10000));

        userDao.deleteById(100L);
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_stream() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insertWithId(user);

        user.setId(101);
        userDao.insertWithId(user);

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);

        for (int i = 0; i < 1000; i++) {
            userDao.stream(CF.eq("firstName", "Forrest")).forEach(Fnn.println());

            userDao.stream(CF.eq("firstName", "Forrest"), rs -> rs.getString("firstName")).forEach(Fnn.println());

            userDao.stream(CF.eq("firstName", "Forrest"), (rs, cnl) -> rs.getString("firstName")).forEach(Fnn.println());

            userDao.stream(N.asList("firstName", "lastName"), CF.eq("firstName", "Forrest")).forEach(Fnn.println());

            userDao.stream(N.asList("firstName", "lastName"), CF.eq("firstName", "Forrest"), rs -> rs.getString(1)).forEach(Fnn.println());

            userDao.stream(N.asList("firstName", "lastName"), CF.eq("firstName", "Forrest"), (rs, cnl) -> rs.getString(1)).forEach(Fnn.println());
        }

        userDao.list(CF.gt("id", 0), rs -> rs.getString(1) != null, Jdbc.RowMapper.builder().get(1, ResultSet::getString).toList()).forEach(Fn.println());

        userDao.list(CF.gt("id", 0), (rs, cnl) -> rs.getString(1) != null, Jdbc.BiRowMapper.builder().get("firstName", ResultSet::getString).to(List.class))
                .forEach(Fn.println());

        userDao.list(CF.gt("id", 0), (rs, cnl) -> rs.getString(1) != null, Jdbc.BiRowMapper.builder().getString("firstName").to(LinkedHashMap.class))
                .forEach(Fn.println());

        userDao.list(CF.gt("id", 0), (rs, cnl) -> rs.getString(1) != null, Jdbc.BiRowMapper.builder().get("firstName", ResultSet::getString).to(User.class))
                .forEach(Fn.println());

        userDao.list(CF.gt("id", 0), (rs, cnl) -> rs.getString(1) != null, Jdbc.BiRowMapper.to(User.class)).forEach(Fn.println());

        userDao.streamOne(0).forEach(Fn.println());

        userDao.streamOne_2(0).forEach(Fn.println());

        userDao.updateFirstAndLastName("Tom", "Hanks", 100);

        userDao.allUsers().map(e -> e.getFirstName() + " " + e.getLastName()).forEach(Fn.println());

        userDao.deleteById(100L);

        assertEquals(1, JdbcUtil.executeUpdate(dataSource, "delete from user1 where id = ? ", 101));
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    @SuppressWarnings("deprecation")
    public void test_query_with_sub_entity_property() throws SQLException {
        N.println(userDao.targetEntityClass());
        N.println(userDao.targetDaoInterface());
        N.println(userDao.executor());

        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insertWithId(user);

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);

        Device device = Device.builder().userId(userFromDB.getId()).manufacture("Apple").model("iPhone 11").build();
        deviceDao.insert(device);

        Device device2 = Device.builder().userId(userFromDB.getId()).manufacture("Apple").model("iPhone 12").build();
        deviceDao.insert(device2);

        Address address = Address.builder().userId(userFromDB.getId()).street("infinite loop 1").city("Cupertino").build();
        addressDao.insert(address);

        N.copy(userFromDB);
        userDao.loadAllJoinEntities(userFromDB);
        System.out.println(userFromDB);

        final String query = "select first_name, last_name, device.manufacture as \" devices.manufacture\", device.model as \"devices.model\", device.user_id as \"devices.user_id\", address.street as \"address.street\", address.city as \"address.city\" from user1 left join device on user1.id = device.user_id left join address on user1.id = address.user_id";

        userDao.prepareQuery(query).list(User.class).forEach(Fn.println());
        userDao.prepareQuery(query).query(Jdbc.ResultExtractor.toDataSet(User.class)).println();

        userDao.deleteAllJoinEntities(user);
        userDao.delete(user);
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    @SuppressWarnings("deprecation")
    public void test_mergedBy() throws SQLException {
        N.println(userDao.targetEntityClass());
        N.println(userDao.targetDaoInterface());
        N.println(userDao.executor());

        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insertWithId(user);

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);

        Device device = Device.builder().userId(userFromDB.getId()).manufacture("Apple").model("iPhone 11").build();
        deviceDao.insert(device);

        Device device2 = Device.builder().userId(userFromDB.getId()).manufacture("Apple").model("iPhone 12").build();
        deviceDao.insert(device2);

        Address address = Address.builder().userId(userFromDB.getId()).street("infinite loop 1").city("Cupertino").build();
        addressDao.insert(address);

        N.copy(userFromDB);
        userDao.loadAllJoinEntities(userFromDB);
        System.out.println(userFromDB);

        userDao.listTomergedEntities().forEach(Fn.println());

        userDao.listTomergedEntities_2().forEach(Fn.println());

        userDao.listTomergedEntities_3().forEach(Fn.println());

        assertEquals(userDao.listTomergedEntities_2(), userDao.listTomergedEntities_3());

        userDao.findOneTomergedEntities().ifPresent(Fn.println());

        userDao.deleteAllJoinEntities(user);
        userDao.delete(user);
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    @SuppressWarnings("deprecation")
    public void test_joinedBy() throws SQLException {
        N.println(userDao.targetEntityClass());
        N.println(userDao.targetDaoInterface());
        N.println(userDao.executor());

        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insertWithId(user);

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);

        Device device = Device.builder().userId(userFromDB.getId()).manufacture("Apple").model("iPhone 11").build();
        deviceDao.insert(device);

        Address address = Address.builder().userId(userFromDB.getId()).street("infinite loop 1").city("Cupertino").build();
        addressDao.insert(address);

        N.copy(userFromDB);
        userDao.loadAllJoinEntities(userFromDB);
        System.out.println(userFromDB);

        userFromDB = userDao.gett(100L);
        N.copy(userFromDB);
        userDao.loadJoinEntitiesIfNull(userFromDB);
        System.out.println(userFromDB);

        userFromDB = userDao.gett(100L);
        N.copy(userFromDB);
        userDao.loadJoinEntities(userFromDB, Device.class);
        System.out.println(userFromDB);

        userFromDB = userDao.gett(100L);
        N.copy(userFromDB);
        userDao.loadJoinEntitiesIfNull(userFromDB, Address.class);
        System.out.println(userFromDB);

        userFromDB = userDao.gett(100L);
        N.copy(userFromDB);
        userDao.loadAllJoinEntities(userFromDB, true);
        System.out.println(userFromDB);

        userDao.deleteJoinEntities(userFromDB, Address.class);
        userDao.deleteJoinEntities(N.asList(userFromDB, userFromDB, userFromDB), Device.class);

        userDao.deleteById(100L);
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_joinedBy_2() throws SQLException {
        final List<User> users = new ArrayList<>();

        for (int i = 0; i < 1999; i++) {
            User user = User.builder().id(100 + i).firstName("Forrest").lastName("Gump").email("123@email.com").build();
            userDao.insertWithId(user);

            User userFromDB = userDao.gett(100L + i);
            System.out.println(userFromDB);
            users.add(userFromDB);

            Device device = Device.builder().userId(userFromDB.getId()).manufacture("Apple").model("iPhone 11").build();
            deviceDao.insert(device);

            Address address = Address.builder().userId(userFromDB.getId()).street("infinite loop 1").city("Cupertino").build();
            addressDao.insert(address);
        }

        List<User> users2 = Stream.of(users).map(N::copy).toList();
        List<User> users3 = Stream.of(users).map(N::copy).toList();

        userDao.loadAllJoinEntities(users2);
        users2.forEach(Fn.println());

        users2 = Stream.of(users).map(N::copy).toList();
        users3 = Stream.of(users).map(N::copy).toList();

        userDao.loadJoinEntitiesIfNull(users2);
        users2.forEach(Fn.println());

        users2 = Stream.of(users).map(N::copy).toList();
        users3 = Stream.of(users).map(N::copy).toList();

        userDao.loadJoinEntities(users2, Device.class);
        users2.forEach(Fn.println());

        users2 = Stream.of(users).map(N::copy).toList();
        users3 = Stream.of(users).map(N::copy).toList();

        userDao.loadJoinEntitiesIfNull(users2, Address.class);
        System.out.println(users2);

        users2 = Stream.of(users).map(N::copy).toList();
        users3 = Stream.of(users).map(N::copy).toList();

        userDao.loadAllJoinEntities(users2, true);
        users2.forEach(Fn.println());

        userDao.deleteJoinEntities(users3, Address.class);
        userDao.deleteJoinEntities(users, Device.class);

        userDao.batchDelete(users);
    }

    /**
     *
     */
    @Test
    public void test_SQLParser() {
        String sql = "SELECT employee_id AS \"employeeId\", first_name AS \"firstName\", last_name AS \"lastName\" FROM employee WHERE 1 < 2";
        SQLParser.parse(sql).forEach(Fn.println());
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_many_to_many() throws SQLException {

        Employee employee = Employee.builder().employeeId(100).firstName("Forrest").lastName("Gump").build();
        employeeDao.insert(employee);

        Employee employeeFromDB = employeeDao.gett(employee.getEmployeeId());
        employeeDao.loadAllJoinEntities(employeeFromDB);
        System.out.println(employeeFromDB);

        Project project = Project.builder().projectId(1000).title("Project X").build();
        projectDao.insert(project);

        Project projectFromDB = projectDao.gett(project.getProjectId());
        projectDao.loadAllJoinEntities(projectFromDB);
        System.out.println(projectFromDB);

        EmployeeProject employeeProject = EmployeeProject.builder().employeeId(employeeFromDB.getEmployeeId()).projectId(projectFromDB.getProjectId()).build();
        EntityId entityId = employeeProjectDao.insert(employeeProject);
        N.println(entityId);

        employeeDao.loadAllJoinEntities(employeeFromDB);
        System.out.println(employeeFromDB);

        List<Employee> employeesFromDB = N.asList(employeeDao.gett(employee.getEmployeeId()), employeeDao.gett(employee.getEmployeeId()));
        employeeDao.loadAllJoinEntities(employeesFromDB);
        System.out.println(employeesFromDB);

        employeeFromDB = employeeDao.gett(employee.getEmployeeId());
        employeeDao.loadJoinEntities(employeeFromDB, Project.class, N.asList("title"));
        System.out.println(employeeFromDB);

        employeesFromDB = N.asList(employeeDao.gett(employee.getEmployeeId()), employeeDao.gett(employee.getEmployeeId()));
        employeeDao.loadJoinEntities(employeesFromDB, Project.class, N.asList("title"));
        System.out.println(employeesFromDB);

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
        EmployeeProject entityId2 = employeeProjectDao2.insert(employeeProject);
        N.println(entityId2);

        List<Employee> employees = employeeDao.list(CF.alwaysTrue());
        employeeDao.loadAllJoinEntities(employees);
        System.out.println(employees);

        employeeDao.loadJoinEntities(employees, Project.class, N.asList("title"));
        System.out.println(employees);

        List<Project> projects = projectDao.list(CF.alwaysTrue());
        projectDao.loadAllJoinEntities(projects);
        System.out.println(projects);

        projectDao.loadJoinEntities(projects, Employee.class, N.asList("firstName"));
        System.out.println(projects);

        assertTrue(employeeProjectDao.exists(entityId));
        assertNotNull(employeeProjectDao.gett(entityId));

        assertTrue(employeeProjectDao2.exists(entityId2));
        assertNotNull(employeeProjectDao2.gett(entityId2));

        employeeDao.deleteAllJoinEntities(employees);

        projectDao.deleteAllJoinEntities(projects);

        employeeDao.deleteAllJoinEntities(employees.get(0));

        projectDao.deleteAllJoinEntities(projects.get(0));

        employeeDao.delete(CF.alwaysTrue());
        projectDao.delete(CF.alwaysTrue());
        employeeProjectDao.deleteById(entityId);
        employeeProjectDao2.deleteById(entityId2);

        assertFalse(employeeProjectDao.exists(entityId));
        assertNull(employeeProjectDao.gett(entityId));
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_mergedEntity() throws SQLException {
        Employee employee = Employee.builder().employeeId(100).firstName("Forrest").lastName("Gump").build();
        employeeDao.insert(employee);

        Project project = Project.builder().title("Project X").build();
        projectDao.insert(project);

        Project project2 = Project.builder().title("Project Y").build();
        projectDao.insert(project2);

        Project project3 = Project.builder().title("Project Z").build();
        projectDao.insert(project3);

        EmployeeProject employeeProject = EmployeeProject.builder().employeeId(employee.getEmployeeId()).projectId(project.getProjectId()).build();
        EntityId entityId = employeeProjectDao.insert(employeeProject);
        N.println(entityId);

        employeeProject = EmployeeProject.builder().employeeId(employee.getEmployeeId()).projectId(project2.getProjectId()).build();
        entityId = employeeProjectDao.insert(employeeProject);
        N.println(entityId);

        employeeProject = EmployeeProject.builder().employeeId(employee.getEmployeeId()).projectId(project3.getProjectId()).build();
        entityId = employeeProjectDao.insert(employeeProject);
        N.println(entityId);

        employeeDao.loadJoinEntities(employee, Project.class, N.asList("title"));
        System.out.println(employee);

        // String query = "select e.employee_id AS \"employeeId\", e.first_name AS \"firstName\", p.project_id AS \"projects.projectId\", p.title AS \"projects.title\" from employee e, employee_project ep left join project p on employee_id = ep.employee_id AND ep.project_id = p.project_id";

        String query = PSC.select(Employee.class, true)
                .from("employee e")
                .leftJoin("employee_project ep")
                .on("employee_id = ep.employee_id")
                .leftJoin("project")
                .on("ep.project_id = project.project_id")
                .sql();

        List<Employee> employees = employeeDao.prepareQuery(query).query().toMergedEntities(Employee.class);

        N.println(employees);

        employeeDao.delete(CF.alwaysTrue());
        projectDao.delete(CF.alwaysTrue());
        employeeProjectDao.delete(CF.alwaysTrue());
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_innerHandler() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insertWithId(user);

        userDao.testInnerHandler(100).forEach(Fn.println());

        userDao.deleteById(100L);
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_toDisposableObjArray() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insertWithId(user);

        userDao.forEach(CF.eq("firstName", "Forrest"), Jdbc.RowConsumer.oneOff(a -> N.println(a.join(", "))));
        userDao.forEach(CF.eq("firstName", "Forrest"), Jdbc.RowConsumer.oneOff(User.class, a -> N.println(a.join(", "))));

        userDao.forEach(CF.eq("firstName", "Forrest"), Jdbc.BiRowConsumer.oneOff((cls, a) -> N.println(a.join(", "))));
        userDao.forEach(CF.eq("firstName", "Forrest"), Jdbc.BiRowConsumer.oneOff(User.class, (cls, a) -> N.println(a.join(", "))));

        userDao.stream(CF.eq("firstName", "Forrest"), Jdbc.RowMapper.toDisposableObjArray()).forEach(Fn.println());
        userDao.stream(CF.eq("firstName", "Forrest"), Jdbc.RowMapper.toDisposableObjArray(User.class)).forEach(Fn.println());

        userDao.stream(CF.eq("firstName", "Forrest"), Jdbc.BiRowMapper.toDisposableObjArray()).forEach(Fn.println());
        userDao.stream(CF.eq("firstName", "Forrest"), Jdbc.BiRowMapper.toDisposableObjArray(User.class)).forEach(Fn.println());

        userDao.deleteById(100L);
    }

    /**
     *
     *
     * @throws SQLException
     */
    @Test
    public void test_myUserDao() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        myUserDaoA.save(user, N.asList("id", "firstName", "lastName", "email"));

        User userFromDB = myUserDaoA.gett(100L);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        myUserDaoA.findFirst(CF.eq("id", 100L), Jdbc.BiRowMapper.TO_MAP).ifPresent(Fn.println());

        myUserDaoA.findFirst(CF.eq("id", 100L), Jdbc.BiRowMapper.toMap(Fn.toUpperCaseWithUnderscore())).ifPresent(Fn.println());

        myUserDaoA.deleteById(100L);

        long id = myUserDaoA.insert(user, N.asList("firstName", "lastName", "email"));
        userFromDB = myUserDaoA.gett(id);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);
        myUserDaoA.deleteById(id);

        assertFalse(myUserDaoA.exists(id));
    }

}
