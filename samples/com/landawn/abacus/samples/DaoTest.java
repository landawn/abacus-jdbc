package com.landawn.abacus.samples;

import static com.landawn.abacus.samples.Jdbc.addressMapper;
import static com.landawn.abacus.samples.Jdbc.dataSource;
import static com.landawn.abacus.samples.Jdbc.deviceMapper;
import static com.landawn.abacus.samples.Jdbc.employeeDao;
import static com.landawn.abacus.samples.Jdbc.employeeProjectDao;
import static com.landawn.abacus.samples.Jdbc.noUpdateUserDao;
import static com.landawn.abacus.samples.Jdbc.projectDao;
import static com.landawn.abacus.samples.Jdbc.readOnlyUserDao;
import static com.landawn.abacus.samples.Jdbc.sqlExecutor;
import static com.landawn.abacus.samples.Jdbc.userDao;
import static com.landawn.abacus.samples.Jdbc.userMapper;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.EntityId;
import com.landawn.abacus.condition.ConditionFactory.CF;
import com.landawn.abacus.samples.entity.Address;
import com.landawn.abacus.samples.entity.Device;
import com.landawn.abacus.samples.entity.Employee;
import com.landawn.abacus.samples.entity.EmployeeProject;
import com.landawn.abacus.samples.entity.Project;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.Fn.Fnn;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.JdbcUtil.BiRowMapper;
import com.landawn.abacus.util.JdbcUtil.RowMapper;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Profiler;
import com.landawn.abacus.util.SQLParser;
import com.landawn.abacus.util.SQLTransaction;
import com.landawn.abacus.util.stream.IntStream;
import com.landawn.abacus.util.stream.LongStream;
import com.landawn.abacus.util.stream.Stream;

public class DaoTest {

    @Test
    public void test_orderBy() throws SQLException {
        JdbcUtil.enableSQLLog(true);
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
        
        userDao.query(CF.criteria().groupBy("last_Name").having(CF.ne("last_Name", "aa")).orderBy("FIRST_NAME")).println();
        userDao.deleteById(id);

        assertFalse(userDao.exists(id));
    }

    @Test
    public void test_cache() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insert(user, N.asList("id", "firstName", "lastName", "email"));

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        System.out.println(userFromDB);
        assertNotNull(userFromDB);

        Profiler.run(1, 10000, 1, () -> userDao.gett(100L)).printResult();

        userDao.delete(userFromDB);

        Profiler.run(1, 10000, 1, () -> userDao.gett(100L)).printResult();

