package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.jdbc.annotation.BindList;
import com.landawn.abacus.jdbc.annotation.Query;
import com.landawn.abacus.jdbc.annotation.SqlFragment;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.Seid;
import com.landawn.abacus.util.Throwables;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DaoImplParameterBindingIntegrationTest extends TestBase {

    private Connection keeper;
    private BindingDao dao;

    @BeforeAll
    void createDatabase() throws SQLException {
        final JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:dao_binding_" + UUID.randomUUID());
        keeper = dataSource.getConnection();
        try (Statement statement = keeper.createStatement()) {
            statement.execute("CREATE TABLE binding_record (id BIGINT PRIMARY KEY)");
            statement.executeUpdate("INSERT INTO binding_record VALUES (1), (2), (3)");
        }
        dao = JdbcUtil.createDao(BindingDao.class, dataSource);
    }

    @AfterAll
    void closeDatabase() throws SQLException {
        if (keeper != null) {
            keeper.close();
        }
    }

    @ParameterizedTest(name = "{0}, state={1}, withFragmentAndScalar={2}")
    @MethodSource("bindListCases")
    void query_bindListVariants_preservePlaceholderOrder(final Binding binding, final Input input, final boolean multipleParameters) throws SQLException {
        final Object values = switch (input) {
            case VALUES -> binding.values();
            case EMPTY -> binding.empty();
            case NULL -> null;
        };
        final int expected = input == Input.VALUES ? (multipleParameters ? 1 : 2) : 0;

        assertEquals(expected, (multipleParameters ? binding.multiple() : binding.single()).apply(dao, values));
    }

    @Test
    void query_namedAggregateParameters_bindOnlyReferencedProperties() throws SQLException {
        final Record record = new Record();
        record.setId(3L);

        assertEquals(3L, dao.byMap(Map.of("id", 3L, "unused", "ignored")));
        assertEquals(3L, dao.byEntityId(Seid.of("binding_record").set("id", 3L)));
        assertEquals(3L, dao.byBean(record));
        assertEquals(3L, dao.byRecord(new IdParameter(3L)));

        record.setId(4L);
        assertNull(dao.byMap(Map.of("id", 4L)));
        assertNull(dao.byEntityId(Seid.of("binding_record").set("id", 4L)));
        assertNull(dao.byBean(record));
        assertNull(dao.byRecord(new IdParameter(4L)));
    }

    @SuppressWarnings("unchecked")
    private static Stream<Arguments> bindListCases() {
        final List<Binding> bindings = List.of(
                new Binding("ints", new int[] { 1, 3 }, new int[0], (dao, values) -> dao.ints((int[]) values),
                        (dao, values) -> dao.intsWithFragment("id", (int[]) values, 1)),
                new Binding("longs", new long[] { 1L, 3L }, new long[0], (dao, values) -> dao.longs((long[]) values),
                        (dao, values) -> dao.longsWithFragment("id", (long[]) values, 1)),
                new Binding("doubles", new double[] { 1.0, 3.0 }, new double[0], (dao, values) -> dao.doubles((double[]) values),
                        (dao, values) -> dao.doublesWithFragment("id", (double[]) values, 1)),
                new Binding("strings", new String[] { "1", "3" }, new String[0], (dao, values) -> dao.strings((String[]) values),
                        (dao, values) -> dao.stringsWithFragment("id", (String[]) values, 1)),
                new Binding("objects", new Object[] { 1, 3L }, new Object[0], (dao, values) -> dao.objects((Object[]) values),
                        (dao, values) -> dao.objectsWithFragment("id", (Object[]) values, 1)),
                new Binding("shorts", new short[] { 1, 3 }, new short[0], (dao, values) -> dao.shorts((short[]) values),
                        (dao, values) -> dao.shortsWithFragment("id", (short[]) values, 1)),
                new Binding("collection", List.of(1, 3), List.of(), (dao, values) -> dao.collection((List<Integer>) values),
                        (dao, values) -> dao.collectionWithFragment("id", (List<Integer>) values, 1)));
        return bindings.stream()
                .flatMap(binding -> Stream.of(Input.values()).flatMap(input -> Stream.of(false, true).map(multiple -> Arguments.of(binding, input, multiple))));
    }

    private enum Input {
        VALUES, EMPTY, NULL
    }

    private record Binding(String name, Object values, Object empty, Throwables.BiFunction<BindingDao, Object, Integer, SQLException> single,
            Throwables.BiFunction<BindingDao, Object, Integer, SQLException> multiple) {
        @Override
        public String toString() {
            return name;
        }
    }

    public record IdParameter(Long id) {
    }

    interface BindingDao extends CrudDao<Record, Long, BindingDao> {
        @Query("SELECT COUNT(*) FROM binding_record WHERE id IN ({values})")
        int ints(@BindList("values") int[] values) throws SQLException;

        @Query("SELECT COUNT(*) FROM binding_record WHERE {column} IN ({values}) AND id > ?")
        int intsWithFragment(@SqlFragment("column") String column, @BindList("values") int[] values, long minimum) throws SQLException;

        @Query("SELECT COUNT(*) FROM binding_record WHERE id IN ({values})")
        int longs(@BindList("values") long[] values) throws SQLException;

        @Query("SELECT COUNT(*) FROM binding_record WHERE {column} IN ({values}) AND id > ?")
        int longsWithFragment(@SqlFragment("column") String column, @BindList("values") long[] values, long minimum) throws SQLException;

        @Query("SELECT COUNT(*) FROM binding_record WHERE id IN ({values})")
        int doubles(@BindList("values") double[] values) throws SQLException;

        @Query("SELECT COUNT(*) FROM binding_record WHERE {column} IN ({values}) AND id > ?")
        int doublesWithFragment(@SqlFragment("column") String column, @BindList("values") double[] values, long minimum) throws SQLException;

        @Query("SELECT COUNT(*) FROM binding_record WHERE id IN ({values})")
        int strings(@BindList("values") String[] values) throws SQLException;

        @Query("SELECT COUNT(*) FROM binding_record WHERE {column} IN ({values}) AND id > ?")
        int stringsWithFragment(@SqlFragment("column") String column, @BindList("values") String[] values, long minimum) throws SQLException;

        @Query("SELECT COUNT(*) FROM binding_record WHERE id IN ({values})")
        int objects(@BindList("values") Object[] values) throws SQLException;

        @Query("SELECT COUNT(*) FROM binding_record WHERE {column} IN ({values}) AND id > ?")
        int objectsWithFragment(@SqlFragment("column") String column, @BindList("values") Object[] values, long minimum) throws SQLException;

        @Query("SELECT COUNT(*) FROM binding_record WHERE id IN ({values})")
        int shorts(@BindList("values") short[] values) throws SQLException;

        @Query("SELECT COUNT(*) FROM binding_record WHERE {column} IN ({values}) AND id > ?")
        int shortsWithFragment(@SqlFragment("column") String column, @BindList("values") short[] values, long minimum) throws SQLException;

        @Query("SELECT COUNT(*) FROM binding_record WHERE id IN ({values})")
        int collection(@BindList("values") List<Integer> values) throws SQLException;

        @Query("SELECT COUNT(*) FROM binding_record WHERE {column} IN ({values}) AND id > ?")
        int collectionWithFragment(@SqlFragment("column") String column, @BindList("values") List<Integer> values, long minimum) throws SQLException;

        @Query("SELECT id FROM binding_record WHERE id = :id")
        Long byMap(Map<String, Object> parameters) throws SQLException;

        @Query("SELECT id FROM binding_record WHERE id = :id")
        Long byEntityId(EntityId parameters) throws SQLException;

        @Query("SELECT id FROM binding_record WHERE id = :id")
        Long byBean(Record parameters) throws SQLException;

        @Query("SELECT id FROM binding_record WHERE id = :id")
        Long byRecord(IdParameter parameters) throws SQLException;
    }

    @Table("binding_record")
    public static class Record {
        @Id
        private Long id;

        public Long getId() {
            return id;
        }

        public void setId(final Long id) {
            this.id = id;
        }
    }
}
