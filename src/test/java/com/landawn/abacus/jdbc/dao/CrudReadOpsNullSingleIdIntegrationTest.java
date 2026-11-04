package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.jdbc.JdbcUtil;

/**
 * Pins the documented {@code null}-element contract of {@code batchGet}/{@code count(ids)} for a single-id entity, against a
 * real database: a {@code null} element is matched with {@code IS NULL} (next to the other ids, or alone, also when every
 * element is {@code null}) and never throws; the {@code IllegalArgumentException} for an all-{@code null} {@code ids} applies
 * to a composite-id entity only.
 */
public class CrudReadOpsNullSingleIdIntegrationTest extends TestBase {

    @Table("nullable_id_row")
    public static class NullableIdRow {
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

    public interface NullableIdRowDao extends CrudDao<NullableIdRow, Long, NullableIdRowDao> {
    }

    public interface UncheckedNullableIdRowDao extends UncheckedCrudDao<NullableIdRow, Long, UncheckedNullableIdRowDao> {
    }

    @Test
    public void testBatchGetAndCount_NullElementsOfASingleId_MatchIsNullAndDoNotThrow() throws SQLException {
        final JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:nullable_single_id_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        // No primary key: the id column holds NULL in one row.
        JdbcUtil.executeUpdate(ds, "CREATE TABLE nullable_id_row (id BIGINT, name VARCHAR(32))");
        JdbcUtil.executeUpdate(ds, "INSERT INTO nullable_id_row (id, name) VALUES (NULL, 'null id'), (1, 'one'), (2, 'two')");

        final NullableIdRowDao dao = JdbcUtil.createDao(NullableIdRowDao.class, ds);
        final UncheckedNullableIdRowDao uncheckedDao = JdbcUtil.createDao(UncheckedNullableIdRowDao.class, ds);

        final List<Long> nullAndOne = Arrays.asList(null, 1L);
        final List<Long> onlyNull = Arrays.asList((Long) null);
        final List<Long> allNull = Arrays.asList(null, null);

        assertEquals(List.of("null id", "one"), names(dao.batchGet(nullAndOne)));
        assertEquals(2, dao.count(nullAndOne));
        assertEquals(List.of("null id"), names(dao.batchGet(onlyNull)));
        assertEquals(1, dao.count(onlyNull));
        assertEquals(List.of("null id"), names(dao.batchGet(allNull)));
        assertEquals(1, dao.count(allNull));
        // A chunk of its own holding only the null id.
        assertEquals(List.of("null id", "one"), names(dao.batchGet(nullAndOne, null, 1)));

        assertEquals(List.of("null id", "one"), names(uncheckedDao.batchGet(nullAndOne)));
        assertEquals(2, uncheckedDao.count(nullAndOne));
        assertEquals(List.of("null id"), names(uncheckedDao.batchGet(allNull)));
        assertEquals(1, uncheckedDao.count(allNull));
    }

    private static List<String> names(final List<NullableIdRow> rows) {
        return rows.stream().map(NullableIdRow::getName).sorted().toList();
    }
}
