package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.UncheckedCrudDao;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.util.EntityId;

class DaoImplUpsertRegressionTest extends TestBase {
    private JdbcDataSource ds;
    private Connection keeper;

    @BeforeEach
    void openDatabase() throws SQLException {
        ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:upsert_regression_" + UUID.randomUUID());
        keeper = ds.getConnection();
    }

    @AfterEach
    void closeDatabase() throws SQLException {
        keeper.close();
    }

    private void execute(final String sql) throws SQLException {
        try (Statement stmt = keeper.createStatement()) {
            stmt.execute(sql);
        }
    }

    @Table("nullable_row")
    public static class NullableRow {
        @Id
        private Long tenantId;
        @Id
        private Long rowId;
        private String matchKey;
        private String payload;

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

        public String getMatchKey() {
            return matchKey;
        }

        public void setMatchKey(final String matchKey) {
            this.matchKey = matchKey;
        }

        public String getPayload() {
            return payload;
        }

        public void setPayload(final String payload) {
            this.payload = payload;
        }
    }

    public interface NullableDao extends CrudDao<NullableRow, EntityId, NullableDao> {
    }

    public interface UncheckedNullableDao extends UncheckedCrudDao<NullableRow, EntityId, UncheckedNullableDao> {
    }

    private CrudDao<NullableRow, EntityId, ?> nullableDao(final boolean unchecked) {
        return unchecked ? JdbcUtil.createDao(UncheckedNullableDao.class, ds) : JdbcUtil.createDao(NullableDao.class, ds);
    }

    private static NullableRow row(final Long tenant, final Long id, final String key, final String payload) {
        final NullableRow row = new NullableRow();
        row.setTenantId(tenant);
        row.setRowId(id);
        row.setMatchKey(key);
        row.setPayload(payload);
        return row;
    }

    // A UNIQUE constraint permits duplicate nullable IDs; the caller's condition still identifies only one row.
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void singleUpsertKeepsOriginalMatchEvenWhenAMatchPropertyChanges(final boolean unchecked) throws SQLException {
        execute("CREATE TABLE nullable_row(tenant_id BIGINT, row_id BIGINT, match_key VARCHAR UNIQUE, payload VARCHAR, UNIQUE(tenant_id,row_id))");
        execute("INSERT INTO nullable_row VALUES(1,NULL,'a','old-a'),(1,NULL,'b','old-b')");
        final CrudDao<NullableRow, EntityId, ?> dao = nullableDao(unchecked);

        dao.upsert(row(1L, null, "renamed", "new-a"), Filters.eq("matchKey", "a"));

        assertEquals("new-a", dao.findOnlyOne(Filters.eq("matchKey", "renamed")).get().getPayload());
        assertEquals("old-b", dao.findOnlyOne(Filters.eq("matchKey", "b")).get().getPayload());
        assertEquals(2, dao.count(Filters.expr("1 = 1")));
    }

    // Exercise the split between nullable IDs, ordinary batch updates and inserts, across batch boundaries.
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void batchUpsertKeepsMatchPropertiesForRowsSharingAnAllNullId(final boolean unchecked) throws SQLException {
        execute("CREATE TABLE nullable_row(tenant_id BIGINT, row_id BIGINT, match_key VARCHAR UNIQUE, payload VARCHAR, UNIQUE(tenant_id,row_id))");
        execute("INSERT INTO nullable_row VALUES(NULL,NULL,'a','old-a'),(NULL,NULL,'b','old-b'),(1,2,'c','old-c')");
        final CrudDao<NullableRow, EntityId, ?> dao = nullableDao(unchecked);

        final List<NullableRow> result = dao.batchUpsert(List.of(row(null, null, "a", "new-a"), row(1L, 2L, "c", "new-c"), row(2L, 3L, "d", "new-d")),
                List.of("matchKey"), 1);

        assertEquals(List.of("a", "c", "d"), result.stream().map(NullableRow::getMatchKey).toList());
        assertEquals(List.of("new-a", "old-b", "new-c", "new-d"),
                dao.list(Filters.expr("1 = 1 ORDER BY match_key")).stream().map(NullableRow::getPayload).toList());
    }

