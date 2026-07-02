package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

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
import com.landawn.abacus.util.u.Optional;

/**
 * End-to-end proof that a <b>read-only</b> join-entity DAO can now be declared, created, and used.
 *
 * <p>Before the RO-JOIN-BOUND fix, {@link ReadOnlyCrudJoinEntityHelper} (and its siblings) bounded
 * their self-type {@code TD} to the full {@code CrudDao} type, which no read-only DAO can satisfy —
 * so a {@code ReadOnlyCrudDao + ReadOnlyCrudJoinEntityHelper} interface simply would not compile, and
 * the existing {@code ReadOnly*JoinEntityHelperTest} classes could only make structural (reflection)
 * assertions. The helper bounds were re-pointed at the read-only DAO family and the internal
 * {@code DaoUtil.getReadOps}/{@code getCrudReadOps} casts generalized to the read capability
 * ({@code ReadOps}/{@code CrudReadOps}), so the pattern below now works.
 *
 * <p>This test declares exactly that previously-impossible DAO, seeds through separate full-CRUD DAOs
 * (the read-only DAO has no insert), and exercises every read-side join path — {@code loadJoinEntities},
 * {@code loadAllJoinEntities}, {@code get(id, Class)}, and {@code findFirst(..., Class, cond)}.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class ReadOnlyJoinEntityHelperIntegrationTest extends TestBase {

    @Table("ro_join_user")
    public static class RoUser {
        @Id
        @ReadOnly
        private Long id;
        private String name;

        @JoinedBy("id=RoOrder.userId")
        private List<RoOrder> orders;

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

        public List<RoOrder> getOrders() {
            return orders;
        }

        public void setOrders(final List<RoOrder> orders) {
            this.orders = orders;
        }
    }

    @Table("ro_join_order")
    public static class RoOrder {
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

    /** Full-CRUD DAOs used only to seed rows (the read-only DAO under test cannot insert). */
    public interface RoSeedUserDao extends UncheckedCrudDao<RoUser, Long, RoSeedUserDao> {
    }

    public interface RoOrderDao extends UncheckedCrudDao<RoOrder, Long, RoOrderDao> {
    }

    /**
     * The DAO the RO-JOIN-BOUND fix makes possible: a read-only CRUD DAO that also mixes in read-side
     * join loading. Extending {@code UncheckedReadOnlyCrudDao} means insert/update/delete are absent at
     * compile time, while {@code UncheckedReadOnlyCrudJoinEntityHelper} supplies the join reads.
     */
    @DaoConfig(allowJoiningByNullOrDefaultValue = true)
    public interface RoUserDao extends UncheckedReadOnlyCrudDao<RoUser, Long, RoUserDao>, UncheckedReadOnlyCrudJoinEntityHelper<RoUser, Long, RoUserDao> {
    }

    private DataSource ds;
    private RoSeedUserDao seedUserDao;
    private RoOrderDao orderDao;
    private RoUserDao roUserDao;

    @BeforeAll
    public void initDb() throws SQLException {
        ds = JdbcUtil.createHikariDataSource("jdbc:h2:mem:ro_join_it;DB_CLOSE_DELAY=-1", "sa", "");

        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS ro_join_user (id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(64))");
            st.execute("CREATE TABLE IF NOT EXISTS ro_join_order (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT, amount DOUBLE)");
        }

        seedUserDao = JdbcUtil.createDao(RoSeedUserDao.class, ds);
        orderDao = JdbcUtil.createDao(RoOrderDao.class, ds);
        // The previously-impossible call: creating a read-only join DAO must succeed.
        roUserDao = JdbcUtil.createDao(RoUserDao.class, ds);
    }

    @AfterAll
    public void dropDb() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS ro_join_user");
            st.execute("DROP TABLE IF EXISTS ro_join_order");
        }
    }

    @BeforeEach
    public void cleanTables() throws SQLException {
        try (Connection conn = ds.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("TRUNCATE TABLE ro_join_user");
            st.execute("TRUNCATE TABLE ro_join_order");
        }
    }

    private long seedUser(final String name, final double... amounts) {
        final RoUser u = new RoUser();
        u.setName(name);
        final Long id = seedUserDao.insert(u);
        for (final double amt : amounts) {
            final RoOrder o = new RoOrder();
            o.setUserId(id);
            o.setAmount(amt);
            orderDao.insert(o);
        }
        return id;
    }

    /** The read-only join DAO exists and is genuinely read-only (not a full {@code Dao}/{@code CrudDao}). */
    @Test
    public void testReadOnlyCrudJoinDao_isReadOnlyButCrudReadableAndJoinable() {
        assertNotNull(roUserDao);
        // Has read + crud-read + join-read capability...
        assertTrue(CrudReadOps.class.isAssignableFrom(RoUserDao.class));
        assertTrue(CrudJoinEntityReadOps.class.isAssignableFrom(RoUserDao.class));
        // ...but is NOT a full Dao/CrudDao (write ops absent at compile time).
        assertFalse(Dao.class.isAssignableFrom(RoUserDao.class));
        assertFalse(CrudDao.class.isAssignableFrom(RoUserDao.class));
    }

    /** {@code gett(id)} + {@code loadJoinEntities} / {@code loadAllJoinEntities} run through the generalized cast. */
    @Test
    public void testReadOnlyCrudJoinDao_loadJoins() {
        final long id = seedUser("Reader", 10.0, 20.0, 30.0);

        final RoUser u = roUserDao.gett(id);
        assertNotNull(u);
        assertEquals("Reader", u.getName());

        roUserDao.loadJoinEntities(u, RoOrder.class);
        assertEquals(3, u.getOrders().size());

        final RoUser u2 = roUserDao.gett(id);
        roUserDao.loadAllJoinEntities(u2);
        assertEquals(3, u2.getOrders().size());
    }

    /** {@code get(id, Class)} (CrudJoinEntityReadOps) loads the entity and its joins in one call. */
    @Test
    public void testReadOnlyCrudJoinDao_getByIdWithJoin() {
        final long id = seedUser("GetWithJoin", 1.0, 2.0);

        final Optional<RoUser> loaded = roUserDao.get(id, RoOrder.class);
        assertTrue(loaded.isPresent());
        assertEquals(2, loaded.get().getOrders().size());
    }

    /** {@code findFirst(selectPropNames, Class, cond)} (JoinEntityReadOps) works on a read-only DAO. */
    @Test
    public void testReadOnlyCrudJoinDao_findFirstWithJoin() {
        seedUser("FindMe", 5.0, 6.0);

        final Optional<RoUser> found = roUserDao.findFirst(null, RoOrder.class, Filters.eq("name", "FindMe"));
        assertTrue(found.isPresent());
        assertEquals(2, found.get().getOrders().size());
    }
}
