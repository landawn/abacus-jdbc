package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
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
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.jdbc.annotation.DaoConfig;
import com.landawn.abacus.jdbc.annotation.FetchColumnByEntityClass;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.annotation.SqlLogEnabled;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.Dao;
import com.landawn.abacus.jdbc.dao.NonUpdateCrudDao;
import com.landawn.abacus.jdbc.dao.UncheckedCrudDao;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.SqlDialect;
import com.landawn.abacus.query.SqlDialect.ProductInfo;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.query.condition.Criteria;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.u;
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

    @Test
    public void testBatchOperationsRejectNullEntitiesButRetainNullScalarIds() throws SQLException {
        assertThrows(IllegalArgumentException.class, () -> dao.batchInsert(Arrays.asList((UserAccount) null)));
        assertThrows(IllegalArgumentException.class, () -> dao.batchDelete(Arrays.asList((UserAccount) null)));
        assertEquals(0, dao.batchDeleteByIds(Arrays.asList((Long) null)));
    }

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

    public interface UniquePrimitiveDao extends CrudDao<UserAccount, Long, UniquePrimitiveDao> {
        @Query(value = "SELECT age FROM user_account", op = QueryOperation.queryForUnique)
        u.OptionalBoolean booleanValue() throws SQLException;

        @Query(value = "SELECT age FROM user_account", op = QueryOperation.findOnlyOne)
        u.OptionalChar charValue() throws SQLException;

        @Query("SELECT age FROM user_account")
        u.OptionalByte queryForUniqueByte() throws SQLException;

        @Query("SELECT age FROM user_account")
        u.OptionalShort findOnlyOneShort() throws SQLException;

        @Query(value = "SELECT age FROM user_account", op = QueryOperation.queryForUnique)
        u.OptionalInt intValue() throws SQLException;

        @Query(value = "SELECT age FROM user_account", op = QueryOperation.findOnlyOne)
        u.OptionalLong longValue() throws SQLException;

        @Query("SELECT age FROM user_account")
        u.OptionalFloat queryForUniqueFloat() throws SQLException;

        @Query("SELECT age FROM user_account")
        u.OptionalDouble findOnlyOneDouble() throws SQLException;

        @Query(value = "SELECT age FROM user_account", op = QueryOperation.queryForSingle)
        u.OptionalInt queryForUniqueFirstRow() throws SQLException;
    }

    @Test
    public void testUniquePrimitiveOptionalsPreserveEmptyNullAndDuplicateSemantics() throws SQLException {
        final UniquePrimitiveDao uniqueDao = JdbcUtil.createDao(UniquePrimitiveDao.class, ds);
        final List<Throwables.Supplier<?, SQLException>> queries = List.of(uniqueDao::booleanValue, uniqueDao::charValue, uniqueDao::queryForUniqueByte,
                uniqueDao::findOnlyOneShort, uniqueDao::intValue, uniqueDao::longValue, uniqueDao::queryForUniqueFloat, uniqueDao::findOnlyOneDouble);
        final List<Object> emptyValues = List.of(u.OptionalBoolean.empty(), u.OptionalChar.empty(), u.OptionalByte.empty(), u.OptionalShort.empty(),
                u.OptionalInt.empty(), u.OptionalLong.empty(), u.OptionalFloat.empty(), u.OptionalDouble.empty());
        final List<Object> nullValues = List.of(u.OptionalBoolean.of(false), u.OptionalChar.of((char) 0), u.OptionalByte.of((byte) 0),
                u.OptionalShort.of((short) 0), u.OptionalInt.of(0), u.OptionalLong.of(0), u.OptionalFloat.of(0), u.OptionalDouble.of(0));
        final List<Object> oneValues = List.of(u.OptionalBoolean.of(true), u.OptionalChar.of('1'), u.OptionalByte.of((byte) 1), u.OptionalShort.of((short) 1),
                u.OptionalInt.of(1), u.OptionalLong.of(1), u.OptionalFloat.of(1), u.OptionalDouble.of(1));

        for (int i = 0; i < queries.size(); i++) {
            assertEquals(emptyValues.get(i), queries.get(i).get());
        }

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("INSERT INTO user_account (age) VALUES (NULL)");
            for (int i = 0; i < queries.size(); i++) {
                assertEquals(nullValues.get(i), queries.get(i).get());
            }

            stmt.executeUpdate("UPDATE user_account SET age = 1");
            for (int i = 0; i < queries.size(); i++) {
                assertEquals(oneValues.get(i), queries.get(i).get());
            }

            stmt.executeUpdate("INSERT INTO user_account (age) VALUES (1)");
            for (final Throwables.Supplier<?, SQLException> query : queries) {
                assertThrows(DuplicateResultException.class, query::get);
            }
            assertEquals(u.OptionalInt.of(1), uniqueDao.queryForUniqueFirstRow());
        }
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

    @Table("mixed_id_account")
    public static class MixedIdAccount {
        @Id
        private Long id;
        private String name;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }

    public interface MixedIdAccountDao extends CrudDao<MixedIdAccount, Long, MixedIdAccountDao> {
    }

    private DataSource ds;
    private UserAccountDao dao;
    private TypeProbeDao typeDao;
    private MixedIdAccountDao mixedIdDao;

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
            st.execute("CREATE TABLE IF NOT EXISTS mixed_id_account (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, name VARCHAR(64))");
            st.execute("DELETE FROM type_probe");
            st.execute("INSERT INTO type_probe (id, char_val, date_val, time_val, ts_val, bytes_val) "
                    + "VALUES (1, 'A', DATE '2020-01-15', TIME '10:30:00', TIMESTAMP '2020-01-15 10:30:00', X'0102')");
        }

        dao = JdbcUtil.createDao(UserAccountDao.class, ds);
        typeDao = JdbcUtil.createDao(TypeProbeDao.class, ds);
        mixedIdDao = JdbcUtil.createDao(MixedIdAccountDao.class, ds);
    }

    @AfterAll
    public void dropDb() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS user_account");
            st.execute("DROP TABLE IF EXISTS type_probe");
            st.execute("DROP TABLE IF EXISTS mixed_id_account");
        }
    }

    @BeforeEach
    public void cleanTable() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("TRUNCATE TABLE user_account");
            st.execute("TRUNCATE TABLE mixed_id_account RESTART IDENTITY");
        }
    }

    // A standalone ORDER BY satisfies paginate's ordering requirement and still receives the page LIMIT.
    @Test
    public void testPaginateWithStandaloneOrderByLimitsFirstPage() throws SQLException {
        final List<Long> ids = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            ids.add(dao.insert(newUser("P" + i, "Page", 20 + i)));
        }

        try (var pages = dao.paginate(Filters.orderBy("id"), 2, (query, previousPage) -> {
        })) {
            final Dataset firstPage = pages.first().orElseThrow();
            assertEquals(ids.subList(0, 2), idsOf(firstPage));
        }

        // A literal outer ORDER BY with a keyset placeholder pages through every row exactly once.
        final List<Long> pagedIds = new ArrayList<>();

        try (var pages = dao.paginate(Filters.expr("id > ? ORDER BY id"), 2,
                (query, previousPage) -> query.setLong(1, previousPage == null ? 0 : idsOf(previousPage).get(previousPage.size() - 1)))) {
            pages.forEach(page -> pagedIds.addAll(idsOf(page)));
        }

        assertEquals(ids, pagedIds);
    }

    private static List<Long> idsOf(final Dataset page) {
        final String idColumn = page.columnNames().stream().filter("id"::equalsIgnoreCase).findFirst().orElseThrow();
        final List<Long> ids = new ArrayList<>();

        for (final Object id : page.getColumn(idColumn)) {
            ids.add(((Number) id).longValue());
        }

        return ids;
    }

    public interface DatasetTypingDao extends CrudDao<UserAccount, Long, DatasetTypingDao> {
        @Query("SELECT id, first_name, age * 2 AS double_age FROM user_account ORDER BY id")
        @FetchColumnByEntityClass(true)
        Dataset withEntityTypes() throws SQLException;

        @Query("SELECT id, first_name, age * 2 AS double_age FROM user_account ORDER BY id")
        @FetchColumnByEntityClass(false)
        Dataset withDefaultTypes() throws SQLException;
    }

    // FetchColumnByEntityClass only chooses how values are read; neither setting drops or renames columns.
    @Test
    public void testFetchColumnByEntityClassKeepsEveryColumnLabel() throws SQLException {
        dao.insert(newUser("Ada", "Lovelace", 36));
        final DatasetTypingDao typingDao = JdbcUtil.createDao(DatasetTypingDao.class, ds);

        for (final Dataset dataset : List.of(typingDao.withEntityTypes(), typingDao.withDefaultTypes())) {
            assertEquals(List.of("ID", "FIRST_NAME", "DOUBLE_AGE"), dataset.columnNames());
            assertEquals(1, dataset.size());
            assertEquals("Ada", dataset.getColumn("FIRST_NAME").get(0));
            assertEquals(72, ((Number) dataset.getColumn("DOUBLE_AGE").get(0)).intValue());
        }
    }

    // insert returns the generated key; getOrNull / get / exists round-trip the row.
    @Test
    public void testInsertAndGet() throws SQLException {
        final Long id = dao.insert(newUser("Ada", "Lovelace", 36));
        assertNotNull(id);

        final UserAccount loaded = dao.getOrNull(id);
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

        final UserAccount loaded = dao.getOrNull(id, List.of("id", "firstName"));
        assertNotNull(loaded);
        assertEquals("Grace", loaded.getFirstName());
        // lastName was not selected.
        assertEquals(null, loaded.getLastName());
    }

    // update(entity), update(prop,val,id) and update(map,id) all persist changes.
    @Test
    public void testUpdateVariants() throws SQLException {
        final Long id = dao.insert(newUser("Alan", "Turing", 41));

        final UserAccount u = dao.getOrNull(id);
        u.setAge(42);
        assertEquals(1, dao.update(u));
        assertEquals(42, dao.getOrNull(id).getAge());

        assertEquals(1, dao.update("lastName", "T.", id));
        assertEquals("T.", dao.getOrNull(id).getLastName());

        assertEquals(1, dao.update(Map.of("firstName", "Alan M.", "age", 43), id));
        final UserAccount after = dao.getOrNull(id);
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

        final UserAccount u2 = dao.getOrNull(id2);
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
        assertEquals(130, dao.getOrNull(ids.get(0)).getAge());

        assertEquals(5, dao.batchDelete(loaded));
        assertEquals(0, dao.count(Filters.eq("lastName", "User")));
    }

    @Test
    public void testBatchInsert_MixedGeneratedAndExplicitIds() throws SQLException {
        final MixedIdAccount explicitId1 = new MixedIdAccount();
        explicitId1.setName("Explicit-1");
        explicitId1.setId(10_000L);
        final MixedIdAccount generatedId1 = new MixedIdAccount();
        generatedId1.setName("Generated-1");
        final MixedIdAccount explicitId2 = new MixedIdAccount();
        explicitId2.setName("Explicit-2");
        explicitId2.setId(10_001L);
        final MixedIdAccount generatedId2 = new MixedIdAccount();
        generatedId2.setName("Generated-2");

        final List<Long> ids = mixedIdDao.batchInsert(List.of(explicitId1, generatedId1, explicitId2, generatedId2), 1);

        assertEquals(List.of(explicitId1.getId(), generatedId1.getId(), explicitId2.getId(), generatedId2.getId()), ids);
        assertEquals(10_000L, explicitId1.getId());
        assertEquals(10_001L, explicitId2.getId());
        assertNotNull(generatedId1.getId());
        assertNotNull(generatedId2.getId());
        assertEquals("Generated-1", mixedIdDao.getOrNull(generatedId1.getId()).getName());
        assertEquals("Generated-2", mixedIdDao.getOrNull(generatedId2.getId()).getName());
        assertEquals("Explicit-1", mixedIdDao.getOrNull(explicitId1.getId()).getName());
        assertEquals("Explicit-2", mixedIdDao.getOrNull(explicitId2.getId()).getName());
    }

    // Regression: the mixed generated/explicit-id batchInsert wrote each run's generated IDs back onto its entities
    // before the enclosing transaction committed. When a later run failed, the transaction rolled the earlier rows
    // back but those entities kept the IDs of rows that no longer existed (so a retry inserted them as explicit IDs).
    @Test
    public void testBatchInsert_MixedIds_FailureDoesNotLeaveRolledBackIdsOnEntities() throws SQLException {
        final MixedIdAccount existing = new MixedIdAccount();
        existing.setName("Existing");
        existing.setId(10_000L);
        mixedIdDao.insert(existing);

        final MixedIdAccount generatedId = new MixedIdAccount();
        generatedId.setName("Generated");
        final MixedIdAccount duplicateExplicitId = new MixedIdAccount();
        duplicateExplicitId.setName("Duplicate");
        duplicateExplicitId.setId(10_000L);

        assertThrows(SQLException.class, () -> mixedIdDao.batchInsert(List.of(generatedId, duplicateExplicitId), 10));

        assertEquals(null, generatedId.getId(), "an ID of a rolled-back row must not be written back");
        assertEquals(1, mixedIdDao.count(Filters.isNotNull("id")));
        assertFalse(mixedIdDao.findFirst(Filters.eq("name", "Generated")).isPresent());
    }

    @Test
    public void testBatchSave_MixedGeneratedAndExplicitIds() throws SQLException {
        final MixedIdAccount generatedId1 = new MixedIdAccount();
        generatedId1.setName("Generated-1");
        final MixedIdAccount explicitId1 = new MixedIdAccount();
        explicitId1.setName("Explicit-1");
        explicitId1.setId(10_000L);
        final MixedIdAccount generatedId2 = new MixedIdAccount();
        generatedId2.setName("Generated-2");
        final MixedIdAccount explicitId2 = new MixedIdAccount();
        explicitId2.setName("Explicit-2");
        explicitId2.setId(10_001L);

        mixedIdDao.batchSave(List.of(generatedId1, explicitId1, generatedId2, explicitId2), 1);

        assertEquals(4, mixedIdDao.count(Filters.isNotNull("id")));
        assertEquals("Explicit-1", mixedIdDao.getOrNull(10_000L).getName());
        assertEquals("Explicit-2", mixedIdDao.getOrNull(10_001L).getName());
        assertTrue(mixedIdDao.findFirst(Filters.eq("name", "Generated-1")).isPresent());
        assertTrue(mixedIdDao.findFirst(Filters.eq("name", "Generated-2")).isPresent());
    }

    @Table("composite_key_row")
    public static class CompositeKeyRow {
        @Id
        private Long tenantId;
        @Id
        private Long rowId;
        private String name;

        public Long getTenantId() {
            return tenantId;
        }

        public void setTenantId(final Long tenantId) {
            this.tenantId = tenantId;
        }

        public Long getRowId() {
            return rowId;
        }

        public void setRowId(final Long rowId) {
            this.rowId = rowId;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }

    // A plain (non-CRUD) Dao declares no ID type.
    public interface CompositeKeyRowDao extends Dao<CompositeKeyRow, CompositeKeyRowDao> {
    }

    public interface CompositeRefreshDao extends CrudDao<CompositeKeyRow, GeneratedKeyRowId, CompositeRefreshDao> {
    }

    public interface UncheckedCompositeRefreshDao extends UncheckedCrudDao<CompositeKeyRow, GeneratedKeyRowId, UncheckedCompositeRefreshDao> {
    }

    @Test
    public void testRefreshSupportsDeclaredCompositeIdBeans() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS composite_key_row (tenant_id BIGINT, row_id BIGINT, name VARCHAR(64), PRIMARY KEY (tenant_id, row_id))");
            st.execute("DELETE FROM composite_key_row");
            st.execute("INSERT INTO composite_key_row VALUES (1, 2, 'current')");
        }

        try {
            final CompositeRefreshDao checked = JdbcUtil.createDao(CompositeRefreshDao.class, ds);
            final UncheckedCompositeRefreshDao unchecked = JdbcUtil.createDao(UncheckedCompositeRefreshDao.class, ds);
            final CompositeKeyRow row = newCompositeKeyRow(1, 2, "stale");
            assertTrue(checked.refresh(row, List.of("name")));
            assertEquals("current", row.getName());
            row.setName("stale again");
            assertTrue(unchecked.refresh(row));
            assertEquals("current", row.getName());
            assertEquals(1L, row.getTenantId());
            assertEquals(2L, row.getRowId());

            final CompositeKeyRow missing = newCompositeKeyRow(2, 2, "unchanged");
            assertFalse(checked.refresh(missing));
            assertFalse(unchecked.refresh(missing, List.of("name")));
            assertEquals("unchanged", missing.getName());
        } finally {
            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS composite_key_row");
            }
        }
    }

    private static CompositeKeyRow newCompositeKeyRow(final long tenantId, final long rowId, final String name) {
        final CompositeKeyRow row = new CompositeKeyRow();
        row.setTenantId(tenantId);
        row.setRowId(rowId);
        row.setName(name);
        return row;
    }

    // The entity class itself declared as the (composite) ID type.
    public interface EntityAsCompositeIdDao extends CrudDao<CompositeKeyRow, CompositeKeyRow, EntityAsCompositeIdDao> {
    }

    public interface UncheckedEntityAsCompositeIdDao extends UncheckedCrudDao<CompositeKeyRow, CompositeKeyRow, UncheckedEntityAsCompositeIdDao> {
    }

    // update(Map, id) matched a bean/record composite id on EVERY property of the id object (Filters.allEqual(id)): with the
    // entity class as the ID type its non-id property (name) joined the WHERE clause, so the row was missed. Like
    // getOrNull/exists/deleteById and batchGet, only the id properties may locate the row.
    @Test
    public void testUpdateByCompositeBeanIdMatchesOnlyIdProperties() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS composite_key_row (tenant_id BIGINT, row_id BIGINT, name VARCHAR(64), PRIMARY KEY (tenant_id, row_id))");
            st.execute("DELETE FROM composite_key_row");
            st.execute("INSERT INTO composite_key_row VALUES (1, 2, 'current')");
        }

        try {
            final EntityAsCompositeIdDao entityAsIdDao = JdbcUtil.createDao(EntityAsCompositeIdDao.class, ds);
            final CompositeKeyRow id = newCompositeKeyRow(1, 2, null);

            assertTrue(entityAsIdDao.exists(id));
            assertEquals(1, entityAsIdDao.update(Map.of("name", "renamed"), id));
            assertEquals("renamed", entityAsIdDao.getOrNull(id).getName());

            // A stale non-id value on the id object must not change which row is updated.
            assertEquals(1, entityAsIdDao.update("name", "renamed again", newCompositeKeyRow(1, 2, "stale")));
            assertEquals("renamed again", entityAsIdDao.getOrNull(id).getName());
            assertEquals(0, entityAsIdDao.update(Map.of("name", "missing"), newCompositeKeyRow(2, 2, null)));

            final UncheckedEntityAsCompositeIdDao uncheckedEntityAsIdDao = JdbcUtil.createDao(UncheckedEntityAsCompositeIdDao.class, ds);
            assertEquals(1, uncheckedEntityAsIdDao.update(Map.of("name", "unchecked"), newCompositeKeyRow(1, 2, "stale")));
            assertEquals("unchecked", uncheckedEntityAsIdDao.getOrNull(id).getName());

            // A dedicated ID class (id properties only) keeps working.
            final GeneratedKeyRowId keyId = new GeneratedKeyRowId();
            keyId.setTenantId(1L);
            keyId.setRowId(2L);
            assertEquals(1, JdbcUtil.createDao(CompositeRefreshDao.class, ds).update(Map.of("name", "by id class"), keyId));
            assertEquals("by id class", entityAsIdDao.getOrNull(id).getName());
        } finally {
            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS composite_key_row");
            }
        }
    }

    // save/batchSave on a plain Dao whose entity has a composite @Id used to fail with a NullPointerException: with no
    // declared ID type, the composite ID was built as an instance of the (null) ID class.
    @Test
    public void testSaveAndBatchSave_NonCrudDaoWithCompositeId() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS composite_key_row (tenant_id BIGINT, row_id BIGINT, name VARCHAR(64), PRIMARY KEY (tenant_id, row_id))");
            st.execute("DELETE FROM composite_key_row");
        }

        try {
            final CompositeKeyRowDao compositeDao = JdbcUtil.createDao(CompositeKeyRowDao.class, ds);

            compositeDao.save(newCompositeKeyRow(1, 1, "single"));
            compositeDao.batchSave(List.of(newCompositeKeyRow(1, 2, "batch-1"), newCompositeKeyRow(2, 1, "batch-2")), 1);
            compositeDao.batchSave(List.of(newCompositeKeyRow(3, 1, "batch-3")));

            assertEquals(4, compositeDao.count(Filters.isNotNull("tenantId")));
            assertEquals("batch-2", compositeDao.findOnlyOne(Filters.and(Filters.eq("tenantId", 2L), Filters.eq("rowId", 1L))).get().getName());
        } finally {
            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS composite_key_row");
            }
        }
    }

    @Table("generated_key_row")
    public static class GeneratedKeyRow {
        @Id
        private Long tenantId;
        @Id
        private Long rowId;
        private String name;

        public Long getTenantId() {
            return tenantId;
        }

        public void setTenantId(final Long tenantId) {
            this.tenantId = tenantId;
        }

        public Long getRowId() {
            return rowId;
        }

        public void setRowId(final Long rowId) {
            this.rowId = rowId;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }

    // Composite ID class without @Id annotations: its properties are matched to the entity's @Id properties by name.
    public static class GeneratedKeyRowId {
        private Long tenantId;
        private Long rowId;

        public Long getTenantId() {
            return tenantId;
        }

        public void setTenantId(final Long tenantId) {
            this.tenantId = tenantId;
        }

        public Long getRowId() {
            return rowId;
        }

        public void setRowId(final Long rowId) {
            this.rowId = rowId;
        }
    }

    public interface GeneratedKeyRowDao extends CrudDao<GeneratedKeyRow, GeneratedKeyRowId, GeneratedKeyRowDao> {
    }

    // batchInsert re-checked the generated composite IDs with the generic default-ID test, which treats any bean whose
    // class declares no @Id property as "all default", so the generated IDs were dropped and never set on the entities.
    @Test
    public void testBatchInsert_GeneratedCompositeIdOfIdClassWithoutIdAnnotation_IsSetOnEntities() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS generated_key_row (tenant_id BIGINT DEFAULT 7, row_id BIGINT AUTO_INCREMENT, name VARCHAR(64), "
                    + "PRIMARY KEY (tenant_id, row_id))");
            st.execute("DELETE FROM generated_key_row");
        }

        try {
            final GeneratedKeyRowDao generatedKeyDao = JdbcUtil.createDao(GeneratedKeyRowDao.class, ds);
            final GeneratedKeyRow first = new GeneratedKeyRow();
            first.setName("first");
            final GeneratedKeyRow second = new GeneratedKeyRow();
            second.setName("second");

            final List<GeneratedKeyRowId> ids = generatedKeyDao.batchInsert(List.of(first, second));

            assertEquals(2, ids.size());
            assertEquals(7L, first.getTenantId());
            assertEquals(7L, second.getTenantId());
            assertNotNull(first.getRowId());
            assertNotNull(second.getRowId());
            assertTrue(first.getRowId() < second.getRowId());
            assertEquals(first.getRowId(), ids.get(0).getRowId());
            assertEquals(second.getRowId(), ids.get(1).getRowId());
            assertThrows(IllegalArgumentException.class, () -> generatedKeyDao.batchDeleteByIds(Arrays.asList((GeneratedKeyRowId) null)));
        } finally {
            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS generated_key_row");
            }
        }
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

    // count(cond) counts the matching records: a LIMIT/OFFSET or ORDER BY on the condition used to be applied to the single
    // COUNT(*) row, so an OFFSET skipped it (count 0) and ORDER BY failed with an aggregate error.
    @Test
    public void testCount_IgnoresLimitOffsetAndOrderBy() throws SQLException {
        dao.insert(newUser("Q1", "Cond", 18));
        dao.insert(newUser("Q2", "Cond", 25));
        dao.insert(newUser("Q3", "Cond", 25));
        dao.insert(newUser("Q4", "Other", 25));

        final Condition byLastName = Filters.eq("lastName", "Cond");

        assertEquals(3, dao.count(Criteria.builder().where(byLastName).limit(2).build()));
        assertEquals(3, dao.count(Criteria.builder().where(byLastName).limit(2, 1).build()));
        assertEquals(3, dao.count(Criteria.builder().where(byLastName).orderBy("id").limit(2, 1).build()));
        assertEquals(3, dao.count(Criteria.builder().where(byLastName).orderBy("id").build()));
        assertEquals(4, dao.count(Filters.limit(2, 1)));
        assertEquals(1, dao.count(Criteria.builder().where(Filters.eq("age", 18)).limit(1, 5).build()));
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
        assertEquals("Sert-Updated", dao.getOrNull(inserted.getId()).getLastName());
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
        assertEquals("Updated", dao.getOrNull(existingId).getLastName());
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
        assertEquals(71, dao.getOrNull(existingId).getAge());
        assertEquals(3, dao.count(Filters.eq("firstName", "Multi").or(Filters.eq("lastName", "Key"))));
    }

    // batchUpsert resolves each match name through the bean metadata (which also accepts a non-canonical
    // spelling such as "FIRSTNAME"). The multi-prop path always queried by the resolved property name, but the
    // single-prop lookup used the raw spelling and failed with "Column FIRSTNAME not found".
    @Test
    public void testBatchUpsert_SingleMatchPropUsesResolvedPropertyName() throws SQLException {
        final Long existingId = dao.insert(newUser("Resolved", "Before", 40));

        final List<UserAccount> result = dao.batchUpsert(Arrays.asList(newUser("Resolved", "After", 41), newUser("ResolvedNew", "New", 42)),
                List.of("FIRSTNAME"), 10);

        assertEquals(2, result.size());
        assertEquals("After", dao.getOrNull(existingId).getLastName());
        assertEquals(1, dao.count(Filters.eq("firstName", "Resolved")));
        assertEquals(1, dao.count(Filters.eq("firstName", "ResolvedNew")));
    }

    public interface UncheckedUserAccountCrudDao extends com.landawn.abacus.jdbc.dao.UncheckedCrudDao<UserAccount, Long, UncheckedUserAccountCrudDao> {
    }

    public interface UncheckedUserAccountDao extends com.landawn.abacus.jdbc.dao.UncheckedDao<UserAccount, UncheckedUserAccountDao> {
    }

    // upsert(T, matchPropNames) used to render the raw spelling into SQL: "FIRSTNAME" resolved the property value
    // but referenced a non-existent column. Covers Dao/CrudDao (inherited), UncheckedCrudDao and UncheckedDao on both
    // the update and the insert path; an unknown name is still rejected.
    @Test
    public void testUpsert_MatchPropNamesUseResolvedPropertyNames() throws SQLException {
        final UncheckedUserAccountCrudDao uncheckedCrudDao = JdbcUtil.createDao(UncheckedUserAccountCrudDao.class, ds);
        final UncheckedUserAccountDao uncheckedDao = JdbcUtil.createDao(UncheckedUserAccountDao.class, ds);

        final Long id1 = dao.insert(newUser("UpsName1", "Before", 40));
        final Long id2 = dao.insert(newUser("UpsName2", "Before", 41));
        final Long id3 = dao.insert(newUser("UpsName3", "Before", 42));

        dao.upsert(newUser("UpsName1", "After", 50), List.of("FIRSTNAME"));
        uncheckedCrudDao.upsert(newUser("UpsName2", "After", 51), List.of("first_name"));
        uncheckedDao.upsert(newUser("UpsName3", "After", 52), List.of("FirstName", "LASTNAME", "age"));

        assertEquals("After", dao.getOrNull(id1).getLastName());
        assertEquals("After", dao.getOrNull(id2).getLastName());
        // No row matched (UpsName3, After, 52), so a new row is inserted and the existing one is untouched.
        assertEquals("Before", dao.getOrNull(id3).getLastName());
        assertEquals(2, dao.count(Filters.eq("firstName", "UpsName3")));

        dao.upsert(newUser("UpsNameNew", "New", 60), List.of("FIRSTNAME"));
        assertEquals(1, dao.count(Filters.eq("firstName", "UpsNameNew")));

        assertThrows(IllegalArgumentException.class, () -> dao.upsert(newUser("UpsName1", "X", 1), List.of("noSuchProp")));
    }

    // batchUpsert de-duplicates lookup keys before splitting them into query batches: equal match
    // keys landing in different query batches previously returned the same database row more than
    // once, making the throwing merger report a spurious duplicate result (CrudDao de-dup comment).
    @Test
    public void testBatchUpsert_DuplicateMatchKeysAcrossQueryBatches() throws SQLException {
        final Long existingId = dao.insert(newUser("Dup", "Existing", 80));

        final List<UserAccount> batch = new ArrayList<>();
        batch.add(newUser("Dup", "First", 81));
        batch.add(newUser("Dup", "Second", 82));
        batch.add(newUser("Dup", "Third", 83));
        batch.add(newUser("Other", "New", 30));

        // batchSize=2 splits the four (pre-dedup) keys into two query batches that share the "Dup" key.
        final List<UserAccount> result = assertDoesNotThrow(() -> dao.batchUpsert(batch, List.of("firstName"), 2));
        assertEquals(4, result.size());

        // Still exactly one "Dup" row: the three duplicate-key entities all updated the same existing row.
        assertEquals(1, dao.count(Filters.eq("firstName", "Dup")));
        assertEquals(existingId, dao.list(Filters.eq("firstName", "Dup")).get(0).getId());
    }

    // Regression: batchUpsert inserts the new entities and then updates the matched ones in one transaction, and
    // batchInsert writes the generated IDs onto the new entities immediately. When the update then failed, the rollback
    // discarded the inserted rows but those entities kept the IDs of rows that no longer existed (so a retry inserted
    // them with those IDs as explicit values). Covers CrudDao and the UncheckedCrudDao delegation.
    @Test
    public void testBatchUpsert_FailedUpdateDoesNotLeaveRolledBackIdsOnInsertedEntities() throws SQLException {
        final Long existingId = dao.insert(newUser("UpsertKeep", "Before", 40));
        final String tooLongLastName = "x".repeat(65); // last_name is VARCHAR(64)

        final UserAccount fresh = newUser("UpsertFresh", "New", 41);
        assertThrows(SQLException.class, () -> dao.batchUpsert(List.of(fresh, newUser("UpsertKeep", tooLongLastName, 42)), List.of("firstName"), 10));

        assertEquals(null, fresh.getId(), "an ID of a rolled-back row must not be written back");
        assertEquals(0, dao.count(Filters.eq("firstName", "UpsertFresh")));
        assertEquals("Before", dao.getOrNull(existingId).getLastName());

        final UncheckedUserAccountCrudDao uncheckedCrudDao = JdbcUtil.createDao(UncheckedUserAccountCrudDao.class, ds);
        final UserAccount uncheckedFresh = newUser("UpsertFresh", "New", 43);
        assertThrows(com.landawn.abacus.exception.UncheckedSQLException.class,
                () -> uncheckedCrudDao.batchUpsert(List.of(uncheckedFresh, newUser("UpsertKeep", tooLongLastName, 44)), List.of("firstName"), 10));

        assertEquals(null, uncheckedFresh.getId(), "an ID of a rolled-back row must not be written back");
        assertEquals(0, dao.count(Filters.eq("firstName", "UpsertFresh")));

        // The restored entity can be upserted again once the conflicting update is fixed.
        final List<UserAccount> result = dao.batchUpsert(List.of(fresh, newUser("UpsertKeep", "After", 45)), List.of("firstName"), 10);
        assertEquals(2, result.size());
        assertNotNull(fresh.getId());
        assertEquals("UpsertFresh", dao.getOrNull(fresh.getId()).getFirstName());
        assertEquals("After", dao.getOrNull(existingId).getLastName());
    }

    // Joined to the caller's transaction, the failed upsert marks that transaction rollback-only, so the inserted rows are
    // discarded with it and the IDs are restored as well.
    @Test
    public void testBatchUpsert_FailureInsideOuterTransactionRestoresInsertedIds() throws SQLException {
        dao.insert(newUser("UpsertKeep", "Before", 40));
        final UserAccount fresh = newUser("UpsertFresh", "New", 41);
        final SqlTransaction tran = JdbcUtil.beginTransaction(ds);

        try {
            assertThrows(SQLException.class, () -> dao.batchUpsert(List.of(fresh, newUser("UpsertKeep", "x".repeat(65), 42)), List.of("firstName"), 10));
            assertEquals(null, fresh.getId(), "an ID of a row that will be rolled back must not be written back");
        } finally {
            tran.rollbackIfNotCommitted();
        }

        assertEquals(0, dao.count(Filters.eq("firstName", "UpsertFresh")));
    }

    // SqlTransaction.commit() rethrows an unchecked failure from resetting/releasing the connection after the rows were
    // committed; the generated IDs of those committed rows must then be kept, not restored. (Also passes on pass9-baseline,
    // which never restored IDs.)
    @Test
    public void testBatchUpsert_ConnectionCleanupFailureAfterCommitKeepsInsertedIds() throws SQLException {
        final Long existingId = dao.insert(newUser("UpsertKeep", "Before", 40));
        final java.util.concurrent.atomic.AtomicBoolean failNextAutoCommitReset = new java.util.concurrent.atomic.AtomicBoolean();
        final DataSource cleanupFailingDs = org.mockito.Mockito.mock(DataSource.class, org.mockito.AdditionalAnswers.delegatesTo(ds));

        org.mockito.Mockito.doAnswer(dsInvocation -> {
            final Connection conn = ds.getConnection();
            final Connection failingConn = org.mockito.Mockito.mock(Connection.class, org.mockito.AdditionalAnswers.delegatesTo(conn));

            org.mockito.Mockito.doAnswer(invocation -> {
                conn.commit();
                failNextAutoCommitReset.set(true);
                return null;
            }).when(failingConn).commit();

            org.mockito.Mockito.doAnswer(invocation -> {
                final boolean autoCommit = invocation.getArgument(0);

                if (autoCommit && failNextAutoCommitReset.getAndSet(false)) {
                    throw new IllegalStateException("connection cleanup failed after commit");
                }

                conn.setAutoCommit(autoCommit);
                return null;
            }).when(failingConn).setAutoCommit(org.mockito.ArgumentMatchers.anyBoolean());

            return failingConn;
        }).when(cleanupFailingDs).getConnection();

        final UserAccountDao cleanupFailingDao = JdbcUtil.createDao(UserAccountDao.class, cleanupFailingDs);
        final UserAccount fresh = newUser("UpsertFresh", "New", 41);

        assertThrows(IllegalStateException.class,
                () -> cleanupFailingDao.batchUpsert(List.of(fresh, newUser("UpsertKeep", "After", 42)), List.of("firstName"), 10));

        assertNotNull(fresh.getId(), "the rows were committed, so the generated ID must be kept");
        assertEquals("UpsertFresh", dao.getOrNull(fresh.getId()).getFirstName());
        assertEquals("After", dao.getOrNull(existingId).getLastName());
    }

    // getOrNull with an unknown id returns null and exists is false (no-row branch).
    @Test
    public void testGet_UnknownId_ReturnsNull() throws SQLException {
        assertEquals(null, dao.getOrNull(123456789L));
        assertFalse(dao.exists(123456789L));
    }

    // A malformed DAO interface is rejected at creation time.
    @Test
    public void testCreateDao_InvalidEntityId_Throws() {
        assertThrows(Exception.class, () -> JdbcUtil.createDao(NoIdBadDao.class, ds).insert(new NoIdBad()));
    }

    // A custom @Query UPDATE method declared with a WRAPPER return type (Integer/Long/Boolean) must dispatch into
    // the update path at QueryOperation.DEFAULT. Regression: DaoImpl#isUpdateReturnType used to be primitive-only, so
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
        assertEquals(11, dao.getOrNull(id).getAge());

        // Long return — receives the raw long count.
        final Long longResult = wrapDao.bumpAgeReturnLong(12, id);
        assertNotNull(longResult);
        assertEquals(Long.valueOf(1L), longResult);
        assertEquals(12, dao.getOrNull(id).getAge());

        // Boolean return — receives true when at least one row was affected.
        final Boolean boolResult = wrapDao.bumpAgeReturnBoolean(13, id);
        assertNotNull(boolResult);
        assertTrue(boolResult);
        assertEquals(13, dao.getOrNull(id).getAge());

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
        @com.landawn.abacus.jdbc.annotation.PerfLog(sqlPerfLogThresholdMillis = 7777L, daoMethodPerfLogThresholdMillis = 8888L)
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
        final long priorMinPerfLog = JdbcUtil.getSqlPerfLogThresholdMillis();

        // Closing the Hikari pool makes beginTransaction's getConnection() throw, simulating a
        // mid-method DataSource failure that occurs after the @SqlLogEnabled/@PerfLog wrapper has
        // already mutated the thread-locals.
        ((com.zaxxer.hikari.HikariDataSource) scratchDs).close();

        assertThrows(Exception.class, txDao::countWithTx);

        // The fix: even though beginTransaction threw, the wrapper's finally block must have
        // restored both thread-locals.
        assertEquals(priorSqlLogEnabled, JdbcUtil.isSqlLogEnabled(), "SQL log thread-local must be restored after beginTransaction failure");
        assertEquals(priorMinPerfLog, JdbcUtil.getSqlPerfLogThresholdMillis(), "Perf log thread-local must be restored after beginTransaction failure");
    }

    // Many-to-many @JoinedBy fixtures (UserRoleUserEntity/RoleLookupEntity/UserRoleLink) are the
    // package-level classes declared in JoinInfoTest: the intermediate entity must be a top-level class
    // in the entity's package.
    public interface UserRoleUserDao
            extends com.landawn.abacus.jdbc.dao.UncheckedDao<UserRoleUserEntity, UserRoleUserDao>,
            com.landawn.abacus.jdbc.dao.UncheckedJoinEntityHelper<UserRoleUserEntity, UserRoleUserDao> {
    }

    // Regression: the batch (more than one source entity) many-to-many load grouped the joined rows by the
    // RAW JDBC value of the intermediate table's key column (rs.getObject), while the source entities are
    // matched by their typed Java property value. With a column whose JDBC type differs from the property
    // type (INT column -> Integer vs long property -> Long; Oracle NUMBER -> BigDecimal), no key ever
    // matched and every source entity silently received an empty collection.
    @Test
    public void testLoadJoinEntities_ManyToManyBatch_KeyColumnTypeDiffersFromPropertyType() throws Exception {
        final DataSource scratchDs = JdbcUtil.createHikariDataSource("jdbc:h2:mem:daoimpl_m2m", "sa", "");

        try {
            try (Connection conn = scratchDs.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE user_role_user_entity (user_id INT PRIMARY KEY)");
                st.execute("CREATE TABLE role_lookup_entity (role_id INT PRIMARY KEY, name VARCHAR(32))");
                st.execute("CREATE TABLE user_role_link (user_id INT, role_id INT)");
                st.execute("INSERT INTO user_role_user_entity VALUES (1), (2), (3)");
                st.execute("INSERT INTO role_lookup_entity VALUES (10, 'admin'), (20, 'dev')");
                st.execute("INSERT INTO user_role_link VALUES (1, 10), (1, 20), (2, 20)");
            }

            final UserRoleUserDao userDao = JdbcUtil.createDao(UserRoleUserDao.class, scratchDs);
            final List<UserRoleUserEntity> users = new ArrayList<>();

            for (final long userId : new long[] { 1, 2, 3 }) {
                final UserRoleUserEntity user = new UserRoleUserEntity();
                user.setUserId(userId);
                users.add(user);
            }

            userDao.loadJoinEntities(users, "roles", null);

            assertEquals(2, users.get(0).getRoles().size());
            assertEquals(1, users.get(1).getRoles().size());
            assertEquals("dev", users.get(1).getRoles().get(0).getName());
            assertTrue(users.get(2).getRoles().isEmpty());

            // The single-entity path must agree with the batch path.
            final UserRoleUserEntity single = new UserRoleUserEntity();
            single.setUserId(1);
            userDao.loadJoinEntities(single, "roles", null);
            assertEquals(2, single.getRoles().size());
        } finally {
            ((com.zaxxer.hikari.HikariDataSource) scratchDs).close();
        }
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

    // Pins the queryForSingleValue/queryForUniqueValue Javadoc: SQL NULL is present-but-null for a wrapper target type,
    // but a primitive target type maps it to the primitive default (still present).
    @Test
    public void testQueryForValue_SqlNull_PrimitiveTargetTypeYieldsDefault() throws SQLException {
        final Long id = dao.insert(newUser("NullAge", "Probe", 5));
        dao.prepareQuery("UPDATE user_account SET age = NULL WHERE id = ?").setLong(1, id).update();

        assertEquals(Nullable.of(0), dao.queryForSingleValue("age", id, int.class));
        assertEquals(Nullable.of(0), dao.queryForUniqueValue("age", id, int.class));
        assertEquals(Nullable.of(0), dao.queryForSingleValue("age", Filters.eq("id", id), int.class));
        assertEquals(Nullable.of(0), dao.queryForUniqueValue("age", Filters.eq("id", id), int.class));

        assertEquals(Nullable.of((Integer) null), dao.queryForSingleValue("age", id, Integer.class));
        assertEquals(Nullable.of((Integer) null), dao.queryForUniqueValue("age", id, Integer.class));
        assertEquals(Nullable.of((Integer) null), dao.queryForSingleValue("age", Filters.eq("id", id), Integer.class));
        assertEquals(Nullable.of((Integer) null), dao.queryForUniqueValue("age", Filters.eq("id", id), Integer.class));

        assertFalse(dao.queryForSingleValue("age", Filters.eq("id", -1L), int.class).isPresent());
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

    // Every property is @ReadOnly (e.g. an entity mapped to a database view), so the entity has no insertable property.
    @Table("user_account")
    public static class UserAccountView {
        @Id
        @ReadOnly
        private Long id;
        @ReadOnly
        private String firstName;

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
    }

    public interface UserAccountViewDao extends com.landawn.abacus.jdbc.dao.ReadOnlyDao<UserAccountView, UserAccountViewDao> {
    }

    public interface UserAccountViewCrudDao extends com.landawn.abacus.jdbc.dao.ReadOnlyCrudDao<UserAccountView, Long, UserAccountViewCrudDao> {
    }

    public interface UncheckedUserAccountViewCrudDao
            extends com.landawn.abacus.jdbc.dao.UncheckedReadOnlyCrudDao<UserAccountView, Long, UncheckedUserAccountViewCrudDao> {
    }

    // A read-only DAO never inserts: createDao used to fail with "No insertable properties remain after exclusions are applied"
    // while eagerly building the unused INSERT statement for such an entity.
    @Test
    public void testReadOnlyDaoForEntityWithoutInsertableProperty() throws SQLException {
        final Long id = dao.insert(newUser("View", "Only", 30));

        final UserAccountViewDao viewDao = JdbcUtil.createDao(UserAccountViewDao.class, ds);
        assertEquals("View", viewDao.findOnlyOne(Filters.eq("id", id)).get().getFirstName());

        final UserAccountViewCrudDao viewCrudDao = JdbcUtil.createDao(UserAccountViewCrudDao.class, ds);
        assertEquals("View", viewCrudDao.get(id).get().getFirstName());
        assertTrue(viewCrudDao.exists(id));

        final UncheckedUserAccountViewCrudDao uncheckedViewCrudDao = JdbcUtil.createDao(UncheckedUserAccountViewCrudDao.class, ds);
        assertEquals("View", uncheckedViewCrudDao.get(id).get().getFirstName());
        assertEquals(1, uncheckedViewCrudDao.batchGet(List.of(id)).size());
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

    public interface ThrowsExceptionQueryDao extends CrudDao<UserAccount, Long, ThrowsExceptionQueryDao> {
        @Query("SELECT COUNT(*) FROM user_account WHERE last_name = ?")
        int countByLastName(String lastName) throws Exception;

        @Query("SELECT no_such_column FROM user_account")
        List<String> selectMissingColumn() throws Exception;

        @Query("SELECT no_such_column FROM user_account")
        List<String> selectMissingColumnThrowsThrowable() throws Throwable;

        // Guard (same behavior before and after the fix): only 'throws UncheckedSQLException' is declared, so a
        // SQLException is still wrapped.
        @Query("SELECT no_such_column FROM user_account")
        List<String> selectMissingColumnUnchecked() throws com.landawn.abacus.exception.UncheckedSQLException;
    }

    // Guard (rejected before and after the fix): declaring both is still contradictory.
    public interface ThrowsBothQueryDao extends CrudDao<UserAccount, Long, ThrowsBothQueryDao> {
        @Query("SELECT COUNT(*) FROM user_account")
        int countAll() throws SQLException, com.landawn.abacus.exception.UncheckedSQLException;
    }

    // 'throws Exception' covers SQLException, but it was also counted as 'throws UncheckedSQLException', so createDao
    // rejected the method as declaring both.
    @Test
    public void testCustomQueryMethodDeclaringThrowsException() throws Throwable {
        final ThrowsExceptionQueryDao teDao = JdbcUtil.createDao(ThrowsExceptionQueryDao.class, ds);
        dao.insert(newUser("Te", "Throws", 40));

        assertEquals(1, teDao.countByLastName("Throws"));
        // The declared 'throws Exception' (or 'throws Throwable') covers the checked SQLException, so it propagates unwrapped.
        assertThrows(SQLException.class, teDao::selectMissingColumn);
        assertThrows(SQLException.class, teDao::selectMissingColumnThrowsThrowable);
        assertThrows(com.landawn.abacus.exception.UncheckedSQLException.class, teDao::selectMissingColumnUnchecked);

        assertThrows(UnsupportedOperationException.class, () -> JdbcUtil.createDao(ThrowsBothQueryDao.class, ds));
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
        // createDao with an explicit SqlDialect returns a usable proxy...
        assertNotNull(bindDao);
        dao.insert(newUser("Bind", "Me", 77));

        // ...that executes queries against the datasource without error. On SQL Server the
        // addLimitForSingleQuery auto-limit renders as OFFSET/FETCH, whose grammar requires
        // ORDER BY, so it must be skipped for this ORDER-BY-less condition instead of
        // producing an unbuildable query.
        assertDoesNotThrow(() -> {
            bindDao.findFirst(Filters.eq("firstName", "me"));
        });
    }

    // Companion to testCreateDao: when the condition carries its own ORDER BY, the SQL Server
    // auto-limit is still added (ORDER BY ... OFFSET 0 ROWS FETCH NEXT 1 ROWS ONLY, which H2
    // also accepts) and findFirst honors the requested order.
    @Test
    public void testCreateDao_SqlServerAutoLimit_WithOrderBy() throws SQLException {
        final SqlDialect sqlDialect = SqlDialect.builder().productInfo(ProductInfo.of("SQL Server", "10")).build();
        final BindDao bindDao = JdbcUtil.createDao(BindDao.class, ds, sqlDialect);
        dao.insert(newUser("Srv", "First", 41));
        dao.insert(newUser("Srv", "Second", 42));

        final Condition orderedCond = Criteria.builder().add(Filters.eq("firstName", "Srv")).orderByDesc("age").build();

        assertEquals("Second", bindDao.findFirst(orderedCond).map(UserAccount::getLastName).orElseNull());
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
        final UserAccount loaded = dao.getOrNull(id);
        loaded.setAge(16);
        loaded.setLastName("Ignored");
        assertEquals(1, dao.update(loaded, List.of("age")));
        final UserAccount after = dao.getOrNull(id);
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

        // DEFAULT QueryOperation + Optional return type -> "find first" semantics.
        @Query("SELECT * FROM user_account WHERE last_name = :ln ORDER BY id")
        Optional<UserAccount> findOptByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        @Query("SELECT * FROM user_account WHERE last_name = :ln ORDER BY id")
        Optional<UserAccount> selectOnlyOneByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        // DEFAULT QueryOperation + List<Entity> with TWO named (@Bind) parameters (multi-bind named-SQL path).
        @Query("SELECT * FROM user_account WHERE age >= :minAge AND last_name = :ln ORDER BY id")
        List<UserAccount> findListByAgeAndLastName(@com.landawn.abacus.jdbc.annotation.Bind("minAge") int minAge,
                @com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        // DEFAULT QueryOperation + single-column List<String>.
        @Query("SELECT first_name FROM user_account WHERE last_name = :ln ORDER BY id")
        List<String> firstNamesByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        // DEFAULT QueryOperation + com.landawn.abacus.util.Dataset return type.
        @Query("SELECT * FROM user_account WHERE last_name = :ln ORDER BY id")
        Dataset datasetByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        // DEFAULT QueryOperation + Stream return type -> lazy streaming. A lazily-evaluated Stream return
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

        // @SqlFragmentList joins the elements verbatim into the {cols} token.
        @Query("SELECT {cols} FROM user_account WHERE last_name = :ln ORDER BY id")
        List<String> firstColumnByFragmentList(@com.landawn.abacus.jdbc.annotation.SqlFragmentList("cols") List<String> cols,
                @com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        // Array form: String elements and primitive (non-String) elements are joined verbatim too.
        @Query("SELECT {cols} FROM user_account WHERE last_name = :ln ORDER BY {positions} DESC")
        List<String> firstColumnByFragmentArrays(@com.landawn.abacus.jdbc.annotation.SqlFragmentList("cols") String[] cols,
                @com.landawn.abacus.jdbc.annotation.SqlFragmentList("positions") int[] positions, @com.landawn.abacus.jdbc.annotation.Bind("ln") String ln)
                throws SQLException;
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
        assertThrows(com.landawn.abacus.exception.DuplicateResultException.class, () -> aqDao.selectOnlyOneByLastName("Sel"));

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

    // Collection results are created by Suppliers.ofCollection/N.newCollection, which return the plain mutable
    // counterpart for an Immutable* type (ArrayList for ImmutableList, HashSet for ImmutableSet, TreeSet for
    // ImmutableSortedSet); returned as-is, every call of these methods failed with ClassCastException.
    public interface ImmutableCollectionReturnDao extends CrudDao<UserAccount, Long, ImmutableCollectionReturnDao> {
        @Query("SELECT * FROM user_account WHERE last_name = :ln ORDER BY id")
        ImmutableList<UserAccount> listImmutableByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        @Query("SELECT first_name FROM user_account WHERE last_name = :ln ORDER BY id")
        com.landawn.abacus.util.ImmutableSet<String> firstNameSetByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        @Query("SELECT first_name FROM user_account WHERE last_name = :ln ORDER BY id")
        com.landawn.abacus.util.ImmutableSortedSet<String> sortedFirstNamesByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln)
                throws SQLException;

        // An ImmutableNavigableSet is also an ImmutableSortedSet: it must not be wrapped as a plain ImmutableSortedSet.
        @Query("SELECT first_name FROM user_account WHERE last_name = :ln ORDER BY id")
        com.landawn.abacus.util.ImmutableNavigableSet<String> navigableFirstNamesByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln)
                throws SQLException;

        @Query("SELECT * FROM user_account WHERE last_name = :ln ORDER BY id")
        @com.landawn.abacus.jdbc.annotation.MergedById
        ImmutableList<UserAccount> listMergedImmutableByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;
    }

    @Test
    public void testCustomSelect_ImmutableCollectionReturnTypesAreWrapped() throws SQLException {
        final ImmutableCollectionReturnDao immutableDao = JdbcUtil.createDao(ImmutableCollectionReturnDao.class, ds);
        dao.insert(newUser("Imm2", "Imm", 20));
        dao.insert(newUser("Imm1", "Imm", 10));

        final ImmutableList<UserAccount> list = immutableDao.listImmutableByLastName("Imm");
        assertEquals(List.of("Imm2", "Imm1"), list.stream().map(UserAccount::getFirstName).toList());

        final com.landawn.abacus.util.ImmutableSet<String> nameSet = immutableDao.firstNameSetByLastName("Imm");
        assertEquals(2, nameSet.size());
        assertTrue(nameSet.containsAll(List.of("Imm1", "Imm2")));

        final com.landawn.abacus.util.ImmutableSortedSet<String> sortedNames = immutableDao.sortedFirstNamesByLastName("Imm");
        assertEquals(List.of("Imm1", "Imm2"), new ArrayList<>(sortedNames));

        final com.landawn.abacus.util.ImmutableNavigableSet<String> navigableNames = immutableDao.navigableFirstNamesByLastName("Imm");
        assertEquals(List.of("Imm1", "Imm2"), new ArrayList<>(navigableNames));
        assertEquals("Imm1", navigableNames.lower("Imm2"));

        final ImmutableList<UserAccount> merged = immutableDao.listMergedImmutableByLastName("Imm");
        assertEquals(List.of("Imm2", "Imm1"), merged.stream().map(UserAccount::getFirstName).toList());
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

    // @SqlFragmentList elements used to be serialized as JSON, which escaped a quoted identifier "FIRST_NAME" into
    // \"FIRST_NAME\" and produced invalid SQL.
    @Test
    public void testSqlFragmentList_QuotedIdentifierElementIsNotJsonEscaped() throws SQLException {
        final AnnotatedQueryDao aqDao = JdbcUtil.createDao(AnnotatedQueryDao.class, ds);
        dao.insert(newUser("FragList1", "FragList", 20));
        dao.insert(newUser("FragList2", "FragList", 30));

        assertEquals(List.of("FragList1", "FragList2"), aqDao.firstColumnByFragmentList(List.of("\"FIRST_NAME\""), "FragList"));
        assertEquals(List.of("FragList2", "FragList1"),
                aqDao.firstColumnByFragmentArrays(new String[] { "\"FIRST_NAME\"" }, new int[] { 1 }, "FragList"));
    }

    // SQL referenced by id (@SqlScript/SqlMapper) is stored in its parameterized form ("?" markers). When the
    // method also has a @SqlFragment, the expanded SQL used to be re-parsed from that parameterized text, so the
    // named parameters were lost and binding ':ln' failed at invocation (IllegalArgumentException).
    public interface ScriptFragmentDao extends CrudDao<UserAccount, Long, ScriptFragmentDao> {
        @com.landawn.abacus.jdbc.annotation.SqlScript(id = "sortedByLastName")
        String SORTED_BY_LAST_NAME = "SELECT * FROM user_account WHERE last_name = :ln ORDER BY {sortCol}";

        @Query(id = "sortedByLastName")
        List<UserAccount> findSortedById(@com.landawn.abacus.jdbc.annotation.SqlFragment("sortCol") String sortCol,
                @com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        @Query("sortedByLastName")
        List<UserAccount> findSortedByValueId(@com.landawn.abacus.jdbc.annotation.SqlFragment("sortCol") String sortCol,
                @com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;
    }

    @Test
    public void testCustomSelect_SqlFragmentWithNamedSqlReferencedById() throws SQLException {
        final ScriptFragmentDao scriptDao = JdbcUtil.createDao(ScriptFragmentDao.class, ds);
        dao.insert(newUser("S1", "ScriptFrag", 30));
        dao.insert(newUser("S2", "ScriptFrag", 10));
        dao.insert(newUser("S3", "Other", 20));

        final List<UserAccount> byId = scriptDao.findSortedById("age", "ScriptFrag");
        assertEquals(2, byId.size());
        assertEquals(10, byId.get(0).getAge());
        assertEquals(30, byId.get(1).getAge());

        final List<UserAccount> byValueId = scriptDao.findSortedByValueId("age DESC", "ScriptFrag");
        assertEquals(2, byValueId.size());
        assertEquals(30, byValueId.get(0).getAge());
        assertEquals(10, byValueId.get(1).getAge());
    }

    public interface NamedFragmentDao extends CrudDao<UserAccount, Long, NamedFragmentDao> {
        @Query(value = "SELECT * FROM user_account WHERE {condition} ORDER BY id", fragmentsContainNamedParameters = true)
        List<UserAccount> byAge(@com.landawn.abacus.jdbc.annotation.SqlFragment("condition") String condition,
                @com.landawn.abacus.jdbc.annotation.Bind("minimumAge") int minimumAge) throws SQLException;

        @Query(value = "SELECT * FROM user_account WHERE last_name = :ln AND {condition} ORDER BY id", fragmentsContainNamedParameters = true)
        List<UserAccount> byAgeAndName(@com.landawn.abacus.jdbc.annotation.SqlFragment("condition") String condition,
                @com.landawn.abacus.jdbc.annotation.Bind("minimumAge") int minimumAge, @com.landawn.abacus.jdbc.annotation.Bind("ln") String lastName)
                throws SQLException;

        @Query(value = "SELECT CAST(:now AS TIMESTAMP) AS first_time, {clock}", fragmentsContainNamedParameters = true, injectCurrentTimeParameters = true)
        Dataset clock(@com.landawn.abacus.jdbc.annotation.SqlFragment("clock") String clock) throws SQLException;

        @Query(value = "UPDATE fragment_clock SET now_time = :now, sys_time = {clock} WHERE id = :id", batch = true, fragmentsContainNamedParameters = true, injectCurrentTimeParameters = true)
        int updateClocks(@com.landawn.abacus.jdbc.annotation.SqlFragment("clock") String clock, List<Map<String, Object>> rows) throws SQLException;
    }

    @Test
    public void testNamedParametersIntroducedBySqlFragments() throws SQLException {
        final NamedFragmentDao fragmentDao = JdbcUtil.createDao(NamedFragmentDao.class, ds);
        dao.insert(newUser("Young", "Fragment", 10));
        final long matchingId = dao.insert(newUser("Older", "Fragment", 30));
        dao.insert(newUser("Other", "Name", 40));

        assertEquals(2, fragmentDao.byAge("age >= :minimumAge", 20).size());
        assertEquals(matchingId, fragmentDao.byAgeAndName("age >= :minimumAge", 20, "Fragment").get(0).getId());
        assertTrue(fragmentDao.byAge("age >= :minimumAge", 50).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> fragmentDao.byAge("age >= :differentName", 20));
    }

    @Test
    public void testSystemTimeParametersIntroducedBySqlFragments() throws SQLException {
        final NamedFragmentDao fragmentDao = JdbcUtil.createDao(NamedFragmentDao.class, ds);
        final Dataset result = fragmentDao.clock("CAST(:sysTime AS TIMESTAMP) AS second_time, CAST(:sysDate AS DATE) AS date_value");

        assertEquals(1, result.size());
        assertNotNull(result.get(0, 0));
        assertEquals((Object) result.get(0, 0), (Object) result.get(0, 1));
        assertNotNull(result.get(0, 2));
    }

    @Test
    public void testBatchSystemTimeParametersIntroducedBySqlFragments() throws SQLException {
        final NamedFragmentDao fragmentDao = JdbcUtil.createDao(NamedFragmentDao.class, ds);
        try (Connection connection = ds.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE fragment_clock (id INT PRIMARY KEY, now_time TIMESTAMP, sys_time TIMESTAMP)");
            try {
                statement.executeUpdate("INSERT INTO fragment_clock (id) VALUES (1), (2)");
                assertEquals(2, fragmentDao.updateClocks(":sysTime", List.of(Map.of("id", 1), Map.of("id", 2))));
                try (java.sql.ResultSet rows = statement.executeQuery("SELECT now_time, sys_time FROM fragment_clock ORDER BY id")) {
                    for (int i = 0; i < 2; i++) {
                        assertTrue(rows.next());
                        assertNotNull(rows.getTimestamp(1));
                        assertEquals(rows.getTimestamp(1), rows.getTimestamp(2));
                    }
                    assertFalse(rows.next());
                }
            } finally {
                statement.execute("DROP TABLE fragment_clock");
            }
        }
    }

    // =====================================================================================
    // Explicit @Query op() modes: exists / queryForSingle / findOnlyOne / findFirst / list.
    // Each drives a distinct result-converter branch in DaoImpl.
    // =====================================================================================
    public interface OpQueryDao extends CrudDao<UserAccount, Long, OpQueryDao> {

        @Query(value = "SELECT 1 FROM user_account WHERE last_name = :ln", op = QueryOperation.exists)
        boolean existsByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        @Query(value = "SELECT COUNT(*) FROM user_account WHERE last_name = :ln", op = QueryOperation.queryForSingle)
        long countByLastNameSingle(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        @Query(value = "SELECT first_name FROM user_account WHERE id = :id", op = QueryOperation.queryForSingle)
        String firstNameViaSingle(@com.landawn.abacus.jdbc.annotation.Bind("id") long id) throws SQLException;

        @Query(value = "SELECT * FROM user_account WHERE id = :id", op = QueryOperation.findOnlyOne)
        UserAccount onlyOneById(@com.landawn.abacus.jdbc.annotation.Bind("id") long id) throws SQLException;

        @Query(value = "SELECT * FROM user_account WHERE last_name = :ln ORDER BY age", op = QueryOperation.findFirst)
        Optional<UserAccount> firstByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        @Query(value = "SELECT * FROM user_account WHERE last_name = :ln ORDER BY id", op = QueryOperation.list)
        List<UserAccount> listByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;
    }

    @Test
    public void testExplicitOp_Variants() throws SQLException {
        final OpQueryDao opDao = JdbcUtil.createDao(OpQueryDao.class, ds);
        final Long id = dao.insert(newUser("Op1", "QueryOperation", 25));
        dao.insert(newUser("Op2", "QueryOperation", 35));

        assertTrue(opDao.existsByLastName("QueryOperation"));
        assertFalse(opDao.existsByLastName("Nope"));
        assertEquals(2L, opDao.countByLastNameSingle("QueryOperation"));
        assertEquals("Op1", opDao.firstNameViaSingle(id));
        assertEquals(25, opDao.onlyOneById(id).getAge());

        final Optional<UserAccount> first = opDao.firstByLastName("QueryOperation");
        assertTrue(first.isPresent());
        assertEquals(25, first.get().getAge());

        assertEquals(2, opDao.listByLastName("QueryOperation").size());
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
        assertEquals(99, dao.getOrNull(id).getAge());

        assertEquals(1, nDao.deleteNamed("Dml"));
        assertEquals(0, dao.count(Filters.eq("lastName", "Dml")));
    }

    // A bean that is NOT the DAO entity: custom INSERTs into another table must not write the DAO entity's id into it.
    public static class CrossTableEventLog {
        private Long id;
        private String msg;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public String getMsg() {
            return msg;
        }

        public void setMsg(final String msg) {
            this.msg = msg;
        }
    }

    private static CrossTableEventLog newEventLog(final long id, final String msg) {
        final CrossTableEventLog log = new CrossTableEventLog();
        log.setId(id);
        log.setMsg(msg);
        return log;
    }

    public interface CrossTableInsertDao extends CrudDao<UserAccount, Long, CrossTableInsertDao> {
        @Query("INSERT INTO dao_it_event_log (id, msg) VALUES (:id, :msg)")
        void insertEventLog(CrossTableEventLog log) throws SQLException;

        @Query("INSERT INTO dao_it_event_log (id, msg) VALUES (:id, :msg)")
        long insertEventLogReturningId(CrossTableEventLog log) throws SQLException;

        @Query(value = "INSERT INTO dao_it_event_log (id, msg) VALUES (:id, :msg)", batch = true)
        void batchInsertEventLogs(Collection<CrossTableEventLog> logs) throws SQLException;

        @Query("INSERT INTO dao_it_audit_note (note) VALUES (:note)")
        void insertAuditNote(@com.landawn.abacus.jdbc.annotation.Bind("note") String note) throws SQLException;

        @Query(value = "INSERT INTO dao_it_audit_note (note) VALUES (:note)", batch = true)
        void batchInsertAuditNotes(List<Map<String, Object>> notes) throws SQLException;

        // void INSERTs whose rows may be DAO entities must still request the generated keys to write the ids back.
        @Query("INSERT INTO user_account (first_name, last_name, age, active) VALUES (:firstName, :lastName, :age, :active)")
        void insertAccount(UserAccount account) throws SQLException;

        @Query(value = "INSERT INTO user_account (first_name, last_name, age, active) VALUES (:firstName, :lastName, :age, :active)", batch = true)
        void batchInsertAccountsWildcard(Collection<? extends UserAccount> accounts) throws SQLException;

        @SuppressWarnings("rawtypes")
        @Query(value = "INSERT INTO user_account (first_name, last_name, age, active) VALUES (:firstName, :lastName, :age, :active)", batch = true)
        void batchInsertAccountsRaw(Collection accounts, int batchSize) throws SQLException;
    }

    @Table("composite_gen_key")
    public static class CompositeGenKeyRow {
        @Id
        private Long seq;
        @Id
        private String region;
        private String name;

        public Long getSeq() {
            return seq;
        }

        public void setSeq(final Long seq) {
            this.seq = seq;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(final String region) {
            this.region = region;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }

    public interface CompositeGenKeyDao extends CrudDao<CompositeGenKeyRow, com.landawn.abacus.util.EntityId, CompositeGenKeyDao> {
        // Simulates a driver that returns only the generated column of a composite key (H2 returns every requested column).
        @Override
        default Jdbc.BiRowMapper<com.landawn.abacus.util.EntityId> idExtractor() {
            return (rs, columnLabels) -> com.landawn.abacus.util.Seid.of("CompositeGenKeyRow").set("seq", rs.getLong(1));
        }

        @Query("INSERT INTO composite_gen_key (region, name) VALUES (:region, :name)")
        com.landawn.abacus.util.EntityId insertRow(CompositeGenKeyRow row) throws SQLException;

        @Query(value = "INSERT INTO composite_gen_key (region, name) VALUES (:region, :name)", batch = true)
        List<com.landawn.abacus.util.EntityId> insertRows(List<CompositeGenKeyRow> rows) throws SQLException;
    }

    private static CompositeGenKeyRow newCompositeGenKeyRow(final String region, final String name) {
        final CompositeGenKeyRow row = new CompositeGenKeyRow();
        row.setRegion(region);
        row.setName(name);
        return row;
    }

    // Regression: when the generated keys cover only part of a composite id, the propNames insert/batchInsert overloads
    // and custom @Query INSERTs returned that partial key ({seq}) instead of the entity's full id ({seq, region}).
    @Test
    public void testInsert_CompositeIdWithPartialGeneratedKeyReturnsFullId() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS composite_gen_key (seq BIGINT GENERATED BY DEFAULT AS IDENTITY, region VARCHAR(8), "
                    + "name VARCHAR(64), PRIMARY KEY (seq, region))");
        }

        try {
            final CompositeGenKeyDao ckDao = JdbcUtil.createDao(CompositeGenKeyDao.class, ds);
            final List<CompositeGenKeyRow> rows = new ArrayList<>();
            final List<com.landawn.abacus.util.EntityId> ids = new ArrayList<>();

            final CompositeGenKeyRow single = newCompositeGenKeyRow("EU", "single");
            rows.add(single);
            ids.add(ckDao.insert(single, List.of("region", "name")));

            final List<CompositeGenKeyRow> batch = List.of(newCompositeGenKeyRow("US", "b1"), newCompositeGenKeyRow("AP", "b2"));
            rows.addAll(batch);
            ids.addAll(ckDao.batchInsert(batch, List.of("region", "name"), 10));

            final CompositeGenKeyRow custom = newCompositeGenKeyRow("SA", "custom");
            rows.add(custom);
            ids.add(ckDao.insertRow(custom));

            final List<CompositeGenKeyRow> customBatch = List.of(newCompositeGenKeyRow("AF", "cb1"), newCompositeGenKeyRow("OC", "cb2"));
            rows.addAll(customBatch);
            ids.addAll(ckDao.insertRows(customBatch));

            assertEquals(rows.size(), ids.size());

            for (int i = 0; i < rows.size(); i++) {
                assertNotNull(rows.get(i).getSeq(), rows.get(i).getName());
                assertEquals(rows.get(i).getSeq(), ids.get(i).get("seq"), rows.get(i).getName());
                assertEquals(rows.get(i).getRegion(), ids.get(i).get("region"), rows.get(i).getName());
            }
        } finally {
            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS composite_gen_key");
            }
        }
    }

    // Guard for the keys-requested decision: a void custom INSERT whose (element) type is the DAO entity, a wildcard
    // bounded by it, or unresolvable (raw collection) still requests generated keys and writes the ids back.
    @Test
    public void testCustomVoidInsert_DaoEntityRowsStillReceiveGeneratedIds() throws SQLException {
        final CrossTableInsertDao crossDao = JdbcUtil.createDao(CrossTableInsertDao.class, ds);

        final UserAccount single = newUser("KeysSingle", "KeysRequested", 1);
        crossDao.insertAccount(single);
        assertNotNull(single.getId());
        assertEquals("KeysSingle", dao.getOrNull(single.getId()).getFirstName());

        final List<UserAccount> wildcardRows = List.of(newUser("KeysW1", "KeysRequested", 2), newUser("KeysW2", "KeysRequested", 3));
        crossDao.batchInsertAccountsWildcard(wildcardRows);

        final List<UserAccount> rawRows = List.of(newUser("KeysR1", "KeysRequested", 4), newUser("KeysR2", "KeysRequested", 5));
        crossDao.batchInsertAccountsRaw(rawRows, 1);

        final List<UserAccount> batchRows = new ArrayList<>(wildcardRows);
        batchRows.addAll(rawRows);

        for (final UserAccount row : batchRows) {
            assertNotNull(row.getId(), row.getFirstName());
            assertEquals(row.getFirstName(), dao.getOrNull(row.getId()).getFirstName());
        }
    }

    // Regression: a custom @Query INSERT whose single argument was ANY bean was treated as the DAO entity, so the
    // generated key was written back through the DAO entity's id setter (and read through its id getter) on an
    // unrelated bean, failing after the row had already been inserted.
    @Test
    public void testCustomInsert_OtherBeanArgumentIsNotTreatedAsDaoEntity() throws SQLException {
        final CrossTableInsertDao crossDao = JdbcUtil.createDao(CrossTableInsertDao.class, ds);

        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS dao_it_event_log (id BIGINT PRIMARY KEY, msg VARCHAR(64))");
            st.execute("DELETE FROM dao_it_event_log");
        }

        try {
            final CrossTableEventLog first = newEventLog(5, "one");
            crossDao.insertEventLog(first);
            assertEquals(5L, first.getId());

            assertEquals(6L, crossDao.insertEventLogReturningId(newEventLog(6, "two")));

            crossDao.batchInsertEventLogs(List.of(newEventLog(7, "three"), newEventLog(8, "four")));

            assertEquals(4, JdbcUtil.prepareQuery(ds, "SELECT COUNT(*) FROM dao_it_event_log").queryForInt().orElseThrow());
        } finally {
            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS dao_it_event_log");
            }
        }
    }

    // Regression: a void custom @Query INSERT into a table without the DAO entity's id column always requested that
    // column as a generated key, which drivers that validate the requested names (H2, PostgreSQL) reject.
    @Test
    public void testCustomVoidInsert_IntoTableWithoutDaoIdColumn() throws SQLException {
        final CrossTableInsertDao crossDao = JdbcUtil.createDao(CrossTableInsertDao.class, ds);

        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS dao_it_audit_note (note VARCHAR(64))");
            st.execute("DELETE FROM dao_it_audit_note");
        }

        try {
            crossDao.insertAuditNote("single");
            crossDao.batchInsertAuditNotes(List.of(Map.of("note", "batch1"), Map.of("note", "batch2")));

            assertEquals(3, JdbcUtil.prepareQuery(ds, "SELECT COUNT(*) FROM dao_it_audit_note").queryForInt().orElseThrow());
        } finally {
            try (Connection conn = ds.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS dao_it_audit_note");
            }
        }
    }

    // =====================================================================================
    // @Handler: a recording handler wired via @Handler(impl=...) must have its beforeInvoke /
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

    @com.landawn.abacus.jdbc.annotation.Handler(impl = RecordingHandler.class)
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

    public static final class SharedFailureHandler implements Jdbc.Handler<SharedFailureHandlerDao> {
        static final AssertionError FAILURE = new AssertionError("shared DAO/handler failure");

        @Override
        public void afterInvoke(final Object result, final SharedFailureHandlerDao proxy, final Object[] args,
                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            if ("failWithSharedError".equals(methodSignature._1.getName())) {
                throw FAILURE;
            }
        }
    }

    @com.landawn.abacus.jdbc.annotation.Handler(impl = SharedFailureHandler.class)
    public interface SharedFailureHandlerDao extends CrudDao<UserAccount, Long, SharedFailureHandlerDao> {
        default void failWithSharedError() {
            throw SharedFailureHandler.FAILURE;
        }
    }

    @Test
    public void testHandler_SharedDaoAndAfterFailureDoesNotSelfSuppress() {
        final SharedFailureHandlerDao handlerDao = JdbcUtil.createDao(SharedFailureHandlerDao.class, ds);

        final AssertionError thrown = assertThrows(AssertionError.class, handlerDao::failWithSharedError);

        assertSame(SharedFailureHandler.FAILURE, thrown);
        assertEquals(0, thrown.getSuppressed().length);
    }

    public static final class RethrowPrimaryHandler implements Jdbc.Handler<CyclicFailureHandlerDao> {
        @Override
        public void afterInvoke(final Object result, final CyclicFailureHandlerDao proxy, final Object[] args,
                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            if ("failWithCleanup".equals(methodSignature._1.getName())) {
                throw (AssertionError) args[0];
            }
        }
    }

    public static final class DistinctCleanupHandler implements Jdbc.Handler<CyclicFailureHandlerDao> {
        @Override
        public void afterInvoke(final Object result, final CyclicFailureHandlerDao proxy, final Object[] args,
                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            if ("failWithCleanup".equals(methodSignature._1.getName())) {
                throw (AssertionError) args[1];
            }
        }
    }

    @com.landawn.abacus.jdbc.annotation.Handler(impl = RethrowPrimaryHandler.class)
    @com.landawn.abacus.jdbc.annotation.Handler(impl = DistinctCleanupHandler.class)
    public interface CyclicFailureHandlerDao extends CrudDao<UserAccount, Long, CyclicFailureHandlerDao> {
        default void failWithCleanup(final AssertionError primaryFailure, final AssertionError cleanupFailure) {
            throw primaryFailure;
        }

        default void invokeNestedFailure(final AssertionError primaryFailure, final AssertionError cleanupFailure) {
            failWithCleanup(primaryFailure, cleanupFailure);
        }
    }

    @Test
    public void testHandler_SharedPrimaryFailureDoesNotCreateSuppressionCycle() {
        final CyclicFailureHandlerDao handlerDao = JdbcUtil.createDao(CyclicFailureHandlerDao.class, ds);
        final AssertionError primaryFailure = new AssertionError("DAO failure");
        final AssertionError cleanupFailure = new AssertionError("cleanup failure");

        final AssertionError thrown = assertThrows(AssertionError.class, () -> handlerDao.failWithCleanup(primaryFailure, cleanupFailure));

        assertSame(primaryFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cleanupFailure, thrown.getSuppressed()[0]);
        assertEquals(0, cleanupFailure.getSuppressed().length);
    }

    @Test
    public void testHandler_NestedSharedPrimaryFailureDoesNotCreateSuppressionCycle() {
        final CyclicFailureHandlerDao handlerDao = JdbcUtil.createDao(CyclicFailureHandlerDao.class, ds);
        final AssertionError primaryFailure = new AssertionError("nested DAO failure");
        final AssertionError cleanupFailure = new AssertionError("nested cleanup failure");

        final AssertionError thrown = assertThrows(AssertionError.class, () -> handlerDao.invokeNestedFailure(primaryFailure, cleanupFailure));

        assertSame(primaryFailure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(cleanupFailure, thrown.getSuppressed()[0]);
        assertEquals(0, cleanupFailure.getSuppressed().length);
    }

    // =====================================================================================
    // @PerfLog + @SqlLogEnabled: thresholds of 0 force both the SQL-perf and DAO-query-operation-perf log
    // branches to run on a successful call (the happy-path counterpart to the existing
    // begin-transaction-failure restoration test).
    // =====================================================================================
    @com.landawn.abacus.jdbc.annotation.PerfLog(sqlPerfLogThresholdMillis = 0, daoMethodPerfLogThresholdMillis = 0)
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
    // @Cache + @CacheResult on a NonUpdate DAO: the second call with identical args is served
    // from cache, so data inserted after the first call is NOT reflected (proves cache hit).
    // =====================================================================================
    @com.landawn.abacus.jdbc.annotation.Cache(capacity = 100, evictDelayMillis = 60000)
    public interface CachedUserDao extends NonUpdateCrudDao<UserAccount, Long, CachedUserDao> {
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

    @com.landawn.abacus.jdbc.annotation.Cache(capacity = 100, evictDelayMillis = 60000)
    public interface JsonCachedUserDao extends NonUpdateCrudDao<UserAccount, Long, JsonCachedUserDao> {
        @com.landawn.abacus.jdbc.annotation.CacheResult(enabled = true, serialization = com.landawn.abacus.jdbc.annotation.CacheSerialization.JSON)
        @Query("SELECT * FROM user_account WHERE last_name = :ln ORDER BY id")
        List<UserAccount> findJsonCachedByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        @com.landawn.abacus.jdbc.annotation.CacheResult(enabled = true, serialization = com.landawn.abacus.jdbc.annotation.CacheSerialization.JSON)
        @Query("SELECT id, first_name, last_name, age, active FROM user_account WHERE last_name = :ln ORDER BY id")
        @com.landawn.abacus.jdbc.annotation.MappedByKey(value = "id", mapClass = java.util.LinkedHashMap.class)
        Map<Long, UserAccount> findJsonCachedMapByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;

        @com.landawn.abacus.jdbc.annotation.CacheResult(enabled = true, serialization = com.landawn.abacus.jdbc.annotation.CacheSerialization.JSON)
        @Query("SELECT * FROM user_account WHERE last_name = :ln ORDER BY id")
        Optional<UserAccount> findJsonCachedFirstByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;
    }

    // Regression: JSON cache serialization deserialized the copy through the runtime class alone
    // (e.g. ArrayList.class), erasing the declared generic type arguments, so a cache hit returned a
    // List/Map/Optional of HashMaps instead of entities (ClassCastException in the caller).
    @Test
    public void testCachedQuery_JsonSerialization_KeepsElementType() throws SQLException {
        final JsonCachedUserDao cachedDao = JdbcUtil.createDao(JsonCachedUserDao.class, ds);
        final long id = dao.insert(newUser("J1", "JsonGrp", 10));

        final List<UserAccount> first = cachedDao.findJsonCachedByLastName("JsonGrp");
        final List<UserAccount> second = cachedDao.findJsonCachedByLastName("JsonGrp");

        assertEquals(1, first.size());
        assertEquals(1, second.size());
        assertEquals(UserAccount.class, ((List<?>) first).get(0).getClass());
        assertEquals(UserAccount.class, ((List<?>) second).get(0).getClass());
        assertEquals("J1", second.get(0).getFirstName());

        cachedDao.findJsonCachedMapByLastName("JsonGrp");
        final Map<Long, UserAccount> cachedMap = cachedDao.findJsonCachedMapByLastName("JsonGrp");
        assertEquals(java.util.LinkedHashMap.class, cachedMap.getClass());
        assertEquals(Long.class, ((Map<?, ?>) cachedMap).keySet().iterator().next().getClass());
        assertEquals(UserAccount.class, ((Map<?, ?>) cachedMap).values().iterator().next().getClass());
        assertEquals("J1", cachedMap.get(id).getFirstName());

        cachedDao.findJsonCachedFirstByLastName("JsonGrp");
        final Optional<UserAccount> cachedFirst = cachedDao.findJsonCachedFirstByLastName("JsonGrp");
        assertEquals(UserAccount.class, ((Optional<?>) cachedFirst).get().getClass());
        assertEquals("J1", cachedFirst.get().getFirstName());
    }

    @com.landawn.abacus.jdbc.annotation.Cache(capacity = 100, evictDelayMillis = 60000)
    @com.landawn.abacus.jdbc.annotation.CacheResult(enabled = true, serialization = com.landawn.abacus.jdbc.annotation.CacheSerialization.JSON, filter = {
            "findFirst", "get", "query" })
    public interface JsonCachedBuiltInUserDao extends NonUpdateCrudDao<UserAccount, Long, JsonCachedBuiltInUserDao> {
        // Only a default method can return a java.util.Optional (abstract DAO methods reject it); "get" puts it under the cache.
        default java.util.Optional<UserAccount> getAsJdkOptional(final long id) throws SQLException {
            return get(id).toJdkOptional();
        }
    }

    // Regression: built-in methods declare value holders over type variables (Optional<T> get(ID), Optional<T>
    // findFirst(Condition), <V> Nullable<V> queryForSingleValue(...)). The JSON cache copy deserialized them through
    // that unresolved declared type, so a cache hit held the entity's JSON String instead of the loaded entity.
    @Test
    public void testCachedQuery_JsonSerialization_BuiltInValueHoldersKeepValueType() throws SQLException {
        final JsonCachedBuiltInUserDao cachedDao = JdbcUtil.createDao(JsonCachedBuiltInUserDao.class, ds);
        final long id = dao.insert(newUser("JB1", "JsonBuiltIn", 10));

        cachedDao.get(id);
        final Optional<UserAccount> cachedById = cachedDao.get(id);
        assertEquals(UserAccount.class, ((Optional<?>) cachedById).get().getClass());
        assertEquals("JB1", cachedById.get().getFirstName());

        cachedDao.findFirst(Filters.eq("lastName", "JsonBuiltIn"));
        final Optional<UserAccount> cachedFirst = cachedDao.findFirst(Filters.eq("lastName", "JsonBuiltIn"));
        assertEquals(UserAccount.class, ((Optional<?>) cachedFirst).get().getClass());
        assertEquals(id, cachedFirst.get().getId());

        cachedDao.queryForSingleValue("id", Filters.eq("lastName", "JsonBuiltIn"), Long.class);
        final Nullable<Long> cachedValue = cachedDao.queryForSingleValue("id", Filters.eq("lastName", "JsonBuiltIn"), Long.class);
        assertEquals(Long.class, ((Nullable<?>) cachedValue).get().getClass());
        assertEquals(id, cachedValue.get().longValue());

        cachedDao.queryForSingleValue("firstName", Filters.eq("lastName", "JsonBuiltIn"), String.class);
        assertEquals("JB1", cachedDao.queryForSingleValue("firstName", Filters.eq("lastName", "JsonBuiltIn"), String.class).get());
    }

    // A row whose value is SQL NULL is a present null, distinct from no row at all; the cached copy must keep that.
    @Test
    public void testCachedQuery_JsonSerialization_NullableKeepsPresentNull() throws SQLException {
        final JsonCachedBuiltInUserDao cachedDao = JdbcUtil.createDao(JsonCachedBuiltInUserDao.class, ds);
        dao.insert(newUser(null, "JsonBuiltInNull", 11));

        cachedDao.queryForSingleValue("firstName", Filters.eq("lastName", "JsonBuiltInNull"), String.class);
        final Nullable<String> cachedNullValue = cachedDao.queryForSingleValue("firstName", Filters.eq("lastName", "JsonBuiltInNull"), String.class);
        assertTrue(cachedNullValue.isPresent());
        assertTrue(cachedNullValue.isNull());
    }

    // Regression: a Dataset was JSON-copied without its column types, so a cache hit read its values back untyped
    // (the BIGINT id column came back holding an Integer).
    @Test
    public void testCachedQuery_JsonSerialization_DatasetKeepsColumnValueTypes() throws SQLException {
        final JsonCachedBuiltInUserDao cachedDao = JdbcUtil.createDao(JsonCachedBuiltInUserDao.class, ds);
        dao.insert(newUser("JD1", "JsonDataset", 10));

        final Dataset first = cachedDao.query(Filters.eq("lastName", "JsonDataset"));
        final Dataset cached = cachedDao.query(Filters.eq("lastName", "JsonDataset"));

        assertEquals(first.columnNames(), cached.columnNames());
        assertEquals(1, cached.size());

        for (final String columnName : first.columnNames()) {
            final Object expected = first.getColumn(columnName).get(0);
            final Object actual = cached.getColumn(columnName).get(0);

            assertEquals(expected == null ? null : expected.getClass(), actual == null ? null : actual.getClass(), columnName);
            assertEquals(expected, actual, columnName);
        }
    }

    // The thread-local cache of openDaoCacheScope() copies with the method's serialization too (JSON here), so a hit
    // inside the scope must also hold the loaded entity rather than its JSON String.
    @Test
    public void testCachedQuery_JsonSerialization_LocalThreadCacheKeepsValueType() throws SQLException {
        final JsonCachedBuiltInUserDao cachedDao = JdbcUtil.createDao(JsonCachedBuiltInUserDao.class, ds);
        final long id = dao.insert(newUser("JL1", "JsonLocal", 13));

        try (JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope()) {
            cachedDao.get(id);
            final Optional<UserAccount> cached = cachedDao.get(id);
            assertEquals(UserAccount.class, ((Optional<?>) cached).get().getClass());
            assertEquals("JL1", cached.get().getFirstName());
        }
    }

    // Guard (passes before and after the value-holder fix): primitive optionals, empty holders and a declared
    // java.util.Optional keep their values on a hit. Data changed through another DAO after the first calls proves the
    // second calls are served from the cache.
    @Test
    public void testCachedQuery_JsonSerialization_PrimitiveEmptyAndJdkHoldersRoundTrip() throws SQLException {
        final JsonCachedBuiltInUserDao cachedDao = JdbcUtil.createDao(JsonCachedBuiltInUserDao.class, ds);
        final long id = dao.insert(newUser("JP1", "JsonPrimitive", 12));

        assertEquals(12, cachedDao.queryForInt("age", Filters.eq("lastName", "JsonPrimitive")).get());
        assertEquals(id, cachedDao.queryForLong("id", Filters.eq("lastName", "JsonPrimitive")).get());
        assertEquals("JP1", cachedDao.getAsJdkOptional(id).get().getFirstName());
        assertFalse(cachedDao.queryForString("firstName", Filters.eq("lastName", "JsonNoRowYet")).isPresent());

        assertEquals(1, dao.update(Map.of("age", 99, "firstName", "JP2"), id));
        dao.insert(newUser("JN1", "JsonNoRowYet", 1));

        assertEquals(12, cachedDao.queryForInt("age", Filters.eq("lastName", "JsonPrimitive")).get());
        assertEquals(id, cachedDao.queryForLong("id", Filters.eq("lastName", "JsonPrimitive")).get());
        final java.util.Optional<UserAccount> cachedJdk = cachedDao.getAsJdkOptional(id);
        assertEquals(UserAccount.class, ((java.util.Optional<?>) cachedJdk).get().getClass());
        assertEquals("JP1", cachedJdk.get().getFirstName());
        assertFalse(cachedDao.queryForString("firstName", Filters.eq("lastName", "JsonNoRowYet")).isPresent());
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

        @Query("SELECT id, first_name, last_name, age, active FROM user_account UNION ALL SELECT id, first_name, last_name, age, active FROM user_account")
        @com.landawn.abacus.jdbc.annotation.MergedById("id")
        Optional<UserAccount> queryForUniqueMerged() throws SQLException;

        @Query(value = "SELECT id, first_name, last_name, age, active FROM user_account UNION ALL SELECT id, first_name, last_name, age, active FROM user_account", op = QueryOperation.findOnlyOne)
        @com.landawn.abacus.jdbc.annotation.MergedById("id")
        Optional<UserAccount> uniqueMerged() throws SQLException;

        @Query(value = "SELECT id, first_name, last_name, age, active FROM user_account ORDER BY id", op = QueryOperation.findFirst)
        @com.landawn.abacus.jdbc.annotation.MergedById("id")
        Optional<UserAccount> queryForUniqueMergedFirst() throws SQLException;
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

    @Test
    public void testMergedByIdUniqueQueriesCheckMergedEntityCount() throws SQLException {
        final MappedUserDao mergedDao = JdbcUtil.createDao(MappedUserDao.class, ds);
        assertTrue(mergedDao.queryForUniqueMerged().isEmpty());
        assertTrue(mergedDao.uniqueMerged().isEmpty());

        final long firstId = dao.insert(newUser("Unique", "Merged", 10));
        // UNION ALL returns two rows for one ID; uniqueness applies after merging those rows.
        assertEquals(firstId, mergedDao.queryForUniqueMerged().get().getId());
        assertEquals(firstId, mergedDao.uniqueMerged().get().getId());

        dao.insert(newUser("Another", "Merged", 20));
        assertThrows(DuplicateResultException.class, mergedDao::queryForUniqueMerged);
        assertThrows(DuplicateResultException.class, mergedDao::uniqueMerged);
        // An explicit operation takes precedence over a uniqueness-style method name.
        assertEquals(firstId, mergedDao.queryForUniqueMergedFirst().get().getId());
    }

    public interface PrefixMappedObjectListDao extends CrudDao<UserAccount, Long, PrefixMappedObjectListDao> {
        // Object is a supertype of the entity, so DAO creation accepts it, but the prefix-mapping row mapper requires a bean class
        // and fails with IllegalArgumentException when it is built, i.e. after the query was prepared but before it executes.
        @Query("SELECT id, first_name FROM user_account ORDER BY id")
        @com.landawn.abacus.jdbc.annotation.PrefixFieldMapping("u=user")
        List<Object> listWithPrefixMapping() throws SQLException;

        // An abstract collection type has no result collection supplier: the lazy row stream is already created when
        // the supplier lookup fails with IllegalArgumentException, and that stream is never consumed or closed.
        @Query("SELECT id, first_name FROM user_account ORDER BY id")
        AbstractUserBag<UserAccount> listIntoAbstractCollection() throws SQLException;
    }

    public abstract static class AbstractUserBag<E> extends java.util.AbstractCollection<E> {
    }

    // Regression: a custom @Query method whose result mapping failed before the query executed threw without closing
    // the prepared query, so every call leaked its statement and its pooled connection.
    @Test
    public void testCustomQuery_ResultMappingSetupFailureReleasesConnection() throws SQLException {
        final DataSource scratchDs = JdbcUtil.createHikariDataSource("jdbc:h2:mem:daoimpl_query_leak;DB_CLOSE_DELAY=-1", "sa", "");

        try {
            try (Connection conn = scratchDs.getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS user_account (id BIGINT AUTO_INCREMENT PRIMARY KEY, first_name VARCHAR(64), "
                        + "last_name VARCHAR(64), age INT, active BOOLEAN)");
            }

            final PrefixMappedObjectListDao prefixDao = JdbcUtil.createDao(PrefixMappedObjectListDao.class, scratchDs);
            final com.zaxxer.hikari.HikariPoolMXBean pool = ((com.zaxxer.hikari.HikariDataSource) scratchDs).getHikariPoolMXBean();

            for (int i = 0; i < 3; i++) {
                assertThrows(IllegalArgumentException.class, prefixDao::listWithPrefixMapping);
                assertEquals(0, pool.getActiveConnections(), "the prepared query's connection must be released after the failure");

                assertThrows(IllegalArgumentException.class, prefixDao::listIntoAbstractCollection);
                assertEquals(0, pool.getActiveConnections(), "the prepared query's connection must be released after the failure");
            }
        } finally {
            ((com.zaxxer.hikari.HikariDataSource) scratchDs).close();
        }
    }

    public interface CustomWriteCacheDao extends CrudDao<UserAccount, Long, CustomWriteCacheDao> {
        // "reset" is not an update-method name prefix.
        @Query("UPDATE user_account SET age = :age WHERE last_name = :ln")
        int resetAgeByLastName(@com.landawn.abacus.jdbc.annotation.Bind("age") int age, @com.landawn.abacus.jdbc.annotation.Bind("ln") String ln)
                throws SQLException;

        // "get" is a query-method name prefix, but the SQL is an UPDATE.
        @Query("UPDATE user_account SET age = age + 1 WHERE last_name = :ln")
        int getAndIncrementAgeByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln) throws SQLException;
    }

    // Regression (thread-local DAO cache scope): whether a custom @Query method invalidated or used the cache was
    // decided only by its name prefix, so an UPDATE named "reset..." left stale cached query results behind, and an
    // UPDATE named "get..." was itself served from the cache on the second call without executing.
    @Test
    public void testLocalThreadCache_CustomWriteQueryClassifiedBySqlNotName() throws SQLException {
        final CustomWriteCacheDao writeDao = JdbcUtil.createDao(CustomWriteCacheDao.class, ds);
        final long id = dao.insert(newUser("CW1", "CustomWrite", 10));

        try (JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope()) {
            assertEquals(10, writeDao.list(Filters.eq("lastName", "CustomWrite")).get(0).getAge());

            assertEquals(1, writeDao.resetAgeByLastName(20, "CustomWrite"));
            assertEquals(20, writeDao.list(Filters.eq("lastName", "CustomWrite")).get(0).getAge());

            assertEquals(1, writeDao.getAndIncrementAgeByLastName("CustomWrite"));
            assertEquals(1, writeDao.getAndIncrementAgeByLastName("CustomWrite"));
        }

        assertEquals(22, dao.getOrNull(id).getAge());
    }

    public interface LocalCacheProbeDao extends CrudDao<UserAccount, Long, LocalCacheProbeDao> {
        // "find" prefix makes this a query method for the thread-local DAO cache; a Stream result must never be cached.
        @Query("SELECT * FROM user_account WHERE last_name = :ln ORDER BY id")
        Stream<UserAccount> findStreamByLastName(@com.landawn.abacus.jdbc.annotation.Bind("ln") String ln);
    }

    // Regression (thread-local DAO cache scope):
    // 1. a query method returning a Stream was cached like any other result, so cloning the live stream for the
    //    cache failed (ParsingException with JSON serialization) or handed a consumed stream to the next caller;
    // 2. built-in generic methods such as list(Condition) declare List<T>, whose element type resolves to Object,
    //    so a JSON-serialized cache hit came back as a List of HashMaps (ClassCastException in the caller).
    @Test
    public void testLocalThreadCache_StreamNotCachedAndBuiltInListKeepsEntityType() throws SQLException {
        final LocalCacheProbeDao probeDao = JdbcUtil.createDao(LocalCacheProbeDao.class, ds);
        dao.insert(newUser("LC1", "LocalCache", 1));
        dao.insert(newUser("LC2", "LocalCache", 2));

        try (JdbcUtil.DaoCacheScope scope = JdbcUtil.openDaoCacheScope()) {
            try (Stream<UserAccount> first = probeDao.findStreamByLastName("LocalCache")) {
                assertEquals(2L, first.count());
            }

            try (Stream<UserAccount> second = probeDao.findStreamByLastName("LocalCache")) {
                assertEquals(2L, second.count());
            }

            final List<UserAccount> firstList = probeDao.list(Filters.eq("lastName", "LocalCache"));
            final List<UserAccount> cachedList = probeDao.list(Filters.eq("lastName", "LocalCache"));

            assertEquals(2, firstList.size());
            assertEquals(2, cachedList.size());
            assertEquals(UserAccount.class, ((List<?>) cachedList).get(0).getClass());
            assertEquals("LC1", cachedList.get(0).getFirstName());
            assertEquals("LC2", cachedList.get(1).getFirstName());
        }
    }
}