    @Test
    void nullableUpsertsNeverAssignTheGeneratedIdComponent() throws SQLException {
        execute("CREATE TABLE nullable_row(tenant_id BIGINT, row_id BIGINT GENERATED ALWAYS AS IDENTITY, match_key VARCHAR UNIQUE, payload VARCHAR)");
        execute("INSERT INTO nullable_row(tenant_id,match_key,payload) VALUES(NULL,'a','old')");
        final NullableDao dao = JdbcUtil.createDao(NullableDao.class, ds);

        dao.upsert(row(null, null, "a", "single"), Filters.eq("matchKey", "a"));
        dao.batchUpsert(List.of(row(null, null, "a", "batch")), List.of("matchKey"));

        final NullableRow saved = dao.findOnlyOne(Filters.eq("matchKey", "a")).get();
        assertEquals(1L, saved.getRowId());
        assertNull(saved.getTenantId());
        assertEquals("batch", saved.getPayload());
    }

    @Table("identity_row")
    public static class IdentityRow {
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

    public interface IdentityDao extends CrudDao<IdentityRow, Long, IdentityDao> {
    }

    @Test
    void ordinaryUpsertNeverAssignsAGeneratedIdentity() throws SQLException {
        execute("CREATE TABLE identity_row(id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name VARCHAR)");
        execute("INSERT INTO identity_row(name) VALUES('old')");
        final IdentityDao dao = JdbcUtil.createDao(IdentityDao.class, ds);
        final IdentityRow row = new IdentityRow();
        row.setId(1L);
        row.setName("new");

        dao.upsert(row);

        assertEquals("new", dao.get(1L).get().getName());
    }

    public enum Level {
        LOW, MID, HIGH
    }

    @Table("ordinal_row")
    public static class OrdinalRow {
        @Id
        @com.landawn.abacus.annotation.Type(enumerated = com.landawn.abacus.util.EnumType.ORDINAL)
        private Level level;
        private String name;

        public Level getLevel() {
            return level;
        }

        public void setLevel(final Level level) {
            this.level = level;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }

    public interface OrdinalDao extends CrudDao<OrdinalRow, Level, OrdinalDao> {
    }

    @Test
    void upsertPreservesTheIdsDeclaredBindingType() throws SQLException {
        execute("CREATE TABLE ordinal_row(level INT PRIMARY KEY, name VARCHAR)");
        execute("INSERT INTO ordinal_row VALUES(1,'old')");
        final OrdinalDao dao = JdbcUtil.createDao(OrdinalDao.class, ds);
        final OrdinalRow row = new OrdinalRow();
        row.setLevel(Level.MID);
        row.setName("new");

        dao.upsert(row, Filters.eq("name", "old"));

        assertEquals("new", dao.get(Level.MID).get().getName());
        assertEquals(1, dao.count(Filters.expr("1 = 1")));
    }

    @Table("id_only_row")
    public static class IdOnlyRow {
        @Id
        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }
    }

    public interface IdOnlyDao extends CrudDao<IdOnlyRow, Long, IdOnlyDao> {
    }

    @Test
    void idOnlyNullableUpsertsRemainNoOps() throws SQLException {
        execute("CREATE TABLE id_only_row(id BIGINT)");
        execute("INSERT INTO id_only_row VALUES(NULL)");
        final IdOnlyDao dao = JdbcUtil.createDao(IdOnlyDao.class, ds);

        assertNull(dao.upsert(new IdOnlyRow()).getId());
        assertNull(dao.batchUpsert(List.of(new IdOnlyRow())).get(0).getId());
        assertEquals(1, dao.count(Filters.expr("1 = 1")));
    }
}
