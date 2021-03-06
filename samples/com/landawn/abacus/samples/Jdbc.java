package com.landawn.abacus.samples;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.IsolationLevel;
import com.landawn.abacus.annotation.Type.EnumBy;
import com.landawn.abacus.condition.ConditionFactory.CF;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.samples.dao.AddressDao;
import com.landawn.abacus.samples.dao.DeviceDao;
import com.landawn.abacus.samples.dao.EmployeeDao;
import com.landawn.abacus.samples.dao.EmployeeProjectDao;
import com.landawn.abacus.samples.dao.EmployeeProjectDao2;
import com.landawn.abacus.samples.dao.MyUserDaoA;
import com.landawn.abacus.samples.dao.NoUpdateUserDao;
import com.landawn.abacus.samples.dao.ProjectDao;
import com.landawn.abacus.samples.dao.ReadOnlyUserDao;
import com.landawn.abacus.samples.dao.UncheckedUserDao;
import com.landawn.abacus.samples.dao.UncheckedUserDaoL;
import com.landawn.abacus.samples.dao.UserDao;
import com.landawn.abacus.samples.dao.UserDaoL;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.EntityCodeConfig;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.HandlerFactory;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NamingPolicy;
import com.landawn.abacus.util.SQLBuilder.NSC;
import com.landawn.abacus.util.SQLBuilder.PSC;
import com.landawn.abacus.util.SQLExecutor;
import com.landawn.abacus.util.SQLTransaction;
import com.landawn.abacus.util.Tuple;

/**
 * CRUD: insert -> read -> update -> delete a record in DB table.
 */
public class Jdbc {
    static {
        HandlerFactory.register("handler1", HandlerFactory.create(
                (obj, args, methodSignature) -> N.println(">>>handler1.beforeInvoke: method: " + methodSignature._1.getName()),
                (result, obj, args, methodSignature) -> N.println("<<<handler1.afterInvoke: method: " + methodSignature._1.getName() + ". result: " + result)));
        HandlerFactory.register("handler2", HandlerFactory.create(
                (obj, args, methodSignature) -> N.println(">>>handler2.beforeInvoke: method: " + methodSignature._1.getName()),
                (result, obj, args, methodSignature) -> N.println("<<<handler2.afterInvoke: method: " + methodSignature._1.getName() + ". result: " + result)));

        JdbcUtil.setIdExtractorForDao(EmployeeDao.class, rs -> rs.getInt(1));
        JdbcUtil.setIdExtractorForDao(UserDaoL.class, rs -> rs.getLong(1));
    }

    static final DataSource dataSource = JdbcUtil.createHikariDataSource("jdbc:h2:~/test", "sa", "");
    static final DataSource dataSource2 = JdbcUtil.createC3p0DataSource("jdbc:h2:~/test", "sa", "");
    static final UserDao userDao = JdbcUtil.createDao(UserDao.class, dataSource);
    static final UserDaoL userDao2 = JdbcUtil.createDao(UserDaoL.class, dataSource);
    static final MyUserDaoA myUserDaoA = JdbcUtil.createDao(MyUserDaoA.class, dataSource);
    static final UncheckedUserDao uncheckedUserDao = JdbcUtil.createDao(UncheckedUserDao.class, dataSource);
    static final UncheckedUserDaoL uncheckedUserDao2 = JdbcUtil.createDao(UncheckedUserDaoL.class, dataSource);
    static final NoUpdateUserDao noUpdateUserDao = JdbcUtil.createDao(NoUpdateUserDao.class, dataSource);
    static final ReadOnlyUserDao readOnlyUserDao = JdbcUtil.createDao(ReadOnlyUserDao.class, dataSource);

    static final EmployeeDao employeeDao = JdbcUtil.createDao(EmployeeDao.class, dataSource);
    static final ProjectDao projectDao = JdbcUtil.createDao(ProjectDao.class, dataSource);
    static final EmployeeProjectDao employeeProjectDao = JdbcUtil.createDao(EmployeeProjectDao.class, dataSource);
    static final EmployeeProjectDao2 employeeProjectDao2 = JdbcUtil.createDao(EmployeeProjectDao2.class, dataSource);

    static final DeviceDao deviceDao = JdbcUtil.createDao(DeviceDao.class, dataSource);
    static final AddressDao addressDao = JdbcUtil.createDao(AddressDao.class, dataSource);

    static final SQLExecutor sqlExecutor = new SQLExecutor(dataSource);

