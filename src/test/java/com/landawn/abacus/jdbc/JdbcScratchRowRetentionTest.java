package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.Throwables;

@Tag("2025")
class JdbcScratchRowRetentionTest {

    private static final List<String> LABELS = List.of("first", "second");

    enum Factory {
        FINISHER, LABEL_FINISHER, FILTERED_MAP, MAP, CONVERTED_MAP, CONSUMER, TYPED_CONSUMER, BI_CONSUMER, TYPED_BI_CONSUMER
    }

    @ParameterizedTest
    @EnumSource(Factory.class)
    void releasesScratchAfterSuccessAndKeepsCopiedResults(final Factory factory) throws Exception {
        final List<List<Object>> copiedRows = new ArrayList<>();
        final Callback callback = callback(factory, row -> copiedRows.add(row.toList()));
        final Object[] values = { new byte[1024], new Object() };
        final ResultSet rs = resultSet(values, null);
        final Object firstResult = callback.invoke(rs);
        final Object[] scratch = scratch(callback.owner());

        assertReleased(scratch);
        assertSame(values[0], copiedRows.get(0).get(0));
        assertSame(values[1], copiedRows.get(0).get(1));
        assertCopiedResult(firstResult, values);

        final Object firstPayload = values[0];
        values[0] = new byte[2048];
        values[1] = new Object();
        final Object secondResult = callback.invoke(rs);

        assertSame(scratch, scratch(callback.owner()), "The empty scratch array should still be reused");
        assertReleased(scratch);
        assertSame(firstPayload, copiedRows.get(0).get(0));
        assertSame(values[0], copiedRows.get(1).get(0));
        assertCopiedResult(secondResult, values);
    }

    @ParameterizedTest
    @EnumSource(Factory.class)
    void releasesPartiallyFilledScratchWhenAColumnReadFails(final Factory factory) throws Exception {
        final SQLException failure = new SQLException("second column failed");
        final Callback callback = callback(factory, row -> {
            throw new AssertionError("No callback should run after a failed column read");
        });

        assertSame(failure, assertThrows(SQLException.class, () -> callback.invoke(resultSet(new Object[] { new byte[1024], null }, failure))));
        assertReleased(scratch(callback.owner()));
    }

    @ParameterizedTest
    @EnumSource(Factory.class)
    void releasesScratchWhenTheCallbackOrExtractorFails(final Factory factory) throws Exception {
        final IllegalStateException failure = new IllegalStateException("callback failed");
        final Callback callback = callback(factory, row -> {
            throw failure;
        });

        assertSame(failure, assertThrows(IllegalStateException.class, () -> callback.invoke(resultSet(new Object[] { new byte[1024], new Object() }, null))));
        assertReleased(scratch(callback.owner()));
    }

    @Test
    void rejectedMapValuesAreNotRetained() throws Exception {
        final Jdbc.BiRowMapper<Map<String, Object>> mapper = Jdbc.BiRowMapper.toMap(extractor(row -> {
        }), (name, value) -> false, HashMap::new);

        assertTrue(mapper.apply(resultSet(new Object[] { new byte[1024], new Object() }, null), LABELS).isEmpty());
        assertReleased(scratch(mapper));
    }

    @Test
    void releasesScratchWhenMapConstructionFailsAfterExtraction() throws Exception {
        final IllegalStateException failure = new IllegalStateException("map construction failed");
        final Jdbc.RowExtractor extractor = extractor(row -> {
        });
        final List<Jdbc.BiRowMapper<Map<String, Object>>> mappers = List.of(Jdbc.BiRowMapper.toMap(extractor, (name, value) -> {
            throw failure;
        }, HashMap::new), Jdbc.BiRowMapper.toMap(extractor, (name, value) -> true, size -> {
            throw failure;
        }), Jdbc.BiRowMapper.toMap(extractor, name -> name, size -> {
            throw failure;
        }), Jdbc.BiRowMapper.toMap(extractor, (name, value) -> true, size -> failingMap(failure)),
                Jdbc.BiRowMapper.toMap(extractor, name -> name, size -> failingMap(failure)));

        for (final Jdbc.BiRowMapper<Map<String, Object>> mapper : mappers) {
            assertSame(failure,
                    assertThrows(IllegalStateException.class, () -> mapper.apply(resultSet(new Object[] { new byte[1024], new Object() }, null), LABELS)));
            assertReleased(scratch(mapper));
        }
    }

    @Test
    void mappersReturningDisposableViewsKeepTheirValuesUntilReuse() throws SQLException {
        final List<Throwables.Function<ResultSet, DisposableObjArray, SQLException>> mappers = List.of(Jdbc.RowMapper.toDisposableObjArray(),
                Jdbc.RowMapper.toDisposableObjArray(PayloadRow.class), rs -> Jdbc.BiRowMapper.toDisposableObjArray().apply(rs, LABELS),
                rs -> Jdbc.BiRowMapper.toDisposableObjArray(PayloadRow.class).apply(rs, LABELS));

        for (final Throwables.Function<ResultSet, DisposableObjArray, SQLException> mapper : mappers) {
            final Object[] values = { new byte[1024], new Object() };
            final DisposableObjArray result = mapper.apply(resultSet(values, null));

            assertSame(values[0], result.get(0));
            assertSame(values[1], result.get(1));
        }
    }

