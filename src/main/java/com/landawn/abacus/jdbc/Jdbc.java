/*
 * Copyright (c) 2022, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.landawn.abacus.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.SequentialOnly;
import com.landawn.abacus.annotation.Stateful;
import com.landawn.abacus.jdbc.Jdbc.Columns.ColumnOne;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.pool.KeyedObjectPool;
import com.landawn.abacus.pool.PoolFactory;
import com.landawn.abacus.pool.Poolable;
import com.landawn.abacus.pool.PoolableWrapper;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.Array;
import com.landawn.abacus.util.Beans;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.IntFunctions;
import com.landawn.abacus.util.ListMultimap;
import com.landawn.abacus.util.Multimap;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.ObjectPool;
import com.landawn.abacus.util.Seid;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Suppliers;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Provides a collection of utility interfaces and classes for simplifying JDBC operations.
 * This class serves as a central hub for functional interfaces like {@code ParametersSetter},
 * {@code ResultExtractor}, and {@code RowMapper}, which are designed to streamline database
 * interactions in a type-safe and efficient manner.
 *
 * <p>Key features include:</p>
 * <ul>
 * <li>Functional interfaces for setting parameters on {@code PreparedStatement}s.</li>
 * <li>Flexible mechanisms for extracting data from a {@code ResultSet} into various data structures like Lists, Maps, and custom objects.</li>
 * <li>Row mappers for converting individual {@code ResultSet} rows into objects.</li>
 * <li>Type-safe column getters for retrieving values from a {@code ResultSet}.</li>
 * <li>Fluent builders for creating complex row mappers with customized column handling.</li>
 * </ul>
 *
 * @see ParametersSetter
 * @see ResultExtractor
 * @see RowMapper
 * @see ColumnGetter
 */
@SuppressWarnings("java:S1192")
public final class Jdbc {

    static final ObjectPool<Type<?>, ColumnGetter<?>> COLUMN_GETTER_POOL = new ObjectPool<>(1024);

    static {
        COLUMN_GETTER_POOL.put(N.typeOf(boolean.class), ColumnGetter.GET_BOOLEAN);
        COLUMN_GETTER_POOL.put(N.typeOf(Boolean.class), ColumnGetter.GET_BOOLEAN);
        COLUMN_GETTER_POOL.put(N.typeOf(byte.class), ColumnGetter.GET_BYTE);
        COLUMN_GETTER_POOL.put(N.typeOf(Byte.class), ColumnGetter.GET_BYTE);
        COLUMN_GETTER_POOL.put(N.typeOf(short.class), ColumnGetter.GET_SHORT);
        COLUMN_GETTER_POOL.put(N.typeOf(Short.class), ColumnGetter.GET_SHORT);
        COLUMN_GETTER_POOL.put(N.typeOf(int.class), ColumnGetter.GET_INT);
        COLUMN_GETTER_POOL.put(N.typeOf(Integer.class), ColumnGetter.GET_INT);
        COLUMN_GETTER_POOL.put(N.typeOf(long.class), ColumnGetter.GET_LONG);
        COLUMN_GETTER_POOL.put(N.typeOf(Long.class), ColumnGetter.GET_LONG);
        COLUMN_GETTER_POOL.put(N.typeOf(float.class), ColumnGetter.GET_FLOAT);
        COLUMN_GETTER_POOL.put(N.typeOf(Float.class), ColumnGetter.GET_FLOAT);
        COLUMN_GETTER_POOL.put(N.typeOf(double.class), ColumnGetter.GET_DOUBLE);
        COLUMN_GETTER_POOL.put(N.typeOf(Double.class), ColumnGetter.GET_DOUBLE);
        COLUMN_GETTER_POOL.put(N.typeOf(BigDecimal.class), ColumnGetter.GET_BIG_DECIMAL);
        COLUMN_GETTER_POOL.put(N.typeOf(String.class), ColumnGetter.GET_STRING);
        COLUMN_GETTER_POOL.put(N.typeOf(java.sql.Date.class), ColumnGetter.GET_DATE);
        COLUMN_GETTER_POOL.put(N.typeOf(java.sql.Time.class), ColumnGetter.GET_TIME);
        COLUMN_GETTER_POOL.put(N.typeOf(java.sql.Timestamp.class), ColumnGetter.GET_TIMESTAMP);
        COLUMN_GETTER_POOL.put(N.typeOf(Object.class), ColumnGetter.GET_OBJECT);
    }

    private Jdbc() {
        // singleton.
    }

    /**
     * A functional interface for setting parameters on a prepared query statement.
     * Implementations of this interface are responsible for binding values to the
     * parameters of a {@code PreparedStatement} or {@code CallableStatement}.
     *
     * <p>This interface is typically used to encapsulate parameter setting logic,
     * making it reusable and improving code readability by separating parameter
     * binding from query execution.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Example: Setting parameters for a PreparedStatement
     * ParametersSetter<PreparedStatement> userParamSetter = ps -> {
     *     ps.setString(1, "Alice");
     *     ps.setInt(2, 30);
     *     ps.setBoolean(3, true);
     * };
     *
     * // Example: Using the setter with a PreparedQuery
     * // query.setParameters(userParamSetter).update();
     * }</pre>
     *
     * @param <QS> the type of the query statement (e.g., {@code PreparedStatement}, {@code CallableStatement}).
     * @see BiParametersSetter
     * @see TriParametersSetter
     */
    @FunctionalInterface
    public interface ParametersSetter<QS> extends Throwables.Consumer<QS, SQLException> {
        /**
         * A no-operation parameter setter that does nothing.
         * This constant is useful as a default or placeholder when no parameters need to be set,
         * avoiding {@code null} checks.
         */
        @SuppressWarnings("rawtypes")
        ParametersSetter DO_NOTHING = preparedQuery -> {
            // No operation performed.
        };

        /**
         * Sets the parameters on the given prepared query statement.
         *
         * @param preparedQuery the prepared query statement (e.g., {@code PreparedStatement}) to set parameters on.
         * @throws SQLException if a database access error occurs or if the statement is closed.
         */
        @Override
        void accept(QS preparedQuery) throws SQLException;
    }

    /**
     * A functional interface for setting parameters on a prepared query using a parameter object.
     * This is useful for mapping an object's properties to the parameters of a statement.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Assuming a User class with getName() and getAge()
     * BiParametersSetter<PreparedStatement, User> setter = (ps, user) -> {
     * ps.setString(1, user.getName());
     * ps.setInt(2, user.getAge());
     * };
     * }</pre>
     *
     * @param <QS> query statement type (e.g., {@code PreparedStatement}, {@code CallableStatement})
     * @param <T> parameter object type
     */
    @FunctionalInterface
    public interface BiParametersSetter<QS, T> extends Throwables.BiConsumer<QS, T, SQLException> {
        /**
         * A no-operation parameter setter that does nothing.
         * Useful as a default or placeholder when no parameters need to be set.
         */
        @SuppressWarnings("rawtypes")
        BiParametersSetter DO_NOTHING = (preparedQuery, param) -> {
            // Do nothing.
        };

        /**
         * Sets the parameters on the given prepared query using the provided parameter object.
         *
         * @param preparedQuery the prepared query to set parameters on
         * @param param the parameter object containing values to set
         * @throws SQLException if a database access error occurs or this method is
         * called on a closed {@code PreparedStatement}
         */
        @Override
        void accept(QS preparedQuery, T param) throws SQLException;

        /**
         * Creates a stateful {@code BiParametersSetter} for setting parameters from an array.
         * It maps array elements to prepared statement parameters based on a list of field names,
         * inferring the SQL type from the corresponding property in the provided {@code entityClass}.
         *
         * <p>
         * <b>Warning:</b> The returned setter is stateful because it caches type information
         * upon first execution. It should not be cached, shared, or used in parallel streams.
         * A new instance should be created for each use.
         * </p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * List<String> fields = List.of("firstName", "age");
         * BiParametersSetter<PreparedStatement, Object[]> setter =
         * BiParametersSetter.createForArray(fields, User.class);
         *
         * // In an execution context:
         * // setter.accept(preparedStatement, new Object[] {"John", 30});
         * }</pre>
         *
         * @param <T> array component type
         * @param fieldNameList the list of property names from the {@code entityClass}. The order
         * must match the order of values in the input array and the '?' placeholders in the SQL statement.
         * @param entityClass the entity class used to infer the data type for each parameter.
         * @return a stateful {@code BiParametersSetter}. Do not cache, reuse, or use it in parallel streams.
         * @throws IllegalArgumentException if {@code fieldNameList} is {@code null} or empty, or if {@code entityClass} is not a valid bean class.
         */
        @Beta
        @SequentialOnly
        @Stateful
        static <T> BiParametersSetter<PreparedStatement, T[]> createForArray(final List<String> fieldNameList, final Class<?> entityClass) {
            N.checkArgNotEmpty(fieldNameList, "'fieldNameList' can't be null or empty");
            N.checkArgument(Beans.isBeanClass(entityClass), "{} is not a valid entity class with getter/setter methods", entityClass);

            return new BiParametersSetter<>() {
                private final int len = fieldNameList.size();
                @SuppressWarnings("rawtypes")
                private Type[] fieldTypes = null;

                @Override
                public void accept(final PreparedStatement stmt, final T[] params) throws SQLException {
                    if (fieldTypes == null) {
                        final BeanInfo entityInfo = ParserUtil.getBeanInfo(entityClass);
                        @SuppressWarnings("rawtypes")
                        final Type[] localFieldTypes = new Type[len];

                        for (int i = 0; i < len; i++) {
                            localFieldTypes[i] = entityInfo.getPropInfo(fieldNameList.get(i)).dbType;
                        }

                        fieldTypes = localFieldTypes;
                    }

                    for (int i = 0; i < len; i++) {
                        fieldTypes[i].set(stmt, i + 1, params[i]);
                    }
                }
            };
        }

        /**
         * Creates a stateful {@code BiParametersSetter} for setting parameters from a {@code List}.
         * It maps list elements to prepared statement parameters based on a list of field names,
         * inferring the SQL type from the corresponding property in the provided {@code entityClass}.
         *
         * <p>
         * <b>Warning:</b> The returned setter is stateful because it caches type information
         * upon first execution. It should not be cached, shared, or used in parallel streams.
         * A new instance should be created for each use.
         * </p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * List<String> fields = List.of("firstName", "age");
         * BiParametersSetter<PreparedStatement, List<Object>> setter =
         * BiParametersSetter.createForList(fields, User.class);
         *
         * // In an execution context:
         * // setter.accept(preparedStatement, List.of("John", 30));
         * }</pre>
         *
         * @param <T> list element type
         * @param fieldNameList the list of property names from the {@code entityClass}. The order
         * must match the order of values in the input list and the '?' placeholders in the SQL statement.
         * @param entityClass the entity class used to infer the data type for each parameter.
         * @return a stateful {@code BiParametersSetter}. Do not cache, reuse, or use it in parallel streams.
         * @throws IllegalArgumentException if {@code fieldNameList} is {@code null} or empty, or if {@code entityClass} is not a valid bean class.
         */
        @Beta
        @SequentialOnly
        @Stateful
        static <T> BiParametersSetter<PreparedStatement, List<T>> createForList(final List<String> fieldNameList, final Class<?> entityClass) {
            N.checkArgNotEmpty(fieldNameList, "'fieldNameList' can't be null or empty");
            N.checkArgument(Beans.isBeanClass(entityClass), "{} is not a valid entity class with getter/setter methods", entityClass);

            return new BiParametersSetter<>() {
                private final int len = fieldNameList.size();
                @SuppressWarnings("rawtypes")
                private Type[] fieldTypes = null;

                @Override
                public void accept(final PreparedStatement stmt, final List<T> params) throws SQLException {
                    if (fieldTypes == null) {
                        final BeanInfo entityInfo = ParserUtil.getBeanInfo(entityClass);
                        @SuppressWarnings("rawtypes")
                        final Type[] localFieldTypes = new Type[len];

                        for (int i = 0; i < len; i++) {
                            localFieldTypes[i] = entityInfo.getPropInfo(fieldNameList.get(i)).dbType;
                        }

                        fieldTypes = localFieldTypes;
                    }

                    for (int i = 0; i < len; i++) {
                        fieldTypes[i].set(stmt, i + 1, params.get(i));
                    }
                }
            };
        }
    }

    /**
     * A functional interface for setting parameters on a prepared query, providing access
     * to the parsed SQL structure. This allows for more advanced parameter setting logic
     * that may depend on the details of the SQL statement itself.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Assuming a User class and a ParsedSql object
     * TriParametersSetter<PreparedStatement, User> setter = (parsedSql, ps, user) -> {
     * // Use parsedSql to find parameter indices dynamically if needed
     * ps.setString(1, user.getName());
     * ps.setInt(2, user.getAge());
     * };
     * }</pre>
     *
     * @param <QS> query statement type (e.g., {@code PreparedStatement}, {@code CallableStatement})
     * @param <T> parameter object type
     */
    @SuppressWarnings("RedundantThrows")
    @FunctionalInterface
    public interface TriParametersSetter<QS, T> extends Throwables.TriConsumer<ParsedSql, QS, T, SQLException> {
        /**
         * A no-operation parameter setter that does nothing.
         * Useful as a default or placeholder when no parameters need to be set.
         */
        @SuppressWarnings("rawtypes")
        TriParametersSetter DO_NOTHING = (TriParametersSetter<Object, Object>) (parsedSql, preparedQuery, param) -> {
            // Do nothing.
        };

        /**
         * Sets parameters on the given prepared query using parsed SQL information and a parameter object.
         *
         * @param parsedSql the parsed SQL containing information about the query and its parameters
         * @param preparedQuery the prepared query to set parameters on
         * @param param the parameter object containing values to set
         * @throws SQLException if a database access error occurs or this method is
         * called on a closed {@code PreparedStatement}
         */
        @Override
        void accept(ParsedSql parsedSql, QS preparedQuery, T param) throws SQLException;
    }

    /**
     * A functional interface for extracting a result from a {@code ResultSet}.
     * This interface is responsible for iterating over the {@code ResultSet} and
     * converting its contents into a desired object or collection.
     *
     * <p><b>Important Note:</b> In most execution contexts (like {@code SQLExecutor}),
     * the {@code ResultSet} passed to the {@code apply} method will be closed automatically
     * after the method returns. Therefore, you should process all required data within
     * the method and not attempt to return or store the {@code ResultSet} itself.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * ResultExtractor<List<String>> extractor = rs -> {
     * List<String> names = new ArrayList<>();
     * while (rs.next()) {
     * names.add(rs.getString("name"));
     * }
     * return names;
     * };
     * }</pre>
     *
     * @param <T> result type
     */
    @FunctionalInterface
    public interface ResultExtractor<T> extends Throwables.Function<ResultSet, T, SQLException> {

        /**
         * A pre-defined {@code ResultExtractor} that converts a {@code ResultSet} to a {@code Dataset}.
         * If the {@code ResultSet} is {@code null}, it returns an empty {@code Dataset}.
         */
        ResultExtractor<Dataset> TO_DATA_SET = rs -> {
            if (rs == null) {
                return N.newEmptyDataset();
            }

            return JdbcUtil.extractData(rs);
        };

        /**
         * Extracts a result from the given {@code ResultSet}. The implementation is responsible
         * for iterating through the result set (e.g., using a {@code while(rs.next())} loop)
         * and building the final return object.
         *
         * <p><b>Warning:</b> The input {@code ResultSet} will often be closed by the calling
         * framework immediately after this method completes. Do not return the {@code ResultSet}
         * or any resources tied to it that might become invalid after closing.</p>
         *
         * @param rs the {@code ResultSet} to extract data from; may be {@code null}.
         * @return the extracted result.
         * @throws SQLException if a database access error occurs.
         */
        @Override
        T apply(ResultSet rs) throws SQLException;

        /**
         * Returns a composed {@code ResultExtractor} that first applies this extractor to
         * the {@code ResultSet} and then applies the {@code after} function to the result.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * ResultExtractor<List<User>> userListExtractor = ...;
         * // Creates an extractor that returns the count of users.
         * ResultExtractor<Integer> userCountExtractor = userListExtractor.andThen(List::size);
         * }</pre>
         *
         * @param <R> result type of the {@code after} function
         * @param after the function to apply after this extractor is applied
         * @return a composed {@code ResultExtractor}
         * @throws IllegalArgumentException if {@code after} is null
         */
        default <R> ResultExtractor<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return rs -> after.apply(apply(rs));
        }

        /**
         * Converts this {@code ResultExtractor} to a {@code BiResultExtractor}.
         * The resulting extractor will ignore the {@code columnLabels} parameter provided to it.
         *
         * @return a {@code BiResultExtractor} that delegates to this extractor.
         */
        default BiResultExtractor<T> toBiResultExtractor() {
            return (rs, columnLabels) -> this.apply(rs);
        }

