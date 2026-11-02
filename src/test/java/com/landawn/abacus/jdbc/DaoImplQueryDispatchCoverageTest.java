package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Answers;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.jdbc.annotation.MappedByKey;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.u.Nullable;
import com.landawn.abacus.util.u.Optional;

/**
 * Exercises {@code DaoImpl.createQueryFunctionByMethod} branches that DAO creation never reaches.
 * Custom query methods are rejected when they declare a row mapper or result extractor, but the dispatch
 * behind that gate is kept for when the feature is enabled. These tests call the factory directly and run
 * the function it returns against a query stand-in.
 */
class DaoImplQueryDispatchCoverageTest extends TestBase {

    private static final Method FACTORY = factory();

    @ParameterizedTest(name = "{0}")
    @MethodSource("dispatches")
    void createQueryFunctionByMethod_coversDispatchThePublicApiRejects(final Dispatch dispatch) throws Throwable {
        final Method method = Probe.class.getDeclaredMethod(dispatch.name(), dispatch.parameterTypes());
        final Throwable failure = invokeFactory(method, dispatch);

        if (dispatch.errorType() != null) {
            assertTrue(dispatch.errorType().isInstance(failure), () -> dispatch.name() + " threw " + failure);
            if (dispatch.messagePart() != null && failure != null) {
                assertTrue(failure.getMessage() != null && failure.getMessage().contains(dispatch.messagePart()), failure::getMessage);
            }
            return;
        }

        if (failure != null) {
            throw failure;
        }
    }

    @Test
    void toDeclaredCollectionType_returnsTheCollectionWhenItIsNotAnImmutableWrapper() throws Exception {
        final Method method = DaoImpl.class.getDeclaredMethod("toDeclaredCollectionType", Collection.class, Class.class);
        method.setAccessible(true);
        final ArrayList<String> values = new ArrayList<>(List.of("a"));

        assertSame(values, method.invoke(null, values, Bag.class));
    }

    private static Throwable invokeFactory(final Method method, final Dispatch dispatch) throws Throwable {
        try {
            final Object function = FACTORY.invoke(null, Entity.class, method, dispatch.mappedByKey(), dispatch.mergedByIds(), dispatch.prefix(), false,
                    dispatch.hasMapper(), dispatch.hasFilter(), dispatch.operation(), dispatch.procedure(), Probe.class.getName() + "." + method.getName());
            if (dispatch.errorType() != null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            final Throwables.BiFunction<AbstractQuery, Object[], Object, SQLException> queryFunction = (Throwables.BiFunction<AbstractQuery, Object[], Object, SQLException>) function;
            final List<String> calls = new ArrayList<>();
            final CallableQuery query = mock(CallableQuery.class, withSettings().defaultAnswer(invocation -> {
                final Method invoked = invocation.getMethod();
                if (invoked.getDeclaringClass() == Object.class) {
                    return Answers.RETURNS_DEFAULTS.answer(invocation);
                }
                calls.add(invoked.getName());
                final Class<?> returnType = invoked.getReturnType();
                if (com.landawn.abacus.util.stream.Stream.class.isAssignableFrom(returnType)) {
                    return com.landawn.abacus.util.stream.Stream.empty();
                }
                if (returnType == boolean.class) {
                    return false;
                }
                if (returnType == int.class) {
                    return 0;
                }
                if (returnType == long.class) {
                    return 0L;
                }
                return null;
            }));

            try {
                queryFunction.apply(query, arguments(method));
            } catch (final SQLException | RuntimeException executionFailure) {
                if (dispatch.expectedCall() != null && !calls.contains(dispatch.expectedCall())) {
                    throw executionFailure;
                }
            }

            if (dispatch.expectedCall() != null) {
                assertTrue(calls.contains(dispatch.expectedCall()), () -> method.getName() + " called " + calls);
            }
            return null;
        } catch (final InvocationTargetException wrapping) {
            return wrapping.getCause();
        }
    }

    private static Object[] arguments(final Method method) {
        final Class<?>[] types = method.getParameterTypes();
        final Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            final Class<?> type = types[i];
            if (Jdbc.RowFilter.class.isAssignableFrom(type)) {
                args[i] = (Jdbc.RowFilter) resultSet -> true;
            } else if (Jdbc.BiRowFilter.class.isAssignableFrom(type)) {
                args[i] = (Jdbc.BiRowFilter) (resultSet, labels) -> true;
            } else if (Jdbc.RowMapper.class.isAssignableFrom(type)) {
                args[i] = (Jdbc.RowMapper<Object>) resultSet -> null;
            } else if (Jdbc.BiRowMapper.class.isAssignableFrom(type)) {
                args[i] = (Jdbc.BiRowMapper<Object>) (resultSet, labels) -> null;
            } else if (Jdbc.ResultExtractor.class.isAssignableFrom(type)) {
                args[i] = (Jdbc.ResultExtractor<Object>) resultSet -> null;
            } else if (Jdbc.BiResultExtractor.class.isAssignableFrom(type)) {
                args[i] = (Jdbc.BiResultExtractor<Object>) (resultSet, labels) -> null;
            } else if (type == int.class) {
                args[i] = 1;
            }
        }
        return args;
    }