    private static Callback callback(final Factory factory, final Consumer<DisposableObjArray> receiver) {
        return switch (factory) {
            case FINISHER -> mapper(Jdbc.RowMapper.builder().to(row -> {
                receiver.accept(row);
                return row.toList();
            }));
            case LABEL_FINISHER -> mapper(Jdbc.RowMapper.builder().to((labels, row) -> {
                assertEquals(LABELS, labels);
                receiver.accept(row);
                return row.toList();
            }));
            case FILTERED_MAP -> mapper(Jdbc.BiRowMapper.toMap(extractor(receiver), (name, value) -> true, HashMap::new));
            case MAP -> mapper(Jdbc.BiRowMapper.toMap(extractor(receiver)));
            case CONVERTED_MAP -> mapper(Jdbc.BiRowMapper.toMap(extractor(receiver), name -> name, HashMap::new));
            case CONSUMER -> consumer(Jdbc.RowConsumer.forDisposableObjArray(receiver));
            case TYPED_CONSUMER -> consumer(Jdbc.RowConsumer.forDisposableObjArray(PayloadRow.class, receiver));
            case BI_CONSUMER -> consumer(Jdbc.BiRowConsumer.forDisposableObjArray((labels, row) -> receiver.accept(row)));
            case TYPED_BI_CONSUMER -> consumer(Jdbc.BiRowConsumer.forDisposableObjArray(PayloadRow.class, (labels, row) -> receiver.accept(row)));
        };
    }

    private static Jdbc.RowExtractor extractor(final Consumer<DisposableObjArray> receiver) {
        return (rs, row) -> {
            row[0] = rs.getObject(1);
            row[1] = rs.getObject(2);
            receiver.accept(DisposableObjArray.wrap(row));
        };
    }

    private static Callback mapper(final Jdbc.RowMapper<?> mapper) {
        return new Callback(mapper, mapper::apply);
    }

    private static Callback mapper(final Jdbc.BiRowMapper<?> mapper) {
        return new Callback(mapper, rs -> mapper.apply(rs, LABELS));
    }

    private static Callback consumer(final Jdbc.RowConsumer consumer) {
        return new Callback(consumer, rs -> {
            consumer.accept(rs);
            return null;
        });
    }

    private static Callback consumer(final Jdbc.BiRowConsumer consumer) {
        return new Callback(consumer, rs -> {
            consumer.accept(rs, LABELS);
            return null;
        });
    }

    private record Callback(Object owner, Throwables.Function<ResultSet, Object, SQLException> action) {
        Object invoke(final ResultSet rs) throws SQLException {
            return action.apply(rs);
        }
    }

    private static Map<String, Object> failingMap(final RuntimeException failure) {
        return new HashMap<>() {
            @Override
            public Object put(final String key, final Object value) {
                throw failure;
            }
        };
    }

    private static void assertCopiedResult(final Object result, final Object[] expected) {
        if (result instanceof Map<?, ?> map) {
            assertSame(expected[0], map.get("first"));
            assertSame(expected[1], map.get("second"));
        } else if (result instanceof List<?> list) {
            assertSame(expected[0], list.get(0));
            assertSame(expected[1], list.get(1));
        }
    }

    private static Object[] scratch(final Object callback) throws ReflectiveOperationException {
        // Inspect the retained references directly, avoiding assertions that depend on GC timing.
        for (final Field field : callback.getClass().getDeclaredFields()) {
            if (field.getType() == Object[].class) {
                field.setAccessible(true);
                return (Object[]) field.get(callback);
            }
        }

        throw new AssertionError("No scratch row found on " + callback.getClass());
    }

    private static void assertReleased(final Object[] scratch) {
        assertEquals(2, scratch.length);

        for (final Object value : scratch) {
            assertNull(value, "A reusable callback must not retain a row after its invocation ends");
        }
    }

    private static ResultSet resultSet(final Object[] values, final SQLException readFailure) {
        final ResultSetMetaData metadata = (ResultSetMetaData) Proxy.newProxyInstance(JdbcScratchRowRetentionTest.class.getClassLoader(),
                new Class<?>[] { ResultSetMetaData.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getColumnCount" -> LABELS.size();
                    case "getColumnLabel", "getColumnName" -> LABELS.get((int) args[0] - 1);
                    default -> throw new UnsupportedOperationException(method.getName());
                });

        return (ResultSet) Proxy.newProxyInstance(JdbcScratchRowRetentionTest.class.getClassLoader(), new Class<?>[] { ResultSet.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMetaData" -> metadata;
                    case "getObject" -> {
                        final int index = (int) args[0];

                        if (index == 2 && readFailure != null) {
                            throw readFailure;
                        }

                        yield values[index - 1];
                    }
                    case "toString" -> "ScratchRetentionResultSet";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    public static class PayloadRow {
        private Object first;
        private Object second;

        public Object getFirst() {
            return first;
        }

        public void setFirst(final Object first) {
            this.first = first;
        }

        public Object getSecond() {
            return second;
        }

        public void setSecond(final Object second) {
            this.second = second;
        }
    }
}