    // initialize DB schema.
    static {
        try {
            //    JdbcUtil.enableSqlLog(true);
            //    JdbcUtil.setMinExecutionTimeForSqlPerfLog(10);

            final String sql_user_drop_table = "DROP TABLE IF EXISTS user";
            final String sql_user_creat_table = "CREATE TABLE IF NOT EXISTS user (" //
                    + "id bigint(20) NOT NULL AUTO_INCREMENT PRIMARY KEY, " //
                    + "first_name varchar(32) NOT NULL, " //
                    + "last_name varchar(32) NOT NULL, " //
                    + "prop1 varchar(32), " //
                    + "email varchar(32), " //
                    + "create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP)";

            JdbcUtil.executeUpdate(dataSource, sql_user_drop_table);
            JdbcUtil.executeUpdate(dataSource, sql_user_creat_table);

            final String sql_device_drop_table = "DROP TABLE IF EXISTS device";
            final String sql_device_creat_table = "CREATE TABLE IF NOT EXISTS device (" //
                    + "id bigint(20) NOT NULL AUTO_INCREMENT PRIMARY KEY, " //
                    + "manufacture varchar(64) NOT NULL, " //
                    + "model varchar(32) NOT NULL, " //
                    + "user_id bigint(20), " //
                    + "FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE)";

            JdbcUtil.executeUpdate(dataSource, sql_device_drop_table);
            JdbcUtil.executeUpdate(dataSource, sql_device_creat_table);

            final String sql_address_drop_table = "DROP TABLE IF EXISTS address";
            final String sql_address_creat_table = "CREATE TABLE IF NOT EXISTS address (" //
                    + "id bigint(20) NOT NULL AUTO_INCREMENT PRIMARY KEY, " //
                    + "street varchar(64) NOT NULL, " //
                    + "city varchar(32) NOT NULL, " //
                    + "user_id bigint(20), " //
                    + "FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE)";

            JdbcUtil.executeUpdate(dataSource, sql_address_drop_table);
            JdbcUtil.executeUpdate(dataSource, sql_address_creat_table);

            // this code is copied from: https://www.baeldung.com/hibernate-many-to-many

            final String sql_employee_drop_table = "DROP TABLE IF EXISTS employee";
            final String sql_employee_creat_table = "CREATE TABLE IF NOT EXISTS employee (" //
                    + "employee_id int(11) NOT NULL AUTO_INCREMENT PRIMARY KEY, " //
                    + "first_name varchar(50) DEFAULT NULL, " //
                    + "last_name varchar(50) DEFAULT NULL)";

            JdbcUtil.executeUpdate(dataSource, sql_employee_drop_table);
            JdbcUtil.executeUpdate(dataSource, sql_employee_creat_table);

            final String sql_project_drop_table = "DROP TABLE IF EXISTS project";
            final String sql_project_creat_table = "CREATE TABLE IF NOT EXISTS project (" //
                    + "project_id int(11) NOT NULL AUTO_INCREMENT PRIMARY KEY, " //
                    + "title varchar(50) DEFAULT NULL)";

            JdbcUtil.executeUpdate(dataSource, sql_project_drop_table);
            JdbcUtil.executeUpdate(dataSource, sql_project_creat_table);

            final String sql_employee_dept_relationship_drop_table = "DROP TABLE IF EXISTS employee_project";
            final String sql_employee_dept_relationship_creat_table = "CREATE TABLE IF NOT EXISTS employee_project (" //
                    + "employee_id int(11) NOT NULL, " //
                    + "project_id int(11) NOT NULL, " //
                    + "create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP)";

            JdbcUtil.executeUpdate(dataSource, sql_employee_dept_relationship_drop_table);
            JdbcUtil.executeUpdate(dataSource, sql_employee_dept_relationship_creat_table);

        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Test
    public void crud_by_Jdbc_00() throws SQLException {
        NSC.selectFrom(User.class).where(CF.gt("id", 1)).toNamedQuery(dataSource).list().forEach(Fn.println());
    }

    @Test
    public void crud_by_Jdbc() throws SQLException {
        Connection conn = null;
        PreparedStatement stmt = null;

        try {
            final String sql = PSC.insertInto(User.class).sql();
            conn = dataSource.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1, 100);
            stmt.setString(2, "Forrest");
            stmt.setString(3, "Gump");
            stmt.setString(4, "Forrest");
            stmt.setString(5, "123@email.com");
            stmt.execute();
        } finally {
            JdbcUtil.closeQuietly(stmt, conn);
        }

        User userFromDB = null;

        try {
            final String sql = PSC.selectFrom(User.class).where("id = ?").sql();

            conn = dataSource.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1, 100);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    userFromDB = User.builder()
                            .id(rs.getLong(1))
                            .firstName(rs.getString(2))
                            .lastName(rs.getString(3))
                            .nickName(rs.getString(4))
                            .email(rs.getString(5))
                            .createTime(rs.getTimestamp(6))
                            .build();
                }
            }
        } finally {
            JdbcUtil.closeQuietly(stmt, conn);
        }

        System.out.println(userFromDB);

        try {
            final String sql = PSC.update(User.class).set("firstName", "lastName").where("id = ?").sql();

            conn = dataSource.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setString(1, "Tom");
            stmt.setString(2, "Hanks");
            stmt.setLong(3, 100);
            stmt.execute();
        } finally {
            JdbcUtil.closeQuietly(stmt, conn);
        }

        try {
            final String sql = PSC.deleteFrom(User.class).where("id = ?").sql();

            conn = dataSource.getConnection();
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1, 100);
            stmt.execute();
        } finally {
            JdbcUtil.closeQuietly(stmt, conn);
        }

        // As you see, here are the problems:
        // 1, Manually create/open/close Connection/Statement/ResultSet.
        // 2, Manually set parameters to Statement.
        // 3, Manually read/map records from ResultSet.
        // If you're working on small project, where 20 tables defined with average 20 columns.
        // On average, if 20 (select/insert/update/delete) queries/methods created for each table,
        // On average, each query/method requires 20 lines codes. 20 tables * 20 queries/table * 20 lines/query = 8000 lines.
        // A lot of efforts will have to be paid to write the codes and maintain (add/update/delete/rename/... tables/columns).
    }

    // A bit(lot of?) improvements:
    @Test
    public void crud_by_PreparedQuery() throws SQLException {

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
                .findOnlyOne(User.class) // or findFirst/list/stream/... a lot more we can do.
                .ifPresent(System.out::println);

        sql = PSC.update(User.class).set("firstName", "lastName").where("id = ?").sql();
        JdbcUtil.prepareQuery(dataSource, sql) //
                .setString(1, "Tom")
                .setString(2, "Hanks")
                .setLong(3, 100)
                .update();

        sql = PSC.deleteFrom(User.class).where("id = ?").sql();
        JdbcUtil.prepareQuery(dataSource, sql) //
                .setLong(1, 100)
                .update();

        // Improvements:
        // 1, No need to manually create/open/close Connection/Statement/ResultSet.
        // 2, No need to manually read/map records from ResultSet.
        // 3, Flexible/fluent APIs
        // But still need to manually set parameters to Statement.
    }

    @Test
    public void crud_by_Dao() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insertWithId(user);

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);

        // There are so much more can be done by findFirst/list/stream/
        // userDao.stream(CF.eq("firstName", "Forrest")).filter(u -> u.getId() > 10).map(e -> e).groupBy(keyMapper);

        userDao.updateFirstAndLastName("Tom", "Hanks", 100);

        userDao.allUsers().map(e -> e.getFirstName() + " " + e.getLastName()).forEach(Fn.println());

        userDao.deleteById(100L);

        // How about transaction?
        // See last sample.
    }

    @Test
    public void test_transaction() throws SQLException {
        User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
        userDao.insertWithId(user);

        User userFromDB = userDao.gett(100L);
        System.out.println(userFromDB);

        try (SQLTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.DEFAULT)) {
            userDao.updateFirstAndLastName("Tom", "Hanks", 100);

            userDao.queryForBoolean("firstName", CF.eq("id", 100)); // throw exception.
            tran.commit();
        } catch (SQLException e) {
            // ignore
        }

        assertEquals("Forrest", userDao.gett(100L).getFirstName());

        try (SQLTransaction tran = JdbcUtil.beginTransaction(dataSource, IsolationLevel.DEFAULT)) {
            userDao.updateFirstAndLastName("Tom", "Hanks", 100);

            tran.commit();
        }

        assertEquals("Tom", userDao.gett(100L).getFirstName());

        userDao.deleteById(100L);
        assertNull(userDao.gett(100L));

        // In you're in Spring and want to use Spring transaction management,
        // then don't need to call beginTransaction because Spring transaction is integrated and supported.
    }

    @Test
    public void test_generateEntityClass() {
        EntityCodeConfig ecc = EntityCodeConfig.builder()
                .packageName("codes.entity")
                .srcDir("./samples")
                .idField("id")
                .readOnlyFields(N.asSet("createTime"))
                .nonUpdatableFields(N.asSet("id"))
                .generateBuilder(true)
                .build();

        String str = JdbcUtil.generateEntityClass(dataSource, "account", ecc);
        System.out.println(str);

        ecc = EntityCodeConfig.builder()
                .className("User")
                .packageName("codes.entity")
                .srcDir("./samples")
                .useBoxedType(true)
                .readOnlyFields(N.asSet("id"))
                .idField("id")
                .nonUpdatableFields(N.asSet("create_time"))
                .idAnnotationClass(javax.persistence.Id.class)
                // .columnAnnotationClass(javax.persistence.Column.class)
                .tableAnnotationClass(javax.persistence.Table.class)
                .customizedFields(N.asList(Tuple.of("createTime", "create_time", java.util.Date.class)))
                .customizedFieldDbTypes(N.asList(Tuple.of("create_time", "List<String>")))
                .chainAccessor(true)
                .generateBuilder(true)
                .generateCopyMethod(true)
                .jsonXmlConfig(EntityCodeConfig.JsonXmlConfig.builder()
                        .namingPolicy(NamingPolicy.UPPER_CASE_WITH_UNDERSCORE)
                        .ignoredFields("id,   create_time")
                        .dateFormat("yyyy-mm-dd\\\"T\\\"")
                        .numberFormat("#.###")
                        .timeZone("PDT")
                        .enumerated(EnumBy.ORDINAL)
                        .build())
                .build();

        str = JdbcUtil.generateEntityClass(dataSource, "user", ecc);
        System.out.println(str);
    }
}
