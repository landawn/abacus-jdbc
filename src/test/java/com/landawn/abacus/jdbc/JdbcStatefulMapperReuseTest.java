package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.IntFunctions;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;

/**
 * The stateful row mappers, consumers and extractors created by the {@link Jdbc} factories and builders cache column
 * metadata; reusing one instance with a result set of a different shape (more, fewer or reordered columns) must
 * re-initialize it instead of applying the stale layout.
 */
public class JdbcStatefulMapperReuseTest extends TestBase {

    private static final String NARROW = "SELECT CAST(1 AS BIGINT) AS \"id\"";
    private static final String WIDE = "SELECT CAST(2 AS BIGINT) AS \"id\", 'n2' AS \"name\", 20 AS \"age\"";
    private static final String REORDERED = "SELECT 30 AS \"age\", 'n3' AS \"name\", CAST(3 AS BIGINT) AS \"id\"";
    private static final String UNMATCHED = "SELECT CAST(4 AS BIGINT) AS \"id\", 'x' AS \"bogus\"";

    private Connection conn;

    @BeforeEach
    public void openConnection() throws SQLException {
        conn = DriverManager.getConnection("jdbc:h2:mem:");
    }

    @AfterEach
    public void closeConnection() throws SQLException {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    public void testRowMapperFactoriesReinitializeForEachResultSetLayout() throws SQLException {
        final Jdbc.RowMapper<Object[]> arrayMapper = Jdbc.RowMapper.toArray(Jdbc.ColumnGetter.GET_OBJECT);
        final Jdbc.RowMapper<List<Object>> listMapper = Jdbc.RowMapper.toList(Jdbc.ColumnGetter.GET_OBJECT);
        final Jdbc.RowMapper<List<Object>> disposableMapper = Jdbc.RowMapper.toDisposableObjArray().andThen(DisposableObjArray::toList);
        final Jdbc.RowMapper<ArrayList<Object>> collectionMapper = Jdbc.RowMapper.toCollection(Jdbc.ColumnGetter.GET_OBJECT, ArrayList::new);

        for (final Jdbc.RowMapper<?> mapper : List.of(arrayMapper.andThen(Arrays::asList), listMapper, disposableMapper, collectionMapper)) {
            assertEquals(List.of(1L), map(NARROW, mapper));
            assertEquals(List.of(2L, "n2", 20), map(WIDE, mapper));
            assertEquals(List.of(30, "n3", 3L), map(REORDERED, mapper));
            assertEquals(List.of(1L), map(NARROW, mapper));
        }
    }

    @Test
    public void testRowMapperTypedDisposableArrayReinitializesColumnTypes() throws SQLException {
        final Jdbc.RowMapper<List<Object>> mapper = Jdbc.RowMapper.toDisposableObjArray(Person.class).andThen(DisposableObjArray::toList);

        assertEquals(List.of(2L, "n2", 20), map(WIDE, mapper));
        // The reordered "age" column must be read as the Integer age property, not with the cached Long "id" type.
        final List<Object> reordered = map(REORDERED, mapper);
        assertEquals(List.of(30, "n3", 3L), reordered);
        assertEquals(Integer.class, reordered.get(0).getClass());
        assertEquals(List.of(1L), map(NARROW, mapper));
    }

    @SuppressWarnings("deprecation")
    @Test
    public void testBiRowMapperToRowMapperReadsLabelsOfEachResultSet() throws SQLException {
        final Jdbc.RowMapper<Map<String, Object>> mapper = Jdbc.BiRowMapper.TO_LINKED_HASH_MAP.toRowMapper();

        assertEquals(List.of("id", "name", "age"), new ArrayList<>(map(WIDE, mapper).keySet()));
        assertEquals(Map.of("id", 1L), map(NARROW, mapper));
        assertEquals(List.of("age", "name", "id"), new ArrayList<>(map(REORDERED, mapper).keySet()));
    }

    @Test
    public void testRowMapperBuilderMappersReinitializeForEachResultSetLayout() throws SQLException {
        final Jdbc.RowMapper.RowMapperBuilder builder = Jdbc.RowMapper.builder();
        final List<Jdbc.RowMapper<?>> mappers = List.of(builder.toArray().andThen(Arrays::asList), builder.toList(),
                builder.<ArrayList<Object>> toCollection(ArrayList::new), builder.to(DisposableObjArray::toList),
                builder.<List<Object>> to((labels, row) -> row.toList()));

        for (final Jdbc.RowMapper<?> mapper : mappers) {
            assertEquals(List.of(1L), map(NARROW, mapper));
            assertEquals(List.of(2L, "n2", 20), map(WIDE, mapper));
            assertEquals(List.of(30, "n3", 3L), map(REORDERED, mapper));
        }

        final Jdbc.RowMapper<Map<String, Object>> mapMapper = builder.toMap(IntFunctions.ofLinkedHashMap());
        assertEquals(Map.of("id", 1L), map(NARROW, mapMapper));
        assertEquals(List.of("age", "name", "id"), new ArrayList<>(map(REORDERED, mapMapper).keySet()));

        final Jdbc.RowMapper<Map<String, Object>> labelFinisher = builder.to((labels, row) -> {
            final Map<String, Object> m = new LinkedHashMap<>();

            for (int i = 0; i < labels.size(); i++) {
                m.put(labels.get(i), row.get(i));
            }

            return m;
        });
        assertEquals(Map.of("id", 2L, "name", "n2", "age", 20), map(WIDE, labelFinisher));
        assertEquals(Map.of("id", 1L), map(NARROW, labelFinisher));
    }

    @Test
    public void testRowMapperBuilderRevalidatesConfiguredIndexForNewResultSet() throws SQLException {
        final Jdbc.RowMapper<Object[]> mapper = Jdbc.RowMapper.builder().getInt(3).toArray();

        assertArrayEquals(new Object[] { 2L, "n2", 20 }, map(WIDE, mapper));
        assertThrows(IllegalArgumentException.class, () -> map(NARROW, mapper));
        assertArrayEquals(new Object[] { 30, "n3", 3 }, map(REORDERED, mapper));
    }

    @Test
    public void testBiRowMapperToBeanReinitializesForEachResultSetLayout() throws SQLException {
        final Jdbc.BiRowMapper<Person> mapper = Jdbc.BiRowMapper.to(Person.class);

        assertPerson(1L, null, null, mapBi(NARROW, mapper));
        assertPerson(2L, "n2", 20, mapBi(WIDE, mapper));
        assertPerson(3L, "n3", 30, mapBi(REORDERED, mapper));
        assertPerson(1L, null, null, mapBi(NARROW, mapper));

        final Jdbc.BiRowMapper<Person> prefixMapper = Jdbc.BiRowMapper.to(Person.class, Map.of("p", "person"));

        assertPerson(1L, null, null, mapBi(NARROW, prefixMapper));
        assertPerson(3L, "n3", 30, mapBi(REORDERED, prefixMapper));
        assertPerson(2L, "n2", 20, mapBi(WIDE, prefixMapper));
    }

    @Test
    public void testBiRowMapperToContainerTargetsReinitializeForEachResultSetLayout() throws SQLException {
        // Non-trivial filter/converter lambdas select the stateful array/List/Map branches.
        final Jdbc.BiRowMapper<Object[]> arrayMapper = Jdbc.BiRowMapper.to(Object[].class, label -> true, label -> label);
        final Jdbc.BiRowMapper<List<Object>> listMapper = cast(Jdbc.BiRowMapper.to(List.class, label -> true, label -> label));
        final Jdbc.BiRowMapper<Map<String, Object>> filteredMapMapper = cast(
                Jdbc.BiRowMapper.to(LinkedHashMap.class, label -> true, label -> label.toUpperCase(Locale.ROOT)));
        final Jdbc.BiRowMapper<Map<String, Object>> mapMapper = cast(Jdbc.BiRowMapper.to(LinkedHashMap.class));

        // NARROW then WIDE: a list mapper initialized for one column used to throw ArrayIndexOutOfBoundsException.
        for (final Jdbc.BiRowMapper<List<Object>> mapper : List.of(arrayMapper.andThen(Arrays::asList), listMapper)) {
            assertEquals(List.of(1L), mapBi(NARROW, mapper));
            assertEquals(List.of(2L, "n2", 20), mapBi(WIDE, mapper));
            assertEquals(List.of(30, "n3", 3L), mapBi(REORDERED, mapper));
            assertEquals(List.of(1L), mapBi(NARROW, mapper));
        }

        assertEquals(Map.of("ID", 1L), mapBi(NARROW, filteredMapMapper));
        assertEquals(List.of("AGE", "NAME", "ID"), new ArrayList<>(mapBi(REORDERED, filteredMapMapper).keySet()));
        assertEquals(Map.of("id", 1L), mapBi(NARROW, mapMapper));
        assertEquals(List.of("id", "name", "age"), new ArrayList<>(mapBi(WIDE, mapMapper).keySet()));
    }

    @Test
    public void testBiRowMapperToScalarRevalidatesColumnCountForNewResultSet() throws SQLException {
        final Jdbc.BiRowMapper<Long> mapper = Jdbc.BiRowMapper.to(Long.class);

        assertEquals(1L, mapBi(NARROW, mapper));
        // Used to silently return the first column after a one-column result set.
        assertThrows(IllegalArgumentException.class, () -> mapBi(WIDE, mapper));
        assertEquals(1L, mapBi(NARROW, mapper));
    }

    @Test
    public void testBiRowMapperToMapVariantsReinitializeForEachResultSetLayout() throws SQLException {
        final List<Jdbc.BiRowMapper<Map<String, Object>>> mappers = List.of( //
                Jdbc.BiRowMapper.toMap(label -> label.toUpperCase(Locale.ROOT)), Jdbc.BiRowMapper.toMap(Jdbc.RowExtractor.builder().build()),
                Jdbc.BiRowMapper.toMap(Jdbc.RowExtractor.forType(Person.class), label -> label.toUpperCase(Locale.ROOT), IntFunctions.ofMap()),
                Jdbc.BiRowMapper.toMap(Jdbc.RowExtractor.forType(Person.class), (key, value) -> true, IntFunctions.ofMap()));

        for (final Jdbc.BiRowMapper<Map<String, Object>> mapper : mappers) {
            final Map<String, Object> narrow = upperKeys(mapBi(NARROW, mapper));
            final Map<String, Object> wide = upperKeys(mapBi(WIDE, mapper));
            final Map<String, Object> reordered = upperKeys(mapBi(REORDERED, mapper));

            assertEquals(Map.of("ID", 1L), narrow);
            assertEquals(Map.of("ID", 2L, "NAME", "n2", "AGE", 20), wide);
            assertEquals(Map.of("ID", 3L, "NAME", "n3", "AGE", 30), reordered);
        }
    }

    @Test
    public void testBiRowMapperDisposableArraysReinitializeForEachResultSetLayout() throws SQLException {
        final Jdbc.BiRowMapper<List<Object>> untyped = Jdbc.BiRowMapper.toDisposableObjArray().andThen(DisposableObjArray::toList);
        final Jdbc.BiRowMapper<List<Object>> typed = Jdbc.BiRowMapper.toDisposableObjArray(Person.class).andThen(DisposableObjArray::toList);

        for (final Jdbc.BiRowMapper<List<Object>> mapper : List.of(untyped, typed)) {
            assertEquals(List.of(1L), mapBi(NARROW, mapper));
            assertEquals(List.of(2L, "n2", 20), mapBi(WIDE, mapper));
            final List<Object> reordered = mapBi(REORDERED, mapper);
            assertEquals(List.of(30, "n3", 3L), reordered);
            assertEquals(Integer.class, reordered.get(0).getClass());
        }
    }

    @Test
    public void testBiRowMapperBuilderMappersReinitializeForEachResultSetLayout() throws SQLException {
        final Jdbc.BiRowMapper.BiRowMapperBuilder builder = Jdbc.BiRowMapper.builder().getLong("id");
        final Jdbc.BiRowMapper<Person> beanMapper = builder.to(Person.class);

        assertPerson(1L, null, null, mapBi(NARROW, beanMapper));
        assertPerson(2L, "n2", 20, mapBi(WIDE, beanMapper));
        assertPerson(3L, "n3", 30, mapBi(REORDERED, beanMapper));

        final Jdbc.BiRowMapper<Object[]> arrayMapper = builder.to(Object[].class);
        final Jdbc.BiRowMapper<List<Object>> listMapper = cast(builder.to(List.class));

        for (final Jdbc.BiRowMapper<List<Object>> mapper : List.of(arrayMapper.andThen(Arrays::asList), listMapper)) {
            assertEquals(List.of(1L), mapBi(NARROW, mapper));
            assertEquals(List.of(2L, "n2", 20), mapBi(WIDE, mapper));
            // The configured "id" getter must follow the column to its new position.
            assertEquals(List.of(30, "n3", 3L), mapBi(REORDERED, mapper));
        }

        final Jdbc.BiRowMapper<Map<String, Object>> mapMapper = cast(builder.to(LinkedHashMap.class));
        assertEquals(Map.of("id", 1L), mapBi(NARROW, mapMapper));
        assertEquals(List.of("age", "name", "id"), new ArrayList<>(mapBi(REORDERED, mapMapper).keySet()));

        final Jdbc.BiRowMapper<Long> scalarMapper = builder.to(Long.class);
        assertEquals(1L, mapBi(NARROW, scalarMapper));
        assertThrows(IllegalArgumentException.class, () -> mapBi(WIDE, scalarMapper));
    }

    @Test
    public void testBiRowMapperBuilderRevalidatesConfiguredNameForNewResultSet() throws SQLException {
        final Jdbc.BiRowMapper.BiRowMapperBuilder builder = Jdbc.BiRowMapper.builder().getString("name");
        final Jdbc.BiRowMapper<Map<String, Object>> mapper = cast(builder.to(LinkedHashMap.class));

        assertEquals(Map.of("id", 2L, "name", "n2", "age", 20), mapBi(WIDE, mapper));
        assertThrows(IllegalArgumentException.class, () -> mapBi(NARROW, mapper));
        // The failed re-initialization left no layout behind: the next result set is mapped afresh, not with WIDE's mapping.
        assertEquals(Map.of("age", 30, "name", "n3", "id", 3L), mapBi(REORDERED, mapper));
        assertEquals(List.of("age", "name", "id"), new ArrayList<>(mapBi(REORDERED, mapper).keySet()));

        final Jdbc.BiRowMapper<Person> beanMapper = builder.to(Person.class);
        assertPerson(2L, "n2", 20, mapBi(WIDE, beanMapper));
        assertThrows(IllegalArgumentException.class, () -> mapBi(NARROW, beanMapper));
        assertPerson(3L, "n3", 30, mapBi(REORDERED, beanMapper));
        assertPerson(2L, "n2", 20, mapBi(WIDE, beanMapper));
    }

    @Test
    public void testBiRowMapperToBeanRetriesAfterUnmatchedColumn() throws SQLException {
        final Jdbc.BiRowMapper<Person> mapper = Jdbc.BiRowMapper.to(Person.class);
        final Jdbc.BiRowMapper<Person> prefixMapper = Jdbc.BiRowMapper.to(Person.class, Map.of("p", "person"));

        for (final Jdbc.BiRowMapper<Person> m : List.of(mapper, prefixMapper)) {
            assertPerson(2L, "n2", 20, mapBi(WIDE, m));
            // ignoreUnmatchedColumns=false: the column without a property fails the re-initialization ...
            assertThrows(IllegalArgumentException.class, () -> mapBi(UNMATCHED, m));
            // ... which leaves no partial mapping behind: the next result sets are mapped from their own layout.
            assertPerson(3L, "n3", 30, mapBi(REORDERED, m));
            assertPerson(1L, null, null, mapBi(NARROW, m));
            assertThrows(IllegalArgumentException.class, () -> mapBi(UNMATCHED, m));
            assertPerson(2L, "n2", 20, mapBi(WIDE, m));
        }

        assertPerson(4L, null, null, mapBi(UNMATCHED, Jdbc.BiRowMapper.to(Person.class, true)));
    }

    @Test
    public void testBiRowMapperToMapRetriesFailedNameConversionWithoutCachingPartialKeys() throws SQLException {
        final int[] conversions = { 0 };
        final boolean[] failed = { false };
        final java.util.function.Function<String, String> converter = label -> {
            conversions[0]++;

            if ("name".equals(label) && !failed[0]) {
                failed[0] = true;
                throw new IllegalStateException("conversion failed");
            }

            return label.toUpperCase(Locale.ROOT);
        };

        for (final Jdbc.BiRowMapper<Map<String, Object>> mapper : List.of(Jdbc.BiRowMapper.toMap(converter),
                Jdbc.BiRowMapper.toMap(converter, IntFunctions.ofLinkedHashMap()))) {
            conversions[0] = 0;
            failed[0] = false;

            assertThrows(IllegalStateException.class, () -> mapBi(WIDE, mapper));
            assertEquals(2, conversions[0]); // "id" converted, "name" failed
            assertEquals(Map.of("ID", 2L, "NAME", "n2", "AGE", 20), mapBi(WIDE, mapper));
            // All three labels were converted again: the "ID" key converted before the failure was not cached.
            assertEquals(5, conversions[0]);
            assertEquals(Map.of("ID", 1L), mapBi(NARROW, mapper));
            assertEquals(6, conversions[0]);
        }
    }

    // Documented limitation (Jdbc class doc, ResultSetLayout): result sets are told apart by identity, so a driver that
    // returns one ResultSet object from every executeQuery of a Statement (sqlite-jdbc) presents a re-executed statement
    // as the same result set and the stale layout is applied; a new mapper per query sees the new layout.
    @Test
    public void testStatefulMappersTreatOneReusedResultSetObjectAsTheSameResultSet() throws SQLException {
        final ReexecutableResultSet reused = new ReexecutableResultSet();
        reused.execute(List.of("id"), 1L);

        final Jdbc.RowMapper<Object[]> arrayMapper = Jdbc.RowMapper.toArray(Jdbc.ColumnGetter.GET_OBJECT);
        final Jdbc.BiRowMapper<Map<String, Object>> mapMapper = Jdbc.BiRowMapper.toMap(label -> label.toUpperCase(Locale.ROOT));

        assertArrayEquals(new Object[] { 1L }, arrayMapper.apply(reused.rs));
        assertEquals(Map.of("ID", 1L), mapMapper.apply(reused.rs, JdbcUtil.getColumnLabels(reused.rs)));

        reused.execute(List.of("name", "id"), "n2", 2L); // the same object with a different layout

        // Not re-initialized: the second column is dropped and the first keeps its stale key.
        assertArrayEquals(new Object[] { "n2" }, arrayMapper.apply(reused.rs));
        assertEquals(Map.of("ID", "n2"), mapMapper.apply(reused.rs, JdbcUtil.getColumnLabels(reused.rs)));

        // A new instance per query sees the new layout.
        assertArrayEquals(new Object[] { "n2", 2L }, Jdbc.RowMapper.toArray(Jdbc.ColumnGetter.GET_OBJECT).apply(reused.rs));
        assertEquals(Map.of("NAME", "n2", "ID", 2L),
                Jdbc.BiRowMapper.toMap(label -> label.toUpperCase(Locale.ROOT)).apply(reused.rs, JdbcUtil.getColumnLabels(reused.rs)));

        // A different ResultSet object with the first layout is re-initialized (compared once by labels or count).
        final ReexecutableResultSet fresh = new ReexecutableResultSet();
        fresh.execute(List.of("id", "name"), 3L, "n3");
        assertArrayEquals(new Object[] { 3L, "n3" }, arrayMapper.apply(fresh.rs));
        assertEquals(Map.of("ID", 3L, "NAME", "n3"), mapMapper.apply(fresh.rs, JdbcUtil.getColumnLabels(fresh.rs)));
    }

    @Test
    public void testRowConsumersReinitializeForEachResultSetLayout() throws SQLException {
        final List<List<Object>> rows = new ArrayList<>();
        final List<Object> columnRow = new ArrayList<>();
        final Jdbc.RowConsumer forEachColumn = Jdbc.RowConsumer.forEachColumn((rs, columnIndex) -> columnRow.add(rs.getObject(columnIndex)));
        final List<Jdbc.RowConsumer> consumers = List.of(Jdbc.RowConsumer.forDisposableObjArray(row -> rows.add(row.toList())),
                Jdbc.RowConsumer.forDisposableObjArray(Person.class, row -> rows.add(row.toList())), rs -> {
                    columnRow.clear();
                    forEachColumn.accept(rs);
                    rows.add(new ArrayList<>(columnRow));
                });

        for (final Jdbc.RowConsumer consumer : consumers) {
            rows.clear();
            consume(WIDE, consumer);
            consume(NARROW, consumer);
            consume(REORDERED, consumer);

            assertEquals(List.of(List.of(2L, "n2", 20), List.of(1L), List.of(30, "n3", 3L)), rows);
        }

        final List<Object> labelsAndRows = new ArrayList<>();
        final List<Jdbc.BiRowConsumer> biConsumers = List.of(Jdbc.BiRowConsumer.forDisposableObjArray((labels, row) -> labelsAndRows.add(row.toList())),
                Jdbc.BiRowConsumer.forDisposableObjArray(Person.class, (labels, row) -> labelsAndRows.add(row.toList())));

        for (final Jdbc.BiRowConsumer consumer : biConsumers) {
            labelsAndRows.clear();
            consumeBi(NARROW, consumer);
            consumeBi(WIDE, consumer);
            consumeBi(REORDERED, consumer);

            assertEquals(List.of(List.of(1L), List.of(2L, "n2", 20), List.of(30, "n3", 3L)), labelsAndRows);
        }
    }

    @Test
    public void testRowExtractorsReinitializeForEachResultSetLayout() throws SQLException {
        for (final Jdbc.RowExtractor extractor : List.of(Jdbc.RowExtractor.forType(Person.class), Jdbc.RowExtractor.builder().build())) {
            assertEquals(List.of(1L), extract(NARROW, extractor, 1));
            assertEquals(List.of(2L, "n2", 20), extract(WIDE, extractor, 3));
            assertEquals(List.of(30, "n3", 3L), extract(REORDERED, extractor, 3));
            assertEquals(List.of(1L), extract(NARROW, extractor, 1));
        }

        // A reused extractor behind ResultExtractor.toDataset(RowExtractor) serves different query structures.
        final Jdbc.ResultExtractor<Dataset> toDataset = Jdbc.ResultExtractor.toDataset(Jdbc.RowExtractor.forType(Person.class));

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(NARROW)) {
            assertEquals(List.of("id"), toDataset.apply(rs).columnNames());
        }

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(REORDERED)) {
            final Dataset dataset = toDataset.apply(rs);
            assertEquals(List.of("age", "name", "id"), dataset.columnNames());
            assertEquals(Integer.valueOf(30), dataset.get(0, 0));
            assertEquals(Long.valueOf(3L), dataset.get(0, 2));
        }
    }

    @Test
    public void testRowExtractorWithConfiguredLabelsKeepsItsMapping() throws SQLException {
        // Configured labels decide the mapping, independent of the result set's own metadata.
        final Jdbc.RowExtractor extractor = Jdbc.RowExtractor.forType(Person.class, List.of("id", "name", "age"));

        assertEquals(List.of(2L, "n2", 20), extract(WIDE, extractor, 3));
        // Column 1 ("age") is read with the configured "id" type and column 3 ("id") with the "age" type.
        assertEquals(List.of(30L, "n3", 3), extract(REORDERED, extractor, 3));
    }

    @Test
    public void testRowExtractorWithNullOrEmptyLabelsDiscoversTheLabelsOfEachResultSet() throws SQLException {
        // No labels given: they are read from each result set, as by forType(Class), not fixed by the first one.
        for (final List<String> noLabels : java.util.Arrays.asList((List<String>) null, List.<String> of())) {
            final Jdbc.RowExtractor extractor = Jdbc.RowExtractor.forType(Person.class, noLabels);

            assertEquals(List.of(1L), extract(NARROW, extractor, 1));
            assertEquals(List.of(30, "n3", 3L), extract(REORDERED, extractor, 3));
            assertEquals(List.of(2L, "n2", 20), extract(WIDE, extractor, 3));
        }
    }

    @Test
    public void testSameResultSetReadsMetadataOnce() throws SQLException {
        final int[] metadataCalls = { 0 };

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT X AS \"id\" FROM SYSTEM_RANGE(1, 5)")) {
            final ResultSet counting = (ResultSet) java.lang.reflect.Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                    new Class<?>[] { ResultSet.class }, (proxy, method, args) -> {
                        if ("getMetaData".equals(method.getName())) {
                            metadataCalls[0]++;
                        }

                        try {
                            return method.invoke(rs, args);
                        } catch (final java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });

            final Jdbc.RowMapper<Map<String, Object>> mapper = Jdbc.RowMapper.builder().toMap();
            int rows = 0;

            while (counting.next()) {
                assertEquals(1, mapper.apply(counting).size());
                rows++;
            }

            assertEquals(5, rows);
            assertEquals(1, metadataCalls[0]);
        }
    }

    // ---- record conversion (abacus-common 8.2.1+ converts record constructor arguments like setter arguments)

    @Test
    public void testBiRowMapperBuilderConvertsValuesForRecordComponents() throws SQLException {
        // getInt -> Long component and Integer -> long component; getLong -> int component.
        final Jdbc.BiRowMapper<PersonRecord> mapper = Jdbc.BiRowMapper.builder().getInt("id").getLong("age").to(PersonRecord.class);

        final PersonRecord wide = mapBi(WIDE, mapper);
        assertEquals(new PersonRecord(2L, "n2", 20), wide);
        assertEquals(new PersonRecord(3L, "n3", 30), mapBi(REORDERED, mapper));

        final Jdbc.BiRowMapper<PrimitiveRecord> primitiveMapper = Jdbc.BiRowMapper.builder().getInt("id").getString("age").to(PrimitiveRecord.class);
        assertEquals(new PrimitiveRecord(2L, "n2", 20L), mapBi(WIDE, primitiveMapper));
    }

    @Test
    public void testBiRowMapperBuilderRecordKeepsNullAndMatchingValues() throws SQLException {
        final Jdbc.BiRowMapper<PersonRecord> mapper = Jdbc.BiRowMapper.builder().to(PersonRecord.class, true);

        assertEquals(new PersonRecord(1L, null, 0), mapBi(NARROW, mapper));
        assertEquals(new PersonRecord(2L, "n2", 20), mapBi(WIDE, mapper));

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT CAST(NULL AS BIGINT) AS \"id\", CAST(NULL AS VARCHAR) AS \"name\", 5 AS \"age\"")) {
            rs.next();
            final Jdbc.BiRowMapper<PersonRecord> nullMapper = Jdbc.BiRowMapper.builder().getObject("id", Long.class).to(PersonRecord.class);
            final PersonRecord result = nullMapper.apply(rs, JdbcUtil.getColumnLabels(rs));
            assertNull(result.id());
            assertNull(result.name());
            assertEquals(5, result.age());
        }
    }

    @Test
    public void testBiRowMapperBuilderMutableBeanStillConverts() throws SQLException {
        final Jdbc.BiRowMapper<Person> mapper = Jdbc.BiRowMapper.builder().getInt("id").getLong("age").to(Person.class);

        assertPerson(2L, "n2", 20, mapBi(WIDE, mapper));
    }

    // ---- helpers

    /**
     * One {@code ResultSet} object whose layout and row change with every "execution", as sqlite-jdbc's single
     * {@code JDBC4ResultSet} per statement does; only the methods the mappers under test use are implemented.
     */
    private static final class ReexecutableResultSet {
        private List<String> labels = List.of();
        private Object[] row = {};

        private final java.sql.ResultSetMetaData metaData = (java.sql.ResultSetMetaData) java.lang.reflect.Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(), new Class<?>[] { java.sql.ResultSetMetaData.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> labels.size();
                    case "getColumnLabel", "getColumnName" -> labels.get((Integer) args[0] - 1);
                    case "toString" -> "ReexecutableResultSetMetaData";
                    default -> unsupported(method);
                });

        final ResultSet rs = (ResultSet) java.lang.reflect.Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> metaData;
                    case "getObject" -> args.length == 1 && args[0] instanceof Integer index ? row[index - 1] : unsupported(method);
                    case "getInt" -> args[0] instanceof Integer index ? ((Number) row[index - 1]).intValue() : unsupported(method);
                    case "wasNull" -> false;
                    case "toString" -> "ReexecutableResultSet";
                    default -> unsupported(method);
                });

        void execute(final List<String> labels, final Object... row) {
            this.labels = labels;
            this.row = row;
        }

        private static Object unsupported(final java.lang.reflect.Method method) {
            throw new UnsupportedOperationException(method.getName());
        }
    }

    private <T> T map(final String sql, final Jdbc.RowMapper<? extends T> mapper) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return mapper.apply(rs);
        }
    }

    private <T> T mapBi(final String sql, final Jdbc.BiRowMapper<? extends T> mapper) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return mapper.apply(rs, JdbcUtil.getColumnLabels(rs));
        }
    }

    private void consume(final String sql, final Jdbc.RowConsumer consumer) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            consumer.accept(rs);
        }
    }

    private void consumeBi(final String sql, final Jdbc.BiRowConsumer consumer) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            consumer.accept(rs, JdbcUtil.getColumnLabels(rs));
        }
    }

    private List<Object> extract(final String sql, final Jdbc.RowExtractor extractor, final int expectedColumnCount) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            final Object[] output = new Object[3];
            extractor.accept(rs, output);
            return Arrays.asList(output).subList(0, expectedColumnCount);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(final Object obj) {
        return (T) obj;
    }

    private static Map<String, Object> upperKeys(final Map<String, Object> map) {
        final Map<String, Object> result = N.newHashMap(map.size());
        map.forEach((k, v) -> result.put(k.toUpperCase(Locale.ROOT), v));
        return result;
    }

    private static void assertPerson(final Long id, final String name, final Integer age, final Person person) {
        assertEquals(id, person.getId());
        assertEquals(name, person.getName());
        assertEquals(age, person.getAge());
    }

    public static class Person {
        private Long id;
        private String name;
        private Integer age;

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

        public Integer getAge() {
            return age;
        }

        public void setAge(final Integer age) {
            this.age = age;
        }
    }

    public record PersonRecord(Long id, String name, int age) {
    }

    public record PrimitiveRecord(long id, String name, long age) {
    }
}
