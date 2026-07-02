package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.landawn.abacus.jdbc.annotation.DaoConfig;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.annotation.SqlLogEnabled;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.NoUpdateCrudDao;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.SqlDialect;
import com.landawn.abacus.query.SqlDialect.ProductInfo;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.u.OptionalBoolean;
import com.landawn.abacus.util.u.OptionalByte;
import com.landawn.abacus.util.u.OptionalChar;
import com.landawn.abacus.util.u.OptionalInt;
import com.landawn.abacus.util.u.OptionalLong;
import com.landawn.abacus.util.u.OptionalShort;
import com.landawn.abacus.util.stream.Stream;

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

    public interface UserAccountDao extends CrudDao<UserAccount, Long, UserAccountDao> {
    }

    @Table("type_probe")
    public static class TypeProbe {
        @Id
        @ReadOnly
        private Long id;
        private char charVal;
        private java.sql.Date dateVal;
        private java.sql.Time timeVal;
        private java.sql.Timestamp tsVal;
        private byte[] bytesVal;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public char getCharVal() {
            return charVal;
        }

        public void setCharVal(final char charVal) {
            this.charVal = charVal;
        }

        public java.sql.Date getDateVal() {
            return dateVal;
        }

        public void setDateVal(final java.sql.Date dateVal) {
            this.dateVal = dateVal;
        }

        public java.sql.Time getTimeVal() {
            return timeVal;
        }

        public void setTimeVal(final java.sql.Time timeVal) {
            this.timeVal = timeVal;
        }

        public java.sql.Timestamp getTsVal() {
            return tsVal;
        }

        public void setTsVal(final java.sql.Timestamp tsVal) {
            this.tsVal = tsVal;
        }

        public byte[] getBytesVal() {
            return bytesVal;
        }

        public void setBytesVal(final byte[] bytesVal) {
            this.bytesVal = bytesVal;
        }
    }

    public interface TypeProbeDao extends CrudDao<TypeProbe, Long, TypeProbeDao> {
    }

    private DataSource ds;
    private UserAccountDao dao;
    private TypeProbeDao typeDao;

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

            // Typed table for the date/time/char/binary queryFor* accessors; one fixed row (id=1).
            st.execute("CREATE TABLE IF NOT EXISTS type_probe (" + "id BIGINT PRIMARY KEY, char_val CHAR(1), date_val DATE, time_val TIME, "
                    + "ts_val TIMESTAMP, bytes_val VARBINARY(16))");
            st.execute("DELETE FROM type_probe");
            st.execute("INSERT INTO type_probe (id, char_val, date_val, time_val, ts_val, bytes_val) "
                    + "VALUES (1, 'A', DATE '2020-01-15', TIME '10:30:00', TIMESTAMP '2020-01-15 10:30:00', X'0102')");
        }

        dao = JdbcUtil.createDao(UserAccountDao.class, ds);
        typeDao = JdbcUtil.createDao(TypeProbeDao.class, ds);
    }

    @AfterAll
    public void dropDb() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS user_account");
            st.execute("DROP TABLE IF EXISTS type_probe");
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

    // batchUpsert with a COMPOSITE unique-prop key drives the multi-prop EntityId path
    // (CrudDao L1311-1334: entityIdExtractor + Filters.id2Cond batch query), distinct from the
    // single-prop path exercised by testBatchUpsert.
    @Test
    public void testBatchUpsert_MultiUniqueProps() throws SQLException {
        final Long existingId = dao.insert(newUser("Multi", "Key", 70));

        final List<UserAccount> batch = new ArrayList<>();
        batch.add(newUser("Multi", "Key", 71)); // matches existing on (firstName,lastName) -> update
        batch.add(newUser("Multi", "Other", 22)); // new
        batch.add(newUser("Solo", "Key", 23)); // new

        final List<UserAccount> result = dao.batchUpsert(batch, List.of("firstName", "lastName"), 2);
        assertEquals(3, result.size());

        // the existing row was updated in place (same id), not duplicated.
        assertEquals(71, dao.gett(existingId).getAge());
        assertEquals(3, dao.count(Filters.eq("firstName", "Multi").or(Filters.eq("lastName", "Key"))));
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
    public interface WrapperReturnDao extends CrudDao<UserAccount, Long, WrapperReturnDao> {
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

    public interface NoIdBadDao extends CrudDao<NoIdBad, Long, NoIdBadDao> {
    }

    // Regression: the @Transactional + @SqlLogEnabled/@PerfLog wrapper used to call
    // JdbcUtil.beginTransaction BEFORE entering the try-block that restores SQL-log and perf-log
    // thread-locals. If beginTransaction itself threw (e.g., the DataSource went down between the
    // SqlLog mutation and the connection acquisition), the thread-locals were never restored,
    // leaking the annotation's settings into all subsequent calls on the same thread.
    public interface TxLeakDao extends CrudDao<UserAccount, Long, TxLeakDao> {
        @com.landawn.abacus.jdbc.annotation.Transactional
        @com.landawn.abacus.jdbc.annotation.SqlLogEnabled(value = true, maxSqlLogLength = 4242)
        @com.landawn.abacus.jdbc.annotation.PerfLog(minExecutionTimeForSql = 7777L, minExecutionTimeForOperation = 8888L)
        @Query("SELECT COUNT(*) FROM user_account")
        long countWithTx() throws SQLException;
    }

    @Test
    public void testTransactionalSqlLogState_RestoredOnBeginTransactionFailure() throws Exception {
        // Use a dedicated H2 datasource for this test so closing it doesn't disturb the shared one.
        final DataSource scratchDs = JdbcUtil.createHikariDataSource("jdbc:h2:mem:daoimpl_txleak;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection conn = scratchDs.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS user_account (" + "id BIGINT AUTO_INCREMENT PRIMARY KEY, " + "first_name VARCHAR(64), "
                    + "last_name VARCHAR(64), " + "age INT, " + "active BOOLEAN)");
        }

        final TxLeakDao txDao = JdbcUtil.createDao(TxLeakDao.class, scratchDs);

        // Snapshot the thread-local SQL-log + perf-log state before the failed call.
        final boolean priorSqlLogEnabled = JdbcUtil.isSqlLogEnabled();
        final long priorMinPerfLog = JdbcUtil.getMinExecutionTimeForSqlPerfLog();

        // Closing the Hikari pool makes beginTransaction's getConnection() throw, simulating a
        // mid-method DataSource failure that occurs after the @SqlLogEnabled/@PerfLog wrapper has
        // already mutated the thread-locals.
        ((com.zaxxer.hikari.HikariDataSource) scratchDs).close();

        assertThrows(Exception.class, txDao::countWithTx);

        // The fix: even though beginTransaction threw, the wrapper's finally block must have
        // restored both thread-locals.
        assertEquals(priorSqlLogEnabled, JdbcUtil.isSqlLogEnabled(), "SQL log thread-local must be restored after beginTransaction failure");
        assertEquals(priorMinPerfLog, JdbcUtil.getMinExecutionTimeForSqlPerfLog(), "Perf log thread-local must be restored after beginTransaction failure");
    }

    // CrudDao queryFor* single-column-by-id family: each typed accessor drives a distinct generated
    // builder branch in DaoImpl.
    @Test
    public void testQueryForById_TypedAccessors() throws SQLException {
        final Long id = dao.insert(newUser("Quinn", "Probe", 7));

        assertEquals(OptionalBoolean.of(true), dao.queryForBoolean("active", id));
        assertEquals(OptionalByte.of((byte) 7), dao.queryForByte("age", id));
        assertEquals(OptionalShort.of((short) 7), dao.queryForShort("age", id));
        assertEquals(OptionalInt.of(7), dao.queryForInt("age", id));
        assertEquals(OptionalLong.of(id), dao.queryForLong("id", id));
        assertEquals(7.0f, dao.queryForFloat("age", id).orElseThrow(), 0.0001f);
        assertEquals(7.0, dao.queryForDouble("age", id).orElseThrow(), 0.0001);
        assertEquals(Nullable.of("Quinn"), dao.queryForString("firstName", id));
        assertEquals(Integer.valueOf(7), dao.queryForSingleValue("age", id, Integer.class).orElseNull());
        assertEquals(Integer.valueOf(7), dao.queryForSingleNonNull("age", id, Integer.class).orElseThrow());
        assertEquals(Integer.valueOf(7), dao.queryForUniqueValue("age", id, Integer.class).orElseNull());
        assertEquals(Integer.valueOf(7), dao.queryForUniqueNonNull("age", id, Integer.class).orElseThrow());
    }

    // Dao queryFor* single-column-by-Condition family.
    @Test
    public void testQueryForByCondition_TypedAccessors() throws SQLException {
        final Long id = dao.insert(newUser("Cara", "Cond", 9));

        assertEquals(OptionalBoolean.of(true), dao.queryForBoolean("active", Filters.eq("id", id)));
        assertEquals(OptionalByte.of((byte) 9), dao.queryForByte("age", Filters.eq("id", id)));
        assertEquals(OptionalShort.of((short) 9), dao.queryForShort("age", Filters.eq("id", id)));
        assertEquals(OptionalInt.of(9), dao.queryForInt("age", Filters.eq("id", id)));
        assertEquals(OptionalLong.of(id), dao.queryForLong("id", Filters.eq("id", id)));
        assertEquals(9.0f, dao.queryForFloat("age", Filters.eq("id", id)).orElseThrow(), 0.0001f);
        assertEquals(9.0, dao.queryForDouble("age", Filters.eq("id", id)).orElseThrow(), 0.0001);
        assertEquals(Nullable.of("Cara"), dao.queryForString("firstName", Filters.eq("id", id)));
        assertEquals(Integer.valueOf(9), dao.queryForSingleValue("age", Filters.eq("id", id), Integer.class).orElseNull());
        assertEquals(Integer.valueOf(9), dao.queryForSingleNonNull("age", Filters.eq("id", id), Integer.class).orElseThrow());
        assertEquals(Integer.valueOf(9), dao.queryForUniqueValue("age", Filters.eq("id", id), Integer.class).orElseNull());
        assertEquals(Integer.valueOf(9), dao.queryForUniqueNonNull("age", Filters.eq("id", id), Integer.class).orElseThrow());
    }

    // findFirst / findOnlyOne overloads (Condition, selectPropNames, RowMapper, BiRowMapper).
    @Test
    public void testFindFirstAndFindOnlyOne_Variants() throws SQLException {
        final Long id = dao.insert(newUser("Fin", "Only", 12));

        assertTrue(dao.findFirst(Filters.eq("id", id)).isPresent());
        assertEquals("Fin", dao.findFirst(Filters.eq("id", id), (Jdbc.RowMapper<String>) rs -> rs.getString("first_name")).orElse(null));
        assertEquals("Fin", dao.findFirst(Filters.eq("id", id), (Jdbc.BiRowMapper<String>) (rs, cols) -> rs.getString("first_name")).orElse(null));
        assertEquals("Fin", dao.findFirst(List.of("firstName"), Filters.eq("id", id)).map(UserAccount::getFirstName).orElse(null));

        assertTrue(dao.findOnlyOne(Filters.eq("id", id)).isPresent());
        assertEquals("Fin", dao.findOnlyOne(Filters.eq("id", id), (Jdbc.RowMapper<String>) rs -> rs.getString("first_name")).orElse(null));
        assertEquals("Fin", dao.findOnlyOne(List.of("firstName"), Filters.eq("id", id)).map(UserAccount::getFirstName).orElse(null));
    }

    // list / stream overloads (Condition, selectPropNames, single-prop, RowMapper, BiRowMapper).
    @Test
    public void testListAndStream_Variants() throws SQLException {
        dao.insert(newUser("L1", "Grp", 20));
        dao.insert(newUser("L2", "Grp", 21));

        assertEquals(2, dao.list(Filters.eq("lastName", "Grp")).size());
        assertEquals(2, dao.list(Filters.eq("lastName", "Grp"), (Jdbc.RowMapper<String>) rs -> rs.getString("first_name")).size());
        assertEquals(2, dao.list(Filters.eq("lastName", "Grp"), (Jdbc.BiRowMapper<String>) (rs, cols) -> rs.getString("first_name")).size());
        assertEquals(2, dao.list(List.of("firstName"), Filters.eq("lastName", "Grp")).size());
        assertEquals(2, dao.<String> list("firstName", Filters.eq("lastName", "Grp")).size());

        assertEquals(2L, dao.stream(Filters.eq("lastName", "Grp")).count());
        assertEquals(2L, dao.stream(Filters.eq("lastName", "Grp"), (Jdbc.RowMapper<String>) rs -> rs.getString("first_name")).count());
    }

    // batchGet overloads + id-set operations (count(ids), notExists, batchDeleteByIds).
    @Test
    public void testBatchGetAndIdSetOps() throws SQLException {
        final Long id1 = dao.insert(newUser("BG1", "Set", 30));
        final Long id2 = dao.insert(newUser("BG2", "Set", 31));
        final List<Long> ids = List.of(id1, id2);

        assertEquals(2, dao.batchGet(ids).size());
        assertEquals(2, dao.batchGet(ids, List.of("id", "firstName")).size());
        assertEquals(2, dao.batchGet(ids, 1).size());
        assertEquals(2, dao.count(ids));
        assertFalse(dao.notExists(id1));
        assertTrue(dao.notExists(999999L));
        assertTrue(dao.notExists(Filters.eq("firstName", "nobody")));

        assertEquals(2, dao.batchDeleteByIds(ids));
        assertEquals(0, dao.count(ids));
    }

    // update(Map, Condition) and delete(Condition) drive the by-condition mutation branches.
    @Test
    public void testUpdateAndDeleteByCondition() throws SQLException {
        dao.insert(newUser("UC1", "Mut", 40));
        dao.insert(newUser("UC2", "Mut", 41));

        assertEquals(2, dao.update(Map.of("active", false), Filters.eq("lastName", "Mut")));
        assertEquals(0, dao.list(Filters.eq("active", true).and(Filters.eq("lastName", "Mut"))).size());

        assertEquals(2, dao.delete(Filters.eq("lastName", "Mut")));
        assertEquals(0, dao.count(Filters.eq("lastName", "Mut")));
    }

    // Custom @Query SELECT/COUNT/DELETE methods with scalar, entity-list, and int return types.
    public interface CustomQueryDao extends CrudDao<UserAccount, Long, CustomQueryDao> {
        @Query("SELECT first_name FROM user_account WHERE id = ?")
        String firstNameById(long id) throws SQLException;

        @Query("SELECT * FROM user_account WHERE age >= ? ORDER BY age")
        List<UserAccount> findOlderThan(int minAge) throws SQLException;

        @Query("SELECT COUNT(*) FROM user_account WHERE last_name = ?")
        int countByLastName(String lastName) throws SQLException;

        @Query("DELETE FROM user_account WHERE last_name = ?")
        int deleteByLastName(String lastName) throws SQLException;
    }

    @Test
    public void testCustomQueryMethods() throws SQLException {
        final CustomQueryDao cqDao = JdbcUtil.createDao(CustomQueryDao.class, ds);
        final Long id = dao.insert(newUser("Cust", "Query", 40));
        dao.insert(newUser("Cust2", "Query", 50));

        assertEquals("Cust", cqDao.firstNameById(id));
        assertEquals(2, cqDao.findOlderThan(40).size());
        assertEquals(2, cqDao.countByLastName("Query"));
        assertEquals(2, cqDao.deleteByLastName("Query"));
        assertEquals(0, cqDao.countByLastName("Query"));
    }

    // queryFor* accessors for char/date/time/timestamp/byte[] against the fixed type_probe row,
    // by id and by Condition — the remaining queryFor* builder branches in DaoImpl.
    @Test
    public void testQueryForById_CharDateTimeBytes() throws SQLException {
        assertEquals(OptionalChar.of('A'), typeDao.queryForChar("charVal", 1L));
        assertEquals(java.sql.Date.valueOf("2020-01-15"), typeDao.queryForDate("dateVal", 1L).orElseNull());
        assertEquals(java.sql.Time.valueOf("10:30:00"), typeDao.queryForTime("timeVal", 1L).orElseNull());
        assertEquals(java.sql.Timestamp.valueOf("2020-01-15 10:30:00"), typeDao.queryForTimestamp("tsVal", 1L).orElseNull());
        assertArrayEquals(new byte[] { 1, 2 }, typeDao.queryForBytes("bytesVal", 1L).orElseNull());
    }

    @Test
    public void testQueryForByCondition_CharDateTimeBytes() throws SQLException {
        assertEquals(OptionalChar.of('A'), typeDao.queryForChar("charVal", Filters.eq("id", 1L)));
        assertEquals(java.sql.Date.valueOf("2020-01-15"), typeDao.queryForDate("dateVal", Filters.eq("id", 1L)).orElseNull());
        assertEquals(java.sql.Time.valueOf("10:30:00"), typeDao.queryForTime("timeVal", Filters.eq("id", 1L)).orElseNull());
        assertEquals(java.sql.Timestamp.valueOf("2020-01-15 10:30:00"), typeDao.queryForTimestamp("tsVal", Filters.eq("id", 1L)).orElseNull());
        assertArrayEquals(new byte[] { 1, 2 }, typeDao.queryForBytes("bytesVal", Filters.eq("id", 1L)).orElseNull());
    }

    // Dao default prepareQuery(String)/prepareQuery(Condition)/prepareNamedQuery(String) factory paths.
    @Test
    public void testPrepareQueryAndNamedQuery() throws SQLException {
        final Long id = dao.insert(newUser("Prep", "Q", 33));

        final List<String> names = dao.prepareQuery("SELECT first_name FROM user_account WHERE id = ?").setLong(1, id).list(String.class);
        assertEquals(1, names.size());
        assertEquals("Prep", names.get(0));

        final OptionalInt age = dao.prepareNamedQuery("SELECT age FROM user_account WHERE id = :id").setLong("id", id).queryForInt();
        assertEquals(OptionalInt.of(33), age);

        assertEquals(1, dao.prepareQuery(Filters.eq("id", id)).list(UserAccount.class).size());
    }

    // @Query with a named parameter bound via @Bind drives the named-SQL custom-method dispatch.
    @SqlLogEnabled(true)
    @DaoConfig(addLimitForSingleQuery = true)
    public interface BindDao extends CrudDao<UserAccount, Long, BindDao> {
        @Query("SELECT first_name FROM user_account WHERE age = :age")
        String firstNameByAge(@com.landawn.abacus.jdbc.annotation.Bind("age") int age) throws SQLException;
    }

    @Test
    public void testCreateDao() throws SQLException {
        SqlDialect sqlDialect = SqlDialect.builder().productInfo(ProductInfo.of("SQL Server", "10")).build();
        final BindDao bindDao = JdbcUtil.createDao(BindDao.class, ds, sqlDialect);
        dao.insert(newUser("Bind", "Me", 77));

        bindDao.findFirst(Filters.eq("firstName", "me"));
    }

    @Test
    public void testBindNamedQuery() throws SQLException {
        final BindDao bindDao = JdbcUtil.createDao(BindDao.class, ds);
        dao.insert(newUser("Bind", "Me", 77));

        assertEquals("Bind", bindDao.firstNameByAge(77));
    }

    // insert/update/batchInsert/batchUpdate overloads that take an explicit prop-name collection.
    @Test
    public void testInsertUpdateBatch_PropNameVariants() throws SQLException {
        final List<String> writableProps = List.of("firstName", "lastName", "age", "active");

        final Long id = dao.insert(newUser("Ins", "Props", 15), writableProps);
        assertNotNull(id);

        // update(entity, propNamesToUpdate): only "age" is persisted; lastName change is ignored.
        final UserAccount loaded = dao.gett(id);
        loaded.setAge(16);
        loaded.setLastName("Ignored");
        assertEquals(1, dao.update(loaded, List.of("age")));
        final UserAccount after = dao.gett(id);
        assertEquals(16, after.getAge());
        assertEquals("Props", after.getLastName());

        // batchInsert with prop names, with and without an explicit batch size.
        assertEquals(2, dao.batchInsert(List.of(newUser("BI1", "BP", 1), newUser("BI2", "BP", 2)), writableProps).size());
        assertEquals(2, dao.batchInsert(List.of(newUser("BI3", "BP", 3), newUser("BI4", "BP", 4)), writableProps, 1).size());
        assertEquals(4, dao.count(Filters.eq("lastName", "BP")));

        // batchUpdate with prop names, with and without an explicit batch size.
        final List<UserAccount> bp = dao.list(Filters.eq("lastName", "BP"));
        for (final UserAccount x : bp) {
            x.setAge(x.getAge() + 10);
        }
        assertEquals(4, dao.batchUpdate(bp, List.of("age")));
        assertEquals(4, dao.batchUpdate(bp, List.of("age"), 2));
    }

    // forEach (RowConsumer / BiRowConsumer) and foreach (DisposableObjArray) iteration paths.
    @Test
    public void testForEachVariants() throws SQLException {
        dao.insert(newUser("FE1", "Each", 50));
        dao.insert(newUser("FE2", "Each", 51));

        final int[] rowCount = { 0 };
        dao.forEach(Filters.eq("lastName", "Each"), (Jdbc.RowConsumer) rs -> rowCount[0]++);
        assertEquals(2, rowCount[0]);

        final int[] biRowCount = { 0 };
        dao.forEach(Filters.eq("lastName", "Each"), (Jdbc.BiRowConsumer) (rs, cols) -> biRowCount[0]++);
        assertEquals(2, biRowCount[0]);

        final int[] daCount = { 0 };
        dao.foreach(Filters.eq("lastName", "Each"), arr -> daCount[0]++);
        assertEquals(2, daCount[0]);
    }

    // =====================================================================================
    // Annotation-driven custom @Query methods: diverse return types, named params, BindList,
    // SqlFragment template substitution. These drive the large getResultConverter /
    // setParameters dispatch blocks in DaoImpl that the plain-CrudDao tests never reach.
    // =====================================================================================
    public interface AnnotatedQueryDao extends CrudDao<UserAccount, Long, AnnotatedQueryDao> {

        // DEFAULT op + Optional return type -> "find first" semantics.
        @Query("SELECT * FROM user_account WHERE last_name = :ln ORDER BY id")
        Optional<UserAccount> findOptByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        // DEFAULT op + List<Entity> with TWO named (@Bind) parameters (multi-bind named-SQL path).
        @Query("SELECT * FROM user_account WHERE age >= :minAge AND last_name = :ln ORDER BY id")
        List<UserAccount> findListByAgeAndLastName(@com.landawn.abacus.jdbc.annotation.Bind("minAge") int minAge,
                @com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        // DEFAULT op + single-column List<String>.
        @Query("SELECT first_name FROM user_account WHERE last_name = :ln ORDER BY id")
        List<String> firstNamesByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        // DEFAULT op + com.landawn.abacus.util.Dataset return type.
        @Query("SELECT * FROM user_account WHERE last_name = :ln ORDER BY id")
        Dataset datasetByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        // DEFAULT op + Stream return type -> lazy streaming. A lazily-evaluated Stream return
        // must NOT declare a checked throws clause (DaoImpl rejects it at creation time).
        @Query("SELECT * FROM user_account WHERE last_name = :ln ORDER BY id")
        Stream<UserAccount> streamByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln);

        // @BindList expands the collection into the IN-clause placeholders.
        @Query("SELECT * FROM user_account WHERE id IN ({ids}) ORDER BY id")
        List<UserAccount> byIds(@com.landawn.abacus.jdbc.annotation.BindList("ids") Collection<Long> ids) throws SQLException;

        // @SqlFragment rewrites the {sortCol} token in the SQL text before the statement is prepared.
        @Query("SELECT * FROM user_account WHERE last_name = :ln ORDER BY {sortCol}")
        List<UserAccount> findSortedByFragment(@com.landawn.abacus.jdbc.annotation.SqlFragment("sortCol") String sortCol,
                @com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;
    }

    @Test
    public void testCustomSelect_VariousReturnTypes() throws SQLException {
        final AnnotatedQueryDao aqDao = JdbcUtil.createDao(AnnotatedQueryDao.class, ds);
        dao.insert(newUser("Sel1", "Sel", 30));
        dao.insert(newUser("Sel2", "Sel", 40));
        dao.insert(newUser("Sel3", "Sel", 50));

        final Optional<UserAccount> opt = aqDao.findOptByLastName("Sel");
        assertTrue(opt.isPresent());
        assertEquals("Sel1", opt.get().getFirstName());

        // two @Bind params (age >= 40) -> Sel2, Sel3
        assertEquals(2, aqDao.findListByAgeAndLastName(40, "Sel").size());

        // single-column List<String>
        assertEquals(List.of("Sel1", "Sel2", "Sel3"), aqDao.firstNamesByLastName("Sel"));
    }

    @Test
    public void testCustomSelect_DatasetReturn() throws SQLException {
        final AnnotatedQueryDao aqDao = JdbcUtil.createDao(AnnotatedQueryDao.class, ds);
        dao.insert(newUser("Ds1", "Ds", 11));
        dao.insert(newUser("Ds2", "Ds", 12));

        final Dataset dataset = aqDao.datasetByLastName("Ds");
        assertEquals(2, dataset.size());
    }

    @Test
    public void testCustomSelect_StreamReturn() throws SQLException {
        final AnnotatedQueryDao aqDao = JdbcUtil.createDao(AnnotatedQueryDao.class, ds);
        dao.insert(newUser("St1", "St", 1));
        dao.insert(newUser("St2", "St", 2));
        dao.insert(newUser("St3", "St", 3));

        try (Stream<UserAccount> s = aqDao.streamByLastName("St")) {
            assertEquals(3L, s.count());
        }
    }

    @Test
    public void testCustomSelect_BindList() throws SQLException {
        final AnnotatedQueryDao aqDao = JdbcUtil.createDao(AnnotatedQueryDao.class, ds);
        final Long id1 = dao.insert(newUser("B1", "BL", 1));
        final Long id2 = dao.insert(newUser("B2", "BL", 2));
        dao.insert(newUser("B3", "BL", 3));

        final List<UserAccount> result = aqDao.byIds(List.of(id1, id2));
        assertEquals(2, result.size());
        assertEquals(id1, result.get(0).getId());
        assertEquals(id2, result.get(1).getId());
    }

    @Test
    public void testCustomSelect_SqlFragment() throws SQLException {
        final AnnotatedQueryDao aqDao = JdbcUtil.createDao(AnnotatedQueryDao.class, ds);
        dao.insert(newUser("F1", "Frag", 30));
        dao.insert(newUser("F2", "Frag", 10));
        dao.insert(newUser("F3", "Frag", 20));

        final List<UserAccount> sorted = aqDao.findSortedByFragment("age", "Frag");
        assertEquals(3, sorted.size());
        assertEquals(10, sorted.get(0).getAge());
        assertEquals(30, sorted.get(2).getAge());
    }

    // =====================================================================================
    // Explicit @Query op() modes: exists / queryForSingle / findOnlyOne / findFirst / list.
    // Each drives a distinct result-converter branch in DaoImpl.
    // =====================================================================================
    public interface OpQueryDao extends CrudDao<UserAccount, Long, OpQueryDao> {

        @Query(value = "SELECT 1 FROM user_account WHERE last_name = :ln", op = OP.exists)
        boolean existsByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        @Query(value = "SELECT COUNT(*) FROM user_account WHERE last_name = :ln", op = OP.queryForSingle)
        long countByLastNameSingle(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        @Query(value = "SELECT first_name FROM user_account WHERE id = :id", op = OP.queryForSingle)
        String firstNameViaSingle(@com.landawn.abacus.jdbc.annotation.Bind("id") long id) throws SQLException;

        @Query(value = "SELECT * FROM user_account WHERE id = :id", op = OP.findOnlyOne)
        UserAccount onlyOneById(@com.landawn.abacus.jdbc.annotation.Bind("id") long id) throws SQLException;

        @Query(value = "SELECT * FROM user_account WHERE last_name = :ln ORDER BY age", op = OP.findFirst)
        Optional<UserAccount> firstByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        @Query(value = "SELECT * FROM user_account WHERE last_name = :ln ORDER BY id", op = OP.list)
        List<UserAccount> listByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;
    }

    @Test
    public void testExplicitOp_Variants() throws SQLException {
        final OpQueryDao opDao = JdbcUtil.createDao(OpQueryDao.class, ds);
        final Long id = dao.insert(newUser("Op1", "Op", 25));
        dao.insert(newUser("Op2", "Op", 35));

        assertTrue(opDao.existsByLastName("Op"));
        assertFalse(opDao.existsByLastName("Nope"));
        assertEquals(2L, opDao.countByLastNameSingle("Op"));
        assertEquals("Op1", opDao.firstNameViaSingle(id));
        assertEquals(25, opDao.onlyOneById(id).getAge());

        final Optional<UserAccount> first = opDao.firstByLastName("Op");
        assertTrue(first.isPresent());
        assertEquals(25, first.get().getAge());

        assertEquals(2, opDao.listByLastName("Op").size());
    }

    // =====================================================================================
    // Named INSERT / UPDATE / DELETE custom-SQL methods (:named params): single-bean auto-bind,
    // multi-@Bind, and single-@Bind dispatch into the update-path converters.
    // =====================================================================================
    public interface NamedDmlDao extends CrudDao<UserAccount, Long, NamedDmlDao> {

        // single bean parameter -> named placeholders auto-bound to its properties.
        @Query("INSERT INTO user_account (first_name, last_name, age, active) VALUES (:firstName, :lastName, :age, :active)")
        void insertNamed(UserAccount entity) throws SQLException;

        // multi @Bind UPDATE returning affected-row count.
        @Query("UPDATE user_account SET age = :age WHERE id = :id")
        int updateAgeNamed(@com.landawn.abacus.jdbc.annotation.Bind("age") int age, @com.landawn.abacus.jdbc.annotation.Bind("id") long id) throws SQLException;

        // single @Bind DELETE returning affected-row count.
        @Query("DELETE FROM user_account WHERE last_name = :ln")
        int deleteNamed(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;
    }

    @Test
    public void testNamedDml() throws SQLException {
        final NamedDmlDao nDao = JdbcUtil.createDao(NamedDmlDao.class, ds);

        nDao.insertNamed(newUser("N1", "Dml", 21));
        assertEquals(1, dao.count(Filters.eq("lastName", "Dml")));

        final Long id = dao.findFirst(Filters.eq("firstName", "N1")).get().getId();
        assertEquals(1, nDao.updateAgeNamed(99, id));
        assertEquals(99, dao.gett(id).getAge());

        assertEquals(1, nDao.deleteNamed("Dml"));
        assertEquals(0, dao.count(Filters.eq("lastName", "Dml")));
    }

    // =====================================================================================
    // @Handler: a recording handler wired via @Handler(type=...) must have its beforeInvoke /
    // afterInvoke run around the annotated DAO method (drives the handler wrapper in DaoImpl).
    // =====================================================================================
    public static final class RecordingHandler implements Jdbc.Handler<HandlerDao> {
        static final AtomicInteger BEFORE = new AtomicInteger();
        static final AtomicInteger AFTER = new AtomicInteger();

        @Override
        public void beforeInvoke(final HandlerDao proxy, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            if ("handledCount".equals(methodSignature._1.getName())) {
                BEFORE.incrementAndGet();
            }
        }

        @Override
        public void afterInvoke(final Object result, final HandlerDao proxy, final Object[] args,
                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            if ("handledCount".equals(methodSignature._1.getName())) {
                AFTER.incrementAndGet();
            }
        }
    }

    @com.landawn.abacus.jdbc.annotation.Handler(type = RecordingHandler.class)
    public interface HandlerDao extends CrudDao<UserAccount, Long, HandlerDao> {
        @Query("SELECT COUNT(*) FROM user_account")
        long handledCount() throws SQLException;
    }

    @Test
    public void testHandler_BeforeAfterInvoked() throws SQLException {
        final HandlerDao handlerDao = JdbcUtil.createDao(HandlerDao.class, ds);
        RecordingHandler.BEFORE.set(0);
        RecordingHandler.AFTER.set(0);

        dao.insert(newUser("H1", "Hand", 30));
        assertEquals(1L, handlerDao.handledCount());

        assertEquals(1, RecordingHandler.BEFORE.get());
        assertEquals(1, RecordingHandler.AFTER.get());
    }

    // =====================================================================================
    // @PerfLog + @SqlLogEnabled: thresholds of 0 force both the SQL-perf and DAO-op-perf log
    // branches to run on a successful call (the happy-path counterpart to the existing
    // begin-transaction-failure restoration test).
    // =====================================================================================
    @com.landawn.abacus.jdbc.annotation.PerfLog(minExecutionTimeForSql = 0, minExecutionTimeForOperation = 0)
    @com.landawn.abacus.jdbc.annotation.SqlLogEnabled
    public interface PerfLogDao extends CrudDao<UserAccount, Long, PerfLogDao> {
        @Query("SELECT COUNT(*) FROM user_account")
        long perfCount() throws SQLException;
    }

    @Test
    public void testPerfLogAndSqlLogEnabled() throws SQLException {
        final PerfLogDao perfDao = JdbcUtil.createDao(PerfLogDao.class, ds);
        dao.insert(newUser("P1", "Perf", 30));
        assertEquals(1L, perfDao.perfCount());
    }

    // =====================================================================================
    // @Transactional default methods (commit + rollback-on-exception) and a plain default
    // method composing other DAO methods (DaoImpl special-cases interface default methods).
    // =====================================================================================
    public interface TxDao extends CrudDao<UserAccount, Long, TxDao> {

        @com.landawn.abacus.jdbc.annotation.Transactional
        default void insertTwoInTx(final UserAccount a, final UserAccount b) throws SQLException {
            insert(a);
            insert(b);
        }

        @com.landawn.abacus.jdbc.annotation.Transactional
        default void insertThenFail(final UserAccount a) throws SQLException {
            insert(a);
            throw new RuntimeException("intentional rollback");
        }

        // plain (non-transactional) default method that delegates to inherited DAO methods.
        default Optional<UserAccount> findByFullName(final String first, final String last) throws SQLException {
            return findFirst(Filters.eq("firstName", first).and(Filters.eq("lastName", last)));
        }
    }

    @Test
    public void testTransactional_Commit() throws SQLException {
        final TxDao txDao = JdbcUtil.createDao(TxDao.class, ds);
        txDao.insertTwoInTx(newUser("Tx1", "Commit", 1), newUser("Tx2", "Commit", 2));
        assertEquals(2, dao.count(Filters.eq("lastName", "Commit")));
    }

    @Test
    public void testTransactional_Rollback() throws SQLException {
        final TxDao txDao = JdbcUtil.createDao(TxDao.class, ds);
        assertThrows(RuntimeException.class, () -> txDao.insertThenFail(newUser("Tx3", "Rolled", 3)));
        // the insert inside the failed transaction must have been rolled back.
        assertEquals(0, dao.count(Filters.eq("lastName", "Rolled")));
    }

    @Test
    public void testDefaultMethod_Dispatch() throws SQLException {
        final TxDao txDao = JdbcUtil.createDao(TxDao.class, ds);
        dao.insert(newUser("Def", "Method", 44));

        final Optional<UserAccount> found = txDao.findByFullName("Def", "Method");
        assertTrue(found.isPresent());
        assertEquals(44, found.get().getAge());
    }

    // =====================================================================================
    // @Cache + @CacheResult on a NoUpdate DAO: the second call with identical args is served
    // from cache, so data inserted after the first call is NOT reflected (proves cache hit).
    // =====================================================================================
    @com.landawn.abacus.jdbc.annotation.Cache(capacity = 100, evictDelay = 60000)
    public interface CachedUserDao extends NoUpdateCrudDao<UserAccount, Long, CachedUserDao> {
        @com.landawn.abacus.jdbc.annotation.CacheResult(enabled = true)
        @Query("SELECT * FROM user_account WHERE last_name = :ln ORDER BY id")
        List<UserAccount> findCachedByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;
    }

    @Test
    public void testCachedQuery_Cached() throws SQLException {
        final CachedUserDao cachedDao = JdbcUtil.createDao(CachedUserDao.class, ds);

        dao.insert(newUser("C1", "CacheGrp", 10));
        // first call populates the cache for arg "CacheGrp".
        assertEquals(1, cachedDao.findCachedByLastName("CacheGrp").size());

        // mutate the underlying table via a different DAO -> the cache is NOT invalidated.
        dao.insert(newUser("C2", "CacheGrp", 11));
        dao.insert(newUser("C3", "CacheGrp", 12));

        // second call with the same arg returns the cached (now stale) result of size 1.
        assertEquals(1, cachedDao.findCachedByLastName("CacheGrp").size());

        // a different arg is a cache miss -> fresh query against the (empty for this name) table.
        assertEquals(0, cachedDao.findCachedByLastName("CacheOther").size());
    }

    // =====================================================================================
    // @MappedByKey (Map-returning query keyed by a column) and @MergedById (row merge by id).
    // =====================================================================================
    public interface MappedUserDao extends CrudDao<UserAccount, Long, MappedUserDao> {
        @Query("SELECT id, first_name, last_name, age, active FROM user_account ORDER BY id")
        @com.landawn.abacus.jdbc.annotation.MappedByKey("id")
        Map<Long, UserAccount> findAllMapped() throws SQLException;

        @Query("SELECT id, first_name, last_name, age, active FROM user_account WHERE id = :id")
        @com.landawn.abacus.jdbc.annotation.MergedById("id")
        Optional<UserAccount> findMergedById(@com.landawn.abacus.jdbc.annotation.Bind("id") long id) throws SQLException;

        @Query("SELECT id, first_name, last_name, age, active FROM user_account ORDER BY id")
        @com.landawn.abacus.jdbc.annotation.MergedById("id")
        List<UserAccount> listMerged() throws SQLException;
    }

    // @MappedByKey returns a Map keyed by the named column value.
    @Test
    public void testMappedByKey() throws SQLException {
        final long id1 = dao.insert(newUser("Map1", "Grp", 10));
        final long id2 = dao.insert(newUser("Map2", "Grp", 20));
        final MappedUserDao mDao = JdbcUtil.createDao(MappedUserDao.class, ds);

        final Map<Long, UserAccount> map = mDao.findAllMapped();
        assertEquals(2, map.size());
        assertEquals("Map1", map.get(id1).getFirstName());
        assertEquals("Map2", map.get(id2).getFirstName());
    }

    // @MergedById collapses rows sharing an id into a single entity (Optional return).
    @Test
    public void testMergedById_Optional() throws SQLException {
        final long id = dao.insert(newUser("Merge", "One", 33));
        final MappedUserDao mDao = JdbcUtil.createDao(MappedUserDao.class, ds);

        final Optional<UserAccount> found = mDao.findMergedById(id);
        assertTrue(found.isPresent());
        assertEquals(33, found.get().getAge());
    }

    // @MergedById over a multi-row list result (one entity per distinct id).
    @Test
    public void testMergedById_List() throws SQLException {
        dao.insert(newUser("ML1", "G", 1));
        dao.insert(newUser("ML2", "G", 2));
        final MappedUserDao mDao = JdbcUtil.createDao(MappedUserDao.class, ds);

        final List<UserAccount> list = mDao.listMerged();
        assertEquals(2, list.size());
    }

}
