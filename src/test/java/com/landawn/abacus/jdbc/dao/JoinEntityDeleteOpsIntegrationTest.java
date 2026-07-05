package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.annotation.ReadOnly;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.jdbc.JdbcUtil;
import com.landawn.abacus.jdbc.annotation.DaoConfig;

/**
 * End-to-end integration coverage for the <i>non-crud</i> join-entity helper bodies that the existing
 * {@link JoinEntityHelperIntegrationTest} (which only wires <i>Crud</i> DAOs) never executes.
 *
 * <p>The Crud join helpers ({@code CrudJoinEntityHelper} / {@code UncheckedCrudJoinEntityHelper}) re-declare
 * and override the delete / load methods, so a Crud DAO never runs the bodies declared in
 * {@link UncheckedJoinEntityDeleteOps}, {@link UncheckedJoinEntityReadOps} and
 * {@link JoinEntityReadOps}. To execute those bodies this test uses genuinely non-crud DAOs:</p>
 * <ul>
 *   <li>{@link DJUserDao} {@code = UncheckedDao + UncheckedJoinEntityHelper} (covers the unchecked
 *       deletable + readable helper bodies).</li>
 *   <li>{@link CJUserDao} {@code = Dao + JoinEntityHelper} (covers the checked readable helper bodies;
 *       its methods declare {@code throws SQLException}).</li>
 * </ul>
 *
 * <p>Both entities declare a <b>single</b> {@code @JoinedBy} property so the {@code size() == 1}
 * single-property branches (which the two-property model in {@code JoinEntityHelperIntegrationTest}
 * skips) are reached. Rows are seeded with raw JDBC so the (non-crud) DAOs need not expose insert/gett
 * by id; entity instances are built in Java with the id set and handed to the load/delete methods.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
public class JoinEntityDeleteOpsIntegrationTest extends TestBase {

    // ----- Unchecked, non-crud entity/DAO (single join property) -----
    @Table("dj_user")
    public static class DJUser {
        @Id
        @ReadOnly
        private Long id;
        private String name;

        @JoinedBy("id=DJOrder.userId")
        private List<DJOrder> orders;

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

        public List<DJOrder> getOrders() {
            return orders;
        }

        public void setOrders(final List<DJOrder> orders) {
            this.orders = orders;
        }
    }

    @Table("dj_order")
    public static class DJOrder {
        @Id
        @ReadOnly
        private Long id;
        private long userId;
        private double amount;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public long getUserId() {
            return userId;
        }

        public void setUserId(final long userId) {
            this.userId = userId;
        }

        public double getAmount() {
            return amount;
        }

        public void setAmount(final double amount) {
            this.amount = amount;
        }
    }

    @DaoConfig(allowJoiningByNullOrDefaultValue = true)
    public interface DJUserDao extends UncheckedDao<DJUser, DJUserDao>, UncheckedJoinEntityHelper<DJUser, DJUserDao> {
    }

    // ----- Checked, non-crud entity/DAO (single join property) -----
    @Table("cj_user")
    public static class CJUser {
        @Id
        @ReadOnly
        private Long id;
        private String name;

        @JoinedBy("id=CJOrder.userId")
        private List<CJOrder> orders;

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

        public List<CJOrder> getOrders() {
            return orders;
        }

        public void setOrders(final List<CJOrder> orders) {
            this.orders = orders;
        }
    }

    @Table("cj_order")
    public static class CJOrder {
        @Id
        @ReadOnly
        private Long id;
        private long userId;
        private double amount;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }

        public long getUserId() {
            return userId;
        }

        public void setUserId(final long userId) {
            this.userId = userId;
        }

        public double getAmount() {
            return amount;
        }

        public void setAmount(final double amount) {
            this.amount = amount;
        }
    }

    @DaoConfig(allowJoiningByNullOrDefaultValue = true)
    public interface CJUserDao extends Dao<CJUser, CJUserDao>, JoinEntityHelper<CJUser, CJUserDao> {
    }

    /** Same-thread executor so the explicit-executor overloads run deterministically. */
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    private DataSource ds;
    private DJUserDao djUserDao;
    private CJUserDao cjUserDao;

    @BeforeAll
    public void initDb() throws SQLException {
        ds = JdbcUtil.createHikariDataSource("jdbc:h2:mem:deletable_join_it;DB_CLOSE_DELAY=-1", "sa", "");

        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS dj_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(64))");
            st.execute("CREATE TABLE IF NOT EXISTS dj_order (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, amount DOUBLE)");
            st.execute("CREATE TABLE IF NOT EXISTS cj_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(64))");
            st.execute("CREATE TABLE IF NOT EXISTS cj_order (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, amount DOUBLE)");
        }

        djUserDao = JdbcUtil.createDao(DJUserDao.class, ds);
        cjUserDao = JdbcUtil.createDao(CJUserDao.class, ds);
    }

    @AfterAll
    public void dropDb() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS dj_user");
            st.execute("DROP TABLE IF EXISTS dj_order");
            st.execute("DROP TABLE IF EXISTS cj_user");
            st.execute("DROP TABLE IF EXISTS cj_order");
        }
    }

    @BeforeEach
    public void cleanTables() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("TRUNCATE TABLE dj_user");
            st.execute("TRUNCATE TABLE dj_order");
            st.execute("TRUNCATE TABLE cj_user");
            st.execute("TRUNCATE TABLE cj_order");
        }
    }

    // ---------- raw-JDBC seed / count helpers ----------

    /** Seeds one dj_user row (explicit id) plus {@code orderCount} dj_order rows linked to it. */
    private void seedDjUser(final long userId, final String name, final int orderCount) throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO dj_user (id, name) VALUES (" + userId + ", '" + name + "')");
            for (int i = 0; i < orderCount; i++) {
                st.execute("INSERT INTO dj_order (user_id, amount) VALUES (" + userId + ", " + ((i + 1) * 10.0) + ")");
            }
        }
    }

    /** Seeds one cj_user row (explicit id) plus {@code orderCount} cj_order rows linked to it. */
    private void seedCjUser(final long userId, final String name, final int orderCount) throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("INSERT INTO cj_user (id, name) VALUES (" + userId + ", '" + name + "')");
            for (int i = 0; i < orderCount; i++) {
                st.execute("INSERT INTO cj_order (user_id, amount) VALUES (" + userId + ", " + ((i + 1) * 10.0) + ")");
            }
        }
    }

    private long countChildRows(final String table, final long userId) throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE user_id = " + userId)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long djOrderCount(final long userId) throws SQLException {
        return countChildRows("dj_order", userId);
    }

    private static DJUser djUser(final long id) {
        final DJUser u = new DJUser();
        u.setId(id);
        return u;
    }

    private static CJUser cjUser(final long id) {
        final CJUser u = new CJUser();
        u.setId(id);
        return u;
    }

    // =====================================================================
    // UncheckedJoinEntityDeleteOps
    // =====================================================================

    // deleteJoinEntities(entity, Class) with a single join property -> delegates to the single prop-name overload (L77).
    @Test
    public void testDeleteJoinEntitiesByClass_SingleProp() throws SQLException {
        seedDjUser(1, "DelByClass", 3);
        assertEquals(3, djOrderCount(1));

        final int deleted = djUserDao.deleteJoinEntities(djUser(1), DJOrder.class);

        assertEquals(3, deleted);
        assertEquals(0, djOrderCount(1));
    }

    // deleteJoinEntities(entities, Class) with a single join property -> delegates to the single prop-name overload (L127).
    @Test
    public void testDeleteJoinEntitiesByClass_Collection_SingleProp() throws SQLException {
        seedDjUser(1, "DelByClassA", 2);
        seedDjUser(2, "DelByClassB", 1);

        final int deleted = djUserDao.deleteJoinEntities(List.of(djUser(1), djUser(2)), DJOrder.class);

        assertEquals(3, deleted);
        assertEquals(0, djOrderCount(1) + djOrderCount(2));
    }

    // deleteJoinEntities(entity, Collection<String>) early-returns 0 for an empty prop-name collection (L270).
    @Test
    public void testDeleteJoinEntities_EmptyPropNames() throws SQLException {
        seedDjUser(1, "EmptyProps", 2);

        assertEquals(0, djUserDao.deleteJoinEntities(djUser(1), new ArrayList<>()));
        assertEquals(2, djOrderCount(1)); // untouched
    }

    // deleteJoinEntities(entity, Collection<String>, Executor) early-returns 0 for an empty prop-name collection (L323).
    @Test
    public void testDeleteJoinEntities_EmptyPropNames_WithExecutor() throws SQLException {
        seedDjUser(1, "EmptyPropsExec", 2);

        assertEquals(0, djUserDao.deleteJoinEntities(djUser(1), new ArrayList<>(), DIRECT_EXECUTOR));
        assertEquals(2, djOrderCount(1));
    }

    // deleteJoinEntities(entities, Collection<String>) early-returns 0 for empty entities or empty prop-names (L391).
    @Test
    public void testDeleteJoinEntities_Collection_EmptyArgs() throws SQLException {
        seedDjUser(1, "CollEmpty", 2);

        assertEquals(0, djUserDao.deleteJoinEntities(new ArrayList<>(), List.of("orders")));
        assertEquals(0, djUserDao.deleteJoinEntities(List.of(djUser(1)), new ArrayList<>()));
        assertEquals(2, djOrderCount(1)); // untouched
    }

    // deleteJoinEntities(entities, Collection<String>) single-element collection -> firstOrNullIfEmpty delegate (L395).
    @Test
    public void testDeleteJoinEntities_Collection_SinglePropName() throws SQLException {
        seedDjUser(1, "CollSingleProp", 2);

        assertEquals(2, djUserDao.deleteJoinEntities(List.of(djUser(1)), List.of("orders")));
        assertEquals(0, djOrderCount(1));
    }

    // deleteJoinEntities(entities, Collection<String>, false) -> sequential delegate (L445).
    @Test
    public void testDeleteJoinEntities_Collection_SinglePropName_Sequential() throws SQLException {
        seedDjUser(1, "CollSeq", 2);

        assertEquals(2, djUserDao.deleteJoinEntities(List.of(djUser(1)), List.of("orders"), false));
        assertEquals(0, djOrderCount(1));
    }

    // deleteJoinEntities(entities, Collection<String>, Executor) early-returns 0 for empty args (L480).
    @Test
    public void testDeleteJoinEntities_Collection_EmptyArgs_WithExecutor() throws SQLException {
        seedDjUser(1, "CollEmptyExec", 2);

        assertEquals(0, djUserDao.deleteJoinEntities(new ArrayList<>(), List.of("orders"), DIRECT_EXECUTOR));
        assertEquals(2, djOrderCount(1));
    }

    // deleteAllJoinEntities(entity) -> deleteJoinEntities(entity, allJoinPropNames) (L508).
    @Test
    public void testDeleteAllJoinEntities_SingleEntity() throws SQLException {
        seedDjUser(1, "AllSingle", 3);

        assertEquals(3, djUserDao.deleteAllJoinEntities(djUser(1)));
        assertEquals(0, djOrderCount(1));
    }

    // deleteAllJoinEntities(entity, true) -> executor() path; deleteAllJoinEntities(entity, false) -> sequential (L534/L536).
    @Test
    public void testDeleteAllJoinEntities_SingleEntity_Parallel() throws SQLException {
        seedDjUser(1, "AllParallel", 2);
        assertEquals(2, djUserDao.deleteAllJoinEntities(djUser(1), true));
        assertEquals(0, djOrderCount(1));

        seedDjUser(2, "AllSequential", 2);
        assertEquals(2, djUserDao.deleteAllJoinEntities(djUser(2), false));
        assertEquals(0, djOrderCount(2));
    }

    // deleteAllJoinEntities(entity, Executor) -> deleteJoinEntities(entity, propNames, executor) (L563).
    @Test
    public void testDeleteAllJoinEntities_SingleEntity_WithExecutor() throws SQLException {
        seedDjUser(1, "AllSingleExec", 2);

        assertEquals(2, djUserDao.deleteAllJoinEntities(djUser(1), DIRECT_EXECUTOR));
        assertEquals(0, djOrderCount(1));
    }

    // deleteAllJoinEntities(entities) early-returns 0 for an empty collection (L585).
    @Test
    public void testDeleteAllJoinEntities_Collection_Empty() {
        assertEquals(0, djUserDao.deleteAllJoinEntities(new ArrayList<>()));
    }

    // deleteAllJoinEntities(entities) over a collection (L588).
    @Test
    public void testDeleteAllJoinEntities_Collection() throws SQLException {
        seedDjUser(1, "AllCollA", 2);
        seedDjUser(2, "AllCollB", 1);

        assertEquals(3, djUserDao.deleteAllJoinEntities(List.of(djUser(1), djUser(2))));
        assertEquals(0, djOrderCount(1) + djOrderCount(2));
    }

    // deleteAllJoinEntities(entities, true) -> executor() path; (entities, false) -> sequential (L614/L616).
    @Test
    public void testDeleteAllJoinEntities_Collection_Parallel() throws SQLException {
        seedDjUser(1, "AllCollPar1", 2);
        seedDjUser(2, "AllCollPar2", 1);
        assertEquals(3, djUserDao.deleteAllJoinEntities(List.of(djUser(1), djUser(2)), true));
        assertEquals(0, djOrderCount(1) + djOrderCount(2));

        seedDjUser(3, "AllCollSeq1", 2);
        seedDjUser(4, "AllCollSeq2", 1);
        assertEquals(3, djUserDao.deleteAllJoinEntities(List.of(djUser(3), djUser(4)), false));
        assertEquals(0, djOrderCount(3) + djOrderCount(4));
    }

    // deleteAllJoinEntities(entities, Executor) early-returns 0 for an empty collection (L644).
    @Test
    public void testDeleteAllJoinEntities_Collection_Empty_WithExecutor() {
        assertEquals(0, djUserDao.deleteAllJoinEntities(new ArrayList<>(), DIRECT_EXECUTOR));
    }

    // deleteAllJoinEntities(entities, Executor) -> deleteJoinEntities(entities, propNames, executor) (L647).
    @Test
    public void testDeleteAllJoinEntities_Collection_WithExecutor() throws SQLException {
        seedDjUser(1, "AllCollExecA", 2);
        seedDjUser(2, "AllCollExecB", 1);

        assertEquals(3, djUserDao.deleteAllJoinEntities(List.of(djUser(1), djUser(2)), DIRECT_EXECUTOR));
        assertEquals(0, djOrderCount(1) + djOrderCount(2));
    }

    // =====================================================================
    // UncheckedJoinEntityReadOps
    // =====================================================================

    // loadAllJoinEntities(entities) and (entities, Executor) early-return for an empty collection (L940/L994).
    @Test
    public void testLoadAllJoinEntities_Collection_EmptyCollection() {
        assertDoesNotThrow(() -> {
            djUserDao.loadAllJoinEntities(new ArrayList<>());
            djUserDao.loadAllJoinEntities(new ArrayList<>(), DIRECT_EXECUTOR);
        });
    }

    // loadJoinEntitiesIfAbsent(entities, Class) single join property -> get(0) delegate branch (L1111-1112).
    @Test
    public void testLoadJoinEntitiesIfAbsent_Collection_SingleProp() throws SQLException {
        seedDjUser(1, "IfAbsentSingle", 3);
        final List<DJUser> users = new ArrayList<>(List.of(djUser(1)));

        djUserDao.loadJoinEntitiesIfAbsent(users, DJOrder.class);

        assertEquals(3, users.get(0).getOrders().size());
    }

    // Every loadJoinEntitiesIfAbsent collection overload early-returns for empty entities/prop-names
    // (L1102 / L1223 / L1327 / L1359 / L1427).
    @Test
    public void testLoadJoinEntitiesIfAbsent_EmptyCollections() {
        assertDoesNotThrow(() -> {
            djUserDao.loadJoinEntitiesIfAbsent(new ArrayList<>(), DJOrder.class); // L1102
            djUserDao.loadJoinEntitiesIfAbsent(new ArrayList<>(), "orders"); // L1223
            djUserDao.loadJoinEntitiesIfAbsent(new DJUser(), new ArrayList<>(), DIRECT_EXECUTOR); // L1327
            djUserDao.loadJoinEntitiesIfAbsent(new ArrayList<>(), List.of("orders")); // L1359
            djUserDao.loadJoinEntitiesIfAbsent(new ArrayList<>(), List.of("orders"), DIRECT_EXECUTOR); // L1427
        });
    }

    // loadJoinEntitiesIfAbsent(entity, unknownProp) throws IllegalArgumentException (L1168).
    @Test
    public void testLoadJoinEntitiesIfAbsent_UnknownProp() {
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> djUserDao.loadJoinEntitiesIfAbsent(djUser(1), "noSuchProp"));
        assertTrue(ex.getMessage().contains("No property found by name"));
    }

    // loadJoinEntitiesIfAbsent(entities, unknownProp) throws IllegalArgumentException (L1230).
    @Test
    public void testLoadJoinEntitiesIfAbsent_Collection_UnknownProp() {
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> djUserDao.loadJoinEntitiesIfAbsent(List.of(djUser(1)), "noSuchProp"));
        assertTrue(ex.getMessage().contains("No property found by name"));
    }

    // =====================================================================
    // JoinEntityReadOps (checked variant -> throws SQLException)
    // =====================================================================

    // loadJoinEntitiesIfAbsent(entities, Class) single join property -> get(0) delegate branch (L1162-1163).
    @Test
    public void testLoadJoinEntitiesIfAbsent_Checked_Collection_SingleProp() throws SQLException {
        seedCjUser(1, "CheckedIfAbsent", 2);
        final List<CJUser> users = new ArrayList<>(List.of(cjUser(1)));

        cjUserDao.loadJoinEntitiesIfAbsent(users, CJOrder.class);

        assertEquals(2, users.get(0).getOrders().size());
    }

    // loadJoinEntitiesIfAbsent(entity, unknownProp) throws IllegalArgumentException (L1213).
    @Test
    public void testLoadJoinEntitiesIfAbsent_Checked_UnknownProp() {
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> cjUserDao.loadJoinEntitiesIfAbsent(cjUser(1), "noSuchProp"));
        assertTrue(ex.getMessage().contains("No property found by name"));
    }

    // loadJoinEntitiesIfAbsent(entities, unknownProp) throws IllegalArgumentException (L1269).
    @Test
    public void testLoadJoinEntitiesIfAbsent_Checked_Collection_UnknownProp() {
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> cjUserDao.loadJoinEntitiesIfAbsent(List.of(cjUser(1)), "noSuchProp"));
        assertTrue(ex.getMessage().contains("No property found by name"));
    }
}
