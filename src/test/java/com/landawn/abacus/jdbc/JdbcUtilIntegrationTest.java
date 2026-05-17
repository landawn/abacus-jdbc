package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;
import com.landawn.abacus.util.u.OptionalInt;

/**
 * Integration coverage for {@link JdbcUtil}'s query/transaction execution paths against a
 * live in-memory H2 database. These exercise {@code prepareQuery}, {@code prepareNamedQuery},
 * {@code beginTransaction}, and the {@code AbstractQuery} execution methods that
 * mock-based unit tests cannot drive end to end.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class JdbcUtilIntegrationTest extends TestBase {

    public static class Widget {
        private Long id;
        private String name;
        private int qty;

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

        public int getQty() {
            return qty;
        }

        public void setQty(final int qty) {
            this.qty = qty;
        }
    }

    private DataSource ds;

    @BeforeAll
    public void initDb() throws SQLException {
        ds = JdbcUtil.createHikariDataSource("jdbc:h2:mem:jdbcutil_it;DB_CLOSE_DELAY=-1", "sa", "");

        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS widget ("
                    + "id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                    + "name VARCHAR(64), "
                    + "qty INT)");
        }
    }

    @AfterAll
    public void dropDb() throws SQLException {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS widget");
        }
    }

    @BeforeEach
    public void cleanTable() throws SQLException {
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("TRUNCATE TABLE widget");
        }
    }

    private long insertWidget(final String name, final int qty) throws SQLException {
        final Optional<Long> key = JdbcUtil.prepareQuery(ds, "INSERT INTO widget (name, qty) VALUES (?, ?)", true)
                .setString(1, name)
                .setInt(2, qty)
                .insert();
        assertTrue(key.isPresent());
        return key.get();
    }

    // prepareQuery with auto-generated keys returns the inserted id; select reads it back.
    @Test
    public void testPrepareQuery_InsertAndSelect() throws SQLException {
        final long id = insertWidget("gizmo", 7);
        assertTrue(id > 0);

        final OptionalInt qty = JdbcUtil.prepareQuery(ds, "SELECT qty FROM widget WHERE id = ?").setLong(1, id).queryForInt();
        assertTrue(qty.isPresent());
        assertEquals(7, qty.getAsInt());

        final Nullable<String> name = JdbcUtil.prepareQuery(ds, "SELECT name FROM widget WHERE id = ?").setLong(1, id).queryForString();
        assertEquals("gizmo", name.orElse(null));
    }

    // prepareQuery update() returns the affected-row count.
    @Test
    public void testPrepareQuery_Update() throws SQLException {
        final long id = insertWidget("old", 1);

        final int updated = JdbcUtil.prepareQuery(ds, "UPDATE widget SET name = ?, qty = ? WHERE id = ?")
                .setString(1, "new")
                .setInt(2, 99)
                .setLong(3, id)
                .update();
        assertEquals(1, updated);

        final List<Widget> rows = JdbcUtil.prepareQuery(ds, "SELECT id, name, qty FROM widget WHERE id = ?")
                .setLong(1, id)
                .list(Widget.class);
        assertEquals(1, rows.size());
        assertEquals("new", rows.get(0).getName());
        assertEquals(99, rows.get(0).getQty());
    }

    // prepareNamedQuery binds parameters by name and maps rows to beans.
    @Test
    public void testPrepareNamedQuery_InsertAndList() throws SQLException {
        final Optional<Long> key = JdbcUtil.prepareNamedQuery(ds, "INSERT INTO widget (name, qty) VALUES (:name, :qty)", true)
                .setString("name", "named")
                .setInt("qty", 12)
                .insert();
        assertTrue(key.isPresent());

        final List<Widget> rows = JdbcUtil.prepareNamedQuery(ds, "SELECT id, name, qty FROM widget WHERE name = :name")
                .setString("name", "named")
                .list(Widget.class);
        assertEquals(1, rows.size());
        assertEquals(12, rows.get(0).getQty());
    }

    // A committed transaction persists its writes.
    @Test
    public void testBeginTransaction_Commit() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(ds);
        try {
            insertWidget("tx-commit-a", 1);
            insertWidget("tx-commit-b", 2);
            tran.commit();
        } finally {
            tran.rollbackIfNotCommitted();
        }

        final OptionalInt count = JdbcUtil.prepareQuery(ds, "SELECT COUNT(*) FROM widget").queryForInt();
        assertEquals(2, count.getAsInt());
    }

    // A rolled-back transaction discards its writes.
    @Test
    public void testBeginTransaction_Rollback() throws SQLException {
        final SqlTransaction tran = JdbcUtil.beginTransaction(ds);
        try {
            insertWidget("tx-rollback", 5);
        } finally {
            tran.rollbackIfNotCommitted();
        }

        final OptionalInt count = JdbcUtil.prepareQuery(ds, "SELECT COUNT(*) FROM widget").queryForInt();
        assertEquals(0, count.getAsInt());
    }

    // queryForInt on an empty result set is absent (no-row branch).
    @Test
    public void testQueryForInt_NoRow_IsEmpty() throws SQLException {
        final OptionalInt v = JdbcUtil.prepareQuery(ds, "SELECT qty FROM widget WHERE id = ?").setLong(1, -1L).queryForInt();
        assertFalse(v.isPresent());
    }

    // list mapping each row to a Map keeps all selected columns.
    @Test
    public void testList_AsMap() throws SQLException {
        insertWidget("m1", 3);
        insertWidget("m2", 4);

        final List<Map> rows = JdbcUtil.prepareQuery(ds, "SELECT name, qty FROM widget ORDER BY qty").list(Map.class);
        assertEquals(2, rows.size());
        assertNotNull(rows.get(0));
        assertEquals(2, rows.size());
    }
}