        userDao.delete(userFromDB);
    }

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

    @Test
    public void test_sql_log() throws SQLException {

        LongStream.range(100, 110).parallel(8).forEach(idx -> {
            synchronized (JdbcUtil.class) {
                if (idx % 2 == 0) {
                    System.out.println("###: enable log for Thread: " + Thread.currentThread());
                    JdbcUtil.enableSQLLog(true);
                    JdbcUtil.setMinExecutionTimeForSQLPerfLog(0);
                } else {
                    System.out.println("+++: Not enable log for Thread: " + Thread.currentThread());
                }

                User user = User.builder().id(idx).firstName("Forrest").lastName("Gump").email("123@email.com").build();
                userDao.insert(user, N.asList("id", "firstName", "lastName", "email"));

                assertNotNull(userDao.gett(idx));

                userDao.deleteById(idx);

                if (idx % 2 == 0) {
                    System.out.println("###: disable log for Thread: " + Thread.currentThread());
                    JdbcUtil.enableSQLLog(false);
                    JdbcUtil.setMinExecutionTimeForSQLPerfLog(-1);
                }
            }
        });
    }

    @Test
    public void test_operation_log() throws SQLException {

        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insert(user, N.asList("id", "firstName", "lastName", "email"));

        userDao.delete_propagation_SUPPORTS(100);
    }

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

    @Test
    public void test_batch() throws SQLException {

        List<User> users = IntStream.range(1, 1000)
                .mapToObj(i -> User.builder().id(i).firstName("Forrest" + i).lastName("Gump" + i).email("123@email.com" + i).build())
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

        assertEquals(0, N.sum(userDao.batchDeleteByIds2(ids)));
    }

    @Test
    public void test_save_insert() throws SQLException {
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
        userDao.deleteById(id);

        assertFalse(userDao.exists(id));
    }

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

        userDao.delete(user);
    }

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

    @Test
    public void test_batchDelete() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insertWithId(user);

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);

        assertEquals(1, userDao.batchDeleteByIds(N.repeat(100L, 1)));
        assertEquals(0, userDao.batchDeleteByIds(N.repeat(100L, 99)));
        assertEquals(0, userDao.batchDeleteByIds(N.repeat(100L, 199)));
        assertEquals(0, userDao.batchDeleteByIds(N.repeat(100L, 299)));
        assertEquals(0, userDao.batchDeleteByIds(N.repeat(100L, 399)));
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

        userDao.updateFirstAndLastName("Tom", "Hanks", 100);

        userDao.allUsers().map(e -> e.getFirstName() + " " + e.getLastName()).forEach(Fn.println());

        userDao.deleteById(100L);
    }

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

        userDao.listUserByAnnoSql(0).forEach(Fn.println());

        assertEquals(1, userDao.listUserByAnnoSql(0).size());

        assertEquals(1, userDao.listUserByAnnoSql2("newFirstName", 0).size());

        userDao.updateFirstAndLastName("Tom", "Hanks", 100);

        userDao.allUsers().map(e -> e.getFirstName() + " " + e.getLastName()).forEach(Fn.println());

        userDao.deleteById(100L);
    }

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

        userDao.list(CF.gt("id", 0), rs -> rs.getString(1) != null,
                RowMapper.builder().defauLt((i, rs) -> rs.getObject(i)).get(1, (i, rs) -> rs.getString(i)).toList()).forEach(Fn.println());

        userDao.list(CF.gt("id", 0), (rs, cnl) -> rs.getString(1) != null,
                BiRowMapper.builder().defauLt((i, rs) -> rs.getObject(i)).get("firstName", (i, rs) -> rs.getString(i)).to(List.class)).forEach(Fn.println());

        userDao.list(CF.gt("id", 0), (rs, cnl) -> rs.getString(1) != null,
                BiRowMapper.builder().defauLt((i, rs) -> rs.getObject(i)).getString("firstName").to(LinkedHashMap.class)).forEach(Fn.println());

        userDao.list(CF.gt("id", 0), (rs, cnl) -> rs.getString(1) != null,
                BiRowMapper.builder().defauLt((i, rs) -> rs.getObject(i)).get("firstName", (i, rs) -> rs.getString(i)).to(User.class)).forEach(Fn.println());

        userMapper.get(100L).ifPresent(Fn.println());

        userDao.list(CF.gt("id", 0), (rs, cnl) -> rs.getString(1) != null, BiRowMapper.to(User.class)).forEach(Fn.println());

        userDao.updateFirstAndLastName("Tom", "Hanks", 100);

        userDao.allUsers().map(e -> e.getFirstName() + " " + e.getLastName()).forEach(Fn.println());

        userDao.deleteById(100L);

        assertEquals(1, sqlExecutor.update("delete from user where id = ? ", 101));
    }

    @Test
    public void crud_joinedBy() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insertWithId(user);

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);

        Device device = Device.builder().userId(userFromDB.getId()).manufacture("Apple").model("iPhone 11").build();
        deviceMapper.insert(device);

        Address address = Address.builder().userId(userFromDB.getId()).street("infinite loop 1").city("Cupertino").build();
        addressMapper.insert(address);

        User userFromDB2 = N.copy(userFromDB);
        userDao.loadAllJoinEntities(userFromDB);
        System.out.println(userFromDB);

        userMapper.loadAllJoinEntities(userFromDB2);
        System.out.println(userFromDB2);

        assertEquals(userFromDB, userFromDB2);

        userFromDB = userDao.gett(100L);
        userFromDB2 = N.copy(userFromDB);
        userDao.loadJoinEntitiesIfNull(userFromDB);
        System.out.println(userFromDB);

        userMapper.loadJoinEntitiesIfNull(userFromDB2);
        System.out.println(userFromDB2);

        assertEquals(userFromDB, userFromDB2);

        userFromDB = userDao.gett(100L);
        userFromDB2 = N.copy(userFromDB);
        userDao.loadJoinEntities(userFromDB, Device.class);
        System.out.println(userFromDB);

        userMapper.loadJoinEntities(userFromDB2, Device.class);
        System.out.println(userFromDB2);

        assertEquals(userFromDB, userFromDB2);

        userFromDB = userDao.gett(100L);
        userFromDB2 = N.copy(userFromDB);
        userDao.loadJoinEntitiesIfNull(userFromDB, Address.class);
        System.out.println(userFromDB);

        userMapper.loadJoinEntitiesIfNull(userFromDB2, Address.class);
        System.out.println(userFromDB2);

        assertEquals(userFromDB, userFromDB2);

        userFromDB = userDao.gett(100L);
        userFromDB2 = N.copy(userFromDB);
        userDao.loadAllJoinEntities(userFromDB, true);
        System.out.println(userFromDB);

        userMapper.loadJoinEntitiesIfNull(userFromDB2, true);
        System.out.println(userFromDB2);

        assertEquals(userFromDB, userFromDB2);

        userDao.deleteById(100L);
    }

    @Test
    public void crud_joinedBy_2() throws SQLException {
        final List<User> users = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            User user = User.builder().id(100 + i).firstName("Forrest").lastName("Gump").email("123@email.com").build();
            userDao.insertWithId(user);

            User userFromDB = userDao.gett(100L + i);
            System.out.println(userFromDB);
            users.add(userFromDB);

            Device device = Device.builder().userId(userFromDB.getId()).manufacture("Apple").model("iPhone 11").build();
            deviceMapper.insert(device);

            Address address = Address.builder().userId(userFromDB.getId()).street("infinite loop 1").city("Cupertino").build();
            addressMapper.insert(address);
        }

        List<User> users2 = Stream.of(users).map(N::copy).toList();
        List<User> users3 = Stream.of(users).map(N::copy).toList();

        userDao.loadAllJoinEntities(users2);
        System.out.println(users2);

        userMapper.loadAllJoinEntities(users3);
        System.out.println(users3);

        assertEquals(users2, users3);

        users2 = Stream.of(users).map(N::copy).toList();
        users3 = Stream.of(users).map(N::copy).toList();

        userDao.loadJoinEntitiesIfNull(users2);
        System.out.println(users2);

        userMapper.loadJoinEntitiesIfNull(users3);
        System.out.println(users3);

        assertEquals(users2, users3);

        users2 = Stream.of(users).map(N::copy).toList();
        users3 = Stream.of(users).map(N::copy).toList();

        userDao.loadJoinEntities(users2, Device.class);
        System.out.println(users2);

        userMapper.loadJoinEntities(users3, Device.class);
        System.out.println(users3);

        assertEquals(users2, users3);

        users2 = Stream.of(users).map(N::copy).toList();
        users3 = Stream.of(users).map(N::copy).toList();

        userDao.loadJoinEntitiesIfNull(users2, Address.class);
        System.out.println(users2);

        userMapper.loadJoinEntitiesIfNull(users3, Address.class);
        System.out.println(users3);

        assertEquals(users2, users3);

        users2 = Stream.of(users).map(N::copy).toList();
        users3 = Stream.of(users).map(N::copy).toList();

        userDao.loadAllJoinEntities(users2, true);
        System.out.println(users2);

        userMapper.loadAllJoinEntities(users3, true);
        System.out.println(users3);

        assertEquals(users2, users3);

        userDao.batchDelete(users);
    }

    @Test
    public void test_SQLParser() throws SQLException {
        String sql = "SELECT employee_id AS \"employeeId\", first_name AS \"firstName\", last_name AS \"lastName\" FROM employee WHERE 1 < 2";
        SQLParser.parse(sql).forEach(Fn.println());
    }

    @Test
    public void crud_many_to_many() throws SQLException {

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
        entityId = employeeProjectDao.insert(employeeProject);
        N.println(entityId);

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

        employeeDao.delete(CF.alwaysTrue());
        projectDao.delete(CF.alwaysTrue());
        employeeProjectDao.delete(CF.alwaysTrue());
        assertFalse(employeeProjectDao.exists(entityId));
        assertNull(employeeProjectDao.gett(entityId));
    }
}
