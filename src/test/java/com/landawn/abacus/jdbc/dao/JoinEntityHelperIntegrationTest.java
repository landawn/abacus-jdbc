package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
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
import com.landawn.abacus.query.Filters;
import static com.landawn.abacus.query.Dsl.PSC;
import com.landawn.abacus.util.u.Optional;

/**
 * End-to-end integration coverage for the dynamically generated join-entity helper bodies
 * ({@link UncheckedJoinEntityHelper} / {@link UncheckedCrudJoinEntityHelper}) backed by a real
 * in-memory H2 database. The existing {@code JoinEntityHelperTest} / {@code UncheckedJoinEntityHelperTest}
 * mock the {@code DataSource}, which short-circuits every {@code loadJoinEntities} /
 * {@code deleteJoinEntities} / find/list-by-join-class body before it touches a live DAO. This test
 * wires a genuine {@code @JoinedBy} relationship (with two same-type join properties so the
 * multi-property transaction branches are reached) so those bodies actually execute.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class JoinEntityHelperIntegrationTest extends TestBase {

    @Table("join_user")
    public static class JoinUser {
        @Id
        @ReadOnly
        private Long id;
        private String name;

        // Two distinct join properties of the SAME referenced type drive the size() > 1
        // (multi-property, transactional) branches in deleteJoinEntities(..., Class)/(..., Collection).
        @JoinedBy("id=JoinOrder.userId")
        private List<JoinOrder> orders;
        @JoinedBy("id=JoinOrder.userId")
        private List<JoinOrder> backupOrders;

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

        public List<JoinOrder> getOrders() {
            return orders;
        }

        public void setOrders(final List<JoinOrder> orders) {
            this.orders = orders;
        }

        public List<JoinOrder> getBackupOrders() {
            return backupOrders;
        }

        public void setBackupOrders(final List<JoinOrder> backupOrders) {
            this.backupOrders = backupOrders;
        }
    }

    @Table("join_order")
    public static class JoinOrder {
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
    public interface JoinUserDao extends UncheckedCrudDao<JoinUser, Long, JoinUserDao>, UncheckedCrudJoinEntityHelper<JoinUser, Long, JoinUserDao> {
    }

    public interface JoinOrderDao extends UncheckedCrudDao<JoinOrder, Long, JoinOrderDao> {
    }

    /** Same-thread executor so the deprecated parallel overloads run deterministically. */
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    private DataSource ds;
    private JoinUserDao userDao;
    private JoinOrderDao orderDao;

    @BeforeAll
    public void initDb() throws SQLException {
        ds = JdbcUtil.createHikariDataSource("jdbc:h2:mem:join_it;DB_CLOSE_DELAY=-1", "sa", "");

        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS join_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(64))");
            st.execute("CREATE TABLE IF NOT EXISTS join_order (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, amount DOUBLE)");
        }

        userDao = JdbcUtil.createDao(JoinUserDao.class, ds, PSC);
        orderDao = JdbcUtil.createDao(JoinOrderDao.class, ds, PSC);
    }

    @AfterAll
    public void dropDb() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS join_user");
            st.execute("DROP TABLE IF EXISTS join_order");
        }
    }

    @BeforeEach
    public void cleanTables() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("TRUNCATE TABLE join_user");
            st.execute("TRUNCATE TABLE join_order");
        }
    }

    /** Inserts one user plus {@code amounts.length} orders linked to it; returns the persisted user. */
    private JoinUser seedUser(final String name, final double... amounts) {
        final JoinUser u = new JoinUser();
        u.setName(name);
        final Long id = userDao.insert(u);
        u.setId(id);
        for (final double amt : amounts) {
            final JoinOrder o = new JoinOrder();
            o.setUserId(id);
            o.setAmount(amt);
            orderDao.insert(o);
        }
        return u;
    }

    private long orderCountForUser(final long userId) {
        return orderDao.count(Filters.eq("userId", userId));
    }

    // loadJoinEntities by prop-name and by class (both single-type join properties are populated).
    @Test
    public void testLoadJoinEntities_ByPropNameAndByClass() {
        final JoinUser u = seedUser("Loader", 10.0, 20.0, 30.0);

        userDao.loadJoinEntities(u, "orders");
        assertNotNull(u.getOrders());
        assertEquals(3, u.getOrders().size());

        // by class -> getJoinEntityPropNamesByType returns {orders, backupOrders}; both get loaded.
        final JoinUser u2 = userDao.gett(u.getId());
        userDao.loadJoinEntities(u2, JoinOrder.class);
        assertEquals(3, u2.getOrders().size());
        assertEquals(3, u2.getBackupOrders().size());

        // by class with explicit select columns drives the select-prop loop branch.
        final JoinUser u3 = userDao.gett(u.getId());
        userDao.loadJoinEntities(u3, JoinOrder.class, List.of("id", "userId", "amount"));
        assertEquals(3, u3.getOrders().size());
    }

    // loadJoinEntities over a collection of entities, by class and with select columns.
    @Test
    public void testLoadJoinEntities_Collection_ByClass() {
        final JoinUser a = seedUser("CollA", 1.0, 2.0);
        final JoinUser b = seedUser("CollB", 3.0);
        final List<JoinUser> users = new ArrayList<>(List.of(userDao.gett(a.getId()), userDao.gett(b.getId())));

        userDao.loadJoinEntities(users, JoinOrder.class);
        assertEquals(2, users.get(0).getOrders().size());
        assertEquals(1, users.get(1).getOrders().size());

        final List<JoinUser> users2 = new ArrayList<>(List.of(userDao.gett(a.getId()), userDao.gett(b.getId())));
        userDao.loadJoinEntities(users2, JoinOrder.class, List.of("id", "userId", "amount"));
        assertEquals(2, users2.get(0).getOrders().size());
    }

    // loadAllJoinEntities loads every @JoinedBy property; single + collection + executor overloads.
    @Test
    public void testLoadAllJoinEntities_Variants() {
        final JoinUser u = seedUser("AllLoader", 5.0, 6.0);

        final JoinUser single = userDao.gett(u.getId());
        userDao.loadAllJoinEntities(single);
        assertEquals(2, single.getOrders().size());
        assertEquals(2, single.getBackupOrders().size());

        final JoinUser parallel = userDao.gett(u.getId());
        userDao.loadAllJoinEntities(parallel, true);
        assertEquals(2, parallel.getOrders().size());

        final JoinUser exec = userDao.gett(u.getId());
        userDao.loadAllJoinEntities(exec, DIRECT_EXECUTOR);
        assertEquals(2, exec.getOrders().size());

        final List<JoinUser> users = new ArrayList<>(List.of(userDao.gett(u.getId())));
        userDao.loadAllJoinEntities(users);
        assertEquals(2, users.get(0).getOrders().size());

        final List<JoinUser> usersExec = new ArrayList<>(List.of(userDao.gett(u.getId())));
        userDao.loadAllJoinEntities(usersExec, DIRECT_EXECUTOR);
        assertEquals(2, usersExec.get(0).getOrders().size());
    }

    // loadJoinEntitiesIfNull only loads when the property is currently null (single + collection + by class).
    @Test
    public void testLoadJoinEntitiesIfNull_Variants() {
        final JoinUser u = seedUser("IfNull", 7.0, 8.0, 9.0);

        final JoinUser fresh = userDao.gett(u.getId());
        userDao.loadJoinEntitiesIfNull(fresh, JoinOrder.class);
        assertEquals(3, fresh.getOrders().size());

        // already populated -> the IfNull guard skips the reload (still 3, no error).
        userDao.loadJoinEntitiesIfNull(fresh, JoinOrder.class);
        assertEquals(3, fresh.getOrders().size());

        final JoinUser byProps = userDao.gett(u.getId());
        userDao.loadJoinEntitiesIfNull(byProps, JoinOrder.class, List.of("id", "userId", "amount"));
        assertEquals(3, byProps.getOrders().size());

        final List<JoinUser> users = new ArrayList<>(List.of(userDao.gett(u.getId())));
        userDao.loadJoinEntitiesIfNull(users, JoinOrder.class);
        assertEquals(3, users.get(0).getOrders().size());

        final List<JoinUser> usersByProps = new ArrayList<>(List.of(userDao.gett(u.getId())));
        userDao.loadJoinEntitiesIfNull(usersByProps, JoinOrder.class, List.of("id", "amount"));
        assertEquals(3, usersByProps.get(0).getOrders().size());
    }

    // loadJoinEntities(entity/entities, Collection<String>) plus the deprecated parallel / executor overloads.
    @Test
    public void testLoadJoinEntities_PropNameCollection_AndParallel() {
        final JoinUser u = seedUser("MultiProp", 1.0, 2.0);

        final JoinUser byNames = userDao.gett(u.getId());
        userDao.loadJoinEntities(byNames, List.of("orders", "backupOrders"));
        assertEquals(2, byNames.getOrders().size());
        assertEquals(2, byNames.getBackupOrders().size());

        final JoinUser parallel = userDao.gett(u.getId());
        userDao.loadJoinEntities(parallel, List.of("orders", "backupOrders"), true);
        assertEquals(2, parallel.getOrders().size());

        final JoinUser exec = userDao.gett(u.getId());
        userDao.loadJoinEntities(exec, List.of("orders", "backupOrders"), DIRECT_EXECUTOR);
        assertEquals(2, exec.getOrders().size());

        final List<JoinUser> users = new ArrayList<>(List.of(userDao.gett(u.getId())));
        userDao.loadJoinEntities(users, List.of("orders", "backupOrders"));
        assertEquals(2, users.get(0).getOrders().size());

        final List<JoinUser> usersExec = new ArrayList<>(List.of(userDao.gett(u.getId())));
        userDao.loadJoinEntities(usersExec, List.of("orders", "backupOrders"), DIRECT_EXECUTOR);
        assertEquals(2, usersExec.get(0).getOrders().size());
    }

    // findFirst / findOnlyOne / list with a join-entity class (single, collection, includeAll overloads).
    @Test
    public void testFindAndList_WithJoinClass() {
        final JoinUser u = seedUser("Finder", 100.0, 200.0);

        final Optional<JoinUser> first = userDao.findFirst(null, JoinOrder.class, Filters.eq("id", u.getId()));
        assertTrue(first.isPresent());
        assertEquals(2, first.get().getOrders().size());

        final Optional<JoinUser> firstColl = userDao.findFirst(null, List.<Class<?>> of(JoinOrder.class), Filters.eq("id", u.getId()));
        assertTrue(firstColl.isPresent());
        assertEquals(2, firstColl.get().getOrders().size());

        final Optional<JoinUser> firstAll = userDao.findFirst(null, true, Filters.eq("id", u.getId()));
        assertTrue(firstAll.isPresent());
        assertEquals(2, firstAll.get().getBackupOrders().size());

        final Optional<JoinUser> only = userDao.findOnlyOne(null, JoinOrder.class, Filters.eq("id", u.getId()));
        assertTrue(only.isPresent());
        assertEquals(2, only.get().getOrders().size());

        final Optional<JoinUser> onlyColl = userDao.findOnlyOne(null, List.<Class<?>> of(JoinOrder.class), Filters.eq("id", u.getId()));
        assertTrue(onlyColl.isPresent());
        assertEquals(2, onlyColl.get().getOrders().size());

        final Optional<JoinUser> onlyAll = userDao.findOnlyOne(null, true, Filters.eq("id", u.getId()));
        assertTrue(onlyAll.isPresent());
        assertEquals(2, onlyAll.get().getOrders().size());

        final List<JoinUser> byClass = userDao.list(null, JoinOrder.class, Filters.eq("id", u.getId()));
        assertEquals(1, byClass.size());
        assertEquals(2, byClass.get(0).getOrders().size());

        final List<JoinUser> byClassColl = userDao.list(null, List.<Class<?>> of(JoinOrder.class), Filters.eq("id", u.getId()));
        assertEquals(2, byClassColl.get(0).getOrders().size());

        final List<JoinUser> includeAll = userDao.list(null, true, Filters.eq("id", u.getId()));
        assertEquals(2, includeAll.get(0).getOrders().size());
    }

    // deleteJoinEntities by prop-name and by class (multi-property -> transactional branch).
    @Test
    public void testDeleteJoinEntities_ByPropNameAndByClass() {
        final JoinUser u = seedUser("Deleter", 1.0, 2.0, 3.0);
        assertEquals(3, orderCountForUser(u.getId()));

        // single prop-name delete removes all 3 linked rows.
        final int byName = userDao.deleteJoinEntities(u, "orders");
        assertEquals(3, byName);
        assertEquals(0, orderCountForUser(u.getId()));

        // by class with two same-type join props -> transactional multi-prop branch.
        final JoinUser u2 = seedUser("Deleter2", 4.0, 5.0);
        final int byClass = userDao.deleteJoinEntities(u2, JoinOrder.class);
        assertEquals(2, byClass);
        assertEquals(0, orderCountForUser(u2.getId()));
    }

    // deleteJoinEntities(entity, Collection<String>) single + multi-prop, plus deprecated parallel/executor overloads.
    @Test
    public void testDeleteJoinEntities_PropNameCollection_AndParallel() {
        // multi-prop list -> transactional branch.
        final JoinUser u = seedUser("DelMulti", 1.0, 2.0);
        final int multi = userDao.deleteJoinEntities(u, List.of("orders", "backupOrders"));
        assertEquals(2, multi);
        assertEquals(0, orderCountForUser(u.getId()));

        // single-element list -> delegates to the single prop-name overload.
        final JoinUser u2 = seedUser("DelSingle", 9.0);
        assertEquals(1, userDao.deleteJoinEntities(u2, List.of("orders")));
        assertEquals(0, orderCountForUser(u2.getId()));

        // deprecated inParallel=false / true and explicit executor (single prop-name -> deterministic).
        final JoinUser u3 = seedUser("DelSeq", 1.0, 2.0);
        assertEquals(2, userDao.deleteJoinEntities(u3, List.of("orders"), false));
        assertEquals(0, orderCountForUser(u3.getId()));

        final JoinUser u4 = seedUser("DelPar", 1.0, 2.0);
        userDao.deleteJoinEntities(u4, List.of("orders"), true);
        assertEquals(0, orderCountForUser(u4.getId()));

        final JoinUser u5 = seedUser("DelExec", 1.0);
        userDao.deleteJoinEntities(u5, List.of("orders"), DIRECT_EXECUTOR);
        assertEquals(0, orderCountForUser(u5.getId()));
    }

    // deleteJoinEntities over a collection of entities: by class, by prop-name collection, deprecated overloads.
    @Test
    public void testDeleteJoinEntities_Collection_Variants() {
        final JoinUser a = seedUser("DelCollA", 1.0, 2.0);
        final JoinUser b = seedUser("DelCollB", 3.0);
        final List<JoinUser> users = List.of(a, b);

        // by class -> multi same-type prop, transactional.
        final int byClass = userDao.deleteJoinEntities(users, JoinOrder.class);
        assertEquals(3, byClass);
        assertEquals(0, orderCountForUser(a.getId()) + orderCountForUser(b.getId()));

        // by prop-name collection (multi) -> transactional.
        final JoinUser c = seedUser("DelCollC", 4.0, 5.0);
        final List<JoinUser> users2 = List.of(c);
        assertEquals(2, userDao.deleteJoinEntities(users2, List.of("orders", "backupOrders")));
        assertEquals(0, orderCountForUser(c.getId()));

        // deprecated collection overloads (single prop-name -> deterministic).
        final JoinUser d = seedUser("DelCollD", 1.0, 2.0);
        final List<JoinUser> users3 = List.of(d);
        userDao.deleteJoinEntities(users3, List.of("orders"), true);
        assertEquals(0, orderCountForUser(d.getId()));

        final JoinUser e = seedUser("DelCollE", 1.0);
        final List<JoinUser> users4 = List.of(e);
        userDao.deleteJoinEntities(users4, List.of("orders"), DIRECT_EXECUTOR);
        assertEquals(0, orderCountForUser(e.getId()));
    }
}
