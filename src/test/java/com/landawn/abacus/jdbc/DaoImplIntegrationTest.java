package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.ReadOnly;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.SqlBuilder.PSC;
import com.landawn.abacus.util.u.Optional;

/**
 * End-to-end integration coverage for the dynamically generated DAO implementation
 * ({@link DaoImpl}) backed by a real in-memory H2 database. Exercising a live
 * {@link CrudDao} drives the generated CRUD method bodies plus the underlying
 * {@link JdbcUtil} / {@code AbstractQuery} execution paths that mock-only unit tests
 * cannot reach.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class DaoImplIntegrationTest extends TestBase {

    @Table("user_account")
    public static class UserAccount {
        @Id
        @ReadOnly
        private Long id;
        private String firstName;
        private String lastName;
        private int age;
        private boolean active;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(final String firstName) {
            this.firstName = firstName;
        }

        public String getLastName() {
            return lastName;
        }

        public void setLastName(final String lastName) {
            this.lastName = lastName;
        }

        public int getAge() {
            return age;
        }

        public void setAge(final int age) {
            this.age = age;
        }

        public boolean isActive() {
            return active;
        }

        public void setActive(final boolean active) {
            this.active = active;
        }
    }

    public interface UserAccountDao extends CrudDao<UserAccount, Long, PSC, UserAccountDao> {
    }

    private DataSource ds;
    private UserAccountDao dao;

    private static UserAccount newUser(final String first, final String last, final int age) {
        final UserAccount u = new UserAccount();
        u.setFirstName(first);
        u.setLastName(last);
        u.setAge(age);
        u.setActive(true);
        return u;
    }

    @BeforeAll
    public void initDb() throws SQLException {
        ds = JdbcUtil.createHikariDataSource("jdbc:h2:mem:daoimpl_it;DB_CLOSE_DELAY=-1", "sa", "");

        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS user_account (" + "id BIGINT AUTO_INCREMENT PRIMARY KEY, " + "first_name VARCHAR(64), "
                    + "last_name VARCHAR(64), " + "age INT, " + "active BOOLEAN)");
        }

        dao = JdbcUtil.createDao(UserAccountDao.class, ds);
    }

    @AfterAll
    public void dropDb() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS user_account");
        }
    }

    @BeforeEach
    public void cleanTable() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("TRUNCATE TABLE user_account");
        }
    }

    // insert returns the generated key; gett / get / exists round-trip the row.
    @Test
    public void testInsertAndGet() throws SQLException {
        final Long id = dao.insert(newUser("Ada", "Lovelace", 36));
        assertNotNull(id);

        final UserAccount loaded = dao.gett(id);
        assertNotNull(loaded);
        assertEquals("Ada", loaded.getFirstName());
        assertEquals(36, loaded.getAge());

        final Optional<UserAccount> opt = dao.get(id);
        assertTrue(opt.isPresent());
        assertTrue(dao.exists(id));
        assertFalse(dao.exists(999999L));
    }

    // get with a restricted select-prop list only populates the requested columns.
    @Test
    public void testGet_SelectPropNames() throws SQLException {
        final Long id = dao.insert(newUser("Grace", "Hopper", 45));

        final UserAccount loaded = dao.gett(id, List.of("id", "firstName"));
        assertNotNull(loaded);
        assertEquals("Grace", loaded.getFirstName());
        // lastName was not selected.
        assertEquals(null, loaded.getLastName());
    }

    // update(entity), update(prop,val,id) and update(map,id) all persist changes.
    @Test
    public void testUpdateVariants() throws SQLException {
        final Long id = dao.insert(newUser("Alan", "Turing", 41));

        final UserAccount u = dao.gett(id);
        u.setAge(42);
        assertEquals(1, dao.update(u));
        assertEquals(42, dao.gett(id).getAge());

        assertEquals(1, dao.update("lastName", "T.", id));
        assertEquals("T.", dao.gett(id).getLastName());

        assertEquals(1, dao.update(Map.of("firstName", "Alan M.", "age", 43), id));
        final UserAccount after = dao.gett(id);
        assertEquals("Alan M.", after.getFirstName());
        assertEquals(43, after.getAge());
    }

    // delete(entity) and deleteById remove the row.
    @Test
    public void testDeleteVariants() throws SQLException {
        final Long id1 = dao.insert(newUser("Del", "One", 20));
        final Long id2 = dao.insert(newUser("Del", "Two", 21));

        assertEquals(1, dao.deleteById(id1));
        assertFalse(dao.exists(id1));

        final UserAccount u2 = dao.gett(id2);
        assertEquals(1, dao.delete(u2));
        assertFalse(dao.exists(id2));
    }

    // batchInsert / batchUpdate / batchDelete operate over collections.
    @Test
    public void testBatchOperations() throws SQLException {
        final List<UserAccount> users = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            users.add(newUser("Batch" + i, "User", 30 + i));
        }

        final List<Long> ids = dao.batchInsert(users);
        assertEquals(5, ids.size());

        final List<UserAccount> loaded = dao.list(Filters.eq("lastName", "User"));
        assertEquals(5, loaded.size());

        for (final UserAccount u : loaded) {
            u.setAge(u.getAge() + 100);
        }
        assertEquals(5, dao.batchUpdate(loaded));
        assertEquals(130, dao.gett(ids.get(0)).getAge());

        assertEquals(5, dao.batchDelete(loaded));
        assertEquals(0, dao.count(Filters.eq("lastName", "User")));
    }

    // list / count / findFirst with a Condition exercise the query-builder execution path.
    @Test
    public void testQueryByCondition() throws SQLException {
        dao.insert(newUser("Q1", "Cond", 18));
        dao.insert(newUser("Q2", "Cond", 25));
        dao.insert(newUser("Q3", "Cond", 25));

        assertEquals(3, dao.count(Filters.eq("lastName", "Cond")));
        assertEquals(2, dao.list(Filters.eq("age", 25)).size());

        final Optional<UserAccount> first = dao.findFirst(Filters.eq("firstName", "Q1"));
        assertTrue(first.isPresent());
        assertEquals(18, first.get().getAge());
    }

    // upsert inserts when absent and updates when the unique-prop match exists.
    @Test
    public void testUpsert() throws SQLException {
        final UserAccount u = newUser("Up", "Sert", 50);
        final UserAccount inserted = dao.upsert(u, List.of("firstName"));
        assertNotNull(inserted.getId());

        final UserAccount again = newUser("Up", "Sert-Updated", 51);
        final UserAccount updated = dao.upsert(again, List.of("firstName"));
        assertEquals(inserted.getId(), updated.getId());
        assertEquals("Sert-Updated", dao.gett(inserted.getId()).getLastName());
        assertEquals(1, dao.count(Filters.eq("firstName", "Up")));
    }

    // batchUpsert partitions into inserts + updates (CrudDao L1283-1381 DB path).
    @Test
    public void testBatchUpsert() throws SQLException {
        final Long existingId = dao.insert(newUser("Keep", "Existing", 60));

        final List<UserAccount> batch = new ArrayList<>();
        final UserAccount toUpdate = newUser("Keep", "Updated", 61);
        batch.add(toUpdate);
        batch.add(newUser("Fresh1", "New", 22));
        batch.add(newUser("Fresh2", "New", 23));

        final List<UserAccount> result = dao.batchUpsert(batch, List.of("firstName"), 2);
        assertEquals(3, result.size());

        assertEquals(3, dao.count(Filters.eq("lastName", "New").or(Filters.eq("firstName", "Keep"))));
        assertEquals("Updated", dao.gett(existingId).getLastName());
    }

    // gett with an unknown id returns null and exists is false (no-row branch).
    @Test
    public void testGet_UnknownId_ReturnsNull() throws SQLException {
        assertEquals(null, dao.gett(123456789L));
        assertFalse(dao.exists(123456789L));
    }

    // A malformed DAO interface is rejected at creation time.
    @Test
    public void testCreateDao_InvalidEntityId_Throws() {
        assertThrows(Exception.class, () -> JdbcUtil.createDao(NoIdBadDao.class, ds).insert(new NoIdBad()));
    }

    // A custom @Query UPDATE method declared with a WRAPPER return type (Integer/Long/Boolean) must dispatch into
    // the update path at OP.DEFAULT. Regression: DaoImpl#isUpdateReturnType used to be primitive-only, so
    // wrapper returns silently fell through to "Unsupported sql annotation", even though the error message and
    // the result converter both explicitly advertised wrapper support.
    public interface WrapperReturnDao extends CrudDao<UserAccount, Long, PSC, WrapperReturnDao> {
        @Query("UPDATE user_account SET age = ? WHERE id = ?")
        Integer bumpAgeReturnInteger(int newAge, long id) throws SQLException;

        @Query("UPDATE user_account SET age = ? WHERE id = ?")
        Long bumpAgeReturnLong(int newAge, long id) throws SQLException;

        @Query("UPDATE user_account SET age = ? WHERE id = ?")
        Boolean bumpAgeReturnBoolean(int newAge, long id) throws SQLException;
    }

    @Test
    public void testCustomQuery_WrapperReturnTypes_DispatchToUpdatePath() throws SQLException {
        final WrapperReturnDao wrapDao = JdbcUtil.createDao(WrapperReturnDao.class, ds);
        final Long id = dao.insert(newUser("Wrap", "Return", 10));

        // Integer return — receives row-affected count converted via Numbers::toIntExact.
        final Integer intResult = wrapDao.bumpAgeReturnInteger(11, id);
        assertNotNull(intResult);
        assertEquals(Integer.valueOf(1), intResult);
        assertEquals(11, dao.gett(id).getAge());

        // Long return — receives the raw long count.
        final Long longResult = wrapDao.bumpAgeReturnLong(12, id);
        assertNotNull(longResult);
        assertEquals(Long.valueOf(1L), longResult);
        assertEquals(12, dao.gett(id).getAge());

        // Boolean return — receives true when at least one row was affected.
        final Boolean boolResult = wrapDao.bumpAgeReturnBoolean(13, id);
        assertNotNull(boolResult);
        assertTrue(boolResult);
        assertEquals(13, dao.gett(id).getAge());

        // And the false branch: an UPDATE that affects zero rows must return Boolean.FALSE, not throw.
        final Boolean noMatch = wrapDao.bumpAgeReturnBoolean(99, 999999L);
        assertNotNull(noMatch);
        assertFalse(noMatch);
    }

    public static class NoIdBad {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }

    public interface NoIdBadDao extends CrudDao<NoIdBad, Long, PSC, NoIdBadDao> {
    }
}
