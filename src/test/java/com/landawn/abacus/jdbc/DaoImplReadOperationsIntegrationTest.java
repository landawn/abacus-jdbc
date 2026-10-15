package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.exception.DuplicateResultException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.annotation.DaoConfig;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.UncheckedCrudDao;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.RowDataset;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.u.Optional;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class DaoImplReadOperationsIntegrationTest extends TestBase {

    private static final List<String> COLUMNS = List.of("id", "name", "score");
    private static final List<String> ALL_NAMES = List.of("alpha", "beta", "gamma");
    private static final List<String> FILTERED_NAMES = List.of("beta", "gamma");
    private Connection keeper;
    private RecordDao mappedDao;
    private RawRecordDao rawDao;
    private UncheckedRecordDao uncheckedDao;

    @BeforeAll
    void createDatabase() throws SQLException {
        final JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:dao_read_operations_" + System.nanoTime());
        keeper = dataSource.getConnection();
        JdbcUtil.executeUpdate(keeper, "CREATE TABLE coverage_record(id BIGINT PRIMARY KEY, name VARCHAR(40), score INT)");
        JdbcUtil.executeUpdate(keeper, "INSERT INTO coverage_record VALUES(1, 'alpha', 10), (2, 'beta', 20), (3, 'gamma', 30)");
        mappedDao = JdbcUtil.createDao(RecordDao.class, dataSource);
        rawDao = JdbcUtil.createDao(RawRecordDao.class, dataSource);
        uncheckedDao = JdbcUtil.createDao(UncheckedRecordDao.class, dataSource);
    }

    @AfterAll
    void closeDatabase() throws SQLException {
        if (keeper != null) {
            keeper.close();
        }
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("collectionReads")
    void read_collectionOverload_mapsSelectedRows(final DaoStyle style, final String operationName, final ReadOperation operation) throws SQLException {
        final CrudDao<Record, Long, ?> dao = dao(style);
        final List<String> actual = operation.action().apply(dao, Filters.gt("id", 0L));
        assertEquals(operation.filtered() ? FILTERED_NAMES : ALL_NAMES, actual.stream().sorted().toList());
        assertEquals(List.of(), operation.action().apply(dao, Filters.eq("id", -1L)));
    }

    @ParameterizedTest(name = "named={0}, large={1}, selected={2}")
    @CsvSource({ "false, false, false", "true, false, false", "false, true, false", "true, true, false", "false, true, true", "true, true, true" })
    void uncheckedConditionPreparationBindsValuesAndConfiguresLargeResults(final boolean named, final boolean large, final boolean selected)
            throws SQLException {
        final Condition condition = Filters.eq("id", 2L);
        try (AbstractQuery<?, ?> query = named
                ? (large ? (selected ? uncheckedDao.prepareNamedQueryForLargeResult(COLUMNS, condition)
                        : uncheckedDao.prepareNamedQueryForLargeResult(condition)) : uncheckedDao.prepareNamedQuery(condition))
                : (large ? (selected ? uncheckedDao.prepareQueryForLargeResult(COLUMNS, condition) : uncheckedDao.prepareQueryForLargeResult(condition))
                        : uncheckedDao.prepareQuery(condition))) {
            if (large) {
                query.configureStatement(statement -> assertEquals(JdbcUtil.DEFAULT_FETCH_SIZE_FOR_LARGE_RESULT_SET, statement.getFetchSize()));
            }
            final List<Record> records = query.list(Record.class);
            assertEquals(1, records.size());
            assertEquals(2L, records.get(0).getId());
            assertEquals("beta", records.get(0).getName());
            assertEquals(20, records.get(0).getScore());
        }
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("singleReads")
    void read_singleOverload_distinguishesEmptySingleAndDuplicateRows(final DaoStyle style, final String operationName, final SingleOperation operation)
            throws SQLException {
        final CrudDao<Record, Long, ?> dao = dao(style);
        assertEquals(Optional.of("alpha"), operation.action().apply(dao, Filters.eq("id", 1L)));
        assertEquals(Optional.empty(), operation.action().apply(dao, Filters.eq("id", -1L)));
        if (operation.unique()) {
            assertThrows(DuplicateResultException.class, () -> operation.action().apply(dao, Filters.gt("id", 0L)));
        } else {
            assertTrue(ALL_NAMES.contains(operation.action().apply(dao, Filters.gt("id", 0L)).get()));
        }
    }

    @ParameterizedTest
    @EnumSource(DaoStyle.class)
    void read_identifierAndConditionOverloads_preserveScalarAndEntityResults(final DaoStyle style) throws SQLException {
        final CrudDao<Record, Long, ?> dao = dao(style);
        assertEquals(10, dao.queryForSingleNonNull("score", 1L, rs -> rs.getInt(1)).get());
        assertEquals(10, dao.queryForUniqueNonNull("score", 1L, rs -> rs.getInt(1)).get());
        assertEquals(Optional.empty(), dao.queryForSingleNonNull("score", -1L, rs -> rs.getInt(1)));
        assertEquals(Optional.empty(), dao.queryForUniqueNonNull("score", -1L, rs -> rs.getInt(1)));
        assertEquals("alpha", dao.get(1L, List.of("name")).get().getName());
        assertEquals(Optional.empty(), dao.get(-1L, List.of("name")));
        assertEquals(2, dao.count(List.of(1L, 1L, 2L, -1L)));
        assertEquals(2, dao.batchGet(List.of(1L, 2L, -1L), COLUMNS, 1).size());
        assertTrue(dao.notExists(-1L));
        assertFalse(dao.notExists(1L));
        assertTrue(dao.notExists(Filters.eq("id", -1L)));
        assertFalse(dao.notExists(Filters.eq("id", 1L)));
        assertEquals(3, dao.count(Filters.gt("id", 0L)));
    }

    @ParameterizedTest(name = "{0}, {1}, {2}")
    @MethodSource("paginationModes")
    void paginate_resultShape_advancesCursorAndStopsAtEmptyPage(final DaoStyle style, final PaginationForm form, final PageShape shape) {
        final long[] cursor = { 0L };
        final int[] requests = { 0 };
        final Object[] previousResult = { null };
        final Jdbc.BiParametersSetter<PreparedQuery, Object> setter = (query, previous) -> {
            assertSame(previousResult[0], previous);
            requests[0]++;
            query.setLong(1, cursor[0]);
        };
        final Jdbc.ResultExtractor<Object> extractor = rows -> {
            final List<Long> ids = readIds(rows);
            if (!ids.isEmpty()) {
                cursor[0] = ids.get(ids.size() - 1);
            }
            final Object page = shape.wrap(ids);
            previousResult[0] = page;
            return page;
        };
        final List<Long> visited = new ArrayList<>();
        try (var pages = paginate(dao(style), form, setter, extractor)) {
            pages.limit(10).forEach(page -> {
                final List<Long> ids = pageIds(page);
                visited.addAll(ids);
                if (page instanceof Dataset) {
                    cursor[0] = ids.get(ids.size() - 1);
                    previousResult[0] = page;
                }
            });
        }

        assertEquals(shape == PageShape.SCALAR ? List.of(2L, 3L) : List.of(1L, 2L, 3L), visited);
        assertEquals(3, requests[0], "Two nonempty pages and one terminal empty page");
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("paginationForms")
    void paginate_bindingFailure_preservesSqlCause(final DaoStyle style, final PaginationForm form) {
        final SQLException failure = new SQLException("cursor binding failed");
        try (var pages = paginate(dao(style), form, (query, previous) -> {
            throw failure;
        }, DaoImplReadOperationsIntegrationTest::readIds)) {
            assertSame(failure, assertThrows(UncheckedSQLException.class, pages::toList).getCause());
        }
    }

    private CrudDao<Record, Long, ?> dao(final DaoStyle style) {
        return switch (style) {
            case MAPPED -> mappedDao;
            case DRIVER_TYPES -> rawDao;
            case UNCHECKED -> uncheckedDao;
        };
    }

    private static Stream<Arguments> collectionReads() {
        return collectionOperations().flatMap(operation -> Stream.of(DaoStyle.values()).map(style -> Arguments.of(style, operation.name(), operation)));
    }

    private static Stream<ReadOperation> collectionOperations() {
        return Stream.of(read("list(rowMapper)", false, (d, c) -> d.list(c, rs -> rs.getString("name"))),
                read("list(biRowMapper)", false, (d, c) -> d.list(c, (rs, labels) -> rs.getString("name"))),
                read("list(rowFilter,rowMapper)", true, (d, c) -> d.list(c, rs -> rs.getInt("score") >= 20, rs -> rs.getString("name"))),
                read("list(biRowFilter,biRowMapper)", true,
                        (d, c) -> d.list(c, (rs, labels) -> rs.getInt("score") >= 20, (rs, labels) -> rs.getString("name"))),
                read("list(columns,rowMapper)", false, (d, c) -> d.list(COLUMNS, c, rs -> rs.getString("name"))),
                read("list(columns,biRowMapper)", false, (d, c) -> d.list(COLUMNS, c, (rs, labels) -> rs.getString("name"))),
                read("list(columns,rowFilter,rowMapper)", true, (d, c) -> d.list(COLUMNS, c, rs -> rs.getInt("score") >= 20, rs -> rs.getString("name"))),
                read("list(columns,biRowFilter,biRowMapper)", true,
                        (d, c) -> d.list(COLUMNS, c, (rs, labels) -> rs.getInt("score") >= 20, (rs, labels) -> rs.getString("name"))),
                read("stream(rowMapper)", false, (d, c) -> collect(d.stream(c, rs -> rs.getString("name")))),
                read("stream(biRowMapper)", false, (d, c) -> collect(d.stream(c, (rs, labels) -> rs.getString("name")))),
                read("stream(rowFilter,rowMapper)", true, (d, c) -> collect(d.stream(c, rs -> rs.getInt("score") >= 20, rs -> rs.getString("name")))),
                read("stream(biRowFilter,biRowMapper)", true,
                        (d, c) -> collect(d.stream(c, (rs, labels) -> rs.getInt("score") >= 20, (rs, labels) -> rs.getString("name")))),
                read("stream(columns,rowMapper)", false, (d, c) -> collect(d.stream(COLUMNS, c, rs -> rs.getString("name")))),
                read("stream(columns,biRowMapper)", false, (d, c) -> collect(d.stream(COLUMNS, c, (rs, labels) -> rs.getString("name")))),
                read("stream(columns,rowFilter,rowMapper)", true,
                        (d, c) -> collect(d.stream(COLUMNS, c, rs -> rs.getInt("score") >= 20, rs -> rs.getString("name")))),
                read("stream(columns,biRowFilter,biRowMapper)", true,
                        (d, c) -> collect(d.stream(COLUMNS, c, (rs, labels) -> rs.getInt("score") >= 20, (rs, labels) -> rs.getString("name")))),
                read("query(resultExtractor)", false, (d, c) -> d.query(c, DaoImplReadOperationsIntegrationTest::readNames)),
                read("query(biResultExtractor)", false, (d, c) -> d.query(c, (rs, labels) -> readNames(rs))),
                read("query(columns,resultExtractor)", false, (d, c) -> d.query(COLUMNS, c, DaoImplReadOperationsIntegrationTest::readNames)),
                read("query(columns,biResultExtractor)", false, (d, c) -> d.query(COLUMNS, c, (rs, labels) -> readNames(rs))),
                read("query(dataset)", false, (d, c) -> d.query(c).getColumn(1)),
                read("query(columns,dataset)", false, (d, c) -> d.query(List.of("name"), c).getColumn(0)), read("forEach(rowConsumer)", false, (d, c) -> {
                    final List<String> names = new ArrayList<>();
                    d.forEach(c, rs -> names.add(rs.getString("name")));
                    return names;
                }), read("forEach(biRowConsumer)", false, (d, c) -> {
                    final List<String> names = new ArrayList<>();
                    d.forEach(c, (rs, labels) -> names.add(rs.getString("name")));
                    return names;
                }), read("forEach(rowFilter,rowConsumer)", true, (d, c) -> {
                    final List<String> names = new ArrayList<>();
                    d.forEach(c, rs -> rs.getInt("score") >= 20, rs -> names.add(rs.getString("name")));
                    return names;
                }), read("forEach(biRowFilter,biRowConsumer)", true, (d, c) -> {
                    final List<String> names = new ArrayList<>();
                    d.forEach(c, (rs, labels) -> rs.getInt("score") >= 20, (rs, labels) -> names.add(rs.getString("name")));
                    return names;
                }), read("forEach(columns,rowConsumer)", false, (d, c) -> {
                    final List<String> names = new ArrayList<>();
                    d.forEach(COLUMNS, c, rs -> names.add(rs.getString("name")));
                    return names;
                }), read("forEach(columns,biRowConsumer)", false, (d, c) -> {
                    final List<String> names = new ArrayList<>();
                    d.forEach(COLUMNS, c, (rs, labels) -> names.add(rs.getString("name")));
                    return names;
                }), read("forEach(columns,rowFilter,rowConsumer)", true, (d, c) -> {
                    final List<String> names = new ArrayList<>();
                    d.forEach(COLUMNS, c, rs -> rs.getInt("score") >= 20, rs -> names.add(rs.getString("name")));
                    return names;
                }), read("forEach(columns,biRowFilter,biRowConsumer)", true, (d, c) -> {
                    final List<String> names = new ArrayList<>();
                    d.forEach(COLUMNS, c, (rs, labels) -> rs.getInt("score") >= 20, (rs, labels) -> names.add(rs.getString("name")));
                    return names;
                }));
    }

    private static Stream<Arguments> singleReads() {
        return Stream
                .of(single("findFirst(rowMapper)", false, (d, c) -> d.findFirst(c, rs -> rs.getString("name"))),
                        single("findFirst(biRowMapper)", false, (d, c) -> d.findFirst(c, (rs, labels) -> rs.getString("name"))),
                        single("findFirst(columns,rowMapper)", false, (d, c) -> d.findFirst(COLUMNS, c, rs -> rs.getString("name"))),
                        single("findFirst(columns,biRowMapper)", false, (d, c) -> d.findFirst(COLUMNS, c, (rs, labels) -> rs.getString("name"))),
                        single("findOnlyOne(rowMapper)", true, (d, c) -> d.findOnlyOne(c, rs -> rs.getString("name"))),
                        single("findOnlyOne(biRowMapper)", true, (d, c) -> d.findOnlyOne(c, (rs, labels) -> rs.getString("name"))),
                        single("findOnlyOne(columns,rowMapper)", true, (d, c) -> d.findOnlyOne(COLUMNS, c, rs -> rs.getString("name"))),
                        single("findOnlyOne(columns,biRowMapper)", true, (d, c) -> d.findOnlyOne(COLUMNS, c, (rs, labels) -> rs.getString("name"))),
                        single("queryForSingleNonNull(rowMapper)", false, (d, c) -> d.queryForSingleNonNull("name", c, rs -> rs.getString(1))),
                        single("queryForUniqueNonNull(rowMapper)", true, (d, c) -> d.queryForUniqueNonNull("name", c, rs -> rs.getString(1))))
                .flatMap(operation -> Stream.of(DaoStyle.values()).map(style -> Arguments.of(style, operation.name(), operation)));
    }

    private static List<String> readNames(final ResultSet rows) throws SQLException {
        final List<String> names = new ArrayList<>();
        while (rows.next()) {
            names.add(rows.getString("name"));
        }
        return names;
    }

    private static Stream<Arguments> paginationModes() {
        return Stream.of(DaoStyle.values())
                .flatMap(style -> Stream.of(PaginationForm.values())
                        .flatMap(form -> (form == PaginationForm.DATASET || form == PaginationForm.SELECTED_DATASET ? Stream.of(PageShape.LIST)
                                : Stream.of(PageShape.values())).map(shape -> Arguments.of(style, form, shape))));
    }

    private static Stream<Arguments> paginationForms() {
        return Stream.of(DaoStyle.values()).flatMap(style -> Stream.of(PaginationForm.values()).map(form -> Arguments.of(style, form)));
    }

    private static com.landawn.abacus.util.stream.Stream<?> paginate(final CrudDao<Record, Long, ?> dao, final PaginationForm form,
            final Jdbc.BiParametersSetter<PreparedQuery, Object> setter, final Jdbc.ResultExtractor<Object> extractor) {
        final Condition condition = Filters.expr("id > ? ORDER BY id");
        return switch (form) {
            case DATASET -> dao.paginate(condition, 2, (query, page) -> setter.accept(query, page));
            case SELECTED_DATASET -> dao.paginate(COLUMNS, condition, 2, (query, page) -> setter.accept(query, page));
            case EXTRACTOR -> dao.paginate(condition, 2, setter, extractor);
            case BI_EXTRACTOR -> dao.paginate(condition, 2, setter, (rows, labels) -> extractor.apply(rows));
            case SELECTED_EXTRACTOR -> dao.paginate(COLUMNS, condition, 2, setter, extractor);
            case SELECTED_BI_EXTRACTOR -> dao.paginate(COLUMNS, condition, 2, setter, (rows, labels) -> extractor.apply(rows));
        };
    }

    private static List<Long> readIds(final ResultSet rows) throws SQLException {
        final List<Long> ids = new ArrayList<>();
        while (rows.next()) {
            ids.add(rows.getLong("id"));
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private static List<Long> pageIds(final Object page) {
        if (page instanceof Dataset dataset) {
            return dataset.getColumn(0);
        }
        if (page instanceof Map<?, ?> map) {
            return new ArrayList<>((java.util.Set<Long>) map.keySet());
        }
        if (page instanceof Long id) {
            return List.of(id);
        }
        final Iterator<Long> iterator = page instanceof Iterable<?> iterable ? ((Iterable<Long>) iterable).iterator() : (Iterator<Long>) page;
        final List<Long> ids = new ArrayList<>();
        iterator.forEachRemaining(ids::add);
        return ids;
    }

    private static List<String> collect(final com.landawn.abacus.util.stream.Stream<String> rows) {
        try (rows) {
            return rows.toList();
        }
    }

    private static ReadOperation read(final String name, final boolean filtered,
            final Throwables.BiFunction<CrudDao<Record, Long, ?>, Condition, List<String>, SQLException> action) {
        return new ReadOperation(name, filtered, action);
    }

    private static SingleOperation single(final String name, final boolean unique,
            final Throwables.BiFunction<CrudDao<Record, Long, ?>, Condition, Optional<String>, SQLException> action) {
        return new SingleOperation(name, unique, action);
    }

    private record ReadOperation(String name, boolean filtered, Throwables.BiFunction<CrudDao<Record, Long, ?>, Condition, List<String>, SQLException> action) {
    }

    private record SingleOperation(String name, boolean unique,
            Throwables.BiFunction<CrudDao<Record, Long, ?>, Condition, Optional<String>, SQLException> action) {
    }

    private enum DaoStyle {
        MAPPED, DRIVER_TYPES, UNCHECKED
    }

    private enum PaginationForm {
        DATASET, SELECTED_DATASET, EXTRACTOR, BI_EXTRACTOR, SELECTED_EXTRACTOR, SELECTED_BI_EXTRACTOR
    }

    private enum PageShape {
        LIST, MAP, ITERABLE, ITERATOR, DATASET, SCALAR;

        Object wrap(final List<Long> ids) {
            return switch (this) {
                case LIST -> ids;
                case MAP -> {
                    final Map<Long, Long> map = new LinkedHashMap<>();
                    ids.forEach(id -> map.put(id, id));
                    yield map;
                }
                case ITERABLE -> (Iterable<Long>) ids::iterator;
                case ITERATOR -> ids.iterator();
                case DATASET -> new RowDataset(List.of("id"), List.of(new ArrayList<Object>(ids)));
                case SCALAR -> ids.isEmpty() ? null : ids.get(ids.size() - 1);
            };
        }
    }

    public interface RecordDao extends CrudDao<Record, Long, RecordDao> {
    }

    @DaoConfig(fetchColumnByEntityClassForDatasetQuery = false)
    public interface RawRecordDao extends CrudDao<Record, Long, RawRecordDao> {
    }

    public interface UncheckedRecordDao extends UncheckedCrudDao<Record, Long, UncheckedRecordDao> {
    }

    @Table("coverage_record")
    public static class Record {
        @Id
        private Long id;
        private String name;
        private int score;

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

        public int getScore() {
            return score;
        }

        public void setScore(final int score) {
            this.score = score;
        }
    }
}