    private static Method factory() {
        try {
            final Method method = DaoImpl.class.getDeclaredMethod("createQueryFunctionByMethod", Class.class, Method.class, String.class, List.class, Map.class,
                    boolean.class, boolean.class, boolean.class, QueryOperation.class, boolean.class, String.class);
            method.setAccessible(true);
            return method;
        } catch (final NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    static Stream<Dispatch> dispatches() {
        final Class<UnsupportedOperationException> unsupported = UnsupportedOperationException.class;
        final Class<IllegalArgumentException> illegal = IllegalArgumentException.class;
        return Stream.of(Dispatch.error("scalarQuery", QueryOperation.query, false, false, false, unsupported, "not supported"),
                Dispatch.error("optionalUpdate", QueryOperation.update, false, false, false, unsupported, "not supported"),
                Dispatch.error("nullableUpdate", QueryOperation.update, false, false, false, unsupported, "not supported"),
                Dispatch.call("listAllRowFilter", QueryOperation.listAll, true, true, true, "listAllResultSetsAndGetOutParameters"),
                Dispatch.call("listAllRow", QueryOperation.listAll, true, true, false, "listAllResultSetsAndGetOutParameters"),
                Dispatch.call("listAllBiFilter", QueryOperation.listAll, true, true, true, "listAllResultSetsAndGetOutParameters"),
                Dispatch.call("listAllBi", QueryOperation.listAll, true, true, false, "listAllResultSetsAndGetOutParameters"),
                Dispatch.error("listAllBadLast", QueryOperation.listAll, true, true, false, unsupported, "last parameter type"),
                Dispatch.call("listAllRowsFilter", QueryOperation.listAll, true, true, true, "listAllResultSets"),
                Dispatch.call("listAllRows", QueryOperation.listAll, true, true, false, "listAllResultSets"),
                Dispatch.call("listAllRowsBiFilter", QueryOperation.listAll, true, true, true, "listAllResultSets"),
                Dispatch.call("listAllRowsBi", QueryOperation.listAll, true, true, false, "listAllResultSets"),
                Dispatch.error("listAllRowsBad", QueryOperation.listAll, true, true, false, unsupported, "last parameter type"),
                Dispatch.error("queryAllBadTuple", QueryOperation.queryAll, true, false, false, unsupported, "not supported"),
                Dispatch.call("queryAllExtractor", QueryOperation.queryAll, true, true, false, "queryAllResultSetsAndGetOutParameters"),
                Dispatch.call("queryAllBiExtractor", QueryOperation.queryAll, true, true, false, "queryAllResultSetsAndGetOutParameters"),
                Dispatch.error("queryAllBadExtractor", QueryOperation.queryAll, true, true, false, unsupported, "last parameter type"),
                Dispatch.call("queryAllListExtractor", QueryOperation.queryAll, true, true, false, "queryAllResultSets"),
                Dispatch.call("queryAllListBiExtractor", QueryOperation.queryAll, true, true, false, "queryAllResultSets"),
                Dispatch.error("queryAllListBad", QueryOperation.queryAll, true, true, false, unsupported, "last parameter type"),
                Dispatch.call("streamAllExtractor", QueryOperation.streamAll, true, true, false, "streamAllResultSets"),
                Dispatch.call("streamAllBiExtractor", QueryOperation.streamAll, true, true, false, "streamAllResultSets"),
                Dispatch.error("streamAllBad", QueryOperation.streamAll, true, true, false, unsupported, "last parameter type"),
                Dispatch.call("listOutFilter", QueryOperation.list, true, true, true, "listAndGetOutParameters"),
                Dispatch.call("listOut", QueryOperation.list, true, true, false, "listAndGetOutParameters"),
                Dispatch.call("listOutBiFilter", QueryOperation.list, true, true, true, "listAndGetOutParameters"),
                Dispatch.call("listOutBi", QueryOperation.list, true, true, false, "listAndGetOutParameters"),
                Dispatch.call("queryOut", QueryOperation.query, true, true, false, "queryAndGetOutParameters"),
                Dispatch.call("queryOutBi", QueryOperation.query, true, true, false, "queryAndGetOutParameters"),
                Dispatch.error("listOutBad", QueryOperation.list, true, true, false, unsupported, "last parameter type"),
                Dispatch.error("listOutWild", QueryOperation.list, true, false, false, unsupported, "not supported"),
                Dispatch.error("rowsWithKey", QueryOperation.list, false, true, false, "id", List.of(), Map.of(), unsupported, "not supported"),
                Dispatch.error("rowsBadOp", QueryOperation.update, false, true, false, unsupported, "not supported"),
                Dispatch.error("rowsFilterBadOp", QueryOperation.queryAll, false, true, true, unsupported, "RowFilter"),
                Dispatch.call("rowsFilter", QueryOperation.list, false, true, true, "list"),
                Dispatch.call("rows", QueryOperation.list, false, true, false, "list"),
                Dispatch.call("findOnlyOneFilter", QueryOperation.findOnlyOne, false, true, true, "stream"),
                Dispatch.call("findOnlyOneMapped", QueryOperation.findOnlyOne, false, true, false, "findOnlyOne"),
                Dispatch.call("findFirstFilter", QueryOperation.findFirst, false, true, true, "stream"),
                Dispatch.call("findFirstMapped", QueryOperation.findFirst, false, true, false, "findFirst"),
                Dispatch.call("streamFilter", QueryOperation.stream, false, true, true, "stream"),
                Dispatch.call("streamMapped", QueryOperation.stream, false, true, false, "stream"),
                Dispatch.error("nullableMapper", QueryOperation.DEFAULT, false, true, false, unsupported, "can't be"),
                Dispatch.call("onlyOneFilter", QueryOperation.findOnlyOne, false, true, true, "stream"),
                Dispatch.call("onlyOneMapped", QueryOperation.findOnlyOne, false, true, false, "findOnlyOne"),
                Dispatch.call("firstFilter", QueryOperation.findFirst, false, true, true, "stream"),
                Dispatch.call("firstMapped", QueryOperation.findFirst, false, true, false, "findFirst"),
                Dispatch.call("biRowsFilter", QueryOperation.list, false, true, true, "list"),
                Dispatch.call("biRows", QueryOperation.list, false, true, false, "list"),
                Dispatch.call("biFindOnlyOneFilter", QueryOperation.findOnlyOne, false, true, true, "stream"),
                Dispatch.call("biFindOnlyOne", QueryOperation.findOnlyOne, false, true, false, "findOnlyOne"),
                Dispatch.call("biFindFirstFilter", QueryOperation.findFirst, false, true, true, "stream"),
                Dispatch.call("biFindFirst", QueryOperation.findFirst, false, true, false, "findFirst"),
                Dispatch.call("biStreamFilter", QueryOperation.stream, false, true, true, "stream"),
                Dispatch.call("biStream", QueryOperation.stream, false, true, false, "stream"),
                Dispatch.error("biNullable", QueryOperation.DEFAULT, false, true, false, unsupported, "can't be"),
                Dispatch.call("biOnlyOneFilter", QueryOperation.findOnlyOne, false, true, true, "stream"),
                Dispatch.call("biOnlyOne", QueryOperation.findOnlyOne, false, true, false, "findOnlyOne"),
                Dispatch.call("biFirstFilter", QueryOperation.findFirst, false, true, true, "stream"),
                Dispatch.call("biFirst", QueryOperation.findFirst, false, true, false, "findFirst"),
                Dispatch.error("extractorBadOp", QueryOperation.findFirst, false, true, false, unsupported, "not supported"),
                Dispatch.error("extractorMismatch", QueryOperation.query, false, true, false, unsupported, "not assignable"),
                Dispatch.call("biExtractor", QueryOperation.query, false, true, false, "query"),
                Dispatch.error("mappedMissing", QueryOperation.DEFAULT, false, false, false, "missing", List.of(), Map.of(), illegal, "No property found"),
                Dispatch.error("mergedBad", QueryOperation.DEFAULT, false, false, false, "", List.of("id"), Map.of(), unsupported, "MergedById"),
                Dispatch.call("notExistsActive", QueryOperation.DEFAULT, false, false, false, "notExists"),
                Dispatch.call("queryForUniqueValue", QueryOperation.queryForUnique, false, false, false, "queryForUniqueNonNull"),
                Dispatch.call("queryForSingleValue", QueryOperation.queryForSingle, false, false, false, "queryForSingleNonNull"),
                Dispatch.error("rawList", QueryOperation.list, false, false, false, unsupported, "can't be resolved"));
    }

    record Dispatch(String name, QueryOperation operation, boolean procedure, boolean hasMapper, boolean hasFilter, String mappedByKey,
            List<String> mergedByIds, Map<String, String> prefix, Class<? extends Throwable> errorType, String messagePart, String expectedCall) {

        static Dispatch error(final String name, final QueryOperation operation, final boolean procedure, final boolean hasMapper, final boolean hasFilter,
                final Class<? extends Throwable> errorType, final String messagePart) {
            return new Dispatch(name, operation, procedure, hasMapper, hasFilter, "", List.of(), Map.of(), errorType, messagePart, null);
        }

        static Dispatch error(final String name, final QueryOperation operation, final boolean procedure, final boolean hasMapper, final boolean hasFilter,
                final String mappedByKey, final List<String> mergedByIds, final Map<String, String> prefix, final Class<? extends Throwable> errorType,
                final String messagePart) {
            return new Dispatch(name, operation, procedure, hasMapper, hasFilter, mappedByKey, mergedByIds, prefix, errorType, messagePart, null);
        }

        static Dispatch call(final String name, final QueryOperation operation, final boolean procedure, final boolean hasMapper, final boolean hasFilter,
                final String expectedCall) {
            return new Dispatch(name, operation, procedure, hasMapper, hasFilter, "", List.of(), Map.of(), null, null, expectedCall);
        }

        Class<?>[] parameterTypes() throws NoSuchMethodException {
            for (final Method method : Probe.class.getDeclaredMethods()) {
                if (method.getName().equals(name)) {
                    return method.getParameterTypes();
                }
            }
            throw new NoSuchMethodException(name);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** A collection type no immutable wrapper claims, so the adapter returns the collection unchanged. */
    interface Bag extends Collection<String> {
    }

    public static class Entity {
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

    interface Probe {
        String scalarQuery(int id);

        Optional<Long> optionalUpdate();

        Nullable<Long> nullableUpdate();

        Tuple2<List<List<String>>, Jdbc.OutParamResult> listAllRowFilter(Jdbc.RowFilter filter, Jdbc.RowMapper<String> mapper);

        Tuple2<List<List<String>>, Jdbc.OutParamResult> listAllRow(Jdbc.RowMapper<String> mapper);

        Tuple2<List<List<String>>, Jdbc.OutParamResult> listAllBiFilter(Jdbc.BiRowFilter filter, Jdbc.BiRowMapper<String> mapper);

        Tuple2<List<List<String>>, Jdbc.OutParamResult> listAllBi(Jdbc.BiRowMapper<String> mapper);

        Tuple2<List<List<String>>, Jdbc.OutParamResult> listAllBadLast(String name);

        List<List<String>> listAllRowsFilter(Jdbc.RowFilter filter, Jdbc.RowMapper<String> mapper);

        List<List<String>> listAllRows(Jdbc.RowMapper<String> mapper);

        List<List<String>> listAllRowsBiFilter(Jdbc.BiRowFilter filter, Jdbc.BiRowMapper<String> mapper);

        List<List<String>> listAllRowsBi(Jdbc.BiRowMapper<String> mapper);

        List<List<String>> listAllRowsBad(String name);

        Tuple2<String, Jdbc.OutParamResult> queryAllBadTuple();

        Tuple2<List<Dataset>, Jdbc.OutParamResult> queryAllExtractor(Jdbc.ResultExtractor<Dataset> extractor);

        Tuple2<List<Dataset>, Jdbc.OutParamResult> queryAllBiExtractor(Jdbc.BiResultExtractor<Dataset> extractor);

        Tuple2<List<Dataset>, Jdbc.OutParamResult> queryAllBadExtractor(String name);

        List<Dataset> queryAllListExtractor(Jdbc.ResultExtractor<Dataset> extractor);

        List<Dataset> queryAllListBiExtractor(Jdbc.BiResultExtractor<Dataset> extractor);

        List<Dataset> queryAllListBad(String name);

        com.landawn.abacus.util.stream.Stream<Dataset> streamAllExtractor(Jdbc.ResultExtractor<Dataset> extractor);

        com.landawn.abacus.util.stream.Stream<Dataset> streamAllBiExtractor(Jdbc.BiResultExtractor<Dataset> extractor);

        com.landawn.abacus.util.stream.Stream<Dataset> streamAllBad(String name);

        Tuple2<List<String>, Jdbc.OutParamResult> listOutFilter(Jdbc.RowFilter filter, Jdbc.RowMapper<String> mapper);

        Tuple2<List<String>, Jdbc.OutParamResult> listOut(Jdbc.RowMapper<String> mapper);

        Tuple2<List<String>, Jdbc.OutParamResult> listOutBiFilter(Jdbc.BiRowFilter filter, Jdbc.BiRowMapper<String> mapper);

        Tuple2<List<String>, Jdbc.OutParamResult> listOutBi(Jdbc.BiRowMapper<String> mapper);

        Tuple2<Dataset, Jdbc.OutParamResult> queryOut(Jdbc.ResultExtractor<Dataset> extractor);

        Tuple2<Dataset, Jdbc.OutParamResult> queryOutBi(Jdbc.BiResultExtractor<Dataset> extractor);

        Tuple2<List<String>, Jdbc.OutParamResult> listOutBad(String name);

        Tuple2<?, Jdbc.OutParamResult> listOutWild();

        @MappedByKey("id")
        List<String> rowsWithKey(Jdbc.RowMapper<String> mapper);

        List<String> rowsBadOp(Jdbc.RowMapper<String> mapper);

        List<String> rowsFilterBadOp(Jdbc.RowFilter filter, Jdbc.RowMapper<String> mapper);

        List<String> rowsFilter(Jdbc.RowFilter filter, Jdbc.RowMapper<String> mapper);

        List<String> rows(Jdbc.RowMapper<String> mapper);

        Optional<String> findOnlyOneFilter(Jdbc.RowFilter filter, Jdbc.RowMapper<String> mapper);

        Optional<String> findOnlyOneMapped(Jdbc.RowMapper<String> mapper);

        Optional<String> findFirstFilter(Jdbc.RowFilter filter, Jdbc.RowMapper<String> mapper);

        Optional<String> findFirstMapped(Jdbc.RowMapper<String> mapper);

        com.landawn.abacus.util.stream.Stream<String> streamFilter(Jdbc.RowFilter filter, Jdbc.RowMapper<String> mapper);

        com.landawn.abacus.util.stream.Stream<String> streamMapped(Jdbc.RowMapper<String> mapper);

        Nullable<String> nullableMapper(Jdbc.RowMapper<String> mapper);

        String onlyOneFilter(Jdbc.RowFilter filter, Jdbc.RowMapper<String> mapper);

        String onlyOneMapped(Jdbc.RowMapper<String> mapper);

        String firstFilter(Jdbc.RowFilter filter, Jdbc.RowMapper<String> mapper);

        String firstMapped(Jdbc.RowMapper<String> mapper);

        List<String> biRowsFilter(Jdbc.BiRowFilter filter, Jdbc.BiRowMapper<String> mapper);

        List<String> biRows(Jdbc.BiRowMapper<String> mapper);

        Optional<String> biFindOnlyOneFilter(Jdbc.BiRowFilter filter, Jdbc.BiRowMapper<String> mapper);

        Optional<String> biFindOnlyOne(Jdbc.BiRowMapper<String> mapper);

        Optional<String> biFindFirstFilter(Jdbc.BiRowFilter filter, Jdbc.BiRowMapper<String> mapper);

        Optional<String> biFindFirst(Jdbc.BiRowMapper<String> mapper);

        com.landawn.abacus.util.stream.Stream<String> biStreamFilter(Jdbc.BiRowFilter filter, Jdbc.BiRowMapper<String> mapper);

        com.landawn.abacus.util.stream.Stream<String> biStream(Jdbc.BiRowMapper<String> mapper);

        Nullable<String> biNullable(Jdbc.BiRowMapper<String> mapper);

        String biOnlyOneFilter(Jdbc.BiRowFilter filter, Jdbc.BiRowMapper<String> mapper);

        String biOnlyOne(Jdbc.BiRowMapper<String> mapper);

        String biFirstFilter(Jdbc.BiRowFilter filter, Jdbc.BiRowMapper<String> mapper);

        String biFirst(Jdbc.BiRowMapper<String> mapper);

        String extractorBadOp(Jdbc.ResultExtractor<String> extractor);

        String extractorMismatch(Jdbc.ResultExtractor<List<String>> extractor);

        String biExtractor(Jdbc.BiResultExtractor<String> extractor);

        @MappedByKey("missing")
        Map<Long, Entity> mappedMissing();

        String mergedBad();

        boolean notExistsActive();

        Optional<String> queryForUniqueValue();

        Optional<String> queryForSingleValue();

        List rawList();
    }
}