        /**
         * Creates a {@code ResultExtractor} that processes a {@code ResultSet} into a {@code Map}.
         * Each row is mapped to a key-value pair. If duplicate keys are encountered, an
         * {@code IllegalStateException} will be thrown.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * ResultExtractor<Map<Integer, String>> idToNameMapExtractor = ResultExtractor.toMap(
         * rs -> rs.getInt("id"),
         * rs -> rs.getString("name")
         * );
         * }</pre>
         *
         * @param <K> map key type
         * @param <V> map value type
         * @param keyExtractor a {@code RowMapper} to extract the key from each row
         * @param valueExtractor a {@code RowMapper} to extract the value from each row
         * @return a {@code ResultExtractor} that produces a {@code Map}
         * @see #toMap(RowMapper, RowMapper, BinaryOperator)
         */
        static <K, V> ResultExtractor<Map<K, V>> toMap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor) {
            return toMap(keyExtractor, valueExtractor, Suppliers.ofMap());
        }

        /**
         * Creates a {@code ResultExtractor} that processes a {@code ResultSet} into a custom {@code Map}.
         * Each row is mapped to a key-value pair. If duplicate keys are encountered, an
         * {@code IllegalStateException} will be thrown.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * ResultExtractor<LinkedHashMap<Integer, String>> extractor = ResultExtractor.toMap(
         * rs -> rs.getInt("id"),
         * rs -> rs.getString("name"),
         * LinkedHashMap::new
         * );
         * }</pre>
         *
         * @param <K> map key type
         * @param <V> map value type
         * @param <M> concrete map implementation type
         * @param keyExtractor a {@code RowMapper} to extract the key from each row
         * @param valueExtractor a {@code RowMapper} to extract the value from each row
         * @param supplier a {@code Supplier} that provides a new, empty {@code Map} instance
         * @return a {@code ResultExtractor} that produces a custom {@code Map}
         */
        static <K, V, M extends Map<K, V>> ResultExtractor<M> toMap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final Supplier<? extends M> supplier) {
            return toMap(keyExtractor, valueExtractor, Fn.throwingMerger(), supplier);
        }

        /**
         * Creates a {@code ResultExtractor} that processes a {@code ResultSet} into a {@code Map},
         * with a specified function to merge values of duplicate keys.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Sums values for the same category key.
         * ResultExtractor<Map<String, Integer>> categorySumExtractor = ResultExtractor.toMap(
         * rs -> rs.getString("category"),
         * rs -> rs.getInt("value"),
         * Integer::sum
         * );
         * }</pre>
         *
         * @param <K> map key type
         * @param <V> map value type
         * @param keyExtractor a {@code RowMapper} to extract the key from each row
         * @param valueExtractor a {@code RowMapper} to extract the value from each row
         * @param mergeFunction a function to resolve collisions between values associated with the same key
         * @return a {@code ResultExtractor} that produces a {@code Map}
         * @see Fn#throwingMerger()
         * @see Fn#replacingMerger()
         * @see Fn#ignoringMerger()
         */
        static <K, V> ResultExtractor<Map<K, V>> toMap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final BinaryOperator<V> mergeFunction) {
            return toMap(keyExtractor, valueExtractor, mergeFunction, Suppliers.ofMap());
        }

        /**
         * Creates a {@code ResultExtractor} that processes a {@code ResultSet} into a custom {@code Map},
         * with a specified function to merge values of duplicate keys.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Groups numbers by their parity, collecting them into lists.
         * ResultExtractor<TreeMap<String, List<Integer>>> parityGroupExtractor = ResultExtractor.toMap(
         * rs -> rs.getInt("num") % 2 == 0 ? "EVEN" : "ODD",
         * rs -> new ArrayList<>(List.of(rs.getInt("num"))),
         * (list1, list2) -> { list1.addAll(list2); return list1; },
         * TreeMap::new
         * );
         * }</pre>
         *
         * @param <K> map key type
         * @param <V> map value type
         * @param <M> resulting map type
         * @param keyExtractor a {@code RowMapper} to extract the key from each row
         * @param valueExtractor a {@code RowMapper} to extract the value from each row
         * @param mergeFunction a function to resolve collisions between values associated with the same key
         * @param supplier a {@code Supplier} that provides a new, empty {@code Map} instance
         * @return a {@code ResultExtractor} that produces a custom {@code Map}
         * @see Fn#throwingMerger()
         * @see Fn#replacingMerger()
         * @see Fn#ignoringMerger()
         */
        static <K, V, M extends Map<K, V>> ResultExtractor<M> toMap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final BinaryOperator<V> mergeFunction, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, cs.keyExtractor);
            N.checkArgNotNull(valueExtractor, cs.valueExtractor);
            N.checkArgNotNull(mergeFunction, cs.mergeFunction);
            N.checkArgNotNull(supplier, cs.supplier);

            return rs -> {
                final M result = supplier.get();

                while (rs.next()) {
                    JdbcUtil.merge(result, keyExtractor.apply(rs), valueExtractor.apply(rs), mergeFunction);
                }

                return result;
            };
        }

        /**
         * Creates a {@code ResultExtractor} that groups rows into a {@code Map} and applies a
         * downstream {@code Collector} to the values associated with each key. This is analogous
         * to {@code java.util.stream.Collectors.groupingBy}.
         *
         * @param <K> map key type
         * @param <V> input value type for downstream collector
         * @param <D> result type of downstream collector
         * @param keyExtractor a {@code RowMapper} to extract the key from each row
         * @param valueExtractor a {@code RowMapper} to extract the value from each row, which is then fed into the collector
         * @param downstream the {@code Collector} to process values associated with each key
         * @return a {@code ResultExtractor} that produces a {@code Map}
         * @see #groupTo(RowMapper, RowMapper, Collector)
         * @deprecated Replaced by {@link #groupTo(RowMapper, RowMapper, Collector)} which has a more descriptive name.
         */
        @Deprecated
        static <K, V, D> ResultExtractor<Map<K, D>> toMap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final Collector<? super V, ?, D> downstream) {
            return toMap(keyExtractor, valueExtractor, downstream, Suppliers.ofMap());
        }

        /**
         * Creates a {@code ResultExtractor} that groups rows into a custom {@code Map} and applies a
         * downstream {@code Collector} to the values associated with each key.
         *
         * @param <K> map key type
         * @param <V> input value type for downstream collector
         * @param <D> result type of downstream collector
         * @param <M> resulting map type
         * @param keyExtractor a {@code RowMapper} to extract the key from each row
         * @param valueExtractor a {@code RowMapper} to extract the value from each row, which is then fed into the collector
         * @param downstream the {@code Collector} to process values associated with each key
         * @param supplier a {@code Supplier} that provides a new, empty {@code Map} instance
         * @return a {@code ResultExtractor} that produces a custom {@code Map}
         * @see #groupTo(RowMapper, RowMapper, Collector, Supplier)
         * @deprecated Replaced by {@link #groupTo(RowMapper, RowMapper, Collector, Supplier)} which has a more descriptive name.
         */
        @Deprecated
        static <K, V, D, M extends Map<K, D>> ResultExtractor<M> toMap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final Collector<? super V, ?, D> downstream, final Supplier<? extends M> supplier) {
            return groupTo(keyExtractor, valueExtractor, downstream, supplier);
        }

        /**
         * Creates a {@code ResultExtractor} that groups rows into a {@code ListMultimap}, where
         * each key can be associated with multiple values.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Groups users by their department.
         * ResultExtractor<ListMultimap<String, User>> extractor = ResultExtractor.toMultimap(
         * rs -> rs.getString("department"),
         * rs -> new User(rs.getString("name"), rs.getInt("age"))
         * );
         * }</pre>
         *
         * @param <K> map key type
         * @param <V> map value type
         * @param keyExtractor a {@code RowMapper} to extract the key from each row
         * @param valueExtractor a {@code RowMapper} to extract the value from each row
         * @return a {@code ResultExtractor} that produces a {@code ListMultimap}
         */
        static <K, V> ResultExtractor<ListMultimap<K, V>> toMultimap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor) {
            return toMultimap(keyExtractor, valueExtractor, Suppliers.ofListMultimap());
        }

        /**
         * Creates a {@code ResultExtractor} that groups rows into a custom {@code Multimap}.
         * This allows using different collection types for values, such as a {@code Set}.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Groups unique user IDs by department.
         * ResultExtractor<SetMultimap<String, Integer>> extractor = ResultExtractor.toMultimap(
         * rs -> rs.getString("department"),
         * rs -> rs.getInt("user_id"),
         * Suppliers.ofSetMultimap()
         * );
         * }</pre>
         *
         * @param <K> map key type
         * @param <V> map value type
         * @param <C> collection type for grouped values (e.g., {@code List<V>}, {@code Set<V>})
         * @param <M> concrete multimap implementation type
         * @param keyExtractor a {@code RowMapper} to extract the key from each row
         * @param valueExtractor a {@code RowMapper} to extract the value from each row
         * @param multimapSupplier a {@code Supplier} that provides a new, empty {@code Multimap} instance
         * @return a {@code ResultExtractor} that produces a custom {@code Multimap}
         */
        static <K, V, C extends Collection<V>, M extends Multimap<K, V, C>> ResultExtractor<M> toMultimap(final RowMapper<? extends K> keyExtractor,
                final RowMapper<? extends V> valueExtractor, final Supplier<? extends M> multimapSupplier) {
            N.checkArgNotNull(keyExtractor, cs.keyExtractor);
            N.checkArgNotNull(valueExtractor, cs.valueExtractor);
            N.checkArgNotNull(multimapSupplier, cs.multimapSupplier);

            return rs -> {
                final M result = multimapSupplier.get();

                while (rs.next()) {
                    result.put(keyExtractor.apply(rs), valueExtractor.apply(rs));
                }

                return result;
            };
        }

        /**
         * Creates a {@code ResultExtractor} that groups rows into a {@code Map} where each key is
         * associated with a {@code List} of values. This is a common grouping operation.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * ResultExtractor<Map<String, List<User>>> extractor = ResultExtractor.groupTo(
         * rs -> rs.getString("department"),
         * rs -> new User(rs.getString("name"), rs.getInt("age"))
         * );
         * }</pre>
         *
         * @param <K> map key type
         * @param <V> map value type
         * @param keyExtractor a {@code RowMapper} to extract the key from each row
         * @param valueExtractor a {@code RowMapper} to extract the value from each row
         * @return a {@code ResultExtractor} that produces a {@code Map} with {@code List} values
         */
        static <K, V> ResultExtractor<Map<K, List<V>>> groupTo(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor) {
            return groupTo(keyExtractor, valueExtractor, Suppliers.ofMap());
        }

        /**
         * Creates a {@code ResultExtractor} that groups rows into a custom {@code Map} where each key is
         * associated with a {@code List} of values.
         *
         * @param <K> map key type
         * @param <V> map value type
         * @param <M> concrete map implementation type
         * @param keyExtractor a {@code RowMapper} to extract the key from each row
         * @param valueExtractor a {@code RowMapper} to extract the value from each row
         * @param supplier a {@code Supplier} that provides a new, empty {@code Map} instance
         * @return a {@code ResultExtractor} that produces a custom {@code Map} with {@code List} values
         */
        static <K, V, M extends Map<K, List<V>>> ResultExtractor<M> groupTo(final RowMapper<? extends K> keyExtractor,
                final RowMapper<? extends V> valueExtractor, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, cs.keyExtractor);
            N.checkArgNotNull(valueExtractor, cs.valueExtractor);
            N.checkArgNotNull(supplier, cs.supplier);

            return rs -> {
                final M result = supplier.get();
                K key = null;
                List<V> value = null;

                while (rs.next()) {
                    key = keyExtractor.apply(rs);
                    value = result.computeIfAbsent(key, k -> new ArrayList<>());

                    value.add(valueExtractor.apply(rs));
                }

                return result;
            };
        }

        /**
         * Creates a {@code ResultExtractor} that groups rows into a {@code Map} and applies a
         * downstream {@code Collector} to the values associated with each key.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Calculates the average amount per category.
         * ResultExtractor<Map<String, Double>> extractor = ResultExtractor.groupTo(
         * rs -> rs.getString("category"),
         * rs -> rs.getDouble("amount"),
         * Collectors.averagingDouble(Double::doubleValue)
         * );
         * }</pre>
         *
         * @param <K> map key type
         * @param <V> input value type for downstream collector
         * @param <D> result type of downstream collector
         * @param keyExtractor a {@code RowMapper} to extract the key from each row
         * @param valueExtractor a {@code RowMapper} to extract the value from each row
         * @param downstream the {@code Collector} for aggregating values associated with each key
         * @return a {@code ResultExtractor} that produces a {@code Map} with collected values
         */
        static <K, V, D> ResultExtractor<Map<K, D>> groupTo(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final Collector<? super V, ?, D> downstream) {
            return groupTo(keyExtractor, valueExtractor, downstream, Suppliers.ofMap());
        }

        /**
         * Creates a {@code ResultExtractor} that groups rows into a custom {@code Map} and applies a
         * downstream {@code Collector} to the values associated with each key.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Counts the number of items per category, storing results in a TreeMap.
         * ResultExtractor<TreeMap<String, Long>> extractor = ResultExtractor.groupTo(
         * rs -> rs.getString("category"),
         * rs -> rs.getInt("item_id");, // Value extractor can be anything, it's just for the collector
         * Collectors.counting(),
         * TreeMap::new
         * );
         * }</pre>
         *
         * @param <K> map key type
         * @param <V> input value type for downstream collector
         * @param <D> result type of downstream collector
         * @param <M> concrete map implementation type
         * @param keyExtractor a {@code RowMapper} to extract the key from each row
         * @param valueExtractor a {@code RowMapper} to extract the value from each row
         * @param downstream the {@code Collector} for aggregating values associated with each key
         * @param supplier a {@code Supplier} that provides a new, empty {@code Map} instance
         * @return a {@code ResultExtractor} that produces a custom {@code Map} with collected values
         */
        static <K, V, D, M extends Map<K, D>> ResultExtractor<M> groupTo(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final Collector<? super V, ?, D> downstream, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, cs.keyExtractor);
            N.checkArgNotNull(valueExtractor, cs.valueExtractor);
            N.checkArgNotNull(downstream, cs.downstream);
            N.checkArgNotNull(supplier, cs.supplier);

            return rs -> {
                final Supplier<Object> downstreamSupplier = (Supplier<Object>) downstream.supplier();
                final BiConsumer<Object, ? super V> downstreamAccumulator = (BiConsumer<Object, ? super V>) downstream.accumulator();
                final Function<Object, D> downstreamFinisher = (Function<Object, D>) downstream.finisher();

                final M result = supplier.get();
                final Map<K, Object> tmp = (Map<K, Object>) result;
                K key = null;
                Object container = null;

                while (rs.next()) {
                    key = keyExtractor.apply(rs);
                    container = tmp.get(key);

                    if (container == null) {
                        container = downstreamSupplier.get();
                        tmp.put(key, container);
                    }

                    downstreamAccumulator.accept(container, valueExtractor.apply(rs));
                }

                for (final Map.Entry<K, D> entry : result.entrySet()) {
                    entry.setValue(downstreamFinisher.apply(entry.getValue()));
                }

                return result;
            };
        }

        /**
         * Creates a {@code ResultExtractor} that converts a {@code ResultSet} into a {@code List}
         * of objects, where each object is created by applying the given {@code rowMapper} to each row.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * ResultExtractor<List<String>> nameListExtractor = ResultExtractor.toList(
         * rs -> rs.getString("name")
         * );
         * }</pre>
         *
         * @param <T> list element type
         * @param rowMapper the function to map each row to an element
         * @return a {@code ResultExtractor} that produces a {@code List}
         */
        static <T> ResultExtractor<List<T>> toList(final RowMapper<? extends T> rowMapper) {
            return toList(RowFilter.ALWAYS_TRUE, rowMapper);
        }

        /**
         * Creates a {@code ResultExtractor} that converts a {@code ResultSet} into a {@code List}
         * of objects, including only the rows that satisfy the {@code rowFilter}.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Extracts a list of users who are 18 or older.
         * ResultExtractor<List<User>> adultUserExtractor = ResultExtractor.toList(
         * rs -> rs.getInt("age") >= 18,
         * rs -> new User(rs.getString("name"), rs.getInt("age"))
         * );
         * }</pre>
         *
         * @param <T> list element type
         * @param rowFilter a predicate to filter rows from the result set
         * @param rowMapper the function to map each accepted row to an element
         * @return a {@code ResultExtractor} that produces a filtered {@code List}
         */
        static <T> ResultExtractor<List<T>> toList(final RowFilter rowFilter, final RowMapper<? extends T> rowMapper) {
            N.checkArgNotNull(rowFilter, cs.rowFilter);
            N.checkArgNotNull(rowMapper, cs.rowMapper);

            return rs -> {
                final List<T> result = new ArrayList<>();

                while (rs.next()) {
                    if (rowFilter.test(rs)) {
                        result.add(rowMapper.apply(rs));
                    }
                }

                return result;
            };
        }

        /**
         * Creates a {@code ResultExtractor} that converts a {@code ResultSet} into a {@code List} of entities.
         * The mapping from columns to entity properties is done automatically based on column names
         * and property names in the {@code targetClass}.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Assumes User class has properties matching column names (e.g., 'id', 'name').
         * ResultExtractor<List<User>> userListExtractor = ResultExtractor.toList(User.class);
         * }</pre>
         *
         * @param <T> entity type
         * @param targetClass the class of the entities to be created
         * @return a {@code ResultExtractor} that produces a {@code List} of entities
         * @see BiResultExtractor#toList(Class)
         */
        static <T> ResultExtractor<List<T>> toList(final Class<? extends T> targetClass) {
            N.checkArgNotNull(targetClass, cs.targetClass);

            return rs -> {
                final BiRowMapper<? extends T> rowMapper = BiRowMapper.to(targetClass);
                final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);
                final List<T> result = new ArrayList<>();

                while (rs.next()) {
                    result.add(rowMapper.apply(rs, columnLabels));
                }

                return result;
            };

        }

        /**
         * Creates a {@code ResultExtractor} that converts a {@code ResultSet} from a JOIN query
         * into a {@code List} of merged entities. It groups rows by the primary entity's ID
         * and merges related data (e.g., from one-to-many relationships) into collections
         * within each primary entity.
         *
         * <p>This method assumes the {@code targetClass} has identifiable ID properties (e.g., annotated
         * with {@code @Id}).</p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // For a query like "SELECT u.*, o.* FROM users u JOIN orders o ON u.id = o.user_id"
         * // where User has a List<Order> property.
         * ResultExtractor<List<User>> extractor = ResultExtractor.toMergedList(User.class);
         * }</pre>
         *
         * @param <T> entity type
         * @param targetClass the class of the entities to create and merge
         * @return a {@code ResultExtractor} that produces a {@code List} of merged entities
         * @see Dataset#toMergedEntities(Class)
         */
        static <T> ResultExtractor<List<T>> toMergedList(final Class<? extends T> targetClass) {
            N.checkArgNotNull(targetClass, cs.targetClass);

            return rs -> {
                final RowExtractor rowExtractor = RowExtractor.createBy(targetClass);

                return JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, rowExtractor, false).toMergedEntities(targetClass);
            };
        }

        /**
         * Creates a {@code ResultExtractor} that converts a {@code ResultSet} into a {@code List} of
         * merged entities, using a specific property to identify unique entities for merging.
         * This is useful when the default ID detection is not sufficient.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Merge User entities based on the 'email' property instead of 'id'.
         * ResultExtractor<List<User>> extractor = ResultExtractor.toMergedList(User.class, "email");
         * }</pre>
         *
         * @param <T> entity type
         * @param targetClass the class of the entities to create and merge
         * @param idPropNameForMerge the property name to use for identifying unique entities to merge
         * @return a {@code ResultExtractor} that produces a {@code List} of merged entities
         * @see Dataset#toMergedEntities(Collection, Collection, Class)
         */
        static <T> ResultExtractor<List<T>> toMergedList(final Class<? extends T> targetClass, final String idPropNameForMerge) {
            N.checkArgNotNull(targetClass, cs.targetClass);

            return rs -> {
                final RowExtractor rowExtractor = RowExtractor.createBy(targetClass);

                return JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, rowExtractor, false).toMergedEntities(idPropNameForMerge, targetClass);
            };
        }

        /**
         * Creates a {@code ResultExtractor} that converts a {@code ResultSet} into a {@code List} of
         * merged entities, using a composite key (multiple properties) to identify unique entities for merging.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Merge Order entities based on a composite key of 'customerId' and 'orderDate'.
         * ResultExtractor<List<Order>> extractor = ResultExtractor.toMergedList(
         * Order.class,
         * List.of("customerId", "orderDate")
         * );
         * }</pre>
         *
         * @param <T> entity type
         * @param targetClass the class of the entities to create and merge
         * @param idPropNamesForMerge the collection of property names that form the composite key for merging
         * @return a {@code ResultExtractor} that produces a {@code List} of merged entities
         * @see Dataset#toMergedEntities(Collection, Collection, Class)
         */
        static <T> ResultExtractor<List<T>> toMergedList(final Class<? extends T> targetClass, final Collection<String> idPropNamesForMerge) {
            N.checkArgNotNull(targetClass, cs.targetClass);

            return rs -> {
                final RowExtractor rowExtractor = RowExtractor.createBy(targetClass);

                return JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, rowExtractor, false).toMergedEntities(idPropNamesForMerge, targetClass);
            };
        }

        /**
         * Creates a {@code ResultExtractor} that converts a {@code ResultSet} into a {@code Dataset}.
         * Column types within the {@code Dataset} are inferred from the properties of the provided {@code entityClassForExtractor}.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // The resulting Dataset will have columns with types matching User properties.
         * ResultExtractor<Dataset> extractor = ResultExtractor.toDataset(User.class);
         * }</pre>
         *
         * @param entityClassForExtractor the class used to map column names to property types
         * @return a {@code ResultExtractor} that produces a {@code Dataset}
         */
        static ResultExtractor<Dataset> toDataset(final Class<?> entityClassForExtractor) {
            return rs -> JdbcUtil.extractData(rs, RowExtractor.createBy(entityClassForExtractor));
        }

        /**
         * Creates a {@code ResultExtractor} that converts a {@code ResultSet} into a {@code Dataset}
         * with custom field name mapping for nested objects. This is useful for JOIN queries
         * where column names might have prefixes.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Maps 'u_id' to 'user.id', 'u_name' to 'user.name', etc.
         * Map<String, String> prefixMap = Map.of("u_", "user.");
         * ResultExtractor<Dataset> extractor = ResultExtractor.toDataset(User.class, prefixMap);
         * }</pre>
         *
         * @param entityClassForExtractor the class used to map fields from columns
         * @param prefixAndFieldNameMap a map where keys are column prefixes and values are field name prefixes for dot notation
         * @return a {@code ResultExtractor} that produces a {@code Dataset}
         */
        static ResultExtractor<Dataset> toDataset(final Class<?> entityClassForExtractor, final Map<String, String> prefixAndFieldNameMap) {
            return rs -> JdbcUtil.extractData(rs, RowExtractor.createBy(entityClassForExtractor, prefixAndFieldNameMap));
        }

        /**
         * Creates a {@code ResultExtractor} that converts a {@code ResultSet} into a {@code Dataset},
         * including only the rows that satisfy the specified filter.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Creates a Dataset containing only active records.
         * ResultExtractor<Dataset> extractor = ResultExtractor.toDataset(
         * rs -> rs.getBoolean("is_active")
         * );
         * }</pre>
         *
         * @param rowFilter a predicate to filter rows
         * @return a {@code ResultExtractor} that produces a filtered {@code Dataset}
         */
        static ResultExtractor<Dataset> toDataset(final RowFilter rowFilter) {
            return rs -> JdbcUtil.extractData(rs, rowFilter);
        }

        /**
         * Creates a {@code ResultExtractor} that converts a {@code ResultSet} into a {@code Dataset}
         * using a custom {@code RowExtractor} for fine-grained control over value extraction.
         *
         * @param rowExtractor the custom row extractor to process each row
         * @return a {@code ResultExtractor} that produces a {@code Dataset}
         */
        static ResultExtractor<Dataset> toDataset(final RowExtractor rowExtractor) {
            return rs -> JdbcUtil.extractData(rs, rowExtractor);
        }

        /**
         * Creates a {@code ResultExtractor} that converts a {@code ResultSet} into a {@code Dataset}
         * using both a filter and a custom row extractor.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * ResultExtractor<Dataset> extractor = ResultExtractor.toDataset(
         * rs -> rs.getInt("status");           // Filter for positive status
         * RowExtractor.createBy(User.class);   // Use User class for type info
         * );
         * }</pre>
         *
         * @param rowFilter a predicate to filter rows
         * @param rowExtractor the custom row extractor to process each accepted row
         * @return a {@code ResultExtractor} that produces a filtered {@code Dataset}
         */
        static ResultExtractor<Dataset> toDataset(final RowFilter rowFilter, final RowExtractor rowExtractor) {
            return rs -> JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, rowFilter, rowExtractor, false);
        }

        /**
         * Creates a {@code ResultExtractor} that first converts the {@code ResultSet} to a {@code Dataset}
         * and then applies a transformation function to it.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Converts the ResultSet to a Dataset, then to a List of Users.
         * ResultExtractor<List<User>> extractor = ResultExtractor.to(
         * dataset -> dataset.toList(User.class)
         * );
         * }</pre>
         *
         * @param <R> final result type
         * @param after the function to apply to the intermediate {@code Dataset}
         * @return a {@code ResultExtractor} that produces the transformed result
         */
        static <R> ResultExtractor<R> to(final Throwables.Function<Dataset, R, SQLException> after) {
            return rs -> after.apply(TO_DATA_SET.apply(rs));
        }
    }

    /**
     * A functional interface for extracting a result from a {@code ResultSet}, with access to
     * the list of column labels from the result set's metadata. This is useful when the
     * extraction logic needs to be dynamic based on the columns present in the result.
     *
     * <p><b>Important Note:</b> Like {@code ResultExtractor}, the {@code ResultSet} passed to
     * the {@code apply} method will typically be closed automatically after the method returns.
     * Do not attempt to return or store the {@code ResultSet} itself.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Example: Extracting a map where keys are column names
     * BiResultExtractor<Map<String, Object>> dynamicExtractor = (rs, columnLabels) -> {
     *     Map<String, Object> firstRow = new HashMap<>();
     *     if (rs.next()) {
     *         for (int i = 0; i < columnLabels.size(); i++) {
     *             firstRow.put(columnLabels.get(i), rs.getObject(i + 1));
     *         }
     *     }
     *     return firstRow;
     * };
     * }</pre>
     *
     * @param <T> result type
     */
    @FunctionalInterface
    public interface BiResultExtractor<T> extends Throwables.BiFunction<ResultSet, List<String>, T, SQLException> {

        /**
         * A pre-defined {@code BiResultExtractor} that converts a {@code ResultSet} to a {@code Dataset}.
         * If the {@code ResultSet} is {@code null}, it returns an empty {@code Dataset}.
         */
        BiResultExtractor<Dataset> TO_DATA_SET = (rs, columnLabels) -> {
            if (rs == null) {
                return N.newEmptyDataset();
            }

            return JdbcUtil.extractData(rs);
        };

        /**
         * Extracts a result from the given {@code ResultSet} using column label information.
         *
         * <p><b>Warning:</b> The input {@code ResultSet} will often be closed by the calling
         * framework immediately after this method completes. Do not return the {@code ResultSet}
         * or any resources tied to it.</p>
         *
         * @param rs the {@code ResultSet} to extract data from
         * @param columnLabels the list of column labels from the result set's metadata
         * @return the extracted result
         * @throws SQLException if a database access error occurs
         */
        @Override
        T apply(ResultSet rs, List<String> columnLabels) throws SQLException;

        /**
         * Returns a composed {@code BiResultExtractor} that first applies this extractor to
         * the {@code ResultSet} and then applies the {@code after} function to the result.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * BiResultExtractor<List<User>> userListExtractor = ...;
         * // Creates an extractor that returns the count of users.
         * BiResultExtractor<Integer> userCountExtractor = userListExtractor.andThen(List::size);
         * }</pre>
         *
         * @param <R> result type of the {@code after} function
         * @param after the function to apply after this extractor is applied
         * @return a composed {@code BiResultExtractor}
         * @throws IllegalArgumentException if {@code after} is null
         */
        default <R> BiResultExtractor<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return (rs, columnLabels) -> after.apply(apply(rs, columnLabels));
        }

        /**
         * Creates a {@code BiResultExtractor} that processes a {@code ResultSet} into a {@code Map}.
         * Each row is mapped to a key-value pair. Throws an {@code IllegalStateException} on duplicate keys.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * BiResultExtractor<Map<Integer, String>> extractor = BiResultExtractor.toMap(
         * (rs, cols) -> rs.getInt("id"),
         * (rs, cols) -> rs.getString("name")
         * );
         * }</pre>
         *
         * @param <K> map key type
         * @param <V> map value type
         * @param keyExtractor a {@code BiRowMapper} to extract the key from each row
         * @param valueExtractor a {@code BiRowMapper} to extract the value from each row
         * @return a {@code BiResultExtractor} that produces a {@code Map}
         */
        static <K, V> BiResultExtractor<Map<K, V>> toMap(final BiRowMapper<? extends K> keyExtractor, final BiRowMapper<? extends V> valueExtractor) {
            return toMap(keyExtractor, valueExtractor, Suppliers.ofMap());
        }

        /**
         * Creates a {@code BiResultExtractor} that processes a {@code ResultSet} into a custom {@code Map}.
         * Each row is mapped to a key-value pair. Throws an {@code IllegalStateException} on duplicate keys.
         * This method allows specifying a custom map implementation (e.g., LinkedHashMap, TreeMap).
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Create a LinkedHashMap to preserve insertion order
         * BiResultExtractor<LinkedHashMap<Integer, String>> extractor = BiResultExtractor.toMap(
         *     (rs, cols) -> rs.getInt("id"),
         *     (rs, cols) -> rs.getString("name"),
         *     LinkedHashMap::new
         * );
         * }</pre>
         *
         * @param <K> map key type
         * @param <V> map value type
         * @param <M> concrete map implementation type
         * @param keyExtractor a {@code BiRowMapper} to extract the key from each row
         * @param valueExtractor a {@code BiRowMapper} to extract the value from each row
         * @param supplier a {@code Supplier} that provides a new, empty {@code Map} instance
         * @return a {@code BiResultExtractor} that produces a custom {@code Map}
         */
        static <K, V, M extends Map<K, V>> BiResultExtractor<M> toMap(final BiRowMapper<? extends K> keyExtractor,
                final BiRowMapper<? extends V> valueExtractor, final Supplier<? extends M> supplier) {
            return toMap(keyExtractor, valueExtractor, Fn.throwingMerger(), supplier);
        }

        /**
         * Creates a {@code BiResultExtractor} that processes a {@code ResultSet} into a {@code Map},
         * with a specified function to merge values of duplicate keys.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * BiResultExtractor<Map<String, Integer>> extractor = BiResultExtractor.toMap(
         * (rs, cols) -> rs.getString("category"),
         * (rs, cols) -> rs.getInt("value"),
         * Integer::sum // Sum values for duplicate keys
         * );
         * }</pre>
         *
         * @param <K> map key type
         * @param <V> map value type
         * @param keyExtractor a {@code BiRowMapper} to extract the key from each row
         * @param valueExtractor a {@code BiRowMapper} to extract the value from each row
         * @param mergeFunction a function to resolve collisions for the same key
         * @return a {@code BiResultExtractor} that produces a {@code Map}
         * @see Fn#throwingMerger()
         * @see Fn#replacingMerger()
         * @see Fn#ignoringMerger()
         */
        static <K, V> BiResultExtractor<Map<K, V>> toMap(final BiRowMapper<? extends K> keyExtractor, final BiRowMapper<? extends V> valueExtractor,
                final BinaryOperator<V> mergeFunction) {
            return toMap(keyExtractor, valueExtractor, mergeFunction, Suppliers.ofMap());
        }

        /**
         * Creates a {@code BiResultExtractor} that processes a {@code ResultSet} into a custom {@code Map},
         * with a specified function to merge values of duplicate keys. This is the most flexible toMap variant
         * for BiResultExtractor, allowing both custom map type and duplicate key handling.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Sum values for duplicate keys, storing in a TreeMap
         * BiResultExtractor<TreeMap<String, Integer>> extractor = BiResultExtractor.toMap(
         *     (rs, cols) -> rs.getString("category"),
         *     (rs, cols) -> rs.getInt("amount"),
         *     Integer::sum,
         *     TreeMap::new
         * );
         * }</pre>
         *
         * @param <K> map key type
         * @param <V> map value type
         * @param <M> concrete map implementation type
         * @param keyExtractor a {@code BiRowMapper} to extract the key from each row
         * @param valueExtractor a {@code BiRowMapper} to extract the value from each row
         * @param mergeFunction a function to resolve collisions for the same key
         * @param supplier a {@code Supplier} that provides a new, empty {@code Map} instance
         * @return a {@code BiResultExtractor} that produces a custom {@code Map}
         * @see Fn#throwingMerger()
         * @see Fn#replacingMerger()
         * @see Fn#ignoringMerger()
         */
        static <K, V, M extends Map<K, V>> BiResultExtractor<M> toMap(final BiRowMapper<? extends K> keyExtractor,
                final BiRowMapper<? extends V> valueExtractor, final BinaryOperator<V> mergeFunction, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, cs.keyExtractor);
            N.checkArgNotNull(valueExtractor, cs.valueExtractor);
            N.checkArgNotNull(mergeFunction, cs.mergeFunction);
            N.checkArgNotNull(supplier, cs.supplier);

            return (rs, columnLabels) -> {
                final M result = supplier.get();

                while (rs.next()) {
                    JdbcUtil.merge(result, keyExtractor.apply(rs, columnLabels), valueExtractor.apply(rs, columnLabels), mergeFunction);
                }

                return result;
            };
        }

        /**
         * Creates a {@code BiResultExtractor} that groups rows into a {@code Map} and applies a
         * downstream {@code Collector} to the values associated with each key.
         *
         * @param <K> map key type
         * @param <V> input value type for downstream collector
         * @param <D> result type of downstream collector
         * @param keyExtractor a {@code BiRowMapper} to extract the key from each row
         * @param valueExtractor a {@code BiRowMapper} to extract the value from each row
         * @param downstream the {@code Collector} to process values for each key
         * @return a {@code BiResultExtractor} that produces a {@code Map}
         * @see #groupTo(BiRowMapper, BiRowMapper, Collector)
         * @deprecated Replaced by {@link #groupTo(BiRowMapper, BiRowMapper, Collector)} which has a more descriptive name.
         */
        @Deprecated
        static <K, V, D> BiResultExtractor<Map<K, D>> toMap(final BiRowMapper<? extends K> keyExtractor, final BiRowMapper<? extends V> valueExtractor,
                final Collector<? super V, ?, D> downstream) {
            return toMap(keyExtractor, valueExtractor, downstream, Suppliers.ofMap());
        }

        /**
         * Creates a {@code BiResultExtractor} that groups rows into a custom {@code Map} and applies a
         * downstream {@code Collector} to the values associated with each key.
         *
         * @param <K> map key type
         * @param <V> input value type for downstream collector
         * @param <D> result type of downstream collector
         * @param <M> concrete map implementation type
         * @param keyExtractor a {@code BiRowMapper} to extract the key from each row
         * @param valueExtractor a {@code BiRowMapper} to extract the value from each row
         * @param downstream the {@code Collector} to process values for each key
         * @param supplier a {@code Supplier} that provides a new, empty {@code Map} instance
         * @return a {@code BiResultExtractor} that produces a custom {@code Map}
         * @see #groupTo(BiRowMapper, BiRowMapper, Collector, Supplier)
         * @deprecated Replaced by {@link #groupTo(BiRowMapper, BiRowMapper, Collector, Supplier)} which has a more descriptive name.
         */
        @Deprecated
        static <K, V, D, M extends Map<K, D>> BiResultExtractor<M> toMap(final BiRowMapper<? extends K> keyExtractor,
                final BiRowMapper<? extends V> valueExtractor, final Collector<? super V, ?, D> downstream, final Supplier<? extends M> supplier) {
            return groupTo(keyExtractor, valueExtractor, downstream, supplier);
        }

        /**
         * Creates a {@code BiResultExtractor} that groups rows into a {@code ListMultimap}, where
         * each key can be associated with multiple values.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * BiResultExtractor<ListMultimap<String, Integer>> extractor = BiResultExtractor.toMultimap(
         * (rs, cols) -> rs.getString("category"),
         * (rs, cols) -> rs.getInt("value")
         * );
         * }</pre>
         *
         * @param <K> map key type
         * @param <V> map value type
         * @param keyExtractor a {@code BiRowMapper} to extract the key from each row
         * @param valueExtractor a {@code BiRowMapper} to extract the value from each row
         * @return a {@code BiResultExtractor} that produces a {@code ListMultimap}
         */
        static <K, V> BiResultExtractor<ListMultimap<K, V>> toMultimap(final BiRowMapper<? extends K> keyExtractor,
                final BiRowMapper<? extends V> valueExtractor) {
            return toMultimap(keyExtractor, valueExtractor, Suppliers.ofListMultimap());
        }

        /**
         * Creates a {@code BiResultExtractor} that groups rows into a custom {@code Multimap}.
         *
         * @param <K> map key type
         * @param <V> map value type
         * @param <C> collection type for values
         * @param <M> concrete multimap implementation type
         * @param keyExtractor a {@code BiRowMapper} to extract the key from each row
         * @param valueExtractor a {@code BiRowMapper} to extract the value from each row
         * @param multimapSupplier a {@code Supplier} that provides a new, empty {@code Multimap} instance
         * @return a {@code BiResultExtractor} that produces a custom {@code Multimap}
         */
        static <K, V, C extends Collection<V>, M extends Multimap<K, V, C>> BiResultExtractor<M> toMultimap(final BiRowMapper<? extends K> keyExtractor,
                final BiRowMapper<? extends V> valueExtractor, final Supplier<? extends M> multimapSupplier) {
            N.checkArgNotNull(keyExtractor, cs.keyExtractor);
            N.checkArgNotNull(valueExtractor, cs.valueExtractor);
            N.checkArgNotNull(multimapSupplier, cs.multimapSupplier);

            return (rs, columnLabels) -> {
                final M result = multimapSupplier.get();

                while (rs.next()) {
                    result.put(keyExtractor.apply(rs, columnLabels), valueExtractor.apply(rs, columnLabels));
                }

                return result;
            };
        }

        /**
         * Creates a {@code BiResultExtractor} that groups rows into a {@code Map} where each key is
         * associated with a {@code List} of values.
         *
         * @param <K> map key type
         * @param <V> map value type
         * @param keyExtractor a {@code BiRowMapper} to extract the key from each row
         * @param valueExtractor a {@code BiRowMapper} to extract the value from each row
         * @return a {@code BiResultExtractor} that produces a {@code Map} with {@code List} values
         */
        static <K, V> BiResultExtractor<Map<K, List<V>>> groupTo(final BiRowMapper<? extends K> keyExtractor, final BiRowMapper<? extends V> valueExtractor) {
            return groupTo(keyExtractor, valueExtractor, Suppliers.ofMap());
        }

        /**
         * Creates a {@code BiResultExtractor} that groups rows into a custom {@code Map} where each key is
         * associated with a {@code List} of values.
         *
         * @param <K> map key type
         * @param <V> map value type
         * @param <M> concrete map implementation type
         * @param keyExtractor a {@code BiRowMapper} to extract the key from each row
         * @param valueExtractor a {@code BiRowMapper} to extract the value from each row
         * @param supplier a {@code Supplier} that provides a new, empty {@code Map} instance
         * @return a {@code BiResultExtractor} that produces a custom {@code Map} with {@code List} values
         */
        static <K, V, M extends Map<K, List<V>>> BiResultExtractor<M> groupTo(final BiRowMapper<? extends K> keyExtractor,
                final BiRowMapper<? extends V> valueExtractor, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, cs.keyExtractor);
            N.checkArgNotNull(valueExtractor, cs.valueExtractor);
            N.checkArgNotNull(supplier, cs.supplier);

            return (rs, columnLabels) -> {
                final M result = supplier.get();
                K key = null;
                List<V> value = null;

                while (rs.next()) {
                    key = keyExtractor.apply(rs, columnLabels);
                    value = result.computeIfAbsent(key, k -> new ArrayList<>());

                    value.add(valueExtractor.apply(rs, columnLabels));
                }

                return result;
            };
        }

        /**
         * Creates a {@code BiResultExtractor} that groups rows into a {@code Map} and applies a
         * downstream {@code Collector} to the values associated with each key.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * BiResultExtractor<Map<String, Double>> extractor = BiResultExtractor.groupTo(
         * (rs, cols) -> rs.getString("category"),
         * (rs, cols) -> rs.getDouble("amount"),
         * Collectors.summingDouble(Double::doubleValue)
         * );
         * }</pre>
         *
         * @param <K> map key type
         * @param <V> input value type for downstream collector
         * @param <D> result type of downstream collector
         * @param keyExtractor a {@code BiRowMapper} to extract the key from each row
         * @param valueExtractor a {@code BiRowMapper} to extract the value from each row
         * @param downstream the {@code Collector} for aggregating values associated with each key
         * @return a {@code BiResultExtractor} that produces a {@code Map} with collected values
         */
        static <K, V, D> BiResultExtractor<Map<K, D>> groupTo(final BiRowMapper<? extends K> keyExtractor, final BiRowMapper<? extends V> valueExtractor,
                final Collector<? super V, ?, D> downstream) {
            return groupTo(keyExtractor, valueExtractor, downstream, Suppliers.ofMap());
        }

        /**
         * Creates a {@code BiResultExtractor} that groups rows into a custom {@code Map} and applies a
         * downstream {@code Collector} to the values associated with each key.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * BiResultExtractor<TreeMap<String, Long>> extractor = BiResultExtractor.groupTo(
         * (rs, cols) -> rs.getString("category"),
         * (rs, cols) -> rs.getInt("count");, // Value extractor provides input to collector
         * Collectors.counting(),
         * TreeMap::new
         * );
         * }</pre>
         *
         * @param <K> map key type
         * @param <V> input value type for collector
         * @param <D> result type of downstream collector
         * @param <M> resulting map type
         * @param keyExtractor a {@code BiRowMapper} to extract the key from each row
         * @param valueExtractor a {@code BiRowMapper} to extract the value from each row
         * @param downstream the {@code Collector} for aggregating values associated with each key
         * @param supplier a {@code Supplier} that provides a new, empty {@code Map} instance
         * @return a {@code BiResultExtractor} that produces a custom {@code Map} with collected values
         */
        static <K, V, D, M extends Map<K, D>> BiResultExtractor<M> groupTo(final BiRowMapper<? extends K> keyExtractor,
                final BiRowMapper<? extends V> valueExtractor, final Collector<? super V, ?, D> downstream, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, cs.keyExtractor);
            N.checkArgNotNull(valueExtractor, cs.valueExtractor);
            N.checkArgNotNull(downstream, cs.downstream);
            N.checkArgNotNull(supplier, cs.supplier);

            return (rs, columnLabels) -> {

                final Supplier<Object> downstreamSupplier = (Supplier<Object>) downstream.supplier();
                final BiConsumer<Object, ? super V> downstreamAccumulator = (BiConsumer<Object, ? super V>) downstream.accumulator();
                final Function<Object, D> downstreamFinisher = (Function<Object, D>) downstream.finisher();

                final M result = supplier.get();
                final Map<K, Object> tmp = (Map<K, Object>) result;
                K key = null;
                Object container = null;

                while (rs.next()) {
                    key = keyExtractor.apply(rs, columnLabels);
                    container = tmp.get(key);

                    if (container == null) {
                        container = downstreamSupplier.get();
                        tmp.put(key, container);
                    }

                    downstreamAccumulator.accept(container, valueExtractor.apply(rs, columnLabels));
                }

                for (final Map.Entry<K, D> entry : result.entrySet()) {
                    entry.setValue(downstreamFinisher.apply(entry.getValue()));
                }

                return result;
            };
        }

        /**
         * Creates a {@code BiResultExtractor} that converts a {@code ResultSet} into a {@code List}
         * of objects, where each object is created by applying the given {@code rowMapper} to each row.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * BiResultExtractor<List<String>> extractor = BiResultExtractor.toList(
         * (rs, cols) -> rs.getString("name")
         * );
         * }</pre>
         *
         * @param <T> list element type
         * @param rowMapper the function to map each row to an element
         * @return a {@code BiResultExtractor} that produces a {@code List}
         */
        static <T> BiResultExtractor<List<T>> toList(final BiRowMapper<? extends T> rowMapper) {
            return toList(BiRowFilter.ALWAYS_TRUE, rowMapper);
        }

        /**
         * Creates a {@code BiResultExtractor} that converts a {@code ResultSet} into a {@code List}
         * of objects, including only the rows that satisfy the {@code rowFilter}.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * BiResultExtractor<List<User>> extractor = BiResultExtractor.toList(
         * (rs, cols) -> rs.getInt("age"); >= 18,  // Filter for adults
         * (rs, cols) -> new User(rs.getString("name"), rs.getInt("age"))
         * );
         * }</pre>
         *
         * @param <T> list element type
         * @param rowFilter a predicate to filter rows
         * @param rowMapper the function to map each accepted row to an element
         * @return a {@code BiResultExtractor} that produces a filtered {@code List}
         */
        static <T> BiResultExtractor<List<T>> toList(final BiRowFilter rowFilter, final BiRowMapper<? extends T> rowMapper) {
            N.checkArgNotNull(rowFilter, cs.rowFilter);
            N.checkArgNotNull(rowMapper, cs.rowMapper);

            return (rs, columnLabels) -> {
                final List<T> result = new ArrayList<>();

                while (rs.next()) {
                    if (rowFilter.test(rs, columnLabels)) {
                        result.add(rowMapper.apply(rs, columnLabels));
                    }
                }

                return result;
            };
        }

        /**
         * Creates a {@code BiResultExtractor} that converts a {@code ResultSet} into a {@code List} of entities.
         * The mapping from columns to entity properties is done automatically.
         *
         * <p>
         * This method internally uses a stateful {@code BiRowMapper} that caches metadata on its first run.
         * While the returned {@code BiResultExtractor} itself is stateless, for performance-critical
         * scenarios, consider creating the stateful {@code BiRowMapper} once and reusing it if the
         * result set structure is consistent.
         * </p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * BiResultExtractor<List<User>> extractor = BiResultExtractor.toList(User.class);
         * }</pre>
         *
         * @param <T> entity type
         * @param targetClass the class of the entities to be created
         * @return a {@code BiResultExtractor} that produces a {@code List} of entities.
         * @see ResultExtractor#toList(Class)
         * @see BiRowMapper#to(Class)
         */
        static <T> BiResultExtractor<List<T>> toList(final Class<? extends T> targetClass) {
            N.checkArgNotNull(targetClass, cs.targetClass);

            return (rs, columnLabels) -> {
                final BiRowMapper<? extends T> rowMapper = BiRowMapper.to(targetClass);
                final List<T> result = new ArrayList<>();

                while (rs.next()) {
                    result.add(rowMapper.apply(rs, columnLabels));
                }

                return result;
            };
        }
    }

    /**
     * A functional interface for mapping the current row of a {@code ResultSet} to an object.
     * The mapper should only read from the current row and should not advance the cursor (e.g., by calling {@code rs.next()}).
     *
     * <p>For better performance when processing multiple records where column metadata is needed repeatedly,
     * consider using {@link BiRowMapper}, as it provides column labels and avoids repeated metadata lookups.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * RowMapper<User> userMapper = rs -> new User(
     * rs.getInt("id"),
     * rs.getString("name")
     * );
     * }</pre>
     *
     * @param <T> entity type to map each row to
     * @see ColumnOne
     */
    @FunctionalInterface
    public interface RowMapper<T> extends Throwables.Function<ResultSet, T, SQLException> {

        /**
         * Maps the current row of the given {@code ResultSet} to an object of type {@code T}.
         * This method should not advance the ResultSet cursor (e.g., call {@code rs.next()}). It
         * operates solely on the data available at the current cursor position.
         *
         * @param rs the {@code ResultSet} positioned at the row to be mapped
         * @return the mapped object of type {@code T}
         * @throws SQLException if a database access error occurs during column value retrieval
         */
        @Override
        T apply(ResultSet rs) throws SQLException;

        /**
         * Returns a composed {@code RowMapper} that first applies this mapper to the row and then
         * applies the {@code after} function to the result. This allows for chaining transformations.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * RowMapper<User> userMapper = rs -> new User(rs.getString("name"));
         * // Creates a mapper that extracts just the user's name as a String.
         * RowMapper<String> nameMapper = userMapper.andThen(User::getName);
         * }</pre>
         *
         * @param <R> result type of the {@code after} function
         * @param after the function to apply to the result of this mapper; must not be null
         * @return a composed {@code RowMapper}
         * @throws IllegalArgumentException if {@code after} is null
         */
        default <R> RowMapper<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return rs -> after.apply(apply(rs));
        }

        /**
         * Converts this {@code RowMapper} to a {@code BiRowMapper}.
         * The resulting mapper will ignore the {@code columnLabels} parameter. This is useful for
         * adapting a simple {@code RowMapper} to an API that requires a {@code BiRowMapper}.
         *
         * @return a {@code BiRowMapper} that delegates to this {@code RowMapper}.
         */
        default BiRowMapper<T> toBiRowMapper() {
            return (rs, columnLabels) -> this.apply(rs);
        }

        /**
         * Combines two {@code RowMapper} instances into a single mapper that returns a {@code Tuple2}
         * containing the results of both. Both mappers are applied to the same row of the {@code ResultSet}.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * RowMapper<Integer> idMapper = rs -> rs.getInt("id");
         * RowMapper<String> nameMapper = rs -> rs.getString("name");
         * RowMapper<Tuple2<Integer, String>> combinedMapper = RowMapper.combine(idMapper, nameMapper);
         * // combinedMapper.apply(rs) would return Tuple.of(1, "John") for a given row.
         * }</pre>
         *
         * @param <T> result type of first mapper
         * @param <U> result type of second mapper
         * @param rowMapper1 the first mapper; must not be null
         * @param rowMapper2 the second mapper; must not be null
         * @return a new {@code RowMapper} that produces a {@code Tuple2}
         * @throws IllegalArgumentException if either mapper is null
         */
        static <T, U> RowMapper<Tuple2<T, U>> combine(final RowMapper<? extends T> rowMapper1, final RowMapper<? extends U> rowMapper2) {
            N.checkArgNotNull(rowMapper1, cs.rowMapper1);
            N.checkArgNotNull(rowMapper2, cs.rowMapper2);

            return rs -> Tuple.of(rowMapper1.apply(rs), rowMapper2.apply(rs));
        }

        /**
         * Combines three {@code RowMapper} instances into a single mapper that returns a {@code Tuple3}
         * containing the results of all three. All mappers are applied to the same row of the {@code ResultSet}.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * RowMapper<Integer> idMapper = rs -> rs.getInt("id");
         * RowMapper<String> nameMapper = rs -> rs.getString("name");
         * RowMapper<Date> dateMapper = rs -> rs.getDate("join_date");
         * RowMapper<Tuple3<Integer, String, Date>> combinedMapper = RowMapper.combine(idMapper, nameMapper, dateMapper);
         * }</pre>
         *
         * @param <A> result type of first mapper
         * @param <B> result type of second mapper
         * @param <C> result type of third mapper
         * @param rowMapper1 the first mapper; must not be null
         * @param rowMapper2 the second mapper; must not be null
         * @param rowMapper3 the third mapper; must not be null
         * @return a new {@code RowMapper} that produces a {@code Tuple3}
         * @throws IllegalArgumentException if any mapper is null
         */
        static <A, B, C> RowMapper<Tuple3<A, B, C>> combine(final RowMapper<? extends A> rowMapper1, final RowMapper<? extends B> rowMapper2,
                final RowMapper<? extends C> rowMapper3) {
            N.checkArgNotNull(rowMapper1, cs.rowMapper1);
            N.checkArgNotNull(rowMapper2, cs.rowMapper2);
            N.checkArgNotNull(rowMapper3, cs.rowMapper3);

            return rs -> Tuple.of(rowMapper1.apply(rs), rowMapper2.apply(rs), rowMapper3.apply(rs));
        }

        /**
         * Creates a stateful {@code RowMapper} that maps all columns of a row to an {@code Object[]}.
         * The specified {@code columnGetterForAll} is used to extract the value from each column.
         *
         * <p>
         * <b>Warning:</b> The returned mapper is stateful because it caches the column count
         * after the first execution. It should not be cached, shared, or used in parallel streams.
         * </p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * RowMapper<Object[]> arrayMapper = RowMapper.toArray(ColumnGetter.GET_OBJECT);
         * // When applied to a row: Object[] rowData = arrayMapper.apply(rs);
         * }</pre>
         *
         * @param columnGetterForAll the {@code ColumnGetter} used to retrieve the value for every column
         * @return a stateful {@code RowMapper} that maps a row to an {@code Object[]}
         */
        @Beta
        @SequentialOnly
        @Stateful
        static RowMapper<Object[]> toArray(final ColumnGetter<?> columnGetterForAll) {
            return new RowMapper<>() {
                private int columnCount = -1;

                @Override
                public Object[] apply(final ResultSet rs) throws SQLException {
                    if (columnCount < 0) {
                        columnCount = JdbcUtil.getColumnCount(rs);
                    }

                    final Object[] result = new Object[columnCount];

                    for (int i = 0; i < columnCount; i++) {
                        result[i] = columnGetterForAll.apply(rs, i + 1);
                    }

                    return result;
                }
            };
        }

        /**
         * Creates a stateful {@code RowMapper} that maps all columns of a row to a {@code List<Object>}.
         * The specified {@code columnGetterForAll} is used to extract the value from each column.
         *
         * <p>
         * <b>Warning:</b> The returned mapper is stateful because it caches the column count
         * after the first execution. It should not be cached, shared, or used in parallel streams.
         * </p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * RowMapper<List<Object>> listMapper = RowMapper.toList(ColumnGetter.GET_OBJECT);
         * // When applied to a row: List<Object> rowData = listMapper.apply(rs);
         * }</pre>
         *
         * @param columnGetterForAll the {@code ColumnGetter} used to retrieve the value for every column
         * @return a stateful {@code RowMapper} that maps a row to a {@code List<Object>}
         */
        @Beta
        @SequentialOnly
        @Stateful
        static RowMapper<List<Object>> toList(final ColumnGetter<?> columnGetterForAll) {
            return toCollection(columnGetterForAll, IntFunctions.ofList());
        }

        /**
         * Creates a stateful {@code RowMapper} that maps all columns of a row to a {@code Collection}.
         * The specified {@code columnGetterForAll} extracts values, and the {@code supplier} provides
         * the collection instance (e.g., {@code ArrayList::new}, {@code HashSet::new}).
         *
         * <p>
         * <b>Warning:</b> The returned mapper is stateful because it caches the column count
         * after the first execution. It should not be cached, shared, or used in parallel streams.
         * </p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Create a mapper that maps each row to an ArrayList
         * RowMapper<ArrayList<Object>> mapper = RowMapper.toCollection(
         *     ColumnGetter.GET_OBJECT,
         *     size -> new ArrayList<>(size)
         * );
         * }</pre>
         *
         * @param <C> collection type
         * @param columnGetterForAll the {@code ColumnGetter} used to retrieve the value for every column
         * @param supplier a function that takes the column count and returns a new {@code Collection} instance
         * @return a stateful {@code RowMapper} that maps a row to a {@code Collection}
         */
        @Beta
        @SequentialOnly
        @Stateful
        static <C extends Collection<?>> RowMapper<C> toCollection(final ColumnGetter<?> columnGetterForAll, final IntFunction<? extends C> supplier) {
            return new RowMapper<>() {
                private int columnCount = -1;

                @Override
                public C apply(final ResultSet rs) throws SQLException {
                    if (columnCount < 0) {
                        columnCount = JdbcUtil.getColumnCount(rs);
                    }

                    final Collection<Object> result = (Collection<Object>) supplier.apply(columnCount);

                    for (int i = 0; i < columnCount; i++) {
                        result.add(columnGetterForAll.apply(rs, i + 1));
                    }

                    return (C) result;
                }
            };
        }

        /**
         * Creates a stateful {@code RowMapper} that maps a row to a reusable {@code DisposableObjArray}.
         * This is an performance optimization for row processing that avoids creating a new array for
         * each row, reducing garbage collection overhead. The underlying array is reused on subsequent calls.
         *
         * <p>
         * <b>Warning:</b> The returned mapper is highly stateful. The returned {@code DisposableObjArray}
         * is a wrapper around an internal array that will be overwritten on the next call to {@code apply}.
         * You must process or copy its contents before the next row is mapped. Do not cache, share, or
         * use this mapper in parallel streams.
         * </p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * RowMapper<DisposableObjArray> mapper = RowMapper.toDisposableObjArray();
         * // Process immediately: DisposableObjArray data = mapper.apply(rs);
         * // WARNING: Do not store 'data' reference, process it immediately
         * }</pre>
         *
         * @return a stateful {@code RowMapper} for high-performance, single-threaded row processing
         */
        @Beta
        @SequentialOnly
        @Stateful
        static RowMapper<DisposableObjArray> toDisposableObjArray() {
            return new RowMapper<>() {
                private DisposableObjArray disposable = null;
                private int columnCount = -1;
                private Object[] output = null;

                @Override
                public DisposableObjArray apply(final ResultSet rs) throws SQLException {
                    if (disposable == null) {
                        columnCount = JdbcUtil.getColumnCount(rs);
                        output = new Object[columnCount];
                        disposable = DisposableObjArray.wrap(output);
                    }

                    for (int i = 0; i < columnCount; i++) {
                        output[i] = JdbcUtil.getColumnValue(rs, i + 1);
                    }

                    return disposable;
                }
            };
        }

        /**
         * Creates a stateful {@code RowMapper} that maps a row to a reusable {@code DisposableObjArray},
         * using type information from an entity class to perform appropriate type conversions for each column.
         *
         * <p>
         * <b>Warning:</b> The returned mapper is highly stateful and caches type information and the output array.
         * The returned {@code DisposableObjArray} is a wrapper around an internal array that will be overwritten on
         * the next call to {@code apply}. You must process or copy its contents before the next row is mapped.
         * Do not cache, share, or use this mapper in parallel streams.
         * </p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * RowMapper<DisposableObjArray> mapper = RowMapper.toDisposableObjArray(User.class);
         * // Type conversions based on User class properties
         * // WARNING: Process result immediately, do not store
         * }</pre>
         *
         * @param entityClass the class used to infer the data type for each column based on matching property names
         * @return a stateful {@code RowMapper} for high-performance, type-aware, single-threaded row processing
         * @throws IllegalArgumentException if {@code entityClass} is null
         */
        @Beta
        @SequentialOnly
        @Stateful
        static RowMapper<DisposableObjArray> toDisposableObjArray(final Class<?> entityClass) {
            N.checkArgNotNull(entityClass, cs.entityClass);

            return new RowMapper<>() {
                private DisposableObjArray disposable = null;
                private int columnCount = -1;
                private Object[] output = null;

                private Type<?>[] columnTypes = null;

                @Override
                public DisposableObjArray apply(final ResultSet rs) throws SQLException {
                    if (disposable == null) {
                        final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                        columnCount = columnLabels.size();
                        columnTypes = new Type[columnCount];

                        final BeanInfo entityInfo = ParserUtil.getBeanInfo(entityClass);
                        final Map<String, String> column2FieldNameMap = JdbcUtil.getColumn2FieldNameMap(entityClass);
                        PropInfo propInfo = null;

                        for (int i = 0; i < columnCount; i++) {
                            propInfo = entityInfo.getPropInfo(columnLabels.get(i));

                            if (propInfo == null) {
                                String fieldName = column2FieldNameMap.get(columnLabels.get(i));

                                if (Strings.isEmpty(fieldName)) {
                                    fieldName = column2FieldNameMap.get(columnLabels.get(i).toLowerCase());
                                }

                                if (Strings.isNotEmpty(fieldName)) {
                                    propInfo = entityInfo.getPropInfo(fieldName);
                                }
                            }

                            if (propInfo == null) {
                                //    throw new IllegalArgumentException(
                                //            "No property in class: " + ClassUtil.getCanonicalClassName(entityClass) + " mapping to column: " + columnLabels.get(i));
                            } else {
                                columnTypes[i] = propInfo.dbType;
                            }
                        }

                        output = new Object[columnCount];
                        disposable = DisposableObjArray.wrap(output);
                    }

                    for (int i = 0; i < columnCount; i++) {
                        output[i] = columnTypes[i] == null ? JdbcUtil.getColumnValue(rs, i + 1) : columnTypes[i].get(rs, i + 1);
                    }

                    return disposable;
                }
            };
        }

        /**
         * Creates a new {@code RowMapperBuilder} with a default column getter of {@code ColumnGetter.GET_OBJECT}.
         * This builder provides a fluent API for constructing complex {@code RowMapper} instances.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * RowMapper<Object[]> mapper = RowMapper.builder()
         * .getInt(1)
         * .getString(2)
         * .toArray();
         * }</pre>
         *
         * @return a new {@code RowMapperBuilder}
         */
        static RowMapperBuilder builder() {
            return builder(ColumnGetter.GET_OBJECT);
        }

        /**
         * Creates a new {@code RowMapperBuilder} with the specified default column getter.
         * This default getter will be used for any column whose type is not explicitly configured in the builder.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Default to String, but override column 1 to be an Integer.
         * RowMapper<Object[]> mapper = RowMapper.builder(ColumnGetter.GET_STRING)
         * .getInt(1)
         * .toArray();
         * }</pre>
         *
         * @param defaultColumnGetter the default {@code ColumnGetter} to use for unconfigured columns
         * @return a new {@code RowMapperBuilder}
         * @throws IllegalArgumentException if {@code defaultColumnGetter} is null
         */
        static RowMapperBuilder builder(final ColumnGetter<?> defaultColumnGetter) {
            return new RowMapperBuilder(defaultColumnGetter);
        }

        /**
         * A fluent builder for creating customized, stateful {@code RowMapper} instances.
         * This builder allows specifying different {@code ColumnGetter}s for specific column indices
         * and provides terminal methods to create mappers that output various formats
         * (e.g., array, list, map).
         *
         * <p>
         * <b>Warning:</b> All {@code RowMapper} instances created by this builder are stateful,
         * as they cache metadata (like column count and getter configurations) upon first execution.
         * They should not be cached, shared, or used in parallel streams.
         * </p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * RowMapper<Map<String, Object>> mapper = RowMapper.builder()
         * .getInt(1)        // Column 1 as int
         * .getString(2)     // Column 2 as String
         * .getDate(3)       // Column 3 as Date
         * .toMap();
         * }</pre>
         */
        @SequentialOnly
        class RowMapperBuilder {
            private final Map<Integer, ColumnGetter<?>> columnGetterMap;

            /**
             * Constructs a new {@code RowMapperBuilder} with a specified default column getter.
             * This getter will be applied to any column index for which a specific getter has not been configured.
             *
             * @param defaultColumnGetter the default {@code ColumnGetter} to use; must not be null
             * @throws IllegalArgumentException if {@code defaultColumnGetter} is null
             */
            RowMapperBuilder(final ColumnGetter<?> defaultColumnGetter) {
                N.checkArgNotNull(defaultColumnGetter, cs.defaultColumnGetter);

                columnGetterMap = new HashMap<>(9);
                columnGetterMap.put(0, defaultColumnGetter);
            }

            /**
             * Configures the mapper to retrieve a {@code boolean} value from the specified column index.
             *
             * @param columnIndex the 1-based index of the column
             * @return this builder instance for method chaining
             * @throws IllegalArgumentException if {@code columnIndex} is not positive
             */
            public RowMapperBuilder getBoolean(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BOOLEAN);
            }

            /**
             * Configures the mapper to retrieve a {@code byte} value from the specified column index.
             *
             * @param columnIndex the 1-based index of the column
             * @return this builder instance for method chaining
             * @throws IllegalArgumentException if {@code columnIndex} is not positive
             */
            public RowMapperBuilder getByte(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BYTE);
            }

            /**
             * Configures the mapper to retrieve a {@code short} value from the specified column index.
             *
             * @param columnIndex the 1-based index of the column
             * @return this builder instance for method chaining
             * @throws IllegalArgumentException if {@code columnIndex} is not positive
             */
            public RowMapperBuilder getShort(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_SHORT);
            }

            /**
             * Configures the mapper to retrieve an {@code int} value from the specified column index.
             *
             * @param columnIndex the 1-based index of the column
             * @return this builder instance for method chaining
             * @throws IllegalArgumentException if {@code columnIndex} is not positive
             */
            public RowMapperBuilder getInt(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_INT);
            }

            /**
             * Configures the mapper to retrieve a {@code long} value from the specified column index.
             *
             * @param columnIndex the 1-based index of the column
             * @return this builder instance for method chaining
             * @throws IllegalArgumentException if {@code columnIndex} is not positive
             */
            public RowMapperBuilder getLong(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_LONG);
            }

            /**
             * Configures the mapper to retrieve a {@code float} value from the specified column index.
             *
             * @param columnIndex the 1-based index of the column
             * @return this builder instance for method chaining
             * @throws IllegalArgumentException if {@code columnIndex} is not positive
             */
            public RowMapperBuilder getFloat(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_FLOAT);
            }

            /**
             * Configures the mapper to retrieve a {@code double} value from the specified column index.
             *
             * @param columnIndex the 1-based index of the column
             * @return this builder instance for method chaining
             * @throws IllegalArgumentException if {@code columnIndex} is not positive
             */
            public RowMapperBuilder getDouble(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_DOUBLE);
            }

            /**
             * Configures the mapper to retrieve a {@code BigDecimal} value from the specified column index.
             *
             * @param columnIndex the 1-based index of the column
             * @return this builder instance for method chaining
             * @throws IllegalArgumentException if {@code columnIndex} is not positive
             */
            public RowMapperBuilder getBigDecimal(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BIG_DECIMAL);
            }

            /**
             * Configures the mapper to retrieve a {@code String} value from the specified column index.
             *
             * @param columnIndex the 1-based index of the column
             * @return this builder instance for method chaining
             * @throws IllegalArgumentException if {@code columnIndex} is not positive
             */
            public RowMapperBuilder getString(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_STRING);
            }

            /**
             * Configures the mapper to retrieve a {@code java.sql.Date} value from the specified column index.
             *
             * @param columnIndex the 1-based index of the column
             * @return this builder instance for method chaining
             * @throws IllegalArgumentException if {@code columnIndex} is not positive
             */
            public RowMapperBuilder getDate(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_DATE);
            }

            /**
             * Configures the mapper to retrieve a {@code java.sql.Time} value from the specified column index.
             *
             * @param columnIndex the 1-based index of the column
             * @return this builder instance for method chaining
             * @throws IllegalArgumentException if {@code columnIndex} is not positive
             */
            public RowMapperBuilder getTime(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_TIME);
            }

            /**
             * Configures the mapper to retrieve a {@code java.sql.Timestamp} value from the specified column index.
             *
             * @param columnIndex the 1-based index of the column
             * @return this builder instance for method chaining
             * @throws IllegalArgumentException if {@code columnIndex} is not positive
             */
            public RowMapperBuilder getTimestamp(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_TIMESTAMP);
            }

            /**
             * Configures the mapper to retrieve an {@code Object} value from the specified column index.
             *
             * @param columnIndex the 1-based index of the column
             * @return this builder instance for method chaining
             * @throws IllegalArgumentException if {@code columnIndex} is not positive
             * @deprecated The default behavior already uses {@code ColumnGetter.GET_OBJECT} if no specific getter is set.
             */
            @Deprecated
            public RowMapperBuilder getObject(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_OBJECT);
            }

            /**
             * Configures the mapper to retrieve an object of a specific type from the specified column index.
             * A suitable {@code ColumnGetter} for the given type will be used.
             *
             * @param columnIndex the 1-based index of the column
             * @param type the target class type to convert the column value to
             * @return this builder instance for method chaining
             */
            public RowMapperBuilder getObject(final int columnIndex, final Class<?> type) {
                return get(columnIndex, ColumnGetter.get(type));
            }

            /**
             * Configures the mapper to use a custom {@code ColumnGetter} for the specified column index.
             *
             * <p><b>Usage Examples:</b></p>
             * <pre>{@code
             * // Get a string from column 1 and convert it to uppercase.
             * builder.get(1, (rs, idx) -> rs.getString(idx).toUpperCase());
             * }</pre>
             *
             * @param columnIndex the 1-based index of the column
             * @param columnGetter the custom {@code ColumnGetter} to use
             * @return this builder instance for method chaining
             * @throws IllegalArgumentException if {@code columnIndex} is not positive or {@code columnGetter} is null
             */
            public RowMapperBuilder get(final int columnIndex, final ColumnGetter<?> columnGetter) throws IllegalArgumentException {
                N.checkArgPositive(columnIndex, cs.columnIndex);
                N.checkArgNotNull(columnGetter, cs.columnGetter);

                columnGetterMap.put(columnIndex, columnGetter);
                return this;
            }

            private ColumnGetter<?>[] initColumnGetter(final int columnCount) { //NOSONAR
                final ColumnGetter<?>[] rsColumnGetters = new ColumnGetter<?>[columnCount];
                final ColumnGetter<?> defaultColumnGetter = columnGetterMap.get(0);

                for (int i = 0; i < columnCount; i++) {
                    rsColumnGetters[i] = columnGetterMap.getOrDefault(i + 1, defaultColumnGetter);
                }

                return rsColumnGetters;
            }

            /**
             * Builds and returns a stateful {@code RowMapper} that maps each row to an {@code Object[]}.
             *
             * <p>
             * <b>Warning:</b> The returned mapper is stateful and should not be cached, shared, or used in parallel streams.
             * </p>
             *
             * @return a new stateful {@code RowMapper<Object[]>}
             */
            @SequentialOnly
            @Stateful
            public RowMapper<Object[]> toArray() {
                return new RowMapper<>() {
                    private ColumnGetter<?>[] rsColumnGetters = null;
                    private int rsColumnCount = -1;

                    @Override
                    public Object[] apply(final ResultSet rs) throws SQLException {
                        if (rsColumnGetters == null) {
                            rsColumnCount = rs.getMetaData().getColumnCount();
                            rsColumnGetters = initColumnGetter(rsColumnCount);
                        }

                        final Object[] row = new Object[rsColumnCount];

                        for (int i = 0; i < rsColumnCount; i++) {
                            row[i] = rsColumnGetters[i].apply(rs, i + 1);
                        }

                        return row;
                    }
                };
            }

            /**
             * Builds and returns a stateful {@code RowMapper} that maps each row to a {@code List<Object>}.
             *
             * <p>
             * <b>Warning:</b> The returned mapper is stateful and should not be cached, shared, or used in parallel streams.
             * </p>
             *
             * @return a new stateful {@code RowMapper<List<Object>>}
             */
            @SequentialOnly
            @Stateful
            public RowMapper<List<Object>> toList() {
                return toCollection(IntFunctions.ofList());
            }

            /**
             * Builds and returns a stateful {@code RowMapper} that maps each row to a {@code Collection}.
             *
             * <p>
             * <b>Warning:</b> The returned mapper is stateful and should not be cached, shared, or used in parallel streams.
             * </p>
             *
             * @param <C> specific collection type (e.g., {@code List}, {@code Set})
             * @param supplier a function that provides a new collection instance, given the column count
             * @return a new stateful {@code RowMapper<C>}
             */
            @SequentialOnly
            @Stateful
            public <C extends Collection<?>> RowMapper<C> toCollection(final IntFunction<? extends C> supplier) {
                return new RowMapper<>() {
                    private ColumnGetter<?>[] rsColumnGetters = null;
                    private int rsColumnCount = -1;

                    @Override
                    public C apply(final ResultSet rs) throws SQLException {
                        if (rsColumnGetters == null) {
                            rsColumnCount = rs.getMetaData().getColumnCount();
                            rsColumnGetters = initColumnGetter(rsColumnCount);
                        }

                        final Collection<Object> row = (Collection<Object>) supplier.apply(rsColumnCount);

                        for (int i = 0; i < rsColumnCount; i++) {
                            row.add(rsColumnGetters[i].apply(rs, i + 1));
                        }

                        return (C) row;
                    }
                };
            }

            /**
             * Builds and returns a stateful {@code RowMapper} that maps each row to a {@code Map<String, Object>},
             * where keys are the column labels.
             *
             * <p>
             * <b>Warning:</b> The returned mapper is stateful and should not be cached, shared, or used in parallel streams.
             * </p>
             *
             * @return a new stateful {@code RowMapper<Map<String, Object>>}
             */
            @SequentialOnly
            @Stateful
            public RowMapper<Map<String, Object>> toMap() {
                return toMap(IntFunctions.ofMap());
            }

            /**
             * Builds and returns a stateful {@code RowMapper} that maps each row to a {@code Map<String, Object>},
             * using the provided supplier to create the map instance.
             *
             * <p>
             * <b>Warning:</b> The returned mapper is stateful and should not be cached, shared, or used in parallel streams.
             * </p>
             *
             * @param mapSupplier a function that provides a new map instance, given the column count
             * @return a new stateful {@code RowMapper<Map<String, Object>>}
             */
            @SequentialOnly
            @Stateful
            public RowMapper<Map<String, Object>> toMap(final IntFunction<? extends Map<String, Object>> mapSupplier) {
                return new RowMapper<>() {
                    private ColumnGetter<?>[] rsColumnGetters = null;
                    private List<String> columnLabels = null;
                    private int rsColumnCount = -1;

                    @Override
                    public Map<String, Object> apply(final ResultSet rs) throws SQLException {
                        if (rsColumnGetters == null) {
                            columnLabels = JdbcUtil.getColumnLabelList(rs);
                            rsColumnCount = columnLabels.size();
                            rsColumnGetters = initColumnGetter(rsColumnCount);
                        }

                        final Map<String, Object> row = mapSupplier.apply(rsColumnCount);

                        for (int i = 0; i < rsColumnCount; i++) {
                            row.put(columnLabels.get(i), rsColumnGetters[i].apply(rs, i + 1));
                        }

                        return row;
                    }
                };
            }

            /**
             * Builds a stateful {@code RowMapper} that processes row values into a final object using a custom finisher function.
             * The row values are passed to the finisher as a {@code DisposableObjArray} to minimize object creation.
             *
             * <p>
             * <b>Warning:</b> The returned mapper is stateful. Do not cache, share, or use it in parallel streams.
             * </p>
             *
             * <p><b>Usage Examples:</b></p>
             * <pre>{@code
             * RowMapper<User> userMapper = RowMapper.builder()
             * .getString(1)
             * .getInt(2)
             * .to(arr -> new User((String) arr.get(0), (int) arr.get(1)));
             * }</pre>
             *
             * @param <R> final result type
             * @param finisher a function that transforms the row's values into the final result object
             * @return a new stateful {@code RowMapper<R>}
             */
            @SequentialOnly
            @Stateful
            public <R> RowMapper<R> to(final Throwables.Function<DisposableObjArray, R, SQLException> finisher) {
                return new RowMapper<>() {
                    private ColumnGetter<?>[] rsColumnGetters = null;
                    private int rsColumnCount = -1;
                    private Object[] outputRow = null;
                    private DisposableObjArray output;

                    @Override
                    public R apply(final ResultSet rs) throws SQLException {
                        if (rsColumnGetters == null) {
                            rsColumnCount = rs.getMetaData().getColumnCount();
                            rsColumnGetters = initColumnGetter(rsColumnCount);
                            outputRow = new Object[rsColumnCount];
                            output = DisposableObjArray.wrap(outputRow);
                        }

                        for (int i = 0; i < rsColumnCount; i++) {
                            outputRow[i] = rsColumnGetters[i].apply(rs, i + 1);
                        }

                        return finisher.apply(output);
                    }
                };
            }

            /**
             * Builds a stateful {@code RowMapper} that uses a custom finisher function which receives both
             * the column labels and the row values (as a {@code DisposableObjArray}).
             *
             * <p>
             * <b>Warning:</b> The returned mapper is stateful. Do not cache, share, or use it in parallel streams.
             * </p>
             *
             * @param <R> final result type
             * @param finisher a function that transforms column labels and row values into the final result object
             * @return a new stateful {@code RowMapper<R>}
             */
            @SequentialOnly
            @Stateful
            public <R> RowMapper<R> to(final Throwables.BiFunction<List<String>, DisposableObjArray, R, SQLException> finisher) {
                return new RowMapper<>() {
                    private ColumnGetter<?>[] rsColumnGetters = null;
                    private List<String> columnLabels = null;
                    private int rsColumnCount = -1;
                    private Object[] outputRow = null;
                    private DisposableObjArray output;

                    @Override
                    public R apply(final ResultSet rs) throws SQLException {
                        if (rsColumnGetters == null) {
                            columnLabels = JdbcUtil.getColumnLabelList(rs);
                            rsColumnCount = columnLabels.size();
                            rsColumnGetters = initColumnGetter(rsColumnCount);
                            outputRow = new Object[rsColumnCount];
                            output = DisposableObjArray.wrap(outputRow);
                        }

                        for (int i = 0; i < rsColumnCount; i++) {
                            outputRow[i] = rsColumnGetters[i].apply(rs, i + 1);
                        }

                        return finisher.apply(columnLabels, output);
                    }
                };
            }
        }
    }

    /**
     * A functional interface for mapping the current row of a {@code ResultSet} to an object,
     * with access to the list of column labels. This is more efficient than a simple {@code RowMapper}
     * when column metadata is needed, as the labels are fetched once and passed to each invocation,
     * avoiding repeated metadata lookups.
     *
     * <p>The mapper should only read from the current row and should not advance the cursor (e.g., by calling {@code rs.next()}).</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * BiRowMapper<Map<String, Object>> dynamicMapper = (rs, columnLabels) -> {
     * Map<String, Object> rowMap = new LinkedHashMap<>();
     * for (int i = 0; i < columnLabels.size(); i++) {
     * rowMap.put(columnLabels.get(i), rs.getObject(i + 1));
     * }
     * return rowMap;
     * };
     * }</pre>
     *
     * @param <T> entity type to map each row to
     */
    @FunctionalInterface
    public interface BiRowMapper<T> extends Throwables.BiFunction<ResultSet, List<String>, T, SQLException> {

        /**
         * A pre-defined mapper that converts a row into an {@code Object[]}.
         */
        BiRowMapper<Object[]> TO_ARRAY = (rs, columnLabels) -> {
            final int columnCount = columnLabels.size();
            final Object[] result = new Object[columnCount];

            for (int i = 1; i <= columnCount; i++) {
                result[i - 1] = JdbcUtil.getColumnValue(rs, i);
            }

            return result;
        };

        /**
         * A pre-defined mapper that converts a row into a {@code List<Object>}.
         */
        BiRowMapper<List<Object>> TO_LIST = (rs, columnLabels) -> {
            final int columnCount = columnLabels.size();
            final List<Object> result = new ArrayList<>(columnCount);

            for (int i = 1; i <= columnCount; i++) {
                result.add(JdbcUtil.getColumnValue(rs, i));
            }

            return result;
        };

        /**
         * A pre-defined mapper that converts a row into a {@code Map<String, Object>},
         * where keys are the column labels.
         */
        BiRowMapper<Map<String, Object>> TO_MAP = (rs, columnLabels) -> {
            final int columnCount = columnLabels.size();
            final Map<String, Object> result = N.newHashMap(columnCount);

            for (int i = 1; i <= columnCount; i++) {
                result.put(columnLabels.get(i - 1), JdbcUtil.getColumnValue(rs, i));
            }

            return result;
        };

        /**
         * A pre-defined mapper that converts a row into a {@code LinkedHashMap<String, Object>},
         * preserving the order of columns from the {@code ResultSet}.
         */
        BiRowMapper<Map<String, Object>> TO_LINKED_HASH_MAP = (rs, columnLabels) -> {
            final int columnCount = columnLabels.size();
            final Map<String, Object> result = N.newLinkedHashMap(columnCount);

            for (int i = 1; i <= columnCount; i++) {
                result.put(columnLabels.get(i - 1), JdbcUtil.getColumnValue(rs, i));
            }

            return result;
        };

        /**
         * A pre-defined mapper that converts a row into an {@code EntityId}. The property names
         * in the {@code EntityId} correspond to the column labels.
         */
        @SuppressWarnings("deprecation")
        BiRowMapper<EntityId> TO_ENTITY_ID = (rs, columnLabels) -> {
            final int columnCount = columnLabels.size();
            final Seid entityId = Seid.of(Strings.EMPTY);

            for (int i = 1; i <= columnCount; i++) {
                entityId.set(columnLabels.get(i - 1), JdbcUtil.getColumnValue(rs, i));
            }

            return entityId;
        };

        /**
         * Maps the current row of the given {@code ResultSet} to an object of type {@code T}, using the
         * provided list of column labels. This method should not advance the ResultSet cursor.
         *
         * @param rs the {@code ResultSet} positioned at the row to be mapped
         * @param columnLabels the list of column labels from the result set's metadata
         * @return the mapped object of type {@code T}
         * @throws SQLException if a database access error occurs during column value retrieval
         */
        @Override
        T apply(ResultSet rs, List<String> columnLabels) throws SQLException;

        /**
         * Returns a composed {@code BiRowMapper} that first applies this mapper to the row and then
         * applies the {@code after} function to the result.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * BiRowMapper<User> userMapper = (rs, cols) -> new User(rs.getString("name"));
         * // Creates a mapper that extracts just the user's name as a String.
         * BiRowMapper<String> nameMapper = userMapper.andThen(User::getName);
         * }</pre>
         *
         * @param <R> result type of the {@code after} function
         * @param after the function to apply to the result of this mapper; must not be null
         * @return a composed {@code BiRowMapper}
         * @throws IllegalArgumentException if {@code after} is null
         */
        default <R> BiRowMapper<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return (rs, columnLabels) -> after.apply(apply(rs, columnLabels));
        }

        /**
         * Converts this {@code BiRowMapper} to a stateful {@code RowMapper}. The resulting
         * {@code RowMapper} caches the column labels on its first execution.
         *
         * @return a stateful {@code RowMapper}. Do not cache, share, or use it in parallel streams.
         * @see RowMapper#toBiRowMapper()
         * @deprecated This method creates a stateful {@code RowMapper} that can be easily misused,
         * leading to potential issues in multithreaded environments or when reused across different
         * queries. It is recommended to use {@code BiRowMapper} directly where possible.
         */
        @Deprecated
        @SequentialOnly
        @Stateful
        default RowMapper<T> toRowMapper() {
            final BiRowMapper<T> biRowMapper = this;

            return new RowMapper<>() {
                private List<String> cls = null;

                @Override
                public T apply(final ResultSet rs) throws IllegalArgumentException, SQLException {
                    if (cls == null) {
                        cls = JdbcUtil.getColumnLabelList(rs);
                    }

                    return biRowMapper.apply(rs, cls);
                }
            };
        }

        /**
         * Combines two {@code BiRowMapper} instances into a single mapper that returns a {@code Tuple2}
         * containing the results of both. Both mappers are applied to the same row.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * BiRowMapper<Integer> idMapper = (rs, cols) -> rs.getInt("id");
         * BiRowMapper<String> nameMapper = (rs, cols) -> rs.getString("name");
         * BiRowMapper<Tuple2<Integer, String>> combinedMapper = BiRowMapper.combine(idMapper, nameMapper);
         * // combinedMapper.apply(rs, cols) would return Tuple.of(1, "John") for a given row.
         * }</pre>
         *
         * @param <T> result type of first mapper
         * @param <U> result type of second mapper
         * @param rowMapper1 the first mapper; must not be null
         * @param rowMapper2 the second mapper; must not be null
         * @return a new {@code BiRowMapper} that produces a {@code Tuple2}
         */
        static <T, U> BiRowMapper<Tuple2<T, U>> combine(final BiRowMapper<? extends T> rowMapper1, final BiRowMapper<? extends U> rowMapper2) {
            N.checkArgNotNull(rowMapper1, cs.rowMapper1);
            N.checkArgNotNull(rowMapper2, cs.rowMapper2);

            return (rs, cls) -> Tuple.of(rowMapper1.apply(rs, cls), rowMapper2.apply(rs, cls));
        }

        /**
         * Combines three {@code BiRowMapper} instances into a single mapper that returns a {@code Tuple3}
         * containing the results of all three. All mappers are applied to the same row.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * BiRowMapper<Integer> idMapper = (rs, cols) -> rs.getInt("id");
         * BiRowMapper<String> nameMapper = (rs, cols) -> rs.getString("name");
         * BiRowMapper<Date> dateMapper = (rs, cols) -> rs.getDate("join_date");
         * BiRowMapper<Tuple3<Integer, String, Date>> combinedMapper = BiRowMapper.combine(idMapper, nameMapper, dateMapper);
         * }</pre>
         *
         * @param <A> result type of first mapper
         * @param <B> result type of second mapper
         * @param <C> result type of third mapper
         * @param rowMapper1 the first mapper; must not be null
         * @param rowMapper2 the second mapper; must not be null
         * @param rowMapper3 the third mapper; must not be null
         * @return a new {@code BiRowMapper} that produces a {@code Tuple3}
         */
        static <A, B, C> BiRowMapper<Tuple3<A, B, C>> combine(final BiRowMapper<? extends A> rowMapper1, final BiRowMapper<? extends B> rowMapper2,
                final BiRowMapper<? extends C> rowMapper3) {
            N.checkArgNotNull(rowMapper1, cs.rowMapper1);
            N.checkArgNotNull(rowMapper2, cs.rowMapper2);
            N.checkArgNotNull(rowMapper3, cs.rowMapper3);

            return (rs, cls) -> Tuple.of(rowMapper1.apply(rs, cls), rowMapper2.apply(rs, cls), rowMapper3.apply(rs, cls));
        }

        /**
         * Creates a stateful {@code BiRowMapper} that maps a row to an instance of the specified {@code targetClass}.
         * It automatically maps column values to the properties of the target object based on matching names.
         * This factory supports mapping to beans, Maps, Lists, arrays, and primitive wrapper types (for single-column results).
         *
         * <p>
         * <b>Warning:</b> The returned mapper is stateful. It caches metadata (like property and column mappings)
         * upon its first execution. It should not be cached or shared for use with different SQL queries
         * (which may have different column structures), nor should it be used in parallel streams.
         * Create a new instance for each distinct query structure.
         * </p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Assumes User class has properties matching column names like 'id', 'name'.
         * BiRowMapper<User> userMapper = BiRowMapper.to(User.class);
         * }</pre>
         *
         * @param <T> target type
         * @param targetClass the class to map rows to
         * @return a new stateful {@code BiRowMapper}. Do not cache or reuse across different query structures.
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(final Class<? extends T> targetClass) {
            return to(targetClass, false);
        }

        /**
         * Creates a stateful {@code BiRowMapper} that maps a row to an instance of the specified {@code targetClass},
         * with an option to ignore columns in the {@code ResultSet} that do not have a matching property in the class.
         *
         * <p>
         * <b>Warning:</b> The returned mapper is stateful and caches metadata upon first execution. It should not be
         * cached, shared across different query structures, or used in parallel streams.
         * </p>
         *
         * @param <T> target type
         * @param targetClass the class to map rows to
         * @param ignoreNonMatchedColumns if {@code true}, columns without a corresponding property in {@code targetClass} are ignored;
         * if {@code false}, an {@code IllegalArgumentException} is thrown.
         * @return a new stateful {@code BiRowMapper}. Do not cache or reuse across different query structures.
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(final Class<? extends T> targetClass, final boolean ignoreNonMatchedColumns) {
            return to(targetClass, Fn.alwaysTrue(), Fn.identity(), ignoreNonMatchedColumns);
        }

        /**
         * Creates a stateful {@code BiRowMapper} with custom filtering and conversion for column names before mapping
         * them to object properties.
         *
         * <p>
         * <b>Warning:</b> The returned mapper is stateful and caches metadata upon first execution. It should not be
         * cached, shared across different query structures, or used in parallel streams.
         * </p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * BiRowMapper<User> mapper = BiRowMapper.to(
         * User.class,
         * colName -> !colName.startsWith("temp_");,  // Filter out temporary columns
         * String::toLowerCase                      // Convert column names to lowercase before matching
         * );
         * }</pre>
         *
         * @param <T> target type
         * @param targetClass the class to map rows to
         * @param columnNameFilter a predicate to filter which columns should be considered for mapping
         * @param columnNameConverter a function to transform column names before matching them to properties
         * @return a new stateful {@code BiRowMapper}. Do not cache or reuse across different query structures.
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(final Class<? extends T> targetClass, final Predicate<? super String> columnNameFilter,
                final Function<? super String, String> columnNameConverter) {
            return to(targetClass, columnNameFilter, columnNameConverter, false);
        }

        /**
         * Creates a stateful {@code BiRowMapper} with full customization over column filtering, name conversion,
         * and handling of non-matched columns.
         *
         * <p>
         * <b>Warning:</b> The returned mapper is stateful and caches metadata upon first execution. It should not be
         * cached, shared across different query structures, or used in parallel streams.
         * </p>
         *
         * @param <T> target type
         * @param targetClass the class to map rows to
         * @param columnNameFilter a predicate to filter which columns should be considered for mapping
         * @param columnNameConverter a function to transform column names before matching them to properties
         * @param ignoreNonMatchedColumns if {@code true}, filtered columns without a corresponding property are ignored;
         * if {@code false}, an {@code IllegalArgumentException} is thrown.
         * @return a new stateful {@code BiRowMapper}. Do not cache or reuse across different query structures.
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(final Class<? extends T> targetClass, final Predicate<? super String> columnNameFilter,
                final Function<? super String, String> columnNameConverter, final boolean ignoreNonMatchedColumns) {
            N.checkArgNotNull(targetClass, cs.targetClass);

            final Predicate<? super String> columnNameFilterToBeUsed = columnNameFilter == null ? Fn.alwaysTrue() : columnNameFilter;
            final Function<? super String, String> columnNameConverterToBeUsed = columnNameConverter == null ? Fn.identity() : columnNameConverter;

            if (Object[].class.isAssignableFrom(targetClass)) {
                if ((columnNameFilter == null || Objects.equals(columnNameFilter, Fn.alwaysTrue()))
                        && (columnNameConverter == null || Objects.equals(columnNameConverter, Fn.identity()))) {
                    return (rs, columnLabelList) -> {
                        final int columnCount = columnLabelList.size();
                        final Object[] a = Array.newInstance(targetClass.getComponentType(), columnCount);

                        for (int i = 0; i < columnCount; i++) {
                            a[i] = JdbcUtil.getColumnValue(rs, i + 1);
                        }

                        return (T) a;
                    };
                } else {
                    return new BiRowMapper<>() {
                        private String[] columnLabels = null;
                        private int columnCount = -1;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            if (columnLabels == null) {
                                columnCount = columnLabelList.size();

                                columnLabels = columnLabelList.toArray(new String[columnCount]);

                                for (int i = 0; i < columnCount; i++) {
                                    if (columnNameFilterToBeUsed.test(columnLabels[i])) {
                                        columnLabels[i] = columnNameConverterToBeUsed.apply(columnLabels[i]);
                                    } else {
                                        columnLabels[i] = null;
                                    }
                                }
                            }

                            final Object[] a = Array.newInstance(targetClass.getComponentType(), columnCount);

                            for (int i = 0; i < columnCount; i++) {
                                if (columnLabels[i] == null) {
                                    continue;
                                }

                                a[i] = JdbcUtil.getColumnValue(rs, i + 1);
                            }

                            return (T) a;
                        }
                    };
                }
            } else if (List.class.isAssignableFrom(targetClass)) {
                if ((columnNameFilter == null || Objects.equals(columnNameFilter, Fn.alwaysTrue()))
                        && (columnNameConverter == null || Objects.equals(columnNameConverter, Fn.identity()))) {
                    return (rs, columnLabelList) -> {
                        final int columnCount = columnLabelList.size();
                        @SuppressWarnings("rawtypes")
                        final Collection<Object> c = N.newCollection((Class<Collection>) targetClass, columnCount);

                        for (int i = 0; i < columnCount; i++) {
                            c.add(JdbcUtil.getColumnValue(rs, i + 1));
                        }

                        return (T) c;
                    };
                } else {
                    return new BiRowMapper<>() {
                        private String[] columnLabels = null;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            final int columnCount = columnLabelList.size();

                            if (columnLabels == null) {
                                columnLabels = columnLabelList.toArray(new String[columnCount]);

                                for (int i = 0; i < columnCount; i++) {
                                    if (columnNameFilterToBeUsed.test(columnLabels[i])) {
                                        columnLabels[i] = columnNameConverterToBeUsed.apply(columnLabels[i]);
                                    } else {
                                        columnLabels[i] = null;
                                    }
                                }
                            }

                            @SuppressWarnings("rawtypes")
                            final Collection<Object> c = N.newCollection((Class<Collection>) targetClass, columnCount);

                            for (int i = 0; i < columnCount; i++) {
                                if (columnLabels[i] == null) {
                                    continue;
                                }

                                c.add(JdbcUtil.getColumnValue(rs, i + 1));
                            }

                            return (T) c;
                        }
                    };
                }
            } else if (Map.class.isAssignableFrom(targetClass)) {
                if ((columnNameFilter == null || Objects.equals(columnNameFilter, Fn.alwaysTrue()))
                        && (columnNameConverter == null || Objects.equals(columnNameConverter, Fn.identity()))) {
                    return new BiRowMapper<>() {
                        private String[] columnLabels = null;
                        private int columnCount = -1;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            if (columnLabels == null) {
                                columnCount = columnLabelList.size();
                                columnLabels = columnLabelList.toArray(new String[columnCount]);
                            }

                            @SuppressWarnings("rawtypes")
                            final Map<String, Object> m = N.newMap((Class<Map>) targetClass, columnCount);

                            for (int i = 0; i < columnCount; i++) {
                                m.put(columnLabels[i], JdbcUtil.getColumnValue(rs, i + 1));
                            }

                            return (T) m;
                        }
                    };
                } else {
                    return new BiRowMapper<>() {
                        private String[] columnLabels = null;
                        private int columnCount = -1;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            if (columnLabels == null) {
                                columnCount = columnLabelList.size();
                                columnLabels = columnLabelList.toArray(new String[columnCount]);

                                for (int i = 0; i < columnCount; i++) {
                                    if (columnNameFilterToBeUsed.test(columnLabels[i])) {
                                        columnLabels[i] = columnNameConverterToBeUsed.apply(columnLabels[i]);
                                    } else {
                                        columnLabels[i] = null;
                                    }
                                }
                            }

                            @SuppressWarnings("rawtypes")
                            final Map<String, Object> m = N.newMap((Class<Map>) targetClass, columnCount);

                            for (int i = 0; i < columnCount; i++) {
                                if (columnLabels[i] == null) {
                                    continue;
                                }

                                m.put(columnLabels[i], JdbcUtil.getColumnValue(rs, i + 1));
                            }

                            return (T) m;
                        }
                    };
                }
            } else if (Beans.isBeanClass(targetClass)) {
                final BeanInfo entityInfo = ParserUtil.getBeanInfo(targetClass);

                return new BiRowMapper<>() {
                    private String[] columnLabels = null;
                    private PropInfo[] propInfos;
                    private Type<?>[] columnTypes = null;
                    private int columnCount = -1;

                    @Override
                    public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                        if (columnLabels == null) {
                            final Map<String, String> column2FieldNameMap = JdbcUtil.getColumn2FieldNameMap(targetClass);

                            columnCount = columnLabelList.size();
                            columnLabels = columnLabelList.toArray(new String[columnCount]);
                            propInfos = new PropInfo[columnCount];
                            columnTypes = new Type[columnCount];

                            for (int i = 0; i < columnCount; i++) {
                                if (columnNameFilterToBeUsed.test(columnLabels[i])) {
                                    columnLabels[i] = columnNameConverterToBeUsed.apply(columnLabels[i]);

                                    propInfos[i] = entityInfo.getPropInfo(columnLabels[i]);

                                    if (propInfos[i] == null) {
                                        String fieldName = column2FieldNameMap.get(columnLabels[i]);

                                        if (Strings.isEmpty(fieldName)) {
                                            fieldName = column2FieldNameMap.get(columnLabels[i].toLowerCase());
                                        }

                                        if (Strings.isNotEmpty(fieldName)) {
                                            propInfos[i] = entityInfo.getPropInfo(fieldName);
                                        }
                                    }

                                    if (propInfos[i] == null) {
                                        final String newColumnName = JdbcUtil.checkPrefix(entityInfo, columnLabels[i], null, columnLabelList);
                                        propInfos[i] = JdbcUtil.getSubPropInfo(targetClass, newColumnName);

                                        if (propInfos[i] == null) {
                                            String fieldName = column2FieldNameMap.get(newColumnName);

                                            if (Strings.isEmpty(fieldName)) {
                                                fieldName = column2FieldNameMap.get(newColumnName.toLowerCase());
                                            }

                                            if (Strings.isNotEmpty(fieldName)) {
                                                propInfos[i] = JdbcUtil.getSubPropInfo(targetClass, fieldName);

                                                if (propInfos[i] != null) {
                                                    columnLabels[i] = fieldName;
                                                }
                                            }
                                        } else {
                                            columnLabels[i] = newColumnName;
                                        }

                                        if (propInfos[i] == null) {
                                            if (ignoreNonMatchedColumns) {
                                                columnLabels[i] = null;
                                            } else {
                                                throw new IllegalArgumentException("No property in class: " + ClassUtil.getCanonicalClassName(targetClass)
                                                        + " mapping to column: " + columnLabels[i]);
                                            }
                                        } else {
                                            columnTypes[i] = propInfos[i].dbType;
                                            propInfos[i] = null;
                                        }
                                    } else {
                                        columnTypes[i] = propInfos[i].dbType;
                                    }
                                } else {
                                    columnLabels[i] = null;
                                    propInfos[i] = null;
                                    columnTypes[i] = null;
                                }
                            }
                        }

                        final Object result = entityInfo.createBeanResult();

                        for (int i = 0; i < columnCount; i++) {
                            if (columnLabels[i] == null) {
                                continue;
                            }

                            if (propInfos[i] == null) {
                                entityInfo.setPropValue(result, columnLabels[i], columnTypes[i].get(rs, i + 1));
                            } else {
                                propInfos[i].setPropValue(result, columnTypes[i].get(rs, i + 1));
                            }
                        }

                        return entityInfo.finishBeanResult(result);
                    }
                };
            } else {
                if ((columnNameFilter == null || Objects.equals(columnNameFilter, Fn.alwaysTrue()))
                        && (columnNameConverter == null || Objects.equals(columnNameConverter, Fn.identity()))) {
                    return new BiRowMapper<>() {
                        private final Type<? extends T> targetType = N.typeOf(targetClass);
                        private int columnCount = -1;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            if (columnCount != 1 && (columnCount = columnLabelList.size()) != 1) {
                                throw new IllegalArgumentException(
                                        "It's not supported to retrieve value from multiple columns: " + columnLabelList + " for type: " + targetClass);
                            }

                            return targetType.get(rs, 1);
                        }
                    };
                } else

                {
                    throw new IllegalArgumentException(
                            "'columnNameFilter' and 'columnNameConverter' are not supported to convert single column to target type: " + targetClass);
                }
            }
        }

        /**
         * Creates a stateful {@code BiRowMapper} for a target entity class, using a map to resolve
         * column name prefixes to nested property paths. This is useful for mapping results from JOIN
         * queries where columns are prefixed (e.g., {@code "u_id"}, {@code "a_street"}).
         *
         * <p>
         * <b>Warning:</b> The returned mapper is stateful and caches metadata upon first execution. It should not be
         * cached, shared across different query structures, or used in parallel streams.
         * </p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Maps "u_id" to user.id, "a_street" to user.address.street
         * Map<String, String> prefixMap = Map.of("u_", "user.", "a_", "user.address.");
         * BiRowMapper<User> mapper = BiRowMapper.to(User.class, prefixMap);
         * }</pre>
         *
         * @param <T> target entity type
         * @param entityClass the class to map rows to
         * @param prefixAndFieldNameMap a map where keys are column prefixes and values are corresponding property paths
         * @return a new stateful {@code BiRowMapper}. Do not cache or reuse across different query structures.
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(final Class<? extends T> entityClass, final Map<String, String> prefixAndFieldNameMap) {
            return to(entityClass, prefixAndFieldNameMap, false);
        }

        /**
         * Creates a stateful {@code BiRowMapper} with prefix-to-property mapping and an option to ignore
         * non-matched columns.
         *
         * <p>
         * <b>Warning:</b> The returned mapper is stateful and caches metadata upon first execution. It should not be
         * cached, shared across different query structures, or used in parallel streams.
         * </p>
         *
         * @param <T> target entity type
         * @param entityClass the class to map rows to
         * @param prefixAndFieldNameMap a map where keys are column prefixes and values are corresponding property paths
         * @param ignoreNonMatchedColumns if {@code true}, columns without a matching property are ignored
         * @return a new stateful {@code BiRowMapper}. Do not cache or reuse across different query structures.
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(final Class<? extends T> entityClass, final Map<String, String> prefixAndFieldNameMap,
                final boolean ignoreNonMatchedColumns) {
            if (N.isEmpty(prefixAndFieldNameMap)) {
                return to(entityClass, ignoreNonMatchedColumns);
            }

            N.checkArgument(Beans.isBeanClass(entityClass), "{} is not an entity class", entityClass);

            final BeanInfo entityInfo = ParserUtil.getBeanInfo(entityClass);

            return new BiRowMapper<>() {
                private String[] columnLabels = null;
                private PropInfo[] propInfos;
                private Type<?>[] columnTypes = null;
                private int columnCount = -1;

                @Override
                public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {

                    if (columnLabels == null) {
                        final Map<String, String> column2FieldNameMap = JdbcUtil.getColumn2FieldNameMap(entityClass);

                        columnCount = columnLabelList.size();
                        columnLabels = columnLabelList.toArray(new String[columnCount]);
                        propInfos = new PropInfo[columnCount];
                        columnTypes = new Type[columnCount];

                        for (int i = 0; i < columnCount; i++) {
                            propInfos[i] = entityInfo.getPropInfo(columnLabels[i]);

                            if (propInfos[i] == null) {
                                String fieldName = column2FieldNameMap.get(columnLabels[i]);

                                if (Strings.isEmpty(fieldName)) {
                                    fieldName = column2FieldNameMap.get(columnLabels[i].toLowerCase());
                                }

                                if (Strings.isNotEmpty(fieldName)) {
                                    propInfos[i] = entityInfo.getPropInfo(fieldName);
                                }
                            }

                            if (propInfos[i] == null) {
                                final String newColumnName = JdbcUtil.checkPrefix(entityInfo, columnLabels[i], prefixAndFieldNameMap, columnLabelList);
                                propInfos[i] = JdbcUtil.getSubPropInfo(entityClass, newColumnName);

                                if (propInfos[i] == null) {
                                    String fieldName = column2FieldNameMap.get(newColumnName);

                                    if (Strings.isEmpty(fieldName)) {
                                        fieldName = column2FieldNameMap.get(newColumnName.toLowerCase());
                                    }

                                    if (Strings.isNotEmpty(fieldName)) {
                                        propInfos[i] = JdbcUtil.getSubPropInfo(entityClass, fieldName);

                                        if (propInfos[i] != null) {
                                            columnLabels[i] = fieldName;
                                        }
                                    }
                                } else {
                                    columnLabels[i] = newColumnName;
                                }

                                if (propInfos[i] == null) {
                                    if (ignoreNonMatchedColumns) {
                                        columnLabels[i] = null;
                                    } else {
                                        throw new IllegalArgumentException("No property in class: " + ClassUtil.getCanonicalClassName(entityClass)
                                                + " mapping to column: " + columnLabels[i]);
                                    }
                                } else {
                                    columnTypes[i] = propInfos[i].dbType;
                                    propInfos[i] = null;
                                }
                            } else {
                                columnTypes[i] = propInfos[i].dbType;
                            }
                        }
                    }

                    final Object result = entityInfo.createBeanResult();

                    for (int i = 0; i < columnCount; i++) {
                        if (columnLabels[i] == null) {
                            continue;
                        }

                        if (propInfos[i] == null) {
                            entityInfo.setPropValue(result, columnLabels[i], columnTypes[i].get(rs, i + 1));
                        } else {
                            propInfos[i].setPropValue(result, columnTypes[i].get(rs, i + 1));
                        }
                    }

                    return entityInfo.finishBeanResult(result);
                }
            };
        }

        // Removed because: The method toMap(Predicate<Object>) is ambiguous for the type Jdbc.BiRowMapper
        //    /**
        //     * Creates a BiRowMapper that converts rows to a Map with value filtering.
        //     * Only values that pass the filter predicate are included in the map.
        //     *
        //     * <p><b>Usage Examples:</b></p>
        //     * <pre>{@code
        //     * BiRowMapper<Map<String, Object>> mapper = BiRowMapper.toMap(
        //     * value -> value != null  // Exclude null values
        //     * );
        //     * }</pre>
        //     *
        //     * @param valueFilter the predicate to test values
        //     * @return a BiRowMapper that produces a filtered Map
        //     */
        //    static BiRowMapper<Map<String, Object>> toMap(final Predicate<Object> valueFilter) {
        //        return (rs, columnLabels) -> {
        //            final int columnCount = columnLabels.size();
        //            final Map<String, Object> result = N.newHashMap(columnCount);
        //
        //            Object value = null;
        //
        //            for (int i = 1; i <= columnCount; i++) {
        //                value = JdbcUtil.getColumnValue(rs, i);
        //
        //                if (valueFilter.test(value)) {
        //                    result.put(columnLabels.get(i - 1), value);
        //                }
        //            }
        //
        //            return result;
        //        };
        //    }

        /**
         * Creates a {@code BiRowMapper} that converts a row to a {@code Map}, including only the
         * entries that satisfy the given key-value filter. This method allows both filtering
         * based on column names and values, and customizing the map implementation.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Creates a TreeMap excluding internal columns and null values.
         * BiRowMapper<Map<String, Object>> mapper = BiRowMapper.toMap(
         *     (key, value) -> !key.startsWith("_") && value != null,
         *     (size) -> new TreeMap<>()
         * );
         * }</pre>
         *
         * @param valueFilter a bi-predicate to test column names and their corresponding values
         * @param mapSupplier a function that provides a new map instance, given the column count
         * @return a {@code BiRowMapper} that produces a filtered {@code Map}
         */
        static BiRowMapper<Map<String, Object>> toMap(final BiPredicate<String, Object> valueFilter,
                final IntFunction<? extends Map<String, Object>> mapSupplier) {
            return (rs, columnLabels) -> {
                final int columnCount = columnLabels.size();
                final Map<String, Object> result = mapSupplier.apply(columnCount);

                String columnName = null;
                Object value = null;

                for (int i = 1; i <= columnCount; i++) {
                    columnName = columnLabels.get(i - 1);
                    value = JdbcUtil.getColumnValue(rs, i);

                    if (valueFilter.test(columnName, value)) {
                        result.put(columnName, value);
                    }
                }

                return result;
            };
        }

        /**
         * Creates a stateful {@code BiRowMapper} that converts a row to a {@code Map} using a custom
         * {@code RowExtractor} and then filters the results. This method is useful when you need
         * custom value extraction logic (via {@code RowExtractor}) combined with filtering.
         *
         * <p>
         * <b>Warning:</b> The returned mapper is stateful because it reuses an internal array for the extractor.
         * It should not be cached, shared, or used in parallel streams.
         * </p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Extract using custom RowExtractor and filter out null values
         * RowExtractor extractor = RowExtractor.createBy(User.class);
         * BiRowMapper<Map<String, Object>> mapper = BiRowMapper.toMap(
         *     extractor,
         *     (key, value) -> value != null,
         *     IntFunctions.ofLinkedHashMap()
         * );
         * }</pre>
         *
         * @param rowExtractor the custom extractor to get values from the {@code ResultSet} row
         * @param valueFilter a bi-predicate to test column names and their corresponding values
         * @param mapSupplier a function that provides a new map instance, given the column count
         * @return a new stateful {@code BiRowMapper}.
         */
        @SequentialOnly
        @Stateful
        static BiRowMapper<Map<String, Object>> toMap(final RowExtractor rowExtractor, final BiPredicate<String, Object> valueFilter,
                final IntFunction<? extends Map<String, Object>> mapSupplier) {
            return new BiRowMapper<>() {
                private Object[] outputValuesForRowExtractor = null;

                @Override
                public Map<String, Object> apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    final int columnCount = columnLabels.size();

                    if (outputValuesForRowExtractor == null) {
                        outputValuesForRowExtractor = new Object[columnCount];
                    }

                    rowExtractor.accept(rs, outputValuesForRowExtractor);

                    final Map<String, Object> result = mapSupplier.apply(columnCount);

                    String columnName = null;

                    for (int i = 0; i < columnCount; i++) {
                        columnName = columnLabels.get(i);

                        if (valueFilter.test(columnName, outputValuesForRowExtractor[i])) {
                            result.put(columnName, outputValuesForRowExtractor[i]);
                        }
                    }

                    return result;
                }
            };
        }

        /**
         * Creates a stateful {@code BiRowMapper} that converts a row to a {@code Map}, applying a
         * conversion function to each column name to generate the map keys.
         *
         * <p>
         * <b>Warning:</b> The returned mapper is stateful as it caches the converted key names.
         * It should not be cached, shared across different query structures, or used in parallel streams.
         * </p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Converts "FIRST_NAME" to "firstName" for map keys.
         * BiRowMapper<Map<String, Object>> mapper = BiRowMapper.toMap(
         * N::toCamelCase
         * );
         * }</pre>
         *
         * @param columnNameConverter a function to transform column names into map keys
         * @return a new stateful {@code BiRowMapper}.
         */
        @SequentialOnly
        @Stateful
        static BiRowMapper<Map<String, Object>> toMap(final Function<? super String, String> columnNameConverter) {
            return toMap(columnNameConverter, IntFunctions.ofMap());
        }

        /**
         * Creates a stateful {@code BiRowMapper} that converts a row to a custom {@code Map}, applying a
         * conversion function to each column name to generate the map keys. This method is useful for
         * transforming column names (e.g., from SNAKE_CASE to camelCase) while using a custom map type.
         *
         * <p>
         * <b>Warning:</b> The returned mapper is stateful as it caches the converted key names.
         * It should not be cached, shared across different query structures, or used in parallel streams.
         * </p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Converts "FIRST_NAME" to "firstName", using a LinkedHashMap
         * BiRowMapper<Map<String, Object>> mapper = BiRowMapper.toMap(
         *     N::toCamelCase,
         *     IntFunctions.ofLinkedHashMap()
         * );
         * }</pre>
         *
         * @param columnNameConverter a function to transform column names into map keys
         * @param mapSupplier a function that provides a new map instance, given the column count
         * @return a new stateful {@code BiRowMapper}.
         */
        @SequentialOnly
        @Stateful
        static BiRowMapper<Map<String, Object>> toMap(final Function<? super String, String> columnNameConverter,
                final IntFunction<? extends Map<String, Object>> mapSupplier) {
            return new BiRowMapper<>() {
                private String[] keyNames = null;

                @Override
                public Map<String, Object> apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    if (keyNames == null) {
                        keyNames = new String[columnLabels.size()];

                        for (int i = 0, size = columnLabels.size(); i < size; i++) {
                            keyNames[i] = columnNameConverter.apply(columnLabels.get(i));
                        }
                    }

                    final int columnCount = keyNames.length;
                    final Map<String, Object> result = mapSupplier.apply(columnCount);

                    for (int i = 1; i <= columnCount; i++) {
                        result.put(keyNames[i - 1], JdbcUtil.getColumnValue(rs, i));
                    }

                    return result;
                }
            };
        }

        /**
         * Creates a stateful {@code BiRowMapper} that converts a row to a {@code Map} using a custom {@code RowExtractor}.
         * This method is useful when you need custom value extraction logic for all columns, producing a HashMap
         * with original column names as keys.
         *
         * <p>
         * <b>Warning:</b> The returned mapper is stateful as it reuses an internal array.
         * It should not be cached, shared, or used in parallel streams.
         * </p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Use a custom RowExtractor for specialized value extraction
         * RowExtractor extractor = RowExtractor.createBy(User.class);
         * BiRowMapper<Map<String, Object>> mapper = BiRowMapper.toMap(extractor);
         * }</pre>
         *
         * @param rowExtractor the custom extractor to get values from the {@code ResultSet} row
         * @return a new stateful {@code BiRowMapper}.
         */
        @SequentialOnly
        @Stateful
        static BiRowMapper<Map<String, Object>> toMap(final RowExtractor rowExtractor) {
            return new BiRowMapper<>() {
                private Object[] outputValuesForRowExtractor = null;

                @Override
                public Map<String, Object> apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    final int columnCount = columnLabels.size();

                    if (outputValuesForRowExtractor == null) {
                        outputValuesForRowExtractor = new Object[columnCount];
                    }

                    rowExtractor.accept(rs, outputValuesForRowExtractor);

                    final Map<String, Object> result = N.newHashMap(columnCount);

                    for (int i = 0; i < columnCount; i++) {
                        result.put(columnLabels.get(i), outputValuesForRowExtractor[i]);
                    }

                    return result;
                }
            };
        }

        /**
         * Creates a stateful {@code BiRowMapper} that converts a row to a custom {@code Map} using a
         * {@code RowExtractor} and applying a conversion to the column names for keys. This is the most
         * flexible toMap variant, combining custom value extraction, key name transformation, and custom map type.
         *
         * <p>
         * <b>Warning:</b> The returned mapper is stateful as it caches converted key names and reuses an internal array.
         * It should not be cached, shared across different query structures, or used in parallel streams.
         * </p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // Combine custom extractor, name conversion, and TreeMap
         * RowExtractor extractor = RowExtractor.createBy(User.class);
         * BiRowMapper<Map<String, Object>> mapper = BiRowMapper.toMap(
         *     extractor,
         *     N::toCamelCase,
         *     (size) -> new TreeMap<>()
         * );
         * }</pre>
         *
         * @param rowExtractor the custom extractor to get values from the row
         * @param columnNameConverter a function to transform column names into map keys
         * @param mapSupplier a function that provides a new map instance, given the column count
         * @return a new stateful {@code BiRowMapper}.
         */
        @SequentialOnly
        @Stateful
        static BiRowMapper<Map<String, Object>> toMap(final RowExtractor rowExtractor, final Function<? super String, String> columnNameConverter,
                final IntFunction<? extends Map<String, Object>> mapSupplier) {
            return new BiRowMapper<>() {
                private Object[] outputValuesForRowExtractor = null;
                private String[] keyNames = null;

                @Override
                public Map<String, Object> apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    final int columnCount = columnLabels.size();

                    if (outputValuesForRowExtractor == null) {
                        outputValuesForRowExtractor = new Object[columnCount];
                        keyNames = new String[columnCount];

                        for (int i = 0; i < columnCount; i++) {
                            keyNames[i] = columnNameConverter.apply(columnLabels.get(i));
                        }
                    }

                    rowExtractor.accept(rs, outputValuesForRowExtractor);

                    final Map<String, Object> result = mapSupplier.apply(columnCount);

                    for (int i = 0; i < columnCount; i++) {
                        result.put(keyNames[i], outputValuesForRowExtractor[i]);
                    }

                    return result;
                }
            };
        }

        /**
         * Creates a {@code BiRowMapper} that maps all columns of a row to an {@code Object[]}.
         * The specified {@code columnGetterForAll} is used to extract the value from each column.
         *
         * @param columnGetterForAll the {@code ColumnGetter} used for every column
         * @return a {@code BiRowMapper} that produces an {@code Object[]}
         */
        @Beta
        static BiRowMapper<Object[]> toArray(final ColumnGetter<?> columnGetterForAll) {
            return (rs, columnLabels) -> {
                final int columnCount = columnLabels.size();
                final Object[] result = new Object[columnCount];

                for (int i = 0; i < columnCount; i++) {
                    result[i] = columnGetterForAll.apply(rs, i + 1);
                }

                return result;
            };
        }

        /**
         * Creates a {@code BiRowMapper} that maps all columns of a row to a {@code List<Object>}.
         * The specified {@code columnGetterForAll} is used to extract the value from each column.
         *
         * @param columnGetterForAll the {@code ColumnGetter} used for every column
         * @return a {@code BiRowMapper} that produces a {@code List<Object>}
         */
        @Beta
        static BiRowMapper<List<Object>> toList(final ColumnGetter<?> columnGetterForAll) {
            return toCollection(columnGetterForAll, IntFunctions.ofList());
        }

        /**
         * Creates a {@code BiRowMapper} that maps all columns of a row to a {@code Collection}.
         *
         * @param <C> collection type
         * @param columnGetterForAll the {@code ColumnGetter} used for every column
         * @param supplier a function that takes the column count and returns a new {@code Collection} instance
         * @return a {@code BiRowMapper} that produces a {@code Collection}
         */
        @Beta
        static <C extends Collection<?>> BiRowMapper<C> toCollection(final ColumnGetter<?> columnGetterForAll, final IntFunction<? extends C> supplier) {
            return (rs, columnLabels) -> {
                final int columnCount = columnLabels.size();

                final Collection<Object> result = (Collection<Object>) supplier.apply(columnCount);

                for (int i = 0; i < columnCount; i++) {
                    result.add(columnGetterForAll.apply(rs, i + 1));
                }

                return (C) result;
            };
        }

        /**
         * Creates a stateful {@code BiRowMapper} that maps a row to a reusable {@code DisposableObjArray}.
         * This is an optimization to reduce garbage collection by reusing the same array for each row.
         *
         * <p>
         * <b>Warning:</b> The returned mapper is highly stateful. The returned {@code DisposableObjArray}
         * is a wrapper around an internal array that will be overwritten on the next call.
         * You must process or copy its contents before the next row is mapped. Do not cache, share, or
         * use this mapper in parallel streams.
         * </p>
         *
         * @return a stateful {@code BiRowMapper} for high-performance, single-threaded row processing
         */
        @Beta
        @SequentialOnly
        @Stateful
        static BiRowMapper<DisposableObjArray> toDisposableObjArray() {
            return new BiRowMapper<>() {
                private DisposableObjArray disposable = null;
                private int columnCount = -1;
                private Object[] output = null;

                @Override
                public DisposableObjArray apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    if (disposable == null) {
                        columnCount = JdbcUtil.getColumnCount(rs);
                        output = new Object[columnCount];
                        disposable = DisposableObjArray.wrap(output);
                    }

                    for (int i = 0; i < columnCount; i++) {
                        output[i] = JdbcUtil.getColumnValue(rs, i + 1);
                    }

                    return disposable;
                }
            };
        }

        /**
         * Creates a stateful {@code BiRowMapper} that maps a row to a reusable {@code DisposableObjArray},
         * using type information from an entity class to perform type conversions for each column.
         *
         * <p>
         * <b>Warning:</b> The returned mapper is highly stateful. The returned {@code DisposableObjArray}
         * is a wrapper around an internal array that will be overwritten on the next call.
         * You must process or copy its contents before the next row is mapped. Do not cache, share, or
         * use this mapper in parallel streams.
         * </p>
         *
         * @param entityClass the class used to infer the data type for each column based on matching property names
         * @return a stateful {@code BiRowMapper} for high-performance, type-aware, single-threaded row processing
         */
        @Beta
        @SequentialOnly
        @Stateful
        static BiRowMapper<DisposableObjArray> toDisposableObjArray(final Class<?> entityClass) {
            N.checkArgNotNull(entityClass, cs.entityClass);

            return new BiRowMapper<>() {
                private DisposableObjArray disposable = null;
                private int columnCount = -1;
                private Object[] output = null;

                private Type<?>[] columnTypes = null;

                @Override
                public DisposableObjArray apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    if (disposable == null) {
                        columnCount = columnLabels.size();
                        columnTypes = new Type[columnCount];

                        final BeanInfo entityInfo = ParserUtil.getBeanInfo(entityClass);
                        final Map<String, String> column2FieldNameMap = JdbcUtil.getColumn2FieldNameMap(entityClass);
                        PropInfo propInfo = null;

                        for (int i = 0; i < columnCount; i++) {
                            propInfo = entityInfo.getPropInfo(columnLabels.get(i));

                            if (propInfo == null) {
                                String fieldName = column2FieldNameMap.get(columnLabels.get(i));

                                if (Strings.isEmpty(fieldName)) {
                                    fieldName = column2FieldNameMap.get(columnLabels.get(i).toLowerCase());
                                }

                                if (Strings.isNotEmpty(fieldName)) {
                                    propInfo = entityInfo.getPropInfo(fieldName);
                                }
                            }

                            if (propInfo == null) {
                                //    throw new IllegalArgumentException(
                                //            "No property in class: " + ClassUtil.getCanonicalClassName(entityClass) + " mapping to column: " + columnLabels.get(i));
                            } else {
                                columnTypes[i] = propInfo.dbType;
                            }
                        }

                        output = new Object[columnCount];
                        disposable = DisposableObjArray.wrap(output);
                    }

                    for (int i = 0; i < columnCount; i++) {
                        output[i] = columnTypes[i] == null ? JdbcUtil.getColumnValue(rs, i + 1) : columnTypes[i].get(rs, i + 1);
                    }

                    return disposable;
                }
            };
        }

        /**
         * Creates a new {@code BiRowMapperBuilder} with a default column getter of {@code ColumnGetter.GET_OBJECT}.
         *
         * @return a new {@code BiRowMapperBuilder}
         */
        static BiRowMapperBuilder builder() {
            return builder(ColumnGetter.GET_OBJECT);
        }

        /**
         * Creates a new {@code BiRowMapperBuilder} with the specified default column getter. This default
         * will be used for any column whose type is not explicitly configured by name in the builder.
         *
         * @param defaultColumnGetter the default {@code ColumnGetter} to use for unconfigured columns
         * @return a new {@code BiRowMapperBuilder}
         */
        static BiRowMapperBuilder builder(final ColumnGetter<?> defaultColumnGetter) {
            return new BiRowMapperBuilder(defaultColumnGetter);
        }

        /**
         * A fluent builder for creating customized, stateful {@code BiRowMapper} instances.
         * This builder allows specifying different {@code ColumnGetter}s for specific column names
         * and provides a terminal method to create a mapper for a target class.
         *
         * <p>
         * <b>Warning:</b> All {@code BiRowMapper} instances created by this builder are stateful,
         * as they cache metadata upon first execution. They should not be cached, shared, or used
         * in parallel streams.
         * </p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * BiRowMapper<User> userMapper = BiRowMapper.builder()
         * .getString("first_name")
         * .getInt("user_age")
         * .to(User.class);   // Assumes User has properties 'firstName' and 'userAge'
         * }</pre>
         */
        @SequentialOnly
        class BiRowMapperBuilder {
            private final ColumnGetter<?> defaultColumnGetter;
            private final Map<String, ColumnGetter<?>> columnGetterMap;

            BiRowMapperBuilder(final ColumnGetter<?> defaultColumnGetter) {
                this.defaultColumnGetter = defaultColumnGetter;

                columnGetterMap = new HashMap<>(9);
            }

            /**
             * Configures the mapper to retrieve a {@code boolean} value from the specified column.
             *
             * @param columnName the name of the column
             * @return this builder instance for method chaining
             */
            public BiRowMapperBuilder getBoolean(final String columnName) {
                return get(columnName, ColumnGetter.GET_BOOLEAN);
            }

            /**
             * Configures the mapper to retrieve a {@code byte} value from the specified column.
             *
             * @param columnName the name of the column
             * @return this builder instance for method chaining
             */
            public BiRowMapperBuilder getByte(final String columnName) {
                return get(columnName, ColumnGetter.GET_BYTE);
            }

            /**
             * Configures the mapper to retrieve a {@code short} value from the specified column.
             *
             * @param columnName the name of the column
             * @return this builder instance for method chaining
             */
            public BiRowMapperBuilder getShort(final String columnName) {
                return get(columnName, ColumnGetter.GET_SHORT);
            }

            /**
             * Configures the mapper to retrieve an {@code int} value from the specified column.
             *
             * @param columnName the name of the column
             * @return this builder instance for method chaining
             */
            public BiRowMapperBuilder getInt(final String columnName) {
                return get(columnName, ColumnGetter.GET_INT);
            }

            /**
             * Configures the mapper to retrieve a {@code long} value from the specified column.
             *
             * @param columnName the name of the column
             * @return this builder instance for method chaining
             */
            public BiRowMapperBuilder getLong(final String columnName) {
                return get(columnName, ColumnGetter.GET_LONG);
            }

            /**
             * Configures the mapper to retrieve a {@code float} value from the specified column.
             *
             * @param columnName the name of the column
             * @return this builder instance for method chaining
             */
            public BiRowMapperBuilder getFloat(final String columnName) {
                return get(columnName, ColumnGetter.GET_FLOAT);
            }

            /**
             * Configures the mapper to retrieve a {@code double} value from the specified column.
             *
             * @param columnName the name of the column
             * @return this builder instance for method chaining
             */
            public BiRowMapperBuilder getDouble(final String columnName) {
                return get(columnName, ColumnGetter.GET_DOUBLE);
            }

            /**
             * Configures the mapper to retrieve a {@code BigDecimal} value from the specified column.
             *
             * @param columnName the name of the column
             * @return this builder instance for method chaining
             */
            public BiRowMapperBuilder getBigDecimal(final String columnName) {
                return get(columnName, ColumnGetter.GET_BIG_DECIMAL);
            }

            /**
             * Configures the mapper to retrieve a {@code String} value from the specified column.
             *
             * @param columnName the name of the column
             * @return this builder instance for method chaining
             */
            public BiRowMapperBuilder getString(final String columnName) {
                return get(columnName, ColumnGetter.GET_STRING);
            }

            /**
             * Configures the mapper to retrieve a {@code java.sql.Date} value from the specified column.
             *
             * @param columnName the name of the column
             * @return this builder instance for method chaining
             */
            public BiRowMapperBuilder getDate(final String columnName) {
                return get(columnName, ColumnGetter.GET_DATE);
            }

            /**
             * Configures the mapper to retrieve a {@code java.sql.Time} value from the specified column.
             *
             * @param columnName the name of the column
             * @return this builder instance for method chaining
             */
            public BiRowMapperBuilder getTime(final String columnName) {
                return get(columnName, ColumnGetter.GET_TIME);
            }

            /**
             * Configures the mapper to retrieve a {@code java.sql.Timestamp} value from the specified column.
             *
             * @param columnName the name of the column
             * @return this builder instance for method chaining
             */
            public BiRowMapperBuilder getTimestamp(final String columnName) {
                return get(columnName, ColumnGetter.GET_TIMESTAMP);
            }

            /**
             * Configures the mapper to retrieve an {@code Object} value from the specified column.
             *
             * @param columnName the name of the column
             * @return this builder instance for method chaining
             * @deprecated The default behavior already uses {@code ColumnGetter.GET_OBJECT} if no specific getter is set.
             */
            @Deprecated
            public BiRowMapperBuilder getObject(final String columnName) {
                return get(columnName, ColumnGetter.GET_OBJECT);
            }

            /**
             * Configures the mapper to retrieve an object of a specific type from the specified column.
             *
             * @param columnName the name of the column
             * @param type the target class type to convert the column value to
             * @return this builder instance for method chaining
             */
            public BiRowMapperBuilder getObject(final String columnName, final Class<?> type) {
                return get(columnName, ColumnGetter.get(type));
            }

            /**
             * Configures the mapper to use a custom {@code ColumnGetter} for the specified column name.
             *
             * @param columnName the name of the column
             * @param columnGetter the custom {@code ColumnGetter} to use for this column
             * @return this builder instance for method chaining
             * @throws IllegalArgumentException if {@code columnName} is null/empty or {@code columnGetter} is null
             */
            public BiRowMapperBuilder get(final String columnName, final ColumnGetter<?> columnGetter) throws IllegalArgumentException {
                N.checkArgNotNull(columnName, cs.columnName);
                N.checkArgNotNull(columnGetter, cs.columnGetter);

                columnGetterMap.put(columnName, columnGetter);

                return this;
            }

            ColumnGetter<?>[] initColumnGetter(final List<String> columnLabelList) { //NOSONAR
                final int rsColumnCount = columnLabelList.size();
                final ColumnGetter<?>[] rsColumnGetters = new ColumnGetter<?>[rsColumnCount];

                int cnt = 0;
                ColumnGetter<?> columnGetter = null;

                for (int i = 0; i < rsColumnCount; i++) {
                    columnGetter = columnGetterMap.get(columnLabelList.get(i));

                    if (columnGetter != null) {
                        cnt++;
                    }

                    rsColumnGetters[i] = columnGetter == null ? defaultColumnGetter : columnGetter;
                }

                if (cnt < columnGetterMap.size()) {
                    final List<String> tmp = new ArrayList<>(columnGetterMap.keySet());
                    tmp.removeAll(columnLabelList);
                    throw new IllegalArgumentException("ColumnGetters for " + tmp + " are not found in ResultSet columns: " + columnLabelList);
                }

                return rsColumnGetters;
            }

            /**
             * Builds and returns a stateful {@code BiRowMapper} that maps each row to an instance of the specified {@code targetClass}.
             * It uses the configured column getters for specific columns and the default getter for others.
             *
             * <p>
             * <b>Warning:</b> The returned mapper is stateful. Do not cache, share, or use it in parallel streams.
             * </p>
             *
             * @param <T> target type
             * @param targetClass the class to map rows to
             * @return a new stateful {@code BiRowMapper<T>}
             */
            @SequentialOnly
            @Stateful
            public <T> BiRowMapper<T> to(final Class<? extends T> targetClass) {
                return to(targetClass, false);
            }

            /**
             * Builds and returns a stateful {@code BiRowMapper} that maps each row to an instance of the specified {@code targetClass},
             * with an option to ignore columns that don't match any property.
             *
             * <p>
             * <b>Warning:</b> The returned mapper is stateful. Do not cache, share, or use it in parallel streams.
             * </p>
             *
             * @param <T> target type
             * @param targetClass the class to map rows to
             * @param ignoreNonMatchedColumns if {@code true}, columns without a corresponding property are ignored
             * @return a new stateful {@code BiRowMapper<T>}
             */
            @SequentialOnly
            @Stateful
            public <T> BiRowMapper<T> to(final Class<? extends T> targetClass, final boolean ignoreNonMatchedColumns) {
                if (Object[].class.isAssignableFrom(targetClass)) {
                    return new BiRowMapper<>() {
                        private ColumnGetter<?>[] rsColumnGetters = null;
                        private int rsColumnCount = -1;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);
                            }

                            final Object[] a = Array.newInstance(targetClass.getComponentType(), rsColumnCount);

                            for (int i = 0; i < rsColumnCount; i++) {
                                a[i] = rsColumnGetters[i].apply(rs, i + 1);
                            }

                            return (T) a;
                        }
                    };
                } else if (List.class.isAssignableFrom(targetClass)) {
                    return new BiRowMapper<>() {
                        private ColumnGetter<?>[] rsColumnGetters = null;
                        private int rsColumnCount = -1;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);
                            }

                            @SuppressWarnings("rawtypes")
                            final Collection<Object> c = N.newCollection((Class<Collection>) targetClass, rsColumnCount);

                            for (int i = 0; i < rsColumnCount; i++) {
                                c.add(rsColumnGetters[i].apply(rs, i + 1));
                            }

                            return (T) c;
                        }
                    };
                } else if (Map.class.isAssignableFrom(targetClass)) {
                    return new BiRowMapper<>() {
                        private ColumnGetter<?>[] rsColumnGetters = null;
                        private int rsColumnCount = -1;
                        private String[] columnLabels = null;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {

                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);

                                columnLabels = columnLabelList.toArray(new String[rsColumnCount]);
                            }

                            @SuppressWarnings("rawtypes")
                            final Map<String, Object> m = N.newMap((Class<Map>) targetClass, rsColumnCount);

                            for (int i = 0; i < rsColumnCount; i++) {
                                m.put(columnLabels[i], rsColumnGetters[i].apply(rs, i + 1));
                            }

                            return (T) m;
                        }
                    };
                } else if (Beans.isBeanClass(targetClass))

                {
                    return new BiRowMapper<>() {
                        private final BeanInfo entityInfo = ParserUtil.getBeanInfo(targetClass);

                        private int rsColumnCount = -1;
                        private ColumnGetter<?>[] rsColumnGetters = null;
                        private String[] columnLabels = null;
                        private PropInfo[] propInfos;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);

                                columnLabels = columnLabelList.toArray(new String[rsColumnCount]);
                                final PropInfo[] localPropInfos = new PropInfo[rsColumnCount];

                                final Map<String, String> column2FieldNameMap = JdbcUtil.getColumn2FieldNameMap(targetClass);

                                for (int i = 0; i < rsColumnCount; i++) {
                                    localPropInfos[i] = entityInfo.getPropInfo(columnLabels[i]);

                                    if (localPropInfos[i] == null) {
                                        String fieldName = column2FieldNameMap.get(columnLabels[i]);

                                        if (Strings.isEmpty(fieldName)) {
                                            fieldName = column2FieldNameMap.get(columnLabels[i].toLowerCase());
                                        }

                                        if (Strings.isNotEmpty(fieldName)) {
                                            localPropInfos[i] = entityInfo.getPropInfo(fieldName);
                                        }
                                    }

                                    if (localPropInfos[i] == null) {
                                        if (ignoreNonMatchedColumns) {
                                            columnLabels[i] = null;
                                        } else {
                                            throw new IllegalArgumentException("No property in class: " + ClassUtil.getCanonicalClassName(targetClass)
                                                    + " mapping to column: " + columnLabels[i]);
                                        }
                                    } else {
                                        if (rsColumnGetters[i] == ColumnGetter.GET_OBJECT) {
                                            rsColumnGetters[i] = ColumnGetter.get(localPropInfos[i].dbType);
                                        }
                                    }
                                }

                                propInfos = localPropInfos;
                            }

                            final Object result = entityInfo.createBeanResult();

                            for (int i = 0; i < rsColumnCount; i++) {
                                if (columnLabels[i] == null) {
                                    continue;
                                }

                                propInfos[i].setPropValue(result, rsColumnGetters[i].apply(rs, i + 1));
                            }

                            return entityInfo.finishBeanResult(result);
                        }
                    };
                } else {
                    return new BiRowMapper<>() {
                        private int rsColumnCount = -1;
                        private ColumnGetter<?>[] rsColumnGetters = null;

                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            if (rsColumnGetters == null) {
                                rsColumnCount = columnLabelList.size();
                                rsColumnGetters = initColumnGetter(columnLabelList);

                                if (rsColumnGetters[0] == ColumnGetter.GET_OBJECT) {
                                    rsColumnGetters[0] = ColumnGetter.get(N.typeOf(targetClass));
                                }
                            }

                            if (rsColumnCount != 1 && (rsColumnCount = columnLabelList.size()) != 1) {
                                throw new IllegalArgumentException(
                                        "It's not supported to retrieve value from multiple columns: " + columnLabelList + " for type: " + targetClass);
                            }

                            return (T) rsColumnGetters[0].apply(rs, 1);
                        }
                    };
                }
            }
        }
    }

    /**
     * A functional interface for consuming a single row of a {@code ResultSet} without returning a value.
     * This is typically used for side effects, such as printing rows or adding them to a collection.
     *
     * <p><b>Note:</b> If you need to access column labels or count within the consumer, consider using
     * {@link BiRowConsumer} for better performance when processing multiple rows, as it avoids
     * repeatedly fetching metadata.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Example: Printing each row's data
     * RowConsumer printer = rs -> {
     *     System.out.println("ID: " + rs.getInt("id") + ", Name: " + rs.getString("name"));
     * };
     * // preparedQuery.forEach(printer);
     * }</pre>
     */
    @FunctionalInterface
    public interface RowConsumer extends Throwables.Consumer<ResultSet, SQLException> {

        /**
         * A {@code RowConsumer} that performs no operation.
         */
        RowConsumer DO_NOTHING = rs -> {
        };

        /**
         * Performs this operation on the given {@code ResultSet}.
         *
         * @param rs the {@code ResultSet} positioned at the current row to be consumed.
         * @throws SQLException if a database access error occurs.
         */
        @Override
        void accept(ResultSet rs) throws SQLException;

        /**
         * Returns a composed {@code RowConsumer} that performs, in sequence, this
         * operation followed by the {@code after} operation.
         *
         * @param after the operation to perform after this operation.
         * @return a composed {@code RowConsumer} that performs in sequence this operation followed by the {@code after} operation.
         * @throws IllegalArgumentException if {@code after} is {@code null}.
         */
        default RowConsumer andThen(final Throwables.Consumer<? super ResultSet, SQLException> after) {
            N.checkArgNotNull(after);

            return rs -> {
                accept(rs);
                after.accept(rs);
            };
        }

        /**
         * Converts this {@code RowConsumer} to a {@link BiRowConsumer}, which also accepts a list of column labels.
         * The resulting {@code BiRowConsumer} will ignore the column labels parameter.
         *
         * @return a {@code BiRowConsumer} that delegates to this consumer.
         */
        default BiRowConsumer toBiRowConsumer() {
            return (rs, columnLabels) -> accept(rs);
        }

        /**
         * Creates a stateful {@code RowConsumer} that iterates over all columns in each row and applies the specified action.
         *
         * <p><b>Warning:</b> The returned {@code RowConsumer} is stateful. It determines the column count
         * from the {@code ResultSet} on its first execution and reuses that count for all subsequent
         * rows. Therefore, it should not be reused across different queries with varying column counts
         * or used in parallel streams. A new instance should be created for each distinct query execution.</p>
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // A consumer that prints the value of each column in a row.
         * RowConsumer consumer = RowConsumer.create((rs, columnIndex) -> {
         * System.out.println("Column " + columnIndex + ": " + rs.getObject(columnIndex));
         * });
         * preparedQuery.forEach(consumer);
         * }</pre>
         *
         * @param consumerForAll the action to be performed for each column. The first parameter is the
         * {@code ResultSet}, and the second is the 1-based column index.
         * @return a new stateful {@code RowConsumer}.
         */
        @Beta
        @SequentialOnly
        @Stateful
        static RowConsumer create(final Throwables.ObjIntConsumer<? super ResultSet, SQLException> consumerForAll) {
            N.checkArgNotNull(consumerForAll, cs.consumerForAll);

            return new RowConsumer() {
                private int columnCount = -1;

                @Override
                public void accept(final ResultSet rs) throws SQLException {
                    if (columnCount < 0) {
                        columnCount = JdbcUtil.getColumnCount(rs);
                    }

                    for (int i = 0; i < columnCount; i++) {
                        consumerForAll.accept(rs, i + 1);
                    }
                }
            };
        }

        /**
         * Creates a stateful {@code RowConsumer} that converts each row into a reusable {@code DisposableObjArray}
         * and passes it to the specified consumer. This is an efficient way to process rows without creating
         * a new array for each row.
         *
         * <p><b>Warning:</b> The returned {@code RowConsumer} is stateful and reuses an internal buffer.
         * The provided {@code DisposableObjArray} is only valid within the scope of the consumer's lambda.
         * Do not store references to it. This consumer should not be reused across different queries
         * with varying column counts or used in parallel streams.</p>
         *
         * @param consumer the consumer to process the {@code DisposableObjArray} for each row.
         * @return a new stateful {@code RowConsumer}.
         */
        @Beta
        @SequentialOnly
        @Stateful
        static RowConsumer oneOff(final Consumer<DisposableObjArray> consumer) {
            N.checkArgNotNull(consumer, cs.consumer);

            return new RowConsumer() {
                private DisposableObjArray disposable = null;
                private int columnCount = -1;
                private Object[] output = null;

                @Override
                public void accept(final ResultSet rs) throws SQLException {
                    if (disposable == null) {
                        columnCount = JdbcUtil.getColumnCount(rs);
                        output = new Object[columnCount];
                        disposable = DisposableObjArray.wrap(output);
                    }

                    for (int i = 0; i < columnCount; i++) {
                        output[i] = JdbcUtil.getColumnValue(rs, i + 1);
                    }

                    consumer.accept(disposable);
                }
            };
        }

        /**
         * Creates a stateful {@code RowConsumer} that converts each row into a reusable {@code DisposableObjArray}
         * using type information from a specified entity class, then passes it to the consumer. This allows for
         * more precise type mapping from SQL types to Java types based on the entity's field definitions.
         *
         * <p><b>Warning:</b> The returned {@code RowConsumer} is stateful and reuses an internal buffer.
         * The provided {@code DisposableObjArray} is only valid within the scope of the consumer's lambda.
         * This consumer should not be reused across different queries or used in parallel streams.</p>
         *
         * @param entityClass the class used to infer column types for fetching values from the {@code ResultSet}.
         * @param consumer the consumer to process the typed {@code DisposableObjArray} for each row.
         * @return a new stateful {@code RowConsumer}.
         */
        @Beta
        @SequentialOnly
        @Stateful
        static RowConsumer oneOff(final Class<?> entityClass, final Consumer<DisposableObjArray> consumer) {
            N.checkArgNotNull(entityClass, cs.entityClass);
            N.checkArgNotNull(consumer, cs.consumer);

            return new RowConsumer() {
                private DisposableObjArray disposable = null;
                private int columnCount = -1;
                private Object[] output = null;

                private Type<?>[] columnTypes = null;

                @Override
                public void accept(final ResultSet rs) throws SQLException {
                    if (disposable == null) {
                        final List<String> columnLabels = JdbcUtil.getColumnLabelList(rs);

                        columnCount = columnLabels.size();
                        columnTypes = new Type[columnCount];

                        final BeanInfo entityInfo = ParserUtil.getBeanInfo(entityClass);
                        final Map<String, String> column2FieldNameMap = JdbcUtil.getColumn2FieldNameMap(entityClass);
                        PropInfo propInfo = null;

                        for (int i = 0; i < columnCount; i++) {
                            propInfo = entityInfo.getPropInfo(columnLabels.get(i));

                            if (propInfo == null) {
                                String fieldName = column2FieldNameMap.get(columnLabels.get(i));

                                if (Strings.isEmpty(fieldName)) {
                                    fieldName = column2FieldNameMap.get(columnLabels.get(i).toLowerCase());
                                }

                                if (Strings.isNotEmpty(fieldName)) {
                                    propInfo = entityInfo.getPropInfo(fieldName);
                                }
                            }

                            if (propInfo == null) {
                                //    throw new IllegalArgumentException(
                                //            "No property in class: " + ClassUtil.getCanonicalClassName(entityClass) + " mapping to column: " + columnLabels.get(i));
                            } else {
                                columnTypes[i] = propInfo.dbType;
                            }
                        }

                        output = new Object[columnCount];
                        disposable = DisposableObjArray.wrap(output);
                    }

                    for (int i = 0; i < columnCount; i++) {
                        output[i] = columnTypes[i] == null ? JdbcUtil.getColumnValue(rs, i + 1) : columnTypes[i].get(rs, i + 1);
                    }

                    consumer.accept(disposable);
                }
            };
        }
    }

    /**
     * A functional interface for consuming a single row of a {@code ResultSet} along with its column labels.
     * This is more efficient than {@link RowConsumer} when column metadata (like names or count) is needed
     * for processing multiple rows, as the metadata is fetched only once.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Example: Processing each row with column names
     * BiRowConsumer consumer = (rs, columnLabels) -> {
     *     System.out.print("Row: ");
     *     for (int i = 0; i < columnLabels.size(); i++) {
     *         System.out.print(columnLabels.get(i) + "=" + rs.getObject(i + 1) + " ");
     *     }
     *     System.out.println();
     * };
     * // preparedQuery.forEach(consumer);
     * }</pre>
     */
    @FunctionalInterface
    public interface BiRowConsumer extends Throwables.BiConsumer<ResultSet, List<String>, SQLException> {

        /**
         * A {@code BiRowConsumer} that performs no operation.
         */
        BiRowConsumer DO_NOTHING = (rs, cls) -> {
        };

        /**
         * Performs this operation on the given {@code ResultSet} and column labels.
         *
         * @param rs the {@code ResultSet} positioned at the current row to be consumed.
         * @param columnLabels the list of column labels from the result set metadata.
         * @throws SQLException if a database access error occurs.
         */
        @Override
        void accept(ResultSet rs, List<String> columnLabels) throws SQLException;

        /**
         * Returns a composed {@code BiRowConsumer} that performs, in sequence, this
         * operation followed by the {@code after} operation.
         *
         * @param after the operation to perform after this operation.
         * @return a composed {@code BiRowConsumer} that performs in sequence this operation followed by the {@code after} operation.
         * @throws IllegalArgumentException if {@code after} is {@code null}.
         */
        default BiRowConsumer andThen(final Throwables.BiConsumer<? super ResultSet, ? super List<String>, SQLException> after) {
            N.checkArgNotNull(after);

            return (rs, cls) -> {
                accept(rs, cls);
                after.accept(rs, cls);
            };
        }

        /**
         * Creates a {@code BiRowConsumer} that iterates over all columns in each row and applies the specified action.
         *
         * <p><b>Usage Examples:</b></p>
         * <pre>{@code
         * // A consumer that prints each column's name and value.
         * BiRowConsumer consumer = BiRowConsumer.create((rs, columnIndex) -> {
         * System.out.println("Column index " + columnIndex + ": " + rs.getObject(columnIndex));
         * });
         * preparedQuery.forEach(consumer);
         * }</pre>
         *
         * @param consumerForAll the action to be performed for each column. The first parameter is the
         * {@code ResultSet}, and the second is the 1-based column index.
         * @return a new {@code BiRowConsumer}.
         */
        @Beta
        static BiRowConsumer create(final Throwables.ObjIntConsumer<? super ResultSet, SQLException> consumerForAll) {
            N.checkArgNotNull(consumerForAll, cs.consumerForAll);

            return (rs, columnLabels) -> {
                final int columnCount = columnLabels.size();

                for (int i = 0; i < columnCount; i++) {
                    consumerForAll.accept(rs, i + 1);
                }
            };
        }

        /**
         * Creates a stateful {@code BiRowConsumer} that converts each row into a reusable {@code DisposableObjArray}
         * and passes it, along with column labels, to the specified consumer. This is an efficient way to
         * process rows without creating a new array for each row.
         *
         * <p><b>Warning:</b> The returned {@code BiRowConsumer} is stateful and reuses an internal buffer.
         * The provided {@code DisposableObjArray} is only valid within the scope of the consumer's lambda.
         * Do not store references to it. This consumer should not be reused in parallel streams.</p>
         *
         * @param consumer the consumer to process the column labels and {@code DisposableObjArray} for each row.
         * @return a new stateful {@code BiRowConsumer}.
         */
        @Beta
        @SequentialOnly
        @Stateful
        static BiRowConsumer oneOff(final BiConsumer<List<String>, DisposableObjArray> consumer) {
            N.checkArgNotNull(consumer, cs.consumer);

            return new BiRowConsumer() {
                private DisposableObjArray disposable = null;
                private int columnCount = -1;
                private Object[] output = null;

                @Override
                public void accept(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    if (disposable == null) {
                        columnCount = columnLabels.size();
                        output = new Object[columnCount];
                        disposable = DisposableObjArray.wrap(output);
                    }

                    for (int i = 0; i < columnCount; i++) {
                        output[i] = JdbcUtil.getColumnValue(rs, i + 1);
                    }

                    consumer.accept(columnLabels, disposable);
                }
            };
        }

        /**
         * Creates a stateful {@code BiRowConsumer} that converts each row into a reusable {@code DisposableObjArray}
         * using type information from a specified entity class. This allows for more precise type mapping from
         * SQL types to Java types.
         *
         * <p><b>Warning:</b> The returned {@code BiRowConsumer} is stateful and reuses an internal buffer.
         * The provided {@code DisposableObjArray} is only valid within the scope of the consumer's lambda.
         * This consumer should not be reused across different queries or in parallel streams.</p>
         *
         * @param entityClass the class used to infer column types for fetching values from the {@code ResultSet}.
         * @param consumer the consumer to process the column labels and typed {@code DisposableObjArray} for each row.
         * @return a new stateful {@code BiRowConsumer}.
         */
        @Beta
        @SequentialOnly
        @Stateful
        static BiRowConsumer oneOff(final Class<?> entityClass, final BiConsumer<List<String>, DisposableObjArray> consumer) {
            N.checkArgNotNull(entityClass, cs.entityClass);
            N.checkArgNotNull(consumer, cs.consumer);

            return new BiRowConsumer() {
                private DisposableObjArray disposable = null;
                private int columnCount = -1;
                private Object[] output = null;

                private Type<?>[] columnTypes = null;

                @Override
                public void accept(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    if (disposable == null) {
                        columnCount = columnLabels.size();
                        columnTypes = new Type[columnCount];

                        final BeanInfo entityInfo = ParserUtil.getBeanInfo(entityClass);
                        final Map<String, String> column2FieldNameMap = JdbcUtil.getColumn2FieldNameMap(entityClass);
                        PropInfo propInfo = null;

                        for (int i = 0; i < columnCount; i++) {
                            propInfo = entityInfo.getPropInfo(columnLabels.get(i));

                            if (propInfo == null) {
                                String fieldName = column2FieldNameMap.get(columnLabels.get(i));

                                if (Strings.isEmpty(fieldName)) {
                                    fieldName = column2FieldNameMap.get(columnLabels.get(i).toLowerCase());
                                }

                                if (Strings.isNotEmpty(fieldName)) {
                                    propInfo = entityInfo.getPropInfo(fieldName);
                                }
                            }

                            if (propInfo == null) {
                                //    throw new IllegalArgumentException(
                                //            "No property in class: " + ClassUtil.getCanonicalClassName(entityClass) + " mapping to column: " + columnLabels.get(i));
                            } else {
                                columnTypes[i] = propInfo.dbType;
                            }
                        }

                        output = new Object[columnCount];
                        disposable = DisposableObjArray.wrap(output);
                    }

                    for (int i = 0; i < columnCount; i++) {
                        output[i] = columnTypes[i] == null ? JdbcUtil.getColumnValue(rs, i + 1) : columnTypes[i].get(rs, i + 1);
                    }

                    consumer.accept(columnLabels, disposable);
                }
            };
        }
    }

    /**
     * A functional interface that represents a predicate (boolean-valued function) of one {@code ResultSet} argument.
     * Use this to filter rows after they have been fetched from the database.
     *
     * <p><b>Note:</b> It is generally more efficient to filter data on the database side using SQL {@code WHERE} clauses.
     * Use {@code RowFilter} only when the filtering logic cannot be expressed in SQL. 
     * The row filtering should be fast enough to avoid holding DB connections for a long time or slowing down the overall performance.
     * For performance-sensitive filtering that requires column metadata, consider using {@link BiRowFilter}.</p>
     */
    @FunctionalInterface
    public interface RowFilter extends Throwables.Predicate<ResultSet, SQLException> {

        /**
         * A {@code RowFilter} that includes every row.
         */
        RowFilter ALWAYS_TRUE = rs -> true;

        /**
         * A {@code RowFilter} that excludes every row.
         */
        RowFilter ALWAYS_FALSE = rs -> false;

        /**
         * Evaluates this filter on the given {@code ResultSet}.
         * This method should be fast enough to avoid holding DB connections for a long time or slowing down overall performance.
         *
         * @param rs the {@code ResultSet} positioned at the current row.
         * @return {@code true} if the row should be included, {@code false} otherwise.
         * @throws SQLException if a database access error occurs.
         */
        @Override
        boolean test(final ResultSet rs) throws SQLException;

        /**
         * Returns a filter that represents the logical negation of this filter.
         *
         * @return a new {@code RowFilter} that is the negation of this filter.
         */
        @Override
        default RowFilter negate() {
            return rs -> !test(rs);
        }

        /**
         * Returns a composed filter that represents a short-circuiting logical AND of this
         * filter and another.
         *
         * @param other a {@code RowFilter} that will be logically-ANDed with this filter.
         * @return a new composed {@code RowFilter}.
         * @throws IllegalArgumentException if {@code other} is {@code null}.
         */
        default RowFilter and(final Throwables.Predicate<? super ResultSet, SQLException> other) {
            N.checkArgNotNull(other);

            return rs -> test(rs) && other.test(rs);
        }

        /**
         * Converts this {@code RowFilter} to a {@link BiRowFilter}, which also accepts a list of column labels.
         * The resulting {@code BiRowFilter} will ignore the column labels parameter.
         *
         * @return a {@code BiRowFilter} that delegates to this filter.
         */
        default BiRowFilter toBiRowFilter() {
            return (rs, columnLabels) -> test(rs);
        }
    }

    /**
     * A functional interface that represents a predicate (boolean-valued function) of a {@code ResultSet}
     * and its column labels. This is more efficient than {@link RowFilter} if your filtering logic needs
     * to access column metadata, as the metadata is fetched only once per query.
     *
     * <p><b>Note:</b> It is generally more efficient to filter data on the database side using SQL {@code WHERE} clauses.
     * Use {@code BiRowFilter} only when the filtering logic cannot be expressed in SQL. 
     * The row filtering should be fast enough to avoid holding DB connections for a long time or slowing down the overall performance.</p>
     */
    @FunctionalInterface
    public interface BiRowFilter extends Throwables.BiPredicate<ResultSet, List<String>, SQLException> {

        /**
         * A {@code BiRowFilter} that includes every row.
         */
        BiRowFilter ALWAYS_TRUE = (rs, columnLabels) -> true;

        /**
         * A {@code BiRowFilter} that excludes every row.
         */
        BiRowFilter ALWAYS_FALSE = (rs, columnLabels) -> false;

        /**
         * Evaluates this filter on the given {@code ResultSet} and column labels.
         * This method should be fast enough to avoid holding DB connections for a long time or slowing down overall performance.
         *
         * @param rs the {@code ResultSet} positioned at the current row.
         * @param columnLabels the list of column labels from the result set metadata.
         * @return {@code true} if the row should be included, {@code false} otherwise.
         * @throws SQLException if a database access error occurs.
         */
        @Override
        boolean test(ResultSet rs, List<String> columnLabels) throws SQLException;

        /**
         * Returns a filter that represents the logical negation of this filter.
         *
         * @return a new {@code BiRowFilter} that is the negation of this filter.
         */
        default BiRowFilter negate() {
            return (rs, cls) -> !test(rs, cls);
        }

        /**
         * Returns a composed filter that represents a short-circuiting logical AND of this
         * filter and another.
         *
         * @param other a {@code BiRowFilter} that will be logically-ANDed with this filter.
         * @return a new composed {@code BiRowFilter}.
         * @throws IllegalArgumentException if {@code other} is {@code null}.
         */
        default BiRowFilter and(final Throwables.BiPredicate<? super ResultSet, ? super List<String>, SQLException> other) {
            N.checkArgNotNull(other);

            return (rs, cls) -> test(rs, cls) && other.test(rs, cls);
        }
    }

    /**
     * A functional interface for extracting data from the current row of a {@code ResultSet} into a
     * target {@code Object} array. This is useful for efficiently processing rows in bulk.
     */
    @FunctionalInterface
    public interface RowExtractor extends Throwables.BiConsumer<ResultSet, Object[], SQLException> {

        /**
         * Extracts data from the current row of the {@code ResultSet} and populates the {@code outputRow} array.
         * The implementation is responsible for mapping columns to array indices.
         *
         * @param rs the {@code ResultSet} positioned at a valid row.
         * @param outputRow the array to be populated with data from the current row.
         * @throws SQLException if a database access error occurs.
         */
        @Override
        void accept(final ResultSet rs, final Object[] outputRow) throws SQLException;

        /**
         * Creates a stateful {@code RowExtractor} that maps {@code ResultSet} columns to an {@code Object} array
         * based on the properties of the given entity class.
         *
         * <p><b>Warning:</b> The returned extractor is stateful. It initializes its internal type mapping
         * on the first execution. It should not be reused across queries with different column structures
         * or in parallel streams.</p>
         *
         * @param entityClassForFetch the entity class whose properties guide the type mapping.
         * @return a new stateful {@code RowExtractor}.
         */
        @SequentialOnly
        @Stateful
        static RowExtractor createBy(final Class<?> entityClassForFetch) {
            return createBy(entityClassForFetch, null, null);
        }

        /**
         * Creates a stateful {@code RowExtractor} based on an entity class, with custom mapping for
         * column prefixes to field name prefixes. This is useful for handling flattened one-to-one relationships.
         *
         * <p><b>Warning:</b> The returned extractor is stateful and should not be reused across different
         * queries or in parallel streams.</p>
         *
         * @param entityClassForFetch the entity class for type mapping.
         * @param prefixAndFieldNameMap a map where keys are column prefixes and values are corresponding entity field prefixes.
         * @return a new stateful {@code RowExtractor}.
         */
        @SequentialOnly
        @Stateful
        static RowExtractor createBy(final Class<?> entityClassForFetch, final Map<String, String> prefixAndFieldNameMap) {
            return createBy(entityClassForFetch, null, prefixAndFieldNameMap);
        }

        /**
         * Creates a stateful {@code RowExtractor} based on an entity class, using a predefined list of column labels.
         * This can be more efficient than discovering labels from {@code ResultSetMetaData}.
         *
         * <p><b>Warning:</b> The returned extractor is stateful and should not be reused across different
         * queries or in parallel streams.</p>
         *
         * @param entityClassForFetch the entity class for type mapping.
         * @param columnLabels the explicit list of column labels to use for mapping.
         * @return a new stateful {@code RowExtractor}.
         */
        @SequentialOnly
        @Stateful
        static RowExtractor createBy(final Class<?> entityClassForFetch, final List<String> columnLabels) {
            return createBy(entityClassForFetch, columnLabels, null);
        }

        /**
         * Creates a stateful {@code RowExtractor} with comprehensive customization options, including an entity class
         * for type mapping, an explicit list of column labels, and a prefix map for complex mappings.
         *
         * <p><b>Warning:</b> The returned extractor is stateful. It initializes its internal type mapping
         * on the first execution. It should not be reused across queries with different column structures
         * or in parallel streams.</p>
         *
         * @param entityClassForFetch the entity class for type mapping.
         * @param columnLabels an optional list of column labels to use for mapping. If {@code null}, they are discovered from the {@code ResultSet}.
         * @param prefixAndFieldNameMap an optional map for mapping column prefixes to field name prefixes.
         * @return a new stateful {@code RowExtractor}.
         */
        @SequentialOnly
        @Stateful
        static RowExtractor createBy(final Class<?> entityClassForFetch, final List<String> columnLabels, final Map<String, String> prefixAndFieldNameMap) {
            N.checkArgument(Beans.isBeanClass(entityClassForFetch), "entityClassForFetch");

            final BeanInfo entityInfo = ParserUtil.getBeanInfo(entityClassForFetch);

            return new RowExtractor() {
                private Type<?>[] columnTypes = null;
                private int columnCount = -1;

                @Override
                public void accept(final ResultSet rs, final Object[] outputRow) throws SQLException {
                    if (columnTypes == null) {
                        final Map<String, String> column2FieldNameMap = JdbcUtil.getColumn2FieldNameMap(entityClassForFetch);
                        final List<String> columnLabelList = N.isEmpty(columnLabels) ? JdbcUtil.getColumnLabelList(rs) : columnLabels;
                        columnCount = columnLabelList.size();
                        final String[] columnLabels = columnLabelList.toArray(new String[columnCount]);

                        columnTypes = new Type[columnCount];
                        PropInfo propInfo = null;

                        for (int i = 0; i < columnCount; i++) {
                            propInfo = entityInfo.getPropInfo(columnLabels[i]);

                            if (propInfo == null) {
                                String fieldName = column2FieldNameMap.get(columnLabels[i]);

                                if (Strings.isEmpty(fieldName)) {
                                    fieldName = column2FieldNameMap.get(columnLabels[i].toLowerCase());
                                }

                                if (Strings.isNotEmpty(fieldName)) {
                                    propInfo = entityInfo.getPropInfo(fieldName);
                                }
                            }

                            if (propInfo == null) {
                                final String newColumnName = JdbcUtil.checkPrefix(entityInfo, columnLabels[i], prefixAndFieldNameMap, columnLabelList);

                                propInfo = JdbcUtil.getSubPropInfo(entityClassForFetch, newColumnName);

                                if (propInfo == null) {
                                    propInfo = JdbcUtil.getSubPropInfo(entityClassForFetch, columnLabels[i]);

                                    if (propInfo == null) {
                                        String fieldName = column2FieldNameMap.get(columnLabels[i]);

                                        if (Strings.isEmpty(fieldName)) {
                                            fieldName = column2FieldNameMap.get(columnLabels[i].toLowerCase());
                                        }

                                        if (Strings.isNotEmpty(fieldName)) {
                                            propInfo = JdbcUtil.getSubPropInfo(entityClassForFetch, fieldName);
                                        }
                                    }
                                }

                                if (propInfo == null) {
                                    columnTypes[i] = null;
                                } else {
                                    columnTypes[i] = propInfo.dbType;
                                }
                            } else {
                                columnTypes[i] = propInfo.dbType;
                            }
                        }
                    }

                    for (int i = 0; i < columnCount; i++) {
                        outputRow[i] = columnTypes[i] == null ? JdbcUtil.getColumnValue(rs, i + 1) : columnTypes[i].get(rs, i + 1);
                    }
                }
            };
        }

        /**
         * Creates a {@link RowExtractorBuilder} with a specified default {@code ColumnGetter}. This getter
         * will be used for any column that does not have a specific getter configured in the builder.
         *
         * @param defaultColumnGetter the default {@code ColumnGetter} to use.
         * @return a new {@code RowExtractorBuilder}.
         */
        static RowExtractorBuilder create(final ColumnGetter<?> defaultColumnGetter) {
            return new RowExtractorBuilder(defaultColumnGetter);
        }

        /**
         * Creates a {@link RowExtractorBuilder} with a default behavior of retrieving all column values
         * as {@code Object} instances using {@code ColumnGetter.GET_OBJECT}.
         *
         * @return a new {@code RowExtractorBuilder}.
         */
        static RowExtractorBuilder builder() {
            return builder(ColumnGetter.GET_OBJECT);
        }

        /**
         * Creates a {@link RowExtractorBuilder} with a specified default {@code ColumnGetter}. This provides
         * a fluent API to construct a custom {@code RowExtractor}.
         *
         * @param defaultColumnGetter the default {@code ColumnGetter} to use for unconfigured columns.
         * @return a new {@code RowExtractorBuilder}.
         */
        static RowExtractorBuilder builder(final ColumnGetter<?> defaultColumnGetter) {
            return new RowExtractorBuilder(defaultColumnGetter);
        }

        /**
         * A builder for creating customized {@link RowExtractor} instances. This allows for specifying
         * different {@link ColumnGetter}s for individual columns, providing fine-grained control over
         * how data is extracted from a {@code ResultSet}.
         */
        class RowExtractorBuilder {
            private final Map<Integer, ColumnGetter<?>> columnGetterMap;

            RowExtractorBuilder(final ColumnGetter<?> defaultColumnGetter) {
                N.checkArgNotNull(defaultColumnGetter, cs.defaultColumnGetter);

                columnGetterMap = new HashMap<>(9);
                columnGetterMap.put(0, defaultColumnGetter);
            }

            /**
             * Configures the extractor to get a {@code boolean} value from the specified column.
             *
             * @param columnIndex the 1-based index of the column.
             * @return this builder instance for fluent chaining.
             */
            public RowExtractorBuilder getBoolean(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BOOLEAN);
            }

            /**
             * Configures the extractor to get a {@code byte} value from the specified column.
             *
             * @param columnIndex the 1-based index of the column.
             * @return this builder instance for fluent chaining.
             */
            public RowExtractorBuilder getByte(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BYTE);
            }

            /**
             * Configures the extractor to get a {@code short} value from the specified column.
             *
             * @param columnIndex the 1-based index of the column.
             * @return this builder instance for fluent chaining.
             */
            public RowExtractorBuilder getShort(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_SHORT);
            }

            /**
             * Configures the extractor to get an {@code int} value from the specified column.
             *
             * @param columnIndex the 1-based index of the column.
             * @return this builder instance for fluent chaining.
             */
            public RowExtractorBuilder getInt(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_INT);
            }

            /**
             * Configures the extractor to get a {@code long} value from the specified column.
             *
             * @param columnIndex the 1-based index of the column.
             * @return this builder instance for fluent chaining.
             */
            public RowExtractorBuilder getLong(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_LONG);
            }

            /**
             * Configures the extractor to get a {@code float} value from the specified column.
             *
             * @param columnIndex the 1-based index of the column.
             * @return this builder instance for fluent chaining.
             */
            public RowExtractorBuilder getFloat(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_FLOAT);
            }

            /**
             * Configures the extractor to get a {@code double} value from the specified column.
             *
             * @param columnIndex the 1-based index of the column.
             * @return this builder instance for fluent chaining.
             */
            public RowExtractorBuilder getDouble(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_DOUBLE);
            }

            /**
             * Configures the extractor to get a {@code BigDecimal} value from the specified column.
             *
             * @param columnIndex the 1-based index of the column.
             * @return this builder instance for fluent chaining.
             */
            public RowExtractorBuilder getBigDecimal(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BIG_DECIMAL);
            }

            /**
             * Configures the extractor to get a {@code String} value from the specified column.
             *
             * @param columnIndex the 1-based index of the column.
             * @return this builder instance for fluent chaining.
             */
            public RowExtractorBuilder getString(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_STRING);
            }

            /**
             * Configures the extractor to get a {@code java.sql.Date} value from the specified column.
             *
             * @param columnIndex the 1-based index of the column.
             * @return this builder instance for fluent chaining.
             */
            public RowExtractorBuilder getDate(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_DATE);
            }

            /**
             * Configures the extractor to get a {@code java.sql.Time} value from the specified column.
             *
             * @param columnIndex the 1-based index of the column.
             * @return this builder instance for fluent chaining.
             */
            public RowExtractorBuilder getTime(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_TIME);
            }

            /**
             * Configures the extractor to get a {@code java.sql.Timestamp} value from the specified column.
             *
             * @param columnIndex the 1-based index of the column.
             * @return this builder instance for fluent chaining.
             */
            public RowExtractorBuilder getTimestamp(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_TIMESTAMP);
            }

            /**
             * Configures the extractor to get an {@code Object} value from the specified column.
             *
             * @param columnIndex the 1-based index of the column.
             * @return this builder instance for fluent chaining.
             * @deprecated The default behavior is {@link #getObject(int)} if no specific {@code ColumnGetter} is set for the column.
             */
            @Deprecated
            public RowExtractorBuilder getObject(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_OBJECT);
            }

            /**
             * Configures the extractor to get an {@code Object} of a specific type from the specified column.
             *
             * @param columnIndex the 1-based index of the column.
             * @param type the class type to which the column value should be converted.
             * @return this builder instance for fluent chaining.
             */
            public RowExtractorBuilder getObject(final int columnIndex, final Class<?> type) {
                return get(columnIndex, ColumnGetter.get(type));
            }

            /**
             * Configures the extractor to use a custom {@code ColumnGetter} for the specified column.
             *
             * @param columnIndex the 1-based index of the column.
             * @param columnGetter the custom {@code ColumnGetter} to use for this column.
             * @return this builder instance for fluent chaining.
             * @throws IllegalArgumentException if {@code columnIndex} is not positive or {@code columnGetter} is {@code null}.
             */
            public RowExtractorBuilder get(final int columnIndex, final ColumnGetter<?> columnGetter) throws IllegalArgumentException {
                N.checkArgPositive(columnIndex, cs.columnIndex);
                N.checkArgNotNull(columnGetter, cs.columnGetter);

                columnGetterMap.put(columnIndex, columnGetter);
                return this;
            }

            /**
             * Builds a stateful {@code RowExtractor} based on the configured column getters.
             *
             * <p><b>Warning:</b> The returned {@code RowExtractor} is stateful. It initializes its internal array of
             * column getters on the first execution. It should not be cached or reused across different queries
             * or in parallel streams. A new instance should be built for each distinct query execution.</p>
             *
             * @return a new stateful {@code RowExtractor}.
             */
            @SequentialOnly
            @Stateful
            public RowExtractor build() {
                return new RowExtractor() {
                    private ColumnGetter<?>[] rsColumnGetters = null;
                    private int rsColumnCount = -1;

                    @Override
                    public void accept(final ResultSet rs, final Object[] outputRow) throws SQLException {
                        if (rsColumnGetters == null) {
                            rsColumnCount = rs.getMetaData().getColumnCount();
                            rsColumnGetters = initColumnGetter(rsColumnCount);

                            if (N.len(outputRow) < rsColumnCount) {
                                throw new IllegalArgumentException("The length of output array is less than the column count of ResultSet");
                            }
                        }

                        for (int i = 0; i < rsColumnCount; i++) {
                            outputRow[i] = rsColumnGetters[i].apply(rs, i + 1);
                        }
                    }

                    private ColumnGetter<?>[] initColumnGetter(final int columnCount) { //NOSONAR
                        final ColumnGetter<?>[] columnGetters = new ColumnGetter<?>[columnCount];
                        final ColumnGetter<?> defaultColumnGetter = columnGetterMap.get(0);

                        for (int i = 0; i < columnCount; i++) {
                            columnGetters[i] = columnGetterMap.getOrDefault(i + 1, defaultColumnGetter);
                        }

                        return columnGetters;
                    }
                };
            }
        }
    }

    /**
     * A functional interface for extracting a typed value from a specified column of a {@code ResultSet}.
     * This provides a type-safe and reusable way to retrieve column data.
     *
     * @param <V> extracted value type
     */
    @FunctionalInterface
    public interface ColumnGetter<V> {

        /**
         * Predefined getter for {@code boolean} values.
         */
        ColumnGetter<Boolean> GET_BOOLEAN = ResultSet::getBoolean;

        /**
         * Predefined getter for {@code byte} values.
         */
        ColumnGetter<Byte> GET_BYTE = ResultSet::getByte;

        /**
         * Predefined getter for {@code short} values.
         */
        ColumnGetter<Short> GET_SHORT = ResultSet::getShort;

        /**
         * Predefined getter for {@code int} values.
         */
        ColumnGetter<Integer> GET_INT = ResultSet::getInt;

        /**
         * Predefined getter for {@code long} values.
         */
        ColumnGetter<Long> GET_LONG = ResultSet::getLong;

        /**
         * Predefined getter for {@code float} values.
         */
        ColumnGetter<Float> GET_FLOAT = ResultSet::getFloat;

        /**
         * Predefined getter for {@code double} values.
         */
        ColumnGetter<Double> GET_DOUBLE = ResultSet::getDouble;

        /**
         * Predefined getter for {@code BigDecimal} values.
         */
        ColumnGetter<BigDecimal> GET_BIG_DECIMAL = ResultSet::getBigDecimal;

        /**
         * Predefined getter for {@code String} values.
         */
        ColumnGetter<String> GET_STRING = ResultSet::getString;

        /**
         * Predefined getter for {@code java.sql.Date} values.
         */
        ColumnGetter<Date> GET_DATE = ResultSet::getDate;

        /**
         * Predefined getter for {@code java.sql.Time} values.
         */
        ColumnGetter<Time> GET_TIME = ResultSet::getTime;

        /**
         * Predefined getter for {@code java.sql.Timestamp} values.
         */
        ColumnGetter<Timestamp> GET_TIMESTAMP = ResultSet::getTimestamp;

        /**
         * Predefined getter for {@code byte[]} values.
         */
        ColumnGetter<byte[]> GET_BYTES = ResultSet::getBytes;

        /**
         * Predefined getter for {@code InputStream} values.
         */
        ColumnGetter<InputStream> GET_BINARY_STREAM = ResultSet::getBinaryStream;

        /**
         * Predefined getter for {@code Reader} values.
         */
        ColumnGetter<Reader> GET_CHARACTER_STREAM = ResultSet::getCharacterStream;

        /**
         * Predefined getter for {@code Blob} values.
         */
        ColumnGetter<Blob> GET_BLOB = ResultSet::getBlob;

        /**
         * Predefined getter for {@code Clob} values.
         */
        ColumnGetter<Clob> GET_CLOB = ResultSet::getClob;

        /**
         * Predefined getter for {@code Object} values, using {@code JdbcUtil.getColumnValue} for generic type handling.
         */
        @SuppressWarnings("rawtypes")
        ColumnGetter GET_OBJECT = JdbcUtil::getColumnValue;

        /**
         * Extracts a value from the specified column of the given {@code ResultSet}.
         *
         * @param rs the {@code ResultSet} to extract from.
         * @param columnIndex the 1-based index of the column.
         * @return the extracted value of type {@code V}.
         * @throws SQLException if a database access error occurs.
         */
        V apply(ResultSet rs, int columnIndex) throws SQLException;

        /**
         * Retrieves a cached or creates a new {@code ColumnGetter} for the specified class type.
         * It leverages Abacus-common's {@code Type} system to determine the appropriate extraction method.
         *
         * @param <T> target type
         * @param cls the class for which to get a {@code ColumnGetter}.
         * @return a {@code ColumnGetter} for the specified type.
         */
        static <T> ColumnGetter<T> get(final Class<? extends T> cls) {
            return get(N.typeOf(cls));
        }

        /**
         * Retrieves a cached or creates a new {@code ColumnGetter} for the specified Abacus-common {@code Type}.
         * Common types are cached for efficient reuse.
         *
         * @param <T> target type
         * @param type the {@code Type} for which to get a {@code ColumnGetter}.
         * @return a {@code ColumnGetter} for the specified type.
         */
        static <T> ColumnGetter<T> get(final Type<? extends T> type) {
            final ColumnGetter<?> columnGetter = COLUMN_GETTER_POOL.computeIfAbsent(type, k -> type::get);

            return (ColumnGetter<T>) columnGetter;
        }
    }

    /**
     * A utility class containing helpers and constants for column-specific operations,
     * primarily focused on single-column results.
     */
    public static final class Columns {
        private Columns() {
            // singleton for utility class
        }

        /**
         * A utility class providing predefined {@link RowMapper} and {@link BiParametersSetter} instances
         * for operations on the first column of a {@code ResultSet} or the first parameter of a {@code PreparedStatement}.
         */
        public static final class ColumnOne {
            /**
             * A {@code RowMapper} for getting a {@code boolean} value from the first column.
             */
            public static final RowMapper<Boolean> GET_BOOLEAN = rs -> rs.getBoolean(1);

            /**
             * A {@code RowMapper} for getting a {@code byte} value from the first column.
             */
            public static final RowMapper<Byte> GET_BYTE = rs -> rs.getByte(1);

            /**
             * A {@code RowMapper} for getting a {@code short} value from the first column.
             */
            public static final RowMapper<Short> GET_SHORT = rs -> rs.getShort(1);

            /**
             * A {@code RowMapper} for getting an {@code int} value from the first column.
             */
            public static final RowMapper<Integer> GET_INT = rs -> rs.getInt(1);

            /**
             * A {@code RowMapper} for getting a {@code long} value from the first column.
             */
            public static final RowMapper<Long> GET_LONG = rs -> rs.getLong(1);

            /**
             * A {@code RowMapper} for getting a {@code float} value from the first column.
             */
            public static final RowMapper<Float> GET_FLOAT = rs -> rs.getFloat(1);

            /**
             * A {@code RowMapper} for getting a {@code double} value from the first column.
             */
            public static final RowMapper<Double> GET_DOUBLE = rs -> rs.getDouble(1);

            /**
             * A {@code RowMapper} for getting a {@code BigDecimal} value from the first column.
             */
            public static final RowMapper<BigDecimal> GET_BIG_DECIMAL = rs -> rs.getBigDecimal(1);

            /**
             * A {@code RowMapper} for getting a {@code String} value from the first column.
             */
            public static final RowMapper<String> GET_STRING = rs -> rs.getString(1);

            /**
             * A {@code RowMapper} for getting a {@code java.sql.Date} value from the first column.
             */
            public static final RowMapper<Date> GET_DATE = rs -> rs.getDate(1);

            /**
             * A {@code RowMapper} for getting a {@code java.sql.Time} value from the first column.
             */
            public static final RowMapper<Time> GET_TIME = rs -> rs.getTime(1);

            /**
             * A {@code RowMapper} for getting a {@code java.sql.Timestamp} value from the first column.
             */
            public static final RowMapper<Timestamp> GET_TIMESTAMP = rs -> rs.getTimestamp(1);

            /**
             * A {@code RowMapper} for getting a {@code byte[]} value from the first column.
             */
            public static final RowMapper<byte[]> GET_BYTES = rs -> rs.getBytes(1);

            /**
             * A {@code RowMapper} for getting an {@code InputStream} from the first column.
             */
            public static final RowMapper<InputStream> GET_BINARY_STREAM = rs -> rs.getBinaryStream(1);

            /**
             * A {@code RowMapper} for getting a {@code Reader} from the first column.
             */
            public static final RowMapper<Reader> GET_CHARACTER_STREAM = rs -> rs.getCharacterStream(1);

            /**
             * A {@code RowMapper} for getting a {@code Blob} from the first column.
             */
            public static final RowMapper<Blob> GET_BLOB = rs -> rs.getBlob(1);

            /**
             * A {@code RowMapper} for getting a {@code Clob} from the first column.
             */
            public static final RowMapper<Clob> GET_CLOB = rs -> rs.getClob(1);

            /**
             * A {@code RowMapper} for getting an {@code Object} value from the first column.
             */
            public static final RowMapper<Object> GET_OBJECT = rs -> JdbcUtil.getColumnValue(rs, 1);

            /**
             * A {@code BiParametersSetter} for setting a {@code boolean} value for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Boolean> SET_BOOLEAN = (preparedQuery, x) -> preparedQuery.setBoolean(1, x);

            /**
             * A {@code BiParametersSetter} for setting a {@code byte} value for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Byte> SET_BYTE = (preparedQuery, x) -> preparedQuery.setByte(1, x);

            /**
             * A {@code BiParametersSetter} for setting a {@code short} value for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Short> SET_SHORT = (preparedQuery, x) -> preparedQuery.setShort(1, x);

            /**
             * A {@code BiParametersSetter} for setting an {@code int} value for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Integer> SET_INT = (preparedQuery, x) -> preparedQuery.setInt(1, x);

            /**
             * A {@code BiParametersSetter} for setting a {@code long} value for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Long> SET_LONG = (preparedQuery, x) -> preparedQuery.setLong(1, x);

            /**
             * A {@code BiParametersSetter} for setting a {@code float} value for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Float> SET_FLOAT = (preparedQuery, x) -> preparedQuery.setFloat(1, x);

            /**
             * A {@code BiParametersSetter} for setting a {@code double} value for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Double> SET_DOUBLE = (preparedQuery, x) -> preparedQuery.setDouble(1, x);

            /**
             * A {@code BiParametersSetter} for setting a {@code BigDecimal} value for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, BigDecimal> SET_BIG_DECIMAL = (preparedQuery, x) -> preparedQuery.setBigDecimal(1, x);

            /**
             * A {@code BiParametersSetter} for setting a {@code String} value for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, String> SET_STRING = (preparedQuery, x) -> preparedQuery.setString(1, x);

            /**
             * A {@code BiParametersSetter} for setting a {@code java.sql.Date} value for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Date> SET_DATE = (preparedQuery, x) -> preparedQuery.setDate(1, x);

            /**
             * A {@code BiParametersSetter} for setting a {@code java.sql.Time} value for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Time> SET_TIME = (preparedQuery, x) -> preparedQuery.setTime(1, x);

            /**
             * A {@code BiParametersSetter} for setting a {@code java.sql.Timestamp} value for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Timestamp> SET_TIMESTAMP = (preparedQuery, x) -> preparedQuery.setTimestamp(1, x);

            /**
             * A {@code BiParametersSetter} for setting a {@code java.util.Date} as a SQL DATE for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, java.util.Date> SET_DATE_JU = (preparedQuery, x) -> preparedQuery.setDate(1, x);

            /**
             * A {@code BiParametersSetter} for setting a {@code java.util.Date} as a SQL TIME for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, java.util.Date> SET_TIME_JU = (preparedQuery, x) -> preparedQuery.setTime(1, x);

            /**
             * A {@code BiParametersSetter} for setting a {@code java.util.Date} as a SQL TIMESTAMP for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, java.util.Date> SET_TIMESTAMP_JU = (preparedQuery, x) -> preparedQuery.setTimestamp(1, x);

            /**
             * A {@code BiParametersSetter} for setting a {@code byte[]} value for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, byte[]> SET_BYTES = (preparedQuery, x) -> preparedQuery.setBytes(1, x);

            /**
             * A {@code BiParametersSetter} for setting an {@code InputStream} for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, InputStream> SET_BINARY_STREAM = (preparedQuery, x) -> preparedQuery.setBinaryStream(1, x);

            /**
             * A {@code BiParametersSetter} for setting a {@code Reader} for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Reader> SET_CHARACTER_STREAM = (preparedQuery, x) -> preparedQuery.setCharacterStream(1, x);

            /**
             * A {@code BiParametersSetter} for setting a {@code Blob} value for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Blob> SET_BLOB = (preparedQuery, x) -> preparedQuery.setBlob(1, x);

            /**
             * A {@code BiParametersSetter} for setting a {@code Clob} value for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Clob> SET_CLOB = (preparedQuery, x) -> preparedQuery.setClob(1, x);

            /**
             * A {@code BiParametersSetter} for setting an {@code Object} value for the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Object> SET_OBJECT = (preparedQuery, x) -> preparedQuery.setObject(1, x);

            private ColumnOne() {
                // singleton for utility class
            }

            @SuppressWarnings("rawtypes")
            static final Map<Type<?>, RowMapper> rowMapperPool = new ObjectPool<>(1024);

            /**
             * Gets a generic {@code RowMapper} that extracts a value as an {@code Object} from the first column.
             *
             * @param <T> target type (inferred).
             * @return a {@code RowMapper} that extracts an {@code Object} from the first column.
             */
            public static <T> RowMapper<T> getObject() {
                return (RowMapper<T>) GET_OBJECT;
            }

            /**
             * Gets a {@code RowMapper} that extracts a value of the specified type from the first column.
             * This method uses a cache for commonly used types to improve performance.
             *
             * <p><b>Usage Examples:</b></p>
             * <pre>{@code
             * // Returns a mapper that gets a String from column 1.
             * RowMapper<String> stringMapper = ColumnOne.get(String.class);
             * // Returns a mapper that gets an Integer from column 1.
             * RowMapper<Integer> intMapper = ColumnOne.get(Integer.class);
             * }</pre>
             *
             * @param <T> target type
             * @param firstColumnType the class of the value in the first column.
             * @return a {@code RowMapper} for the specified type.
             */
            public static <T> RowMapper<T> get(final Class<? extends T> firstColumnType) {
                return get(N.typeOf(firstColumnType));
            }

            /**
             * Gets a {@code RowMapper} that extracts a value of the specified Abacus-common {@code Type} from the first column.
             * This method uses a cache for commonly used types.
             *
             * <p><b>Usage Examples:</b></p>
             * <pre>{@code
             * Type<Integer> type = Type.of(Integer.class);
             * RowMapper<Integer> mapper = Jdbc.ColumnOne.get(type);
             * Integer value = mapper.apply(resultSet);
             * }</pre>
             *
             * @param <T> target type
             * @param type the {@code Type} of the value in the first column.
             * @return a {@code RowMapper} for the specified type.
             */
            @SuppressWarnings("cast")
            public static <T> RowMapper<T> get(final Type<? extends T> type) {
                final RowMapper<T> rowMapper = rs -> type.get(rs, 1);
                return (RowMapper<T>) rowMapperPool.computeIfAbsent(type, k -> rowMapper);
            }

            /**
             * Creates a {@code RowMapper} that reads a JSON string from the first column and deserializes it
             * into an object of the specified target type.
             *
             * <p><b>Usage Examples:</b></p>
             * <pre>{@code
             * // Assumes column 1 contains a JSON string like '{"id":1, "name":"John"}'
             * RowMapper<User> userMapper = ColumnOne.readJson(User.class);
             * User user = preparedQuery.queryForSingle(userMapper).get();
             * }</pre>
             *
             * @param <T> target type
             * @param targetType the class to deserialize the JSON string into.
             * @return a {@code RowMapper} that performs JSON deserialization.
             */
            public static <T> RowMapper<T> readJson(final Class<? extends T> targetType) {
                return rs -> N.fromJson(rs.getString(1), targetType);
            }

            /**
             * Creates a {@code RowMapper} that reads an XML string from the first column and deserializes it
             * into an object of the specified target type.
             *
             * <p><b>Usage Examples:</b></p>
             * <pre>{@code
             * // Assumes column 1 contains an XML string like '<user><id>1</id><name>John</name></user>'
             * RowMapper<User> userMapper = ColumnOne.readXml(User.class);
             * User user = preparedQuery.queryForSingle(userMapper).get();
             * }</pre>
             *
             * @param <T> target type
             * @param targetType the class to deserialize the XML string into.
             * @return a {@code RowMapper} that performs XML deserialization.
             */
            public static <T> RowMapper<T> readXml(final Class<? extends T> targetType) {
                return rs -> N.fromXml(rs.getString(1), targetType);
            }

            /**
             * Creates a {@code BiParametersSetter} for setting a value of the specified type as the first parameter
             * of a {@code PreparedStatement}.
             *
             * @param <T> parameter type
             * @param type the class of the parameter.
             * @return a {@code BiParametersSetter} for the specified type.
             */
            @SuppressWarnings("rawtypes")
            public static <T> BiParametersSetter<AbstractQuery, T> set(final Class<T> type) {
                return set(N.typeOf(type));
            }

            /**
             * Creates a {@code BiParametersSetter} for setting a value of the specified Abacus-common {@code Type}
             * as the first parameter of a {@code PreparedStatement}.
             *
             * @param <T> parameter type
             * @param type the {@code Type} of the parameter.
             * @return a {@code BiParametersSetter} for the specified type.
             */
            @SuppressWarnings("rawtypes")
            public static <T> BiParametersSetter<AbstractQuery, T> set(final Type<T> type) {
                return (preparedQuery, x) -> type.set(preparedQuery.stmt, 1, x);
            }

        }
    }

    /**
     * Represents an output parameter for a stored procedure call. This class encapsulates all
     * necessary information to register an output parameter with a {@code CallableStatement}.
     *
     * <p>This class provides both a no-argument constructor (via Lombok's {@code @NoArgsConstructor})
     * and an all-arguments constructor (via Lombok's {@code @AllArgsConstructor}) for flexible instantiation.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Using no-args constructor and setters
     * OutParam outParam = new OutParam();
     * outParam.setParameterIndex(1);
     * outParam.setSqlType(Types.VARCHAR);
     *
     * // Using all-args constructor
     * OutParam outParam = new OutParam(1, "result", Types.INTEGER, null);
     * }</pre>
     */
    @NoArgsConstructor
    @AllArgsConstructor
    @Data
    public static final class OutParam {
        /**
         * The 1-based index of the parameter in the stored procedure call.
         */
        private int parameterIndex;

        /**
         * The name of the parameter. This is optional and used for named parameter calls.
         */
        private String parameterName;

        /**
         * The SQL type of the parameter, as defined in {@code java.sql.Types}.
         */
        private int sqlType;

        /**
         * The database-specific type name. This is generally used for user-defined or complex types.
         */
        private String typeName;

        /**
         * The number of digits to the right of the decimal point, for {@code DECIMAL} or {@code NUMERIC} types.
         */
        private int scale;

        /**
         * A factory method to create an {@code OutParam} with the specified index and SQL type.
         *
         * @param parameterIndex the 1-based index of the parameter.
         * @param sqlType the SQL type from {@code java.sql.Types}.
         * @return a new {@code OutParam} instance.
         */
        public static OutParam of(int parameterIndex, int sqlType) {
            final OutParam outParam = new OutParam();
            outParam.setParameterIndex(parameterIndex);
            outParam.setSqlType(sqlType);
            return outParam;
        }
    }

    /**
     * A container for the results of output parameters from a stored procedure execution.
     * It provides methods to retrieve parameter values by their index or name.
     */
    @EqualsAndHashCode
    @ToString
    public static final class OutParamResult {
        private final List<OutParam> outParams;
        private final Map<Object, Object> outParamValues;

        /**
         * Constructs an {@code OutParamResult} with the specified output parameters and their values.
         *
         * @param outParams the list of {@code OutParam} definitions.
         * @param outParamValues a map of output parameter values, keyed by index or name.
         */
        OutParamResult(final List<OutParam> outParams, final Map<Object, Object> outParamValues) {
            this.outParams = outParams;
            this.outParamValues = outParamValues;
        }

        /**
         * Retrieves the value of an output parameter by its 1-based index.
         *
         * @param <T> expected parameter value type
         * @param parameterIndex the 1-based index of the parameter.
         * @return the parameter value, cast to type {@code T}. May be {@code null}.
         */
        public <T> T getOutParamValue(final int parameterIndex) {
            return (T) outParamValues.get(parameterIndex);
        }

        /**
         * Retrieves the value of an output parameter by its name.
         *
         * @param <T> expected parameter value type
         * @param parameterName the name of the parameter.
         * @return the parameter value, cast to type {@code T}. May be {@code null}.
         */
        public <T> T getOutParamValue(final String parameterName) {
            return (T) outParamValues.get(parameterName);
        }

        /**
         * Returns a map containing all output parameter values. The keys of the map are
         * either the parameter index ({@code Integer}) or name ({@code String}).
         *
         * @return an unmodifiable map of all output parameter values.
         */
        public Map<Object, Object> getOutParamValues() {
            return outParamValues;
        }

        /**
         * Returns the list of {@link OutParam} definitions that were used to register the output parameters.
         *
         * @return an unmodifiable list of {@code OutParam} objects.
         */
        public List<OutParam> getOutParams() {
            return outParams;
        }
    }

    /**
     * A handler interface for intercepting method invocations on DAO proxies, similar to an Aspect-Oriented
     * Programming (AOP) interceptor. It allows for executing custom logic before and after a DAO method is called.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * Handler<UserDao> loggingHandler = new Handler<>() {
     * public void beforeInvoke(UserDao proxy, Object[] args, Tuple3<Method, ..., ...> sig) {
     * System.out.println("Calling method: " + sig._1.getName());
     * }
     * public void afterInvoke(Object result, UserDao proxy, Object[] args, Tuple3<Method, ..., ...> sig) {
     * System.out.println("Method returned: " + result);
     * }
     * };
     * }</pre>
     *
     * @param <P> DAO proxy type
     */
    @Beta
    public interface Handler<P> {
        /**
         * This method is invoked before the actual DAO method is called. It can be used for
         * logging, argument validation, security checks, or transaction management.
         *
         * @param proxy the proxy instance on which the method was invoked.
         * @param args the arguments passed to the method.
         * @param methodSignature a tuple containing the {@code Method} object, a list of parameter types, and the return type.
         */
        @SuppressWarnings("unused")
        default void beforeInvoke(final P proxy, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            // empty action.
        }

        /**
         * This method is invoked after the DAO method completes, whether successfully or with an exception.
         * It can be used for logging results, result transformation, or resource cleanup.
         *
         * @param result the value returned by the method. If the method's return type is void, this will be {@code null}.
         * @param proxy the proxy instance on which the method was invoked.
         * @param args the arguments passed to the method.
         * @param methodSignature a tuple containing the {@code Method} object, a list of parameter types, and the return type.
         */
        @SuppressWarnings("unused")
        default void afterInvoke(final Object result, final P proxy, final Object[] args,
                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            // empty action.
        }
    }

    /**
     * A factory for creating and managing {@link Handler} instances. It provides a central registry
     * for handlers and supports integration with the Spring Framework's application context.
     */
    public static final class HandlerFactory {

        @SuppressWarnings("rawtypes")
        static final Handler EMPTY = new Handler() {
            // Do nothing.
        };

        private static final Map<String, Handler<?>> handlerPool = new ConcurrentHashMap<>();
        private static final SpringApplicationContext springAppContext;

        static {
            handlerPool.put(ClassUtil.getCanonicalClassName(Handler.class), EMPTY);
            handlerPool.put(ClassUtil.getClassName(EMPTY.getClass()), EMPTY);

            SpringApplicationContext tmp = null;

            try {
                tmp = new SpringApplicationContext();
            } catch (final Throwable e) {
                // ignore.
            }

            springAppContext = tmp;
        }

        /**
         * Registers a handler by creating a new instance of the specified handler class.
         * The handler is registered using its canonical class name as the qualifier.
         *
         * @param handlerClass the handler class to instantiate and register.
         * @return {@code true} if the handler was registered successfully, {@code false} if a handler with the same name already exists.
         * @throws IllegalArgumentException if {@code handlerClass} is {@code null}.
         */
        public static boolean register(final Class<? extends Handler<?>> handlerClass) throws IllegalArgumentException {
            N.checkArgNotNull(handlerClass, cs.handlerClass);

            return register(N.newInstance(handlerClass));
        }

        /**
         * Registers a handler instance. The handler is registered using its canonical class name as the qualifier.
         *
         * @param handler the handler instance to register.
         * @return {@code true} if the handler was registered successfully, {@code false} if a handler with the same name already exists.
         * @throws IllegalArgumentException if {@code handler} is {@code null}.
         */
        public static boolean register(final Handler<?> handler) throws IllegalArgumentException {
            N.checkArgNotNull(handler, cs.handler);

            return register(ClassUtil.getCanonicalClassName(handler.getClass()), handler);
        }

        /**
         * Registers a handler instance with a specific qualifier string.
         *
         * @param qualifier the unique identifier for the handler.
         * @param handler the handler instance to register.
         * @return {@code true} if the handler was registered successfully, {@code false} if a handler with the same qualifier already exists.
         * @throws IllegalArgumentException if {@code qualifier} is empty or {@code handler} is {@code null}.
         */
        public static boolean register(final String qualifier, final Handler<?> handler) throws IllegalArgumentException {
            N.checkArgNotEmpty(qualifier, cs.qualifier);
            N.checkArgNotNull(handler, cs.handler);

            if (handlerPool.containsKey(qualifier)) {
                return false;
            }

            handlerPool.put(qualifier, handler);

            return true;
        }

        /**
         * Retrieves a handler by its qualifier. It first checks the internal registry, and if not found,
         * it attempts to retrieve it from the Spring application context if available.
         *
         * @param qualifier the unique identifier for the handler.
         * @return the handler instance, or {@code null} if not found.
         * @throws IllegalArgumentException if {@code qualifier} is empty.
         */
        public static Handler<?> get(final String qualifier) { //NOSONAR
            N.checkArgNotEmpty(qualifier, cs.qualifier);

            Handler<?> result = handlerPool.get(qualifier);

            if (result == null && springAppContext != null) {
                final Object bean = springAppContext.getBean(qualifier);

                if (bean instanceof Handler) {
                    result = (Handler<?>) bean;

                    handlerPool.put(qualifier, result);
                }
            }

            return result;
        }

        /**
         * Retrieves a handler by its class. It first checks the internal registry using the class's
         * canonical name as the qualifier. If not found, it attempts to retrieve it from the Spring
         * application context.
         *
         * @param handlerClass the class of the handler to retrieve.
         * @return the handler instance, or {@code null} if not found.
         * @throws IllegalArgumentException if {@code handlerClass} is {@code null}.
         */
        public static Handler<?> get(final Class<? extends Handler<?>> handlerClass) { //NOSONAR
            N.checkArgNotNull(handlerClass, cs.handlerClass);

            final String qualifier = ClassUtil.getCanonicalClassName(handlerClass);

            Handler<?> result = handlerPool.get(qualifier);

            if (result == null && springAppContext != null) {
                result = springAppContext.getBean(handlerClass);

                if (result == null) {
                    final Object bean = springAppContext.getBean(qualifier);

                    if (bean instanceof Handler) {
                        result = (Handler<?>) bean;
                    }
                }

                if (result != null) {
                    handlerPool.put(qualifier, result);
                }
            }

            return result;
        }

        /**
         * Retrieves a handler by its class. If the handler is not found in the registry or Spring context,
         * a new instance is created, registered, and returned.
         *
         * @param handlerClass the class of the handler to retrieve or create.
         * @return the existing or newly created handler instance.
         * @throws IllegalArgumentException if {@code handlerClass} is {@code null}.
         */
        public static Handler<?> getOrCreate(final Class<? extends Handler<?>> handlerClass) { //NOSONAR
            N.checkArgNotNull(handlerClass, cs.handlerClass);

            Handler<?> result = get(handlerClass);

            if (result == null) {
                try {
                    result = N.newInstance(handlerClass);

                    if (result != null) {
                        register(result);
                    }
                } catch (final Throwable e) {
                    // ignore
                }
            }

            return result;
        }

        /**
         * Creates a {@code Handler} with a custom action to be executed before method invocation.
         *
         * @param <T> proxy type
         * @param <E> exception type that action can throw
         * @param beforeInvokeAction the action to perform before the method is called.
         * @return a new {@code Handler} instance.
         * @throws IllegalArgumentException if {@code beforeInvokeAction} is {@code null}.
         */
        public static <T, E extends RuntimeException> Handler<T> create(
                final Throwables.TriConsumer<T, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, E> beforeInvokeAction)
                throws IllegalArgumentException {
            N.checkArgNotNull(beforeInvokeAction, cs.beforeInvokeAction);

            return new Handler<>() {
                @Override
                public void beforeInvoke(final T targetObject, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                    beforeInvokeAction.accept(targetObject, args, methodSignature);
                }
            };
        }

        /**
         * Creates a {@code Handler} with a custom action to be executed after method invocation.
         *
         * @param <T> proxy type
         * @param <E> exception type that action can throw
         * @param afterInvokeAction the action to perform after the method returns.
         * @return a new {@code Handler} instance.
         * @throws IllegalArgumentException if {@code afterInvokeAction} is {@code null}.
         */
        public static <T, E extends RuntimeException> Handler<T> create(
                final Throwables.QuadConsumer<Object, T, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, E> afterInvokeAction)
                throws IllegalArgumentException {
            N.checkArgNotNull(afterInvokeAction, cs.afterInvokeAction);

            return new Handler<>() {
                @Override
                public void afterInvoke(final Object result, final T targetObject, final Object[] args,
                        final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {

                    afterInvokeAction.accept(result, targetObject, args, methodSignature);
                }
            };
        }

        /**
         * Creates a {@code Handler} with custom actions to be executed both before and after method invocation.
         *
         * @param <T> proxy type
         * @param <E> exception type that actions can throw
         * @param beforeInvokeAction the action to perform before the method is called.
         * @param afterInvokeAction the action to perform after the method returns.
         * @return a new {@code Handler} instance.
         * @throws IllegalArgumentException if either action is {@code null}.
         */
        public static <T, E extends RuntimeException> Handler<T> create(
                final Throwables.TriConsumer<T, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, E> beforeInvokeAction,
                final Throwables.QuadConsumer<Object, T, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, E> afterInvokeAction)
                throws IllegalArgumentException {
            N.checkArgNotNull(beforeInvokeAction, cs.beforeInvokeAction);
            N.checkArgNotNull(afterInvokeAction, cs.afterInvokeAction);

            return new Handler<>() {
                @Override
                public void beforeInvoke(final T targetObject, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                    beforeInvokeAction.accept(targetObject, args, methodSignature);
                }

                @Override
                public void afterInvoke(final Object result, final T targetObject, final Object[] args,
                        final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {

                    afterInvokeAction.accept(result, targetObject, args, methodSignature);
                }
            };
        }

        private HandlerFactory() {
            // singleton.
        }
    }

    /**
     * An interface for caching the results of DAO method calls. Implementations can provide
     * various caching strategies (e.g., in-memory, distributed) to improve application performance.
     *
     * <p>The default cache key format is: {@code fullMethodName#tableName#jsonArrayOfParameters}.</p>
     * <p>Example: {@code com.example.UserDao.findById#users#[123]}</p>
     */
    public interface DaoCache {

        /**
         * Creates a {@code DaoCache} with a specified capacity and eviction delay, backed by a {@code LocalCache}.
         *
         * @param capacity the maximum number of entries in the cache.
         * @param evictDelay the interval in milliseconds for the eviction scheduler to run.
         * @return a new {@code DaoCache} instance.
         */
        static DaoCache create(final int capacity, final long evictDelay) {
            return new DefaultDaoCache(capacity, evictDelay);
        }

        /**
         * Creates a {@code DaoCache} backed by a simple {@code java.util.HashMap}. This cache does not
         * perform automatic eviction.
         *
         * @return a new {@code DaoCache} instance backed by a {@code HashMap}.
         */
        static DaoCache createByMap() {
            return new DaoCacheByMap();
        }

        /**
         * Creates a {@code DaoCache} backed by the provided {@code Map}. This allows for using custom
         * map implementations (e.g., {@code ConcurrentHashMap}) for caching.
         *
         * @param map the map to use for caching.
         * @return a new {@code DaoCache} instance backed by the provided map.
         */
        static DaoCache createByMap(Map<String, Object> map) {
            return new DaoCacheByMap(map);
        }

        /**
         * Retrieves a cached result. The implementation can use the provided parameters to customize
         * the cache key generation if needed.
         *
         * <p><b>Implementation Note:</b> This method MUST NOT modify the input arguments.</p>
         *
         * @param defaultCacheKey the default cache key (fullMethodName#tableName#jsonArrayOfParameters).
         * @param daoProxy the DAO proxy instance on which the method was called.
         * @param args the arguments passed to the method.
         * @param methodSignature a tuple containing method metadata.
         * @return the cached result, or {@code null} if not found.
         */
        Object get(String defaultCacheKey, Object daoProxy, Object[] args, Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature);

        /**
         * Caches a result with default time-to-live (TTL) settings.
         *
         * <p><b>Implementation Note:</b> This method MUST NOT modify the input arguments.</p>
         *
         * @param defaultCacheKey the default cache key.
         * @param result the method result to cache.
         * @param daoProxy the DAO proxy instance.
         * @param args the method arguments.
         * @param methodSignature a tuple containing method metadata.
         * @return {@code true} if the result was cached successfully.
         */
        boolean put(String defaultCacheKey, Object result, Object daoProxy, Object[] args, Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature);

        /**
         * Caches a result with custom time-to-live (TTL) and idle time settings.
         *
         * <p><b>Implementation Note:</b> This method MUST NOT modify the input arguments.</p>
         *
         * @param defaultCacheKey the default cache key.
         * @param result the method result to cache.
         * @param liveTime the maximum time in milliseconds the entry should live in the cache.
         * @param maxIdleTime the maximum time in milliseconds the entry can remain idle before being evicted.
         * @param daoProxy the DAO proxy instance.
         * @param args the method arguments.
         * @param methodSignature a tuple containing method metadata.
         * @return {@code true} if the result was cached successfully.
         */
        boolean put(String defaultCacheKey, Object result, final long liveTime, final long maxIdleTime, Object daoProxy, Object[] args,
                Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature);

        /**
         * Updates the cache after a data modification operation (e.g., insert, update, delete). This method
         * is responsible for invalidating or clearing cache entries that may be affected by the operation.
         *
         * <p><b>Implementation Note:</b> This method MUST NOT modify the input arguments.</p>
         *
         * @param defaultCacheKey the default cache key from the modification method.
         * @param result the result of the modification operation (e.g., number of rows affected).
         * @param daoProxy the DAO proxy instance.
         * @param args the arguments of the modification method.
         * @param methodSignature a tuple containing method metadata.
         */
        void update(String defaultCacheKey, Object result, Object daoProxy, Object[] args, Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature);

    }

    /**
     * The default implementation of {@link DaoCache}, using a {@link KeyedObjectPool} for in-memory caching
     * with support for time-to-live (TTL) and idle time-based eviction.
     */
    public static final class DefaultDaoCache implements DaoCache {
        private final KeyedObjectPool<String, PoolableWrapper<Object>> pool;

        /**
         * Creates a {@code DefaultDaoCache} with a specified capacity and eviction delay.
         *
         * @param capacity the maximum number of entries the cache can hold.
         * @param evictDelay the interval in milliseconds for the background eviction thread.
         */
        public DefaultDaoCache(final int capacity, final long evictDelay) {
            pool = PoolFactory.createKeyedObjectPool(capacity, evictDelay);
        }

        @Override
        @SuppressWarnings("unused")
        public Object get(final String defaultCacheKey, final Object daoProxy, final Object[] args,
                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            final PoolableWrapper<Object> w = pool.get(defaultCacheKey);

            return w == null ? null : w.value();
        }

        @Override
        @SuppressWarnings("unused")
        public boolean put(final String defaultCacheKey, final Object result, final Object daoProxy, final Object[] args,
                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            N.checkArgNotNull(defaultCacheKey, "Key cannot be null");

            return pool.put(defaultCacheKey, Poolable.wrap(result, JdbcUtil.DEFAULT_CACHE_LIVE_TIME, JdbcUtil.DEFAULT_CACHE_MAX_IDLE_TIME));
        }

        @Override
        public boolean put(String defaultCacheKey, Object result, long liveTime, long maxIdleTime, Object daoProxy, Object[] args,
                Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            N.checkArgNotNull(defaultCacheKey, "Key cannot be null");

            return pool.put(defaultCacheKey, Poolable.wrap(result, liveTime, maxIdleTime));
        }

        /**
         * Implements cache invalidation. If the table name can be determined from the cache key,
         * it removes all cache entries associated with that table. Otherwise, it clears the entire cache.
         * No action is taken for update operations that affect zero rows.
         */
        @Override
        @SuppressWarnings("unused")
        public void update(final String defaultCacheKey, final Object result, final Object daoProxy, final Object[] args,
                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            final Method method = methodSignature._1;

            if (JdbcUtil.BUILT_IN_DAO_UPDATE_METHODS.contains(method)) {
                if ((methodSignature._3.equals(int.class) || methodSignature._3.equals(long.class)) && (result != null && ((Number) result).longValue() == 0)) {
                    return;
                }
            }

            final String updatedTableName = Strings.substringBetween(defaultCacheKey, JdbcUtil.CACHE_KEY_SPLITOR);

            if (Strings.isEmpty(updatedTableName)) {
                pool.clear();
            } else {
                pool.keySet().stream().filter(k -> Strings.containsIgnoreCase(k, updatedTableName)).toList().forEach(pool::remove);
            }
        }
    }

    /**
         * A simple implementation of {@link DaoCache} that uses a standard {@code java.util.Map}
         * as the backing cache. It does not support automatic eviction or TTL.
         */
    record DaoCacheByMap(Map<String, Object> cache) implements DaoCache {
        /**
         * Creates a {@code DaoCacheByMap} with a new {@code HashMap}.
         */
        public DaoCacheByMap() {
            this(new HashMap<>());
        }

        /**
         * Creates a {@code DaoCacheByMap} with a {@code HashMap} of a specified initial capacity.
         *
         * @param capacity the initial capacity for the backing {@code HashMap}.
         */
        public DaoCacheByMap(final int capacity) {
            this(new HashMap<>(capacity));
        }

        /**
         * Creates a {@code DaoCacheByMap} backed by a provided map instance.
         *
         * @param cache the map to be used for caching.
         */
        DaoCacheByMap {
        }

        @Override
        @SuppressWarnings("unused")
        public Object get(final String defaultCacheKey, final Object daoProxy, final Object[] args,
                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            return cache.get(defaultCacheKey);
        }

        @Override
        @SuppressWarnings("unused")
        public boolean put(final String defaultCacheKey, final Object result, final Object daoProxy, final Object[] args,
                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            cache.put(defaultCacheKey, result);

            return true;
        }

        @Override
        public boolean put(String defaultCacheKey, Object result, long liveTime, long maxIdleTime, Object daoProxy, Object[] args,
                Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            cache.put(defaultCacheKey, result);

            return true;
        }

        /**
         * Implements cache invalidation. If the table name can be determined from the cache key,
         * it removes all entries whose keys contain that table name. Otherwise, it clears the entire cache.
         * No action is taken for update operations that affect zero rows.
         */
        @Override
        @SuppressWarnings("unused")
        public void update(final String defaultCacheKey, final Object result, final Object daoProxy, final Object[] args,
                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            final Method method = methodSignature._1;

            if (JdbcUtil.BUILT_IN_DAO_UPDATE_METHODS.contains(method)) {
                if ((methodSignature._3.equals(int.class) || methodSignature._3.equals(long.class)) && (result != null && ((Number) result).longValue() == 0)) {
                    return;
                }
            }

            final String updatedTableName = Strings.substringBetween(defaultCacheKey, JdbcUtil.CACHE_KEY_SPLITOR);

            if (Strings.isEmpty(updatedTableName)) {
                cache.clear();
            } else {
                cache.entrySet().removeIf(e -> Strings.containsIgnoreCase(e.getKey(), updatedTableName));
            }
        }
    }
}
