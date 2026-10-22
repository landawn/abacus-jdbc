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

    public enum Level {
        LOW, MID, HIGH
    }

    @Table("ordinal_row")
    public static class OrdinalRow {
        @Id
        @com.landawn.abacus.annotation.Type(enumerated = com.landawn.abacus.util.EnumType.ORDINAL)
        private Level level;
        @com.landawn.abacus.annotation.Type(enumerated = com.landawn.abacus.util.EnumType.ORDINAL)
        private Level grade;
        private String name;

        public Level getLevel() {
            return level;
        }

        public void setLevel(final Level level) {
            this.level = level;
        }

        public Level getGrade() {
            return grade;
        }

        public void setGrade(final Level grade) {
            this.grade = grade;
        }

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }
    }

    interface OrdinalRowDao extends CrudDao<OrdinalRow, Level, OrdinalRowDao> {
    }

    @Table("composite_ordinal_row")
    public static class CompositeOrdinalRow {
        @Id
        private Long tenantId;
        @Id
        @com.landawn.abacus.annotation.Type(enumerated = com.landawn.abacus.util.EnumType.ORDINAL)
        private Level level;
        private String name;

        public Long getTenantId() {
            return tenantId;
        }

        public void setTenantId(final Long tenantId) {
            this.tenantId = tenantId;
        }

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

    interface CompositeOrdinalRowDao extends CrudDao<CompositeOrdinalRow, EntityId, CompositeOrdinalRowDao> {
    }

    // Composite id classes without @Id annotations: their properties are matched to the entity's id properties by name and
    // bound with the id class's own property types (see idParamSetter/id2CondFunc in DaoImpl).
    public static class CompositeOrdinalId {
        private Long tenantId;
        @com.landawn.abacus.annotation.Type(enumerated = com.landawn.abacus.util.EnumType.ORDINAL)
        private Level level;

        public CompositeOrdinalId() {
        }

        CompositeOrdinalId(final Long tenantId, final Level level) {
            this.tenantId = tenantId;
            this.level = level;
        }

        public Long getTenantId() {
            return tenantId;
        }

        public void setTenantId(final Long tenantId) {
            this.tenantId = tenantId;
        }

        public Level getLevel() {
            return level;
        }

        public void setLevel(final Level level) {
            this.level = level;
        }
    }

    interface BeanIdCompositeOrdinalRowDao extends CrudDao<CompositeOrdinalRow, CompositeOrdinalId, BeanIdCompositeOrdinalRowDao> {
    }

    public record CompositeOrdinalRecordId(Long tenantId,
            @com.landawn.abacus.annotation.Type(enumerated = com.landawn.abacus.util.EnumType.ORDINAL) Level level) {
    }

    interface RecordIdCompositeOrdinalRowDao extends CrudDao<CompositeOrdinalRow, CompositeOrdinalRecordId, RecordIdCompositeOrdinalRowDao> {
    }

    // update(Map, id) bound the id and the values with setObject (by their runtime class), so an id or a property with a custom
    // @Type, e.g. an enum stored as its ordinal, was written/compared as the enum's name: the row was missed or the statement
    // failed. Like update(entity) and getOrNull(id), they are now bound with the properties' types.
    @Test
    void updateByMap_bindsIdAndValuesWithPropertyTypes() throws SQLException {
        final JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:dao_typed_update_" + UUID.randomUUID());

        try (Connection conn = dataSource.getConnection();
             Statement statement = conn.createStatement()) {
            statement.execute("CREATE TABLE ordinal_row (level INT PRIMARY KEY, grade INT, name VARCHAR(32))");
            statement.execute("CREATE TABLE composite_ordinal_row (tenant_id BIGINT, level INT, name VARCHAR(32), PRIMARY KEY (tenant_id, level))");
            statement.executeUpdate("INSERT INTO ordinal_row VALUES (0, 0, 'low'), (1, 0, 'mid')");
            statement.executeUpdate("INSERT INTO composite_ordinal_row VALUES (1, 1, 'mid'), (1, 2, 'high')");

            final OrdinalRowDao ordinalDao = JdbcUtil.createDao(OrdinalRowDao.class, dataSource);

            assertEquals(1, ordinalDao.update(Map.of("grade", Level.HIGH, "name", "mid2"), Level.MID));
            assertEquals(Level.HIGH, ordinalDao.getOrNull(Level.MID).getGrade());
            assertEquals("mid2", ordinalDao.getOrNull(Level.MID).getName());
            assertEquals(Level.LOW, ordinalDao.getOrNull(Level.LOW).getGrade());

            assertEquals(1, ordinalDao.update(Map.of("grade", Level.MID), com.landawn.abacus.query.Filters.eq("name", "low")));
            assertEquals(Level.MID, ordinalDao.getOrNull(Level.LOW).getGrade());

            // A value that is not of the property's class is still bound by its own class.
            assertEquals(1, ordinalDao.update(Map.of("grade", 0), Level.MID));
            assertEquals(Level.LOW, ordinalDao.getOrNull(Level.MID).getGrade());

            final CompositeOrdinalRowDao compositeDao = JdbcUtil.createDao(CompositeOrdinalRowDao.class, dataSource);
            final EntityId id = EntityId.of("tenantId", 1L, "level", Level.HIGH);

            assertEquals(1, compositeDao.update(Map.of("name", "high2"), id));
            assertEquals("high2", compositeDao.getOrNull(id).getName());
            assertEquals("mid", compositeDao.getOrNull(EntityId.of("tenantId", 1L, "level", Level.MID)).getName());
        }
    }

    // The bean/record composite-id branch of update(Map, id) binds the id values with the id class's own property types, as
    // getOrNull(id) does, so a custom @Type on the id class (an enum stored as its ordinal) matches; and a null value in the map
    // is bound through the property's type (Type.set(stmt, i, null)) instead of setObject(i, null).
    @Test
    void updateByMap_bindsBeanOrRecordCompositeIdAndNullValuesWithPropertyTypes() throws SQLException {
        final JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:dao_typed_update_composite_" + UUID.randomUUID());

        try (Connection conn = dataSource.getConnection();
             Statement statement = conn.createStatement()) {
            statement.execute("CREATE TABLE ordinal_row (level INT PRIMARY KEY, grade INT, name VARCHAR(32))");
            statement.execute("CREATE TABLE composite_ordinal_row (tenant_id BIGINT, level INT, name VARCHAR(32), PRIMARY KEY (tenant_id, level))");
            statement.executeUpdate("INSERT INTO ordinal_row VALUES (0, 0, 'low'), (1, 0, 'mid')");
            statement.executeUpdate("INSERT INTO composite_ordinal_row VALUES (1, 1, 'mid'), (1, 2, 'high'), (2, 2, 'other')");

            final BeanIdCompositeOrdinalRowDao beanIdDao = JdbcUtil.createDao(BeanIdCompositeOrdinalRowDao.class, dataSource);
            final CompositeOrdinalId beanId = new CompositeOrdinalId(1L, Level.HIGH);

            assertEquals(1, beanIdDao.update(Map.of("name", "high2"), beanId));
            assertEquals("high2", beanIdDao.getOrNull(beanId).getName());
            assertEquals("mid", beanIdDao.getOrNull(new CompositeOrdinalId(1L, Level.MID)).getName());
            assertEquals("other", beanIdDao.getOrNull(new CompositeOrdinalId(2L, Level.HIGH)).getName());

            final RecordIdCompositeOrdinalRowDao recordIdDao = JdbcUtil.createDao(RecordIdCompositeOrdinalRowDao.class, dataSource);
            final CompositeOrdinalRecordId recordId = new CompositeOrdinalRecordId(1L, Level.MID);

            assertEquals(1, recordIdDao.update(Map.of("name", "mid2"), recordId));
            assertEquals("mid2", recordIdDao.getOrNull(recordId).getName());
            assertEquals("high2", recordIdDao.getOrNull(new CompositeOrdinalRecordId(1L, Level.HIGH)).getName());
            assertEquals("other", recordIdDao.getOrNull(new CompositeOrdinalRecordId(2L, Level.HIGH)).getName());

            // A null value in the map (Map.of rejects null values).
            final Map<String, Object> nullName = new java.util.HashMap<>();
            nullName.put("name", null);

            assertEquals(1, beanIdDao.update(nullName, beanId));
            assertNull(beanIdDao.getOrNull(beanId).getName());
            assertEquals("mid2", beanIdDao.getOrNull(new CompositeOrdinalId(1L, Level.MID)).getName());

            assertEquals(1, recordIdDao.update(nullName, recordId));
            assertNull(recordIdDao.getOrNull(recordId).getName());
            assertEquals("other", recordIdDao.getOrNull(new CompositeOrdinalRecordId(2L, Level.HIGH)).getName());

            // A null for a property with a custom @Type (enum as ordinal), by id and by condition.
            final OrdinalRowDao ordinalDao = JdbcUtil.createDao(OrdinalRowDao.class, dataSource);
            final Map<String, Object> nullGradeAndName = new java.util.HashMap<>();
            nullGradeAndName.put("grade", null);
            nullGradeAndName.put("name", null);

            assertEquals(1, ordinalDao.update(nullGradeAndName, Level.MID));
            assertNull(ordinalDao.getOrNull(Level.MID).getGrade());
            assertNull(ordinalDao.getOrNull(Level.MID).getName());
            assertEquals(Level.LOW, ordinalDao.getOrNull(Level.LOW).getGrade());
            assertEquals("low", ordinalDao.getOrNull(Level.LOW).getName());

            assertEquals(1, ordinalDao.update(nullGradeAndName, com.landawn.abacus.query.Filters.eq("name", "low")));
            assertNull(ordinalDao.getOrNull(Level.LOW).getGrade());
            assertNull(ordinalDao.getOrNull(Level.LOW).getName());
        }
    }

    @Test
    void query_singleBeanParameter_bindsNestedPropertyPaths() throws SQLException {
        final IdHolder holder = new IdHolder();
        holder.setParameter(new IdParameter(3L));

        assertEquals(3L, dao.byNestedBean(holder));
        assertEquals(1, dao.countByNestedBeanOrNull(holder));

        // A null intermediate binds SQL NULL: the nested comparison matches nothing, the IS NULL branch matches every row.
        holder.setParameter(null);
        assertNull(dao.byNestedBean(holder));
        assertEquals(3, dao.countByNestedBeanOrNull(holder));
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

        @Query("SELECT id FROM binding_record WHERE id = :parameter.id")
        Long byNestedBean(IdHolder holder) throws SQLException;

        @Query("SELECT COUNT(*) FROM binding_record WHERE id = :parameter.id OR CAST(:parameter.id AS BIGINT) IS NULL")
        int countByNestedBeanOrNull(IdHolder holder) throws SQLException;
    }

    public static class IdHolder {
        private IdParameter parameter;

        public IdParameter getParameter() {
            return parameter;
        }

        public void setParameter(final IdParameter parameter) {
            this.parameter = parameter;
        }
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
