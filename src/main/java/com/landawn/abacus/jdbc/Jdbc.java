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
import com.landawn.abacus.cache.CacheFactory;
import com.landawn.abacus.cache.LocalCache;
import com.landawn.abacus.jdbc.Jdbc.Columns.ColumnOne;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.Array;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.DataSet;
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
 * The Jdbc class provides a comprehensive set of utilities for JDBC operations.
 * It includes interfaces and implementations for parameter setting, result extraction,
 * row mapping, filtering, and various other JDBC-related functionalities.
 * 
 * <p>This class serves as a central hub for JDBC utilities and is designed to work
 * seamlessly with the Abacus JDBC framework, providing type-safe and efficient
 * database operations.</p>
 * 
 * <p>Key features include:</p>
 * <ul>
 *   <li>Parameter setters for prepared statements</li>
 *   <li>Result extractors for converting ResultSet to various data structures</li>
 *   <li>Row mappers for object mapping</li>
 *   <li>Column getters for type-safe column value retrieval</li>
 *   <li>Row filters for result filtering</li>
 *   <li>Handler and cache support for advanced use cases</li>
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
     * A functional interface for setting parameters on a prepared query.
     * This interface is typically used to set parameters on PreparedStatement or CallableStatement.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * ParametersSetter<PreparedStatement> setter = ps -> {
     *     ps.setString(1, "John");
     *     ps.setInt(2, 25);
     * };
     * }</pre>
     *
     * @param <QS> the type of the query statement (typically PreparedStatement or CallableStatement)
     */
    @FunctionalInterface
    public interface ParametersSetter<QS> extends Throwables.Consumer<QS, SQLException> {
        /**
         * A no-operation parameter setter that does nothing.
         * Useful as a default or placeholder when no parameters need to be set.
         */
        @SuppressWarnings("rawtypes")
        ParametersSetter DO_NOTHING = preparedQuery -> {
            // Do nothing.
        };

        /**
         * Sets parameters on the given prepared query.
         *
         * @param preparedQuery the prepared query to set parameters on
         * @throws SQLException if a database access error occurs
         */
        @Override
        void accept(QS preparedQuery) throws SQLException;
    }

    /**
     * A functional interface for setting parameters on a prepared query using an additional parameter object.
     * This interface is useful when you need to set multiple parameters based on an object's properties.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * BiParametersSetter<PreparedStatement, User> setter = (ps, user) -> {
     *     ps.setString(1, user.getName());
     *     ps.setInt(2, user.getAge());
     * };
     * }</pre>
     *
     * @param <QS> the type of the query statement
     * @param <T> the type of the parameter object
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
         * Sets parameters on the given prepared query using the provided parameter object.
         *
         * @param preparedQuery the prepared query to set parameters on
         * @param param the parameter object containing values to set
         * @throws SQLException if a database access error occurs
         */
        @Override
        void accept(QS preparedQuery, T param) throws SQLException;

        /**
         * Creates a stateful BiParametersSetter for setting parameters from an array.
         * The setter maps array elements to prepared statement parameters based on field names and types.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * List<String> fields = Arrays.asList("name", "age");
         * BiParametersSetter<PreparedStatement, Object[]> setter = 
         *     BiParametersSetter.createForArray(fields, User.class);
         * setter.accept(ps, new Object[]{"John", 25});
         * }</pre>
         *
         * @param <T> the array type
         * @param fieldNameList the list of field names corresponding to array indices
         * @param entityClass the entity class to extract type information from
         * @return a stateful {@code BiParametersSetter}. Don't save or cache for reuse or use it in parallel stream.
         * @throws IllegalArgumentException if fieldNameList is empty or entityClass is not a valid bean class
         */
        @Beta
        @SequentialOnly
        @Stateful
        static <T> BiParametersSetter<PreparedStatement, T[]> createForArray(final List<String> fieldNameList, final Class<?> entityClass) {
            N.checkArgNotEmpty(fieldNameList, "'fieldNameList' can't be null or empty");
            N.checkArgument(ClassUtil.isBeanClass(entityClass), "{} is not a valid entity class with getter/setter methods", entityClass);

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
         * Creates a stateful BiParametersSetter for setting parameters from a List.
         * The setter maps list elements to prepared statement parameters based on field names and types.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * List<String> fields = Arrays.asList("name", "age");
         * BiParametersSetter<PreparedStatement, List<Object>> setter = 
         *     BiParametersSetter.createForList(fields, User.class);
         * setter.accept(ps, Arrays.asList("John", 25));
         * }</pre>
         *
         * @param <T> the element type of the list
         * @param fieldNameList the list of field names corresponding to list indices
         * @param entityClass the entity class to extract type information from
         * @return a stateful {@code BiParametersSetter}. Don't save or cache for reuse or use it in parallel stream.
         * @throws IllegalArgumentException if fieldNameList is empty or entityClass is not a valid bean class
         */
        @Beta
        @SequentialOnly
        @Stateful
        static <T> BiParametersSetter<PreparedStatement, List<T>> createForList(final List<String> fieldNameList, final Class<?> entityClass) {
            N.checkArgNotEmpty(fieldNameList, "'fieldNameList' can't be null or empty");
            N.checkArgument(ClassUtil.isBeanClass(entityClass), "{} is not a valid entity class with getter/setter methods", entityClass);

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
     * A functional interface for setting parameters on a prepared query using parsed SQL information.
     * This interface provides access to the parsed SQL structure, allowing for more sophisticated
     * parameter setting based on the SQL content.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * TriParametersSetter<PreparedStatement, User> setter = (parsedSql, ps, user) -> {
     *     // Use parsedSql to determine parameter positions
     *     ps.setString(1, user.getName());
     *     ps.setInt(2, user.getAge());
     * };
     * }</pre>
     *
     * @param <QS> the type of the query statement
     * @param <T> the type of the parameter object
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
         * @param parsedSql the parsed SQL containing parameter information
         * @param preparedQuery the prepared query to set parameters on
         * @param param the parameter object containing values to set
         * @throws SQLException if a database access error occurs
         */
        @Override
        void accept(ParsedSql parsedSql, QS preparedQuery, T param) throws SQLException;
    }

    /**
     * A functional interface for extracting results from a ResultSet.
     * This interface is used to convert a ResultSet into a desired object or data structure.
     * 
     * <p>Note: In many scenarios, the ResultSet will be closed after the apply() method returns,
     * so the ResultSet should not be saved or returned directly.</p>
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * ResultExtractor<List<String>> extractor = rs -> {
     *     List<String> names = new ArrayList<>();
     *     while (rs.next()) {
     *         names.add(rs.getString("name"));
     *     }
     *     return names;
     * };
     * }</pre>
     *
     * @param <T> the type of the result
     */
    @FunctionalInterface
    public interface ResultExtractor<T> extends Throwables.Function<ResultSet, T, SQLException> {

        /**
         * A pre-defined ResultExtractor that converts a ResultSet to a DataSet.
         * Returns an empty DataSet if the ResultSet is null.
         */
        ResultExtractor<DataSet> TO_DATA_SET = rs -> {
            if (rs == null) {
                return N.newEmptyDataSet();
            }

            return JdbcUtil.extractData(rs);
        };

        /**
         * Extracts a result from the given ResultSet.
         * 
         * <p>Important: In many scenarios, including PreparedQuery/Dao/SQLExecutor, 
         * the input ResultSet will be closed after this method returns. 
         * Do not save or return the input ResultSet.</p>
         *
         * @param rs the ResultSet to extract data from
         * @return the extracted result
         * @throws SQLException if a database access error occurs
         */
        @Override
        T apply(ResultSet rs) throws SQLException;

        /**
         * Returns a composed ResultExtractor that first applies this extractor and then applies the after function.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * ResultExtractor<List<User>> userExtractor = ...;
         * ResultExtractor<Integer> countExtractor = userExtractor.andThen(List::size);
         * }</pre>
         *
         * @param <R> the type of output of the after function
         * @param after the function to apply after this extractor
         * @return a composed ResultExtractor
         * @throws IllegalArgumentException if after is null
         */
        default <R> ResultExtractor<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return rs -> after.apply(apply(rs));
        }

        /**
         * Converts this ResultExtractor to a BiResultExtractor.
         * The resulting BiResultExtractor ignores the column labels parameter.
         *
         * @return a BiResultExtractor that delegates to this ResultExtractor
         */
        default BiResultExtractor<T> toBiResultExtractor() {
            return (rs, columnLabels) -> this.apply(rs);
        }

        /**
         * Creates a ResultExtractor that converts the result set into a Map.
         * Each row is processed to extract a key-value pair using the provided extractors.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * ResultExtractor<Map<Integer, String>> extractor = ResultExtractor.toMap(
         *     rs -> rs.getInt("id"),
         *     rs -> rs.getString("name")
         * );
         * }</pre>
         *
         * @param <K> the type of keys in the map
         * @param <V> the type of values in the map
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @return a ResultExtractor that produces a Map
         */
        static <K, V> ResultExtractor<Map<K, V>> toMap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor) {
            return toMap(keyExtractor, valueExtractor, Suppliers.ofMap());
        }

        /**
         * Creates a ResultExtractor that converts the result set into a Map with a custom map supplier.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * ResultExtractor<LinkedHashMap<Integer, String>> extractor = ResultExtractor.toMap(
         *     rs -> rs.getInt("id"),
         *     rs -> rs.getString("name"),
         *     LinkedHashMap::new
         * );
         * }</pre>
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M> the map type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @param supplier the supplier to create the map instance
         * @return a ResultExtractor that produces a Map
         */
        static <K, V, M extends Map<K, V>> ResultExtractor<M> toMap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final Supplier<? extends M> supplier) {
            return toMap(keyExtractor, valueExtractor, Fn.throwingMerger(), supplier);
        }

        /**
         * Creates a ResultExtractor that converts the result set into a Map with a merge function.
         * The merge function is used when duplicate keys are encountered.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * ResultExtractor<Map<String, Integer>> extractor = ResultExtractor.toMap(
         *     rs -> rs.getString("category"),
         *     rs -> rs.getInt("value"),
         *     Integer::sum  // Sum values for duplicate keys
         * );
         * }</pre>
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @param mergeFunction the function to merge values when duplicate keys are encountered
         * @return a ResultExtractor that produces a Map
         * @see Fn#throwingMerger()
         * @see Fn#replacingMerger()
         * @see Fn#ignoringMerger()
         */
        static <K, V> ResultExtractor<Map<K, V>> toMap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final BinaryOperator<V> mergeFunction) {
            return toMap(keyExtractor, valueExtractor, mergeFunction, Suppliers.ofMap());
        }

        /**
         * Creates a ResultExtractor that converts the result set into a Map with full customization.
         * Allows specifying key/value extractors, merge function, and map supplier.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * ResultExtractor<TreeMap<String, List<Integer>>> extractor = ResultExtractor.toMap(
         *     rs -> rs.getString("category"),
         *     rs -> Arrays.asList(rs.getInt("value")),
         *     (list1, list2) -> { list1.addAll(list2); return list1; },
         *     TreeMap::new
         * );
         * }</pre>
         *
         * @param <K> the type of keys in the map
         * @param <V> the type of values in the map
         * @param <M> the type of the map
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @param mergeFunction the function to merge values when duplicate keys are encountered
         * @param supplier the supplier to create the map instance
         * @return a ResultExtractor that produces a Map
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
         * Creates a ResultExtractor that converts the result set into a Map with downstream collector.
         * 
         * @param <K> the key type
         * @param <V> the value type
         * @param <D> the downstream result type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @param downstream the downstream collector
         * @return a ResultExtractor that produces a Map
         * @see #groupTo(RowMapper, RowMapper, Collector)
         * @deprecated replaced by {@code groupTo(RowMapper, RowMapper, Collector)}
         */
        @Deprecated
        static <K, V, D> ResultExtractor<Map<K, D>> toMap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final Collector<? super V, ?, D> downstream) {
            return toMap(keyExtractor, valueExtractor, downstream, Suppliers.ofMap());
        }

        /**
         * Creates a ResultExtractor that converts the result set into a Map with downstream collector and custom supplier.
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <D> the downstream result type
         * @param <M> the map type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @param downstream the downstream collector
         * @param supplier the supplier to create the map instance
         * @return a ResultExtractor that produces a Map
         * @see #groupTo(RowMapper, RowMapper, Collector, Supplier)
         * @deprecated replaced by {@code groupTo(RowMapper, RowMapper, Collector, Supplier)}
         */
        @Deprecated
        static <K, V, D, M extends Map<K, D>> ResultExtractor<M> toMap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final Collector<? super V, ?, D> downstream, final Supplier<? extends M> supplier) {
            return groupTo(keyExtractor, valueExtractor, downstream, supplier);
        }

        /**
         * Creates a ResultExtractor that converts the result set into a ListMultimap.
         * Each key can be associated with multiple values.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * ResultExtractor<ListMultimap<String, Integer>> extractor = ResultExtractor.toMultimap(
         *     rs -> rs.getString("category"),
         *     rs -> rs.getInt("value")
         * );
         * }</pre>
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @return a ResultExtractor that produces a ListMultimap
         */
        static <K, V> ResultExtractor<ListMultimap<K, V>> toMultimap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor) {
            return toMultimap(keyExtractor, valueExtractor, Suppliers.ofListMultimap());
        }

        /**
         * Creates a ResultExtractor that converts the result set into a Multimap with custom supplier.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * ResultExtractor<SetMultimap<String, Integer>> extractor = ResultExtractor.toMultimap(
         *     rs -> rs.getString("category"),
         *     rs -> rs.getInt("value"),
         *     Suppliers.ofSetMultimap()
         * );
         * }</pre>
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <C> the collection type for values
         * @param <M> the multimap type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @param multimapSupplier the supplier to create the multimap instance
         * @return a ResultExtractor that produces a Multimap
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
         * Creates a ResultExtractor that groups result set rows into a Map of Lists.
         * Each key is associated with a list of values.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * ResultExtractor<Map<String, List<User>>> extractor = ResultExtractor.groupTo(
         *     rs -> rs.getString("department"),
         *     rs -> new User(rs.getString("name"), rs.getInt("age"))
         * );
         * }</pre>
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @return a ResultExtractor that produces a Map with List values
         */
        static <K, V> ResultExtractor<Map<K, List<V>>> groupTo(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor) {
            return groupTo(keyExtractor, valueExtractor, Suppliers.ofMap());
        }

        /**
         * Creates a ResultExtractor that groups result set rows into a Map of Lists with custom map supplier.
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M> the map type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @param supplier the supplier to create the map instance
         * @return a ResultExtractor that produces a Map with List values
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
         * Creates a ResultExtractor that groups result set rows with a downstream collector.
         * This allows for more complex aggregations beyond simple list collection.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * ResultExtractor<Map<String, Double>> extractor = ResultExtractor.groupTo(
         *     rs -> rs.getString("category"),
         *     rs -> rs.getDouble("amount"),
         *     Collectors.summingDouble(Double::doubleValue)
         * );
         * }</pre>
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <D> the downstream result type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @param downstream the downstream collector for aggregating values
         * @return a ResultExtractor that produces a Map with collected values
         */
        static <K, V, D> ResultExtractor<Map<K, D>> groupTo(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final Collector<? super V, ?, D> downstream) {
            return groupTo(keyExtractor, valueExtractor, downstream, Suppliers.ofMap());
        }

        /**
         * Creates a ResultExtractor that groups result set rows with a downstream collector and custom map supplier.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * ResultExtractor<TreeMap<String, Long>> extractor = ResultExtractor.groupTo(
         *     rs -> rs.getString("category"),
         *     rs -> rs.getInt("count"),
         *     Collectors.counting(),
         *     TreeMap::new
         * );
         * }</pre>
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <D> the downstream result type
         * @param <M> the map type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @param downstream the downstream collector for aggregating values
         * @param supplier the supplier to create the map instance
         * @return a ResultExtractor that produces a Map with collected values
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
         * Creates a ResultExtractor that converts the result set into a List.
         * Each row is mapped to an element using the provided row mapper.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * ResultExtractor<List<String>> extractor = ResultExtractor.toList(
         *     rs -> rs.getString("name")
         * );
         * }</pre>
         *
         * @param <T> the element type
         * @param rowMapper the function to map each row to an element
         * @return a ResultExtractor that produces a List
         */
        static <T> ResultExtractor<List<T>> toList(final RowMapper<? extends T> rowMapper) {
            return toList(RowFilter.ALWAYS_TRUE, rowMapper);
        }

        /**
         * Creates a ResultExtractor that converts the result set into a List with filtering.
         * Only rows that pass the filter are included in the result.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * ResultExtractor<List<User>> extractor = ResultExtractor.toList(
         *     rs -> rs.getInt("age") >= 18,  // Only adults
         *     rs -> new User(rs.getString("name"), rs.getInt("age"))
         * );
         * }</pre>
         *
         * @param <T> the element type
         * @param rowFilter the predicate to filter rows
         * @param rowMapper the function to map each row to an element
         * @return a ResultExtractor that produces a filtered List
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
         * Creates a ResultExtractor that converts the result set into a List of entities.
         * The target class must be a valid bean class with appropriate getters/setters.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * ResultExtractor<List<User>> extractor = ResultExtractor.toList(User.class);
         * }</pre>
         *
         * @param <T> the entity type
         * @param targetClass the class of entities to create
         * @return a ResultExtractor that produces a List of entities
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
         * Creates a ResultExtractor that converts the result set into a List of merged entities.
         * This method is useful for handling results from JOIN queries where the same entity
         * appears in multiple rows with different related data.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * // For a query that joins users with their orders
         * ResultExtractor<List<User>> extractor = ResultExtractor.toMergedList(User.class);
         * }</pre>
         *
         * @param <T> the entity type
         * @param targetClass the class of entities to create
         * @return a ResultExtractor that produces a List of merged entities
         * @see DataSet#toMergedEntities(Class)
         */
        static <T> ResultExtractor<List<T>> toMergedList(final Class<? extends T> targetClass) {
            N.checkArgNotNull(targetClass, cs.targetClass);

            return rs -> {
                final RowExtractor rowExtractor = RowExtractor.createBy(targetClass);

                return JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, rowExtractor, false).toMergedEntities(targetClass);
            };
        }

        /**
         * Creates a ResultExtractor that converts the result set into a List of merged entities
         * using a specific property for merging.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * // Merge users by their ID
         * ResultExtractor<List<User>> extractor = ResultExtractor.toMergedList(User.class, "id");
         * }</pre>
         *
         * @param <T> the entity type
         * @param targetClass the class of entities to create
         * @param idPropNameForMerge the property name to use for identifying entities to merge
         * @return a ResultExtractor that produces a List of merged entities
         * @see DataSet#toMergedEntities(Collection, Collection, Class)
         */
        static <T> ResultExtractor<List<T>> toMergedList(final Class<? extends T> targetClass, final String idPropNameForMerge) {
            N.checkArgNotNull(targetClass, cs.targetClass);

            return rs -> {
                final RowExtractor rowExtractor = RowExtractor.createBy(targetClass);

                return JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, rowExtractor, false).toMergedEntities(idPropNameForMerge, targetClass);
            };
        }

        /**
         * Creates a ResultExtractor that converts the result set into a List of merged entities
         * using multiple properties for merging.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * // Merge orders by customer ID and order date
         * ResultExtractor<List<Order>> extractor = ResultExtractor.toMergedList(
         *     Order.class, 
         *     Arrays.asList("customerId", "orderDate")
         * );
         * }</pre>
         *
         * @param <T> the entity type
         * @param targetClass the class of entities to create
         * @param idPropNamesForMerge the property names to use for identifying entities to merge
         * @return a ResultExtractor that produces a List of merged entities
         * @see DataSet#toMergedEntities(Collection, Collection, Class)
         */
        static <T> ResultExtractor<List<T>> toMergedList(final Class<? extends T> targetClass, final Collection<String> idPropNamesForMerge) {
            N.checkArgNotNull(targetClass, cs.targetClass);

            return rs -> {
                final RowExtractor rowExtractor = RowExtractor.createBy(targetClass);

                return JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, rowExtractor, false).toMergedEntities(idPropNamesForMerge, targetClass);
            };
        }

        /**
         * Creates a ResultExtractor that converts the result set into a DataSet using a specific entity class.
         * The entity class is used to determine how to map columns to fields.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * ResultExtractor<DataSet> extractor = ResultExtractor.toDataSet(User.class);
         * }</pre>
         *
         * @param entityClassForExtractor The class used to map the fields from the columns in the result set
         * @return a ResultExtractor that produces a DataSet
         */
        static ResultExtractor<DataSet> toDataSet(final Class<?> entityClassForExtractor) {
            return rs -> JdbcUtil.extractData(rs, RowExtractor.createBy(entityClassForExtractor));
        }

        /**
         * Creates a ResultExtractor that converts the result set into a DataSet with field name mapping.
         * The prefix and field name map allows for custom column-to-field mapping.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * Map<String, String> prefixMap = new HashMap<>();
         * prefixMap.put("u_", "user.");
         * ResultExtractor<DataSet> extractor = ResultExtractor.toDataSet(User.class, prefixMap);
         * }</pre>
         *
         * @param entityClassForExtractor The class used to map the fields from the columns in the result set
         * @param prefixAndFieldNameMap map of column prefixes to field name prefixes
         * @return a ResultExtractor that produces a DataSet
         */
        static ResultExtractor<DataSet> toDataSet(final Class<?> entityClassForExtractor, final Map<String, String> prefixAndFieldNameMap) {
            return rs -> JdbcUtil.extractData(rs, RowExtractor.createBy(entityClassForExtractor, prefixAndFieldNameMap));
        }

        /**
         * Creates a ResultExtractor that converts the result set into a DataSet with row filtering.
         * Only rows that pass the filter are included in the DataSet.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * ResultExtractor<DataSet> extractor = ResultExtractor.toDataSet(
         *     rs -> rs.getBoolean("active")  // Only active records
         * );
         * }</pre>
         *
         * @param rowFilter the predicate to filter rows
         * @return a ResultExtractor that produces a filtered DataSet
         */
        static ResultExtractor<DataSet> toDataSet(final RowFilter rowFilter) {
            return rs -> JdbcUtil.extractData(rs, rowFilter);
        }

        /**
         * Creates a ResultExtractor that converts the result set into a DataSet using a custom row extractor.
         * The row extractor provides full control over how each row is extracted.
         *
         * @param rowExtractor the custom row extractor
         * @return a ResultExtractor that produces a DataSet
         */
        static ResultExtractor<DataSet> toDataSet(final RowExtractor rowExtractor) {
            return rs -> JdbcUtil.extractData(rs, rowExtractor);
        }

        /**
         * Creates a ResultExtractor that converts the result set into a DataSet with both filtering and custom extraction.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * ResultExtractor<DataSet> extractor = ResultExtractor.toDataSet(
         *     rs -> rs.getInt("status") > 0,  // Filter
         *     RowExtractor.createBy(User.class)  // Custom extraction
         * );
         * }</pre>
         *
         * @param rowFilter the predicate to filter rows
         * @param rowExtractor the custom row extractor
         * @return a ResultExtractor that produces a filtered DataSet with custom extraction
         */
        static ResultExtractor<DataSet> toDataSet(final RowFilter rowFilter, final RowExtractor rowExtractor) {
            return rs -> JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, rowFilter, rowExtractor, false);
        }

        /**
         * Creates a ResultExtractor that first converts to DataSet and then applies a transformation function.
         * This is useful for chaining DataSet operations.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * ResultExtractor<List<User>> extractor = ResultExtractor.to(
         *     dataSet -> dataSet.toList(User.class)
         * );
         * }</pre>
         *
         * @param <R> the result type
         * @param after the function to apply to the DataSet
         * @return a ResultExtractor that produces the transformed result
         */
        static <R> ResultExtractor<R> to(final Throwables.Function<DataSet, R, SQLException> after) {
            return rs -> after.apply(TO_DATA_SET.apply(rs));
        }
    }

    /**
     * A functional interface for extracting results from a ResultSet with column label information.
     * This interface is similar to ResultExtractor but also provides access to column labels,
     * which can be useful for dynamic result processing.
     * 
     * <p>Note: In many scenarios, the ResultSet will be closed after the apply() method returns,
     * so the ResultSet should not be saved or returned directly.</p>
     *
     * @param <T> the type of the result
     */
    @FunctionalInterface
    public interface BiResultExtractor<T> extends Throwables.BiFunction<ResultSet, List<String>, T, SQLException> {

        /**
         * A pre-defined BiResultExtractor that converts a ResultSet to a DataSet.
         * Returns an empty DataSet if the ResultSet is null.
         */
        BiResultExtractor<DataSet> TO_DATA_SET = (rs, columnLabels) -> {
            if (rs == null) {
                return N.newEmptyDataSet();
            }

            return JdbcUtil.extractData(rs);
        };

        /**
         * Extracts a result from the given ResultSet using column label information.
         * 
         * <p>Important: In many scenarios, including PreparedQuery/Dao/SQLExecutor,
         * the input ResultSet will be closed after this method returns.
         * Do not save or return the input ResultSet.</p>
         *
         * @param rs the ResultSet to extract data from
         * @param columnLabels the list of column labels in the result set
         * @return the extracted result
         * @throws SQLException if a database access error occurs
         */
        @Override
        T apply(ResultSet rs, List<String> columnLabels) throws SQLException;

        /**
         * Returns a composed BiResultExtractor that first applies this extractor and then applies the after function.
         *
         * @param <R> the type of output of the after function
         * @param after the function to apply after this extractor
         * @return a composed BiResultExtractor
         * @throws IllegalArgumentException if after is null
         */
        default <R> BiResultExtractor<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return (rs, columnLabels) -> after.apply(apply(rs, columnLabels));
        }

        /**
         * Creates a BiResultExtractor that converts the result set into a Map.
         * Each row is processed to extract a key-value pair using the provided extractors.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * BiResultExtractor<Map<Integer, String>> extractor = BiResultExtractor.toMap(
         *     (rs, cols) -> rs.getInt("id"),
         *     (rs, cols) -> rs.getString("name")
         * );
         * }</pre>
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @return a BiResultExtractor that produces a Map
         */
        static <K, V> BiResultExtractor<Map<K, V>> toMap(final BiRowMapper<? extends K> keyExtractor, final BiRowMapper<? extends V> valueExtractor) {
            return toMap(keyExtractor, valueExtractor, Suppliers.ofMap());
        }

        /**
         * Creates a BiResultExtractor that converts the result set into a Map with a custom map supplier.
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M> the map type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @param supplier the supplier to create the map instance
         * @return a BiResultExtractor that produces a Map
         */
        static <K, V, M extends Map<K, V>> BiResultExtractor<M> toMap(final BiRowMapper<? extends K> keyExtractor,
                final BiRowMapper<? extends V> valueExtractor, final Supplier<? extends M> supplier) {
            return toMap(keyExtractor, valueExtractor, Fn.throwingMerger(), supplier);
        }

        /**
         * Creates a BiResultExtractor that converts the result set into a Map with a merge function.
         * The merge function is used when duplicate keys are encountered.
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @param mergeFunction the function to merge values when duplicate keys are encountered
         * @return a BiResultExtractor that produces a Map
         * @see Fn#throwingMerger()
         * @see Fn#replacingMerger()
         * @see Fn#ignoringMerger()
         */
        static <K, V> BiResultExtractor<Map<K, V>> toMap(final BiRowMapper<? extends K> keyExtractor, final BiRowMapper<? extends V> valueExtractor,
                final BinaryOperator<V> mergeFunction) {
            return toMap(keyExtractor, valueExtractor, mergeFunction, Suppliers.ofMap());
        }

        /**
         * Creates a BiResultExtractor that converts the result set into a Map with full customization.
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M> the map type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @param mergeFunction the function to merge values when duplicate keys are encountered
         * @param supplier the supplier to create the map instance
         * @return a BiResultExtractor that produces a Map
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
         * Creates a BiResultExtractor that converts the result set into a Map with downstream collector.
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <D> the downstream result type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @param downstream the downstream collector
         * @return a BiResultExtractor that produces a Map
         * @see #groupTo(BiRowMapper, BiRowMapper, Collector)
         * @deprecated replaced by {@code groupTo(BiRowMapper, BiRowMapper, Collector)}
         */
        @Deprecated
        static <K, V, D> BiResultExtractor<Map<K, D>> toMap(final BiRowMapper<? extends K> keyExtractor, final BiRowMapper<? extends V> valueExtractor,
                final Collector<? super V, ?, D> downstream) {
            return toMap(keyExtractor, valueExtractor, downstream, Suppliers.ofMap());
        }

        /**
         * Creates a BiResultExtractor that converts the result set into a Map with downstream collector and custom supplier.
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <D> the downstream result type
         * @param <M> the map type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @param downstream the downstream collector
         * @param supplier the supplier to create the map instance
         * @return a BiResultExtractor that produces a Map
         * @see #groupTo(BiRowMapper, BiRowMapper, Collector, Supplier)
         * @deprecated replaced by {@code groupTo(BiRowMapper, BiRowMapper, Collector, Supplier)}
         */
        @Deprecated
        static <K, V, D, M extends Map<K, D>> BiResultExtractor<M> toMap(final BiRowMapper<? extends K> keyExtractor,
                final BiRowMapper<? extends V> valueExtractor, final Collector<? super V, ?, D> downstream, final Supplier<? extends M> supplier) {
            return groupTo(keyExtractor, valueExtractor, downstream, supplier);
        }

        /**
         * Creates a BiResultExtractor that converts the result set into a ListMultimap.
         * Each key can be associated with multiple values.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * BiResultExtractor<ListMultimap<String, Integer>> extractor = BiResultExtractor.toMultimap(
         *     (rs, cols) -> rs.getString("category"),
         *     (rs, cols) -> rs.getInt("value")
         * );
         * }</pre>
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @return a BiResultExtractor that produces a ListMultimap
         */
        static <K, V> BiResultExtractor<ListMultimap<K, V>> toMultimap(final BiRowMapper<? extends K> keyExtractor,
                final BiRowMapper<? extends V> valueExtractor) {
            return toMultimap(keyExtractor, valueExtractor, Suppliers.ofListMultimap());
        }

        /**
         * Creates a BiResultExtractor that converts the result set into a Multimap with custom supplier.
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <C> the collection type for values
         * @param <M> the multimap type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @param multimapSupplier the supplier to create the multimap instance
         * @return a BiResultExtractor that produces a Multimap
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
         * Creates a BiResultExtractor that groups result set rows into a Map of Lists.
         * Each key is associated with a list of values.
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @return a BiResultExtractor that produces a Map with List values
         */
        static <K, V> BiResultExtractor<Map<K, List<V>>> groupTo(final BiRowMapper<? extends K> keyExtractor, final BiRowMapper<? extends V> valueExtractor) {
            return groupTo(keyExtractor, valueExtractor, Suppliers.ofMap());
        }

        /**
         * Creates a BiResultExtractor that groups result set rows into a Map of Lists with custom map supplier.
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M> the map type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @param supplier the supplier to create the map instance
         * @return a BiResultExtractor that produces a Map with List values
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
         * Creates a BiResultExtractor that groups result set rows with a downstream collector.
         * This allows for more complex aggregations beyond simple list collection.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * BiResultExtractor<Map<String, Double>> extractor = BiResultExtractor.groupTo(
         *     (rs, cols) -> rs.getString("category"),
         *     (rs, cols) -> rs.getDouble("amount"),
         *     Collectors.summingDouble(Double::doubleValue)
         * );
         * }</pre>
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <D> the downstream result type
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @param downstream the downstream collector for aggregating values
         * @return a BiResultExtractor that produces a Map with collected values
         */
        static <K, V, D> BiResultExtractor<Map<K, D>> groupTo(final BiRowMapper<? extends K> keyExtractor, final BiRowMapper<? extends V> valueExtractor,
                final Collector<? super V, ?, D> downstream) {
            return groupTo(keyExtractor, valueExtractor, downstream, Suppliers.ofMap());
        }

        /**
         * Creates a BiResultExtractor that groups result set rows with a downstream collector and custom map supplier.
         * Provides full control over grouping with custom aggregation and map type.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * BiResultExtractor<TreeMap<String, Long>> extractor = BiResultExtractor.groupTo(
         *     (rs, cols) -> rs.getString("category"),
         *     (rs, cols) -> rs.getInt("count"),
         *     Collectors.counting(),
         *     TreeMap::new
         * );
         * }</pre>
         *
         * @param <K> the type of keys maintained by the map
         * @param <V> the type of mapped values
         * @param <D> the type of the result of the downstream collector
         * @param <M> the type of the map
         * @param keyExtractor the function to extract keys from the result set
         * @param valueExtractor the function to extract values from the result set
         * @param downstream the collector to accumulate values associated with a key
         * @param supplier the supplier to provide a new map instance
         * @return a BiResultExtractor that produces a Map with collected values
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
         * Creates a BiResultExtractor that converts the result set into a List.
         * Each row is mapped to an element using the provided row mapper.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * BiResultExtractor<List<String>> extractor = BiResultExtractor.toList(
         *     (rs, cols) -> rs.getString("name")
         * );
         * }</pre>
         *
         * @param <T> the element type
         * @param rowMapper the function to map each row to an element
         * @return a BiResultExtractor that produces a List
         */
        static <T> BiResultExtractor<List<T>> toList(final BiRowMapper<? extends T> rowMapper) {
            return toList(BiRowFilter.ALWAYS_TRUE, rowMapper);
        }

        /**
         * Creates a BiResultExtractor that converts the result set into a List with filtering.
         * Only rows that pass the filter are included in the result.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * BiResultExtractor<List<User>> extractor = BiResultExtractor.toList(
         *     (rs, cols) -> rs.getInt("age") >= 18,  // Only adults
         *     (rs, cols) -> new User(rs.getString("name"), rs.getInt("age"))
         * );
         * }</pre>
         *
         * @param <T> the element type
         * @param rowFilter the predicate to filter rows
         * @param rowMapper the function to map each row to an element
         * @return a BiResultExtractor that produces a filtered List
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
         * Creates a BiResultExtractor that converts the result set into a List of entities.
         * The target class must be a valid bean class with appropriate getters/setters.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * BiResultExtractor<List<User>> extractor = BiResultExtractor.toList(User.class);
         * }</pre>
         *
         * @param <T> the entity type
         * @param targetClass the class of entities to create
         * @return a stateful {@code BiResultExtractor}. Don't save or cache for reuse or use it in parallel stream.
         * @see ResultExtractor#toList(Class)
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
     * A functional interface for mapping rows of a ResultSet to objects.
     * This interface is designed to convert a single row of a ResultSet into an object of type T.
     * 
     * <p>If column labels/count are used in the apply() method, consider using BiRowMapper instead
     * as it's more efficient for retrieving multiple records when column labels/count are needed.</p>
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * RowMapper<User> mapper = rs -> new User(
     *     rs.getInt("id"),
     *     rs.getString("name"),
     *     rs.getInt("age")
     * );
     * }</pre>
     *
     * @param <T> the type of the object that each row of the ResultSet will be mapped to
     * @see ColumnOne
     */
    @FunctionalInterface
    public interface RowMapper<T> extends Throwables.Function<ResultSet, T, SQLException> {

        /**
         * Maps a row of the ResultSet to an object of type T.
         * This method should not advance the ResultSet cursor; it should only read
         * from the current row.
         *
         * @param rs the ResultSet positioned at a valid row
         * @return the mapped object of type T
         * @throws SQLException if a database access error occurs
         */
        @Override
        T apply(ResultSet rs) throws SQLException;

        /**
         * Returns a composed RowMapper that first applies this mapper and then applies the after function.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * RowMapper<User> userMapper = ...;
         * RowMapper<String> nameMapper = userMapper.andThen(User::getName);
         * }</pre>
         *
         * @param <R> the type of output of the after function
         * @param after the function to apply after this mapper is applied
         * @return a composed RowMapper
         */
        default <R> RowMapper<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return rs -> after.apply(apply(rs));
        }

        /**
         * Converts this RowMapper to a BiRowMapper.
         * The resulting BiRowMapper ignores the column labels parameter.
         *
         * @return a BiRowMapper that delegates to this RowMapper
         */
        default BiRowMapper<T> toBiRowMapper() {
            return (rs, columnLabels) -> this.apply(rs);
        }

        /**
         * Combines two RowMapper instances into a RowMapper that returns a Tuple2 of their results.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * RowMapper<Integer> idMapper = rs -> rs.getInt("id");
         * RowMapper<String> nameMapper = rs -> rs.getString("name");
         * RowMapper<Tuple2<Integer, String>> combined = RowMapper.combine(idMapper, nameMapper);
         * }</pre>
         *
         * @param <T> the type of the first RowMapper
         * @param <U> the type of the second RowMapper
         * @param rowMapper1 the first RowMapper
         * @param rowMapper2 the second RowMapper
         * @return a RowMapper that returns a Tuple2 of the results
         */
        static <T, U> RowMapper<Tuple2<T, U>> combine(final RowMapper<? extends T> rowMapper1, final RowMapper<? extends U> rowMapper2) {
            N.checkArgNotNull(rowMapper1, cs.rowMapper1);
            N.checkArgNotNull(rowMapper2, cs.rowMapper2);

            return rs -> Tuple.of(rowMapper1.apply(rs), rowMapper2.apply(rs));
        }

        /**
         * Combines three RowMapper instances into a RowMapper that returns a Tuple3 of their results.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * RowMapper<Integer> idMapper = rs -> rs.getInt("id");
         * RowMapper<String> nameMapper = rs -> rs.getString("name");
         * RowMapper<Integer> ageMapper = rs -> rs.getInt("age");
         * RowMapper<Tuple3<Integer, String, Integer>> combined = 
         *     RowMapper.combine(idMapper, nameMapper, ageMapper);
         * }</pre>
         *
         * @param <A> the type of the first RowMapper
         * @param <B> the type of the second RowMapper
         * @param <C> the type of the third RowMapper
         * @param rowMapper1 the first RowMapper
         * @param rowMapper2 the second RowMapper
         * @param rowMapper3 the third RowMapper
         * @return a RowMapper that returns a Tuple3 of the results
         */
        static <A, B, C> RowMapper<Tuple3<A, B, C>> combine(final RowMapper<? extends A> rowMapper1, final RowMapper<? extends B> rowMapper2,
                final RowMapper<? extends C> rowMapper3) {
            N.checkArgNotNull(rowMapper1, cs.rowMapper1);
            N.checkArgNotNull(rowMapper2, cs.rowMapper2);
            N.checkArgNotNull(rowMapper3, cs.rowMapper3);

            return rs -> Tuple.of(rowMapper1.apply(rs), rowMapper2.apply(rs), rowMapper3.apply(rs));
        }

        /**
         * Creates a stateful RowMapper that maps all columns to an Object array.
         * Uses the provided ColumnGetter for all columns.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param columnGetterForAll the ColumnGetter to use for all columns
         * @return a stateful RowMapper. Don't save or cache for reuse or use it in parallel stream.
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
         * Creates a stateful RowMapper that maps all columns to a List.
         * Uses the provided ColumnGetter for all columns.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param columnGetterForAll the ColumnGetter to use for all columns
         * @return a stateful RowMapper. Don't save or cache for reuse or use it in parallel stream.
         */
        @Beta
        @SequentialOnly
        @Stateful
        static RowMapper<List<Object>> toList(final ColumnGetter<?> columnGetterForAll) {
            return toCollection(columnGetterForAll, IntFunctions.ofList());
        }

        /**
         * Creates a stateful RowMapper that maps all columns to a Collection.
         * Uses the provided ColumnGetter for all columns and the supplier to create the collection.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param <C> the collection type
         * @param columnGetterForAll the ColumnGetter to use for all columns
         * @param supplier the function to create the collection with the expected size
         * @return a stateful RowMapper. Don't save or cache for reuse or use it in parallel stream.
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
         * Creates a stateful RowMapper that maps all columns to a DisposableObjArray.
         * This is useful for efficient row processing where the same array can be reused.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @return a stateful RowMapper. Don't save or cache for reuse or use it in parallel stream.
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
         * Creates a stateful RowMapper that maps columns to a DisposableObjArray using entity class metadata.
         * The entity class is used to determine the appropriate type conversions for each column.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param entityClass used to fetch column/row value from ResultSet by the type of fields/columns defined in this class
         * @return a stateful RowMapper. Don't save or cache for reuse or use it in parallel stream.
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
         * Creates a RowMapperBuilder with default column getter for object values.
         *
         * @return a new RowMapperBuilder
         */
        static RowMapperBuilder builder() {
            return builder(ColumnGetter.GET_OBJECT);
        }

        /**
         * Creates a RowMapperBuilder with the specified default column getter.
         * The builder allows for custom configuration of how each column is extracted.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * RowMapper<Object[]> mapper = RowMapper.builder()
         *     .getInt(1)
         *     .getString(2)
         *     .getDate(3)
         *     .toArray();
         * }</pre>
         *
         * @param defaultColumnGetter the default ColumnGetter to use for columns not explicitly configured
         * @return a new RowMapperBuilder
         */
        static RowMapperBuilder builder(final ColumnGetter<?> defaultColumnGetter) {
            return new RowMapperBuilder(defaultColumnGetter);
        }

        /**
         * A builder class for creating customized RowMapper instances.
         * This builder allows specifying different column getters for specific columns
         * and provides various output formats (array, list, map, etc.).
         */
        @SequentialOnly
        class RowMapperBuilder {
            private final Map<Integer, ColumnGetter<?>> columnGetterMap;

            RowMapperBuilder(final ColumnGetter<?> defaultColumnGetter) {
                N.checkArgNotNull(defaultColumnGetter, cs.defaultColumnGetter);

                columnGetterMap = new HashMap<>(9);
                columnGetterMap.put(0, defaultColumnGetter);
            }

            /**
             * Configures the builder to get a boolean value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowMapperBuilder getBoolean(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BOOLEAN);
            }

            /**
             * Configures the builder to get a byte value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowMapperBuilder getByte(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BYTE);
            }

            /**
             * Configures the builder to get a short value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowMapperBuilder getShort(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_SHORT);
            }

            /**
             * Configures the builder to get an int value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowMapperBuilder getInt(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_INT);
            }

            /**
             * Configures the builder to get a long value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowMapperBuilder getLong(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_LONG);
            }

            /**
             * Configures the builder to get a float value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowMapperBuilder getFloat(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_FLOAT);
            }

            /**
             * Configures the builder to get a double value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowMapperBuilder getDouble(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_DOUBLE);
            }

            /**
             * Configures the builder to get a BigDecimal value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowMapperBuilder getBigDecimal(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BIG_DECIMAL);
            }

            /**
             * Configures the builder to get a String value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowMapperBuilder getString(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_STRING);
            }

            /**
             * Configures the builder to get a Date value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowMapperBuilder getDate(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_DATE);
            }

            /**
             * Configures the builder to get a Time value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowMapperBuilder getTime(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_TIME);
            }

            /**
             * Configures the builder to get a Timestamp value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowMapperBuilder getTimestamp(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_TIMESTAMP);
            }

            /**
             * Configures the builder to get an Object value from the specified column.
             * Uses the default object getter if no specific getter is set for the column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             * @deprecated default {@link #getObject(int)} if there is no {@code ColumnGetter} set for the target column
             */
            @Deprecated
            public RowMapperBuilder getObject(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_OBJECT);
            }

            /**
             * Configures the builder to get an Object of specific type from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @param type the class type to convert the column value to
             * @return this builder instance
             */
            public RowMapperBuilder getObject(final int columnIndex, final Class<?> type) {
                return get(columnIndex, ColumnGetter.get(type));
            }

            /**
             * Configures the builder to use a custom ColumnGetter for the specified column.
             * 
             * <p>Example usage:</p>
             * <pre>{@code
             * builder.get(1, (rs, idx) -> rs.getString(idx).toUpperCase());
             * }</pre>
             *
             * @param columnIndex the column index (1-based)
             * @param columnGetter the custom ColumnGetter to use
             * @return this builder instance
             * @throws IllegalArgumentException if columnIndex is not positive or columnGetter is null
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
             * Builds a stateful RowMapper that maps columns to an Object array.
             * Don't cache or reuse the returned RowMapper instance.
             *
             * @return a stateful RowMapper. Don't save or cache for reuse or use it in parallel stream.
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
             * Builds a stateful RowMapper that maps columns to a List.
             * Don't cache or reuse the returned RowMapper instance.
             *
             * @return a stateful RowMapper. Don't save or cache for reuse or use it in parallel stream.
             */
            @SequentialOnly
            @Stateful
            public RowMapper<List<Object>> toList() {
                return toCollection(IntFunctions.ofList());
            }

            /**
             * Builds a stateful RowMapper that maps columns to a Collection.
             * Don't cache or reuse the returned RowMapper instance.
             * 
             * @param <C> the collection type
             * @param supplier the supplier to provide a new collection instance
             * @return a stateful RowMapper. Don't save or cache for reuse or use it in parallel stream.
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
             * Builds a stateful RowMapper that maps columns to a Map with column names as keys.
             * Don't cache or reuse the returned RowMapper instance.
             *
             * @return a stateful RowMapper. Don't save or cache for reuse or use it in parallel stream.
             */
            @SequentialOnly
            @Stateful
            public RowMapper<Map<String, Object>> toMap() {
                return toMap(IntFunctions.ofMap());
            }

            /**
             * Builds a stateful RowMapper that maps columns to a Map with custom supplier.
             * Don't cache or reuse the returned RowMapper instance.
             * 
             * @param mapSupplier the supplier to provide a new map instance
             * @return a stateful RowMapper. Don't save or cache for reuse or use it in parallel stream.
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
             * Builds a stateful RowMapper with a custom finisher function.
             * The finisher receives a DisposableObjArray containing the row values.
             * 
             * <p>Example usage:</p>
             * <pre>{@code
             * RowMapper<User> mapper = builder
             *     .getString(1)
             *     .getInt(2)
             *     .to(arr -> new User((String)arr.get(0), (Integer)arr.get(1)));
             * }</pre>
             *
             * @param <R> the result type
             * @param finisher the function to transform the row array into the result
             * @return a stateful RowMapper. Don't save or cache for reuse or use it in parallel stream.
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
             * Builds a stateful RowMapper with a custom finisher function that also receives column labels.
             * 
             * <p>Example usage:</p>
             * <pre>{@code
             * RowMapper<Map<String, Object>> mapper = builder.to((labels, arr) -> {
             *     Map<String, Object> map = new HashMap<>();
             *     for (int i = 0; i < labels.size(); i++) {
             *         map.put(labels.get(i), arr.get(i));
             *     }
             *     return map;
             * });
             * }</pre>
             *
             * @param <R> the result type
             * @param finisher the function to transform the column labels and row array into the result
             * @return a stateful RowMapper. Don't save or cache for reuse or use it in parallel stream.
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
     * A functional interface for mapping rows of a ResultSet to objects with access to column labels.
     * This interface is similar to RowMapper but also provides column label information,
     * which makes it more efficient when processing multiple records that need column metadata.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * BiRowMapper<User> mapper = (rs, columnLabels) -> {
     *     User user = new User();
     *     for (int i = 0; i < columnLabels.size(); i++) {
     *         String label = columnLabels.get(i);
     *         if ("id".equals(label)) user.setId(rs.getInt(i + 1));
     *         else if ("name".equals(label)) user.setName(rs.getString(i + 1));
     *     }
     *     return user;
     * };
     * }</pre>
     *
     * @param <T> the type of the object that each row will be mapped to
     */
    @FunctionalInterface
    public interface BiRowMapper<T> extends Throwables.BiFunction<ResultSet, List<String>, T, SQLException> {

        /** 
         * Pre-defined mapper that converts a row to an Object array.
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
         * Pre-defined mapper that converts a row to a List of Objects.
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
         * Pre-defined mapper that converts a row to a Map with column names as keys.
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
         * Pre-defined mapper that converts a row to a LinkedHashMap with column names as keys.
         * Preserves the order of columns.
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
         * Pre-defined mapper that converts a row to an EntityId.
         */
        BiRowMapper<EntityId> TO_ENTITY_ID = new BiRowMapper<>() {
            @SuppressWarnings("deprecation")
            @Override
            public EntityId apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                final int columnCount = columnLabels.size();
                final Seid entityId = Seid.of(Strings.EMPTY);

                for (int i = 1; i <= columnCount; i++) {
                    entityId.set(columnLabels.get(i - 1), JdbcUtil.getColumnValue(rs, i));
                }

                return entityId;
            }
        };

        /**
         * Maps a row of the ResultSet to an object of type T using column label information.
         *
         * @param rs the ResultSet positioned at a valid row
         * @param columnLabels the list of column labels in the result set
         * @return the mapped object of type T
         * @throws SQLException if a database access error occurs
         */
        @Override
        T apply(ResultSet rs, List<String> columnLabels) throws SQLException;

        /**
         * Returns a composed BiRowMapper that first applies this mapper and then applies the after function.
         *
         * @param <R> the type of output of the after function
         * @param after the function to apply after this mapper is applied
         * @return a composed BiRowMapper
         * @throws IllegalArgumentException if after is null
         */
        default <R> BiRowMapper<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return (rs, columnLabels) -> after.apply(apply(rs, columnLabels));
        }

        /**
         * Converts this BiRowMapper to a RowMapper.
         * The resulting RowMapper is stateful and caches column labels on first use.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @return a stateful RowMapper. Don't save or cache for reuse or use it in parallel stream.
         * @see RowMapper#toBiRowMapper()
         * @deprecated because it's stateful and may be misused easily and frequently
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
         * Combines two BiRowMapper instances into a BiRowMapper that returns a Tuple2 of their results.
         *
         * @param <T> the type of the first BiRowMapper
         * @param <U> the type of the second BiRowMapper
         * @param rowMapper1 the first BiRowMapper
         * @param rowMapper2 the second BiRowMapper
         * @return a BiRowMapper that returns a Tuple2 of the results
         */
        static <T, U> BiRowMapper<Tuple2<T, U>> combine(final BiRowMapper<? extends T> rowMapper1, final BiRowMapper<? extends U> rowMapper2) {
            N.checkArgNotNull(rowMapper1, cs.rowMapper1);
            N.checkArgNotNull(rowMapper2, cs.rowMapper2);

            return (rs, cls) -> Tuple.of(rowMapper1.apply(rs, cls), rowMapper2.apply(rs, cls));
        }

        /**
         * Combines three BiRowMapper instances into a BiRowMapper that returns a Tuple3 of their results.
         *
         * @param <A> the type of the first BiRowMapper
         * @param <B> the type of the second BiRowMapper
         * @param <C> the type of the third BiRowMapper
         * @param rowMapper1 the first BiRowMapper
         * @param rowMapper2 the second BiRowMapper
         * @param rowMapper3 the third BiRowMapper
         * @return a BiRowMapper that returns a Tuple3 of the results
         */
        static <A, B, C> BiRowMapper<Tuple3<A, B, C>> combine(final BiRowMapper<? extends A> rowMapper1, final BiRowMapper<? extends B> rowMapper2,
                final BiRowMapper<? extends C> rowMapper3) {
            N.checkArgNotNull(rowMapper1, cs.rowMapper1);
            N.checkArgNotNull(rowMapper2, cs.rowMapper2);
            N.checkArgNotNull(rowMapper3, cs.rowMapper3);

            return (rs, cls) -> Tuple.of(rowMapper1.apply(rs, cls), rowMapper2.apply(rs, cls), rowMapper3.apply(rs, cls));
        }

        /**
         * Creates a stateful BiRowMapper that maps a row to an instance of the target class.
         * The target class must be a valid bean class with appropriate getters/setters.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * BiRowMapper<User> mapper = BiRowMapper.to(User.class);
         * }</pre>
         *
         * @param <T> the target type
         * @param targetClass the class to map rows to
         * @return a stateful BiRowMapper. Don't save or cache for reuse or use it in parallel stream.
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(final Class<? extends T> targetClass) {
            return to(targetClass, false);
        }

        /**
         * Creates a stateful BiRowMapper that maps a row to an instance of the target class.
         * Allows ignoring columns that don't match any property in the target class.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param <T> the target type
         * @param targetClass the class to map rows to
         * @param ignoreNonMatchedColumns if true, columns without matching properties are ignored
         * @return a stateful BiRowMapper. Don't save or cache for reuse or use it in parallel stream.
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(final Class<? extends T> targetClass, final boolean ignoreNonMatchedColumns) {
            return to(targetClass, Fn.alwaysTrue(), Fn.identity(), ignoreNonMatchedColumns);
        }

        /**
         * Creates a stateful BiRowMapper with column name filtering and conversion.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * BiRowMapper<User> mapper = BiRowMapper.to(
         *     User.class,
         *     colName -> !colName.startsWith("internal_"),  // Filter out internal columns
         *     colName -> colName.toLowerCase()               // Convert to lowercase
         * );
         * }</pre>
         *
         * @param <T> the target type
         * @param targetClass the class to map rows to
         * @param columnNameFilter predicate to filter column names
         * @param columnNameConverter function to convert column names
         * @return a stateful BiRowMapper. Don't save or cache for reuse or use it in parallel stream.
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(final Class<? extends T> targetClass, final Predicate<? super String> columnNameFilter,
                final Function<? super String, String> columnNameConverter) {
            return to(targetClass, columnNameFilter, columnNameConverter, false);
        }

        /**
         * Creates a stateful BiRowMapper with full customization options.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param <T> the target type
         * @param targetClass the class to map rows to
         * @param columnNameFilter predicate to filter column names
         * @param columnNameConverter function to convert column names
         * @param ignoreNonMatchedColumns if true, columns without matching properties are ignored
         * @return a stateful BiRowMapper. Don't save or cache for reuse or use it in parallel stream.
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
            } else if (ClassUtil.isBeanClass(targetClass)) {
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
                } else {
                    throw new IllegalArgumentException(
                            "'columnNameFilter' and 'columnNameConverter' are not supported to convert single column to target type: " + targetClass);
                }
            }
        }

        /**
         * Creates a stateful BiRowMapper with prefix and field name mapping.
         * This is useful for handling queries with table prefixes or custom column naming conventions.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * Map<String, String> prefixMap = new HashMap<>();
         * prefixMap.put("u_", "user.");
         * prefixMap.put("a_", "address.");
         * BiRowMapper<User> mapper = BiRowMapper.to(User.class, prefixMap);
         * }</pre>
         *
         * @param <T> the target type
         * @param entityClass the class to map rows to
         * @param prefixAndFieldNameMap map of column prefixes to field name prefixes
         * @return a stateful BiRowMapper. Don't save or cache for reuse or use it in parallel stream.
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(final Class<? extends T> entityClass, final Map<String, String> prefixAndFieldNameMap) {
            return to(entityClass, prefixAndFieldNameMap, false);
        }

        /**
         * Creates a stateful BiRowMapper with prefix and field name mapping and option to ignore non-matched columns.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param <T> the target type
         * @param entityClass the class to map rows to
         * @param prefixAndFieldNameMap map of column prefixes to field name prefixes
         * @param ignoreNonMatchedColumns if true, columns without matching properties are ignored
         * @return a stateful BiRowMapper. Don't save or cache for reuse or use it in parallel stream.
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(final Class<? extends T> entityClass, final Map<String, String> prefixAndFieldNameMap,
                final boolean ignoreNonMatchedColumns) {
            if (N.isEmpty(prefixAndFieldNameMap)) {
                return to(entityClass, ignoreNonMatchedColumns);
            }

            N.checkArgument(ClassUtil.isBeanClass(entityClass), "{} is not an entity class", entityClass);

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

        /**
         * Creates a BiRowMapper that converts rows to a Map with value filtering.
         * Only values that pass the filter predicate are included in the map.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * BiRowMapper<Map<String, Object>> mapper = BiRowMapper.toMap(
         *     value -> value != null  // Exclude null values
         * );
         * }</pre>
         *
         * @param valueFilter the predicate to test values
         * @return a BiRowMapper that produces a filtered Map
         */
        static BiRowMapper<Map<String, Object>> toMap(final Predicate<Object> valueFilter) {
            return (rs, columnLabels) -> {
                final int columnCount = columnLabels.size();
                final Map<String, Object> result = N.newHashMap(columnCount);

                Object value = null;

                for (int i = 1; i <= columnCount; i++) {
                    value = JdbcUtil.getColumnValue(rs, i);

                    if (valueFilter.test(value)) {
                        result.put(columnLabels.get(i - 1), value);
                    }
                }

                return result;
            };
        }

        /**
         * Creates a BiRowMapper that converts rows to a Map with key-value filtering.
         * Only entries where both key and value pass the filter are included.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * BiRowMapper<Map<String, Object>> mapper = BiRowMapper.toMap(
         *     (key, value) -> !key.startsWith("_") && value != null,
         *     TreeMap::new
         * );
         * }</pre>
         *
         * @param valueFilter the bi-predicate to test column names and values
         * @param mapSupplier the supplier to create the map instance
         * @return a BiRowMapper that produces a filtered Map
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
         * Creates a stateful BiRowMapper with custom row extraction and value filtering.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param rowExtractor the custom row extractor
         * @param valueFilter the bi-predicate to test column names and values
         * @param mapSupplier the supplier to create the map instance
         * @return a stateful BiRowMapper. Don't save or cache for reuse or use it in parallel stream.
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
         * Creates a stateful BiRowMapper that converts rows to a Map with column name conversion.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * BiRowMapper<Map<String, Object>> mapper = BiRowMapper.toMap(
         *     colName -> colName.toLowerCase().replace("_", "")
         * );
         * }</pre>
         *
         * @param columnNameConverter the function to transform column names
         * @return a stateful BiRowMapper. Don't save or cache for reuse or use it in parallel stream.
         */
        @SequentialOnly
        @Stateful
        static BiRowMapper<Map<String, Object>> toMap(final Function<? super String, String> columnNameConverter) {
            return toMap(columnNameConverter, IntFunctions.ofMap());
        }

        /**
         * Creates a stateful BiRowMapper that converts rows to a Map with column name conversion and custom map supplier.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param columnNameConverter the function to transform column names
         * @param mapSupplier the supplier to create the map instance
         * @return a stateful BiRowMapper. Don't save or cache for reuse or use it in parallel stream.
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
         * Creates a stateful BiRowMapper that uses a custom row extractor.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param rowExtractor the custom row extractor
         * @return a stateful BiRowMapper. Don't save or cache for reuse or use it in parallel stream.
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
         * Creates a stateful BiRowMapper with custom row extraction and column name conversion.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param rowExtractor the custom row extractor
         * @param columnNameConverter the function to transform column names
         * @param mapSupplier the supplier to create the map instance
         * @return a stateful BiRowMapper. Don't save or cache for reuse or use it in parallel stream.
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
         * Creates a BiRowMapper that maps all columns to an Object array.
         * Uses the provided ColumnGetter for all columns.
         *
         * @param columnGetterForAll the ColumnGetter to use for all columns
         * @return a BiRowMapper that produces an Object array
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
         * Creates a BiRowMapper that maps all columns to a List.
         * Uses the provided ColumnGetter for all columns.
         *
         * @param columnGetterForAll the ColumnGetter to use for all columns
         * @return a BiRowMapper that produces a List
         */
        @Beta
        static BiRowMapper<List<Object>> toList(final ColumnGetter<?> columnGetterForAll) {
            return toCollection(columnGetterForAll, IntFunctions.ofList());
        }

        /**
         * Creates a BiRowMapper that maps all columns to a Collection.
         * Uses the provided ColumnGetter for all columns and the supplier to create the collection.
         *
         * @param <C> the collection type
         * @param columnGetterForAll the ColumnGetter to use for all columns
         * @param supplier the function to create the collection with the expected size
         * @return a BiRowMapper that produces a Collection
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
         * Creates a stateful BiRowMapper that maps all columns to a DisposableObjArray.
         * This is useful for efficient row processing where the same array can be reused.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @return a stateful BiRowMapper. Don't save or cache for reuse or use it in parallel stream.
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
         * Creates a stateful BiRowMapper that maps columns to a DisposableObjArray using entity class metadata.
         * The entity class is used to determine the appropriate type conversions for each column.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param entityClass used to fetch column/row value from ResultSet by the type of fields/columns defined in this class
         * @return a stateful BiRowMapper. Don't save or cache for reuse or use it in parallel stream.
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
         * Creates a BiRowMapperBuilder with default column getter for object values.
         *
         * @return a new BiRowMapperBuilder
         */
        static BiRowMapperBuilder builder() {
            return builder(ColumnGetter.GET_OBJECT);
        }

        /**
         * Creates a BiRowMapperBuilder with the specified default column getter.
         * The builder allows for custom configuration of how each column is extracted by name.
         *
         * @param defaultColumnGetter the default ColumnGetter to use for columns not explicitly configured
         * @return a new BiRowMapperBuilder
         */
        static BiRowMapperBuilder builder(final ColumnGetter<?> defaultColumnGetter) {
            return new BiRowMapperBuilder(defaultColumnGetter);
        }

        /**
         * A builder class for creating customized BiRowMapper instances.
         * This builder allows specifying different column getters for specific column names
         * and provides various output formats.
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
             * Configures the builder to get a boolean value from the specified column.
             *
             * @param columnName the column name
             * @return this builder instance
             */
            public BiRowMapperBuilder getBoolean(final String columnName) {
                return get(columnName, ColumnGetter.GET_BOOLEAN);
            }

            /**
             * Configures the builder to get a byte value from the specified column.
             *
             * @param columnName the column name
             * @return this builder instance
             */
            public BiRowMapperBuilder getByte(final String columnName) {
                return get(columnName, ColumnGetter.GET_BYTE);
            }

            /**
             * Configures the builder to get a short value from the specified column.
             *
             * @param columnName the column name
             * @return this builder instance
             */
            public BiRowMapperBuilder getShort(final String columnName) {
                return get(columnName, ColumnGetter.GET_SHORT);
            }

            /**
             * Configures the builder to get an int value from the specified column.
             *
             * @param columnName the column name
             * @return this builder instance
             */
            public BiRowMapperBuilder getInt(final String columnName) {
                return get(columnName, ColumnGetter.GET_INT);
            }

            /**
             * Configures the builder to get a long value from the specified column.
             *
             * @param columnName the column name
             * @return this builder instance
             */
            public BiRowMapperBuilder getLong(final String columnName) {
                return get(columnName, ColumnGetter.GET_LONG);
            }

            /**
             * Configures the builder to get a float value from the specified column.
             *
             * @param columnName the column name
             * @return this builder instance
             */
            public BiRowMapperBuilder getFloat(final String columnName) {
                return get(columnName, ColumnGetter.GET_FLOAT);
            }

            /**
             * Configures the builder to get a double value from the specified column.
             *
             * @param columnName the column name
             * @return this builder instance
             */
            public BiRowMapperBuilder getDouble(final String columnName) {
                return get(columnName, ColumnGetter.GET_DOUBLE);
            }

            /**
             * Configures the builder to get a BigDecimal value from the specified column.
             *
             * @param columnName the column name
             * @return this builder instance
             */
            public BiRowMapperBuilder getBigDecimal(final String columnName) {
                return get(columnName, ColumnGetter.GET_BIG_DECIMAL);
            }

            /**
             * Configures the builder to get a String value from the specified column.
             *
             * @param columnName the column name
             * @return this builder instance
             */
            public BiRowMapperBuilder getString(final String columnName) {
                return get(columnName, ColumnGetter.GET_STRING);
            }

            /**
             * Configures the builder to get a Date value from the specified column.
             *
             * @param columnName the column name
             * @return this builder instance
             */
            public BiRowMapperBuilder getDate(final String columnName) {
                return get(columnName, ColumnGetter.GET_DATE);
            }

            /**
             * Configures the builder to get a Time value from the specified column.
             *
             * @param columnName the column name
             * @return this builder instance
             */
            public BiRowMapperBuilder getTime(final String columnName) {
                return get(columnName, ColumnGetter.GET_TIME);
            }

            /**
             * Configures the builder to get a Timestamp value from the specified column.
             *
             * @param columnName the column name
             * @return this builder instance
             */
            public BiRowMapperBuilder getTimestamp(final String columnName) {
                return get(columnName, ColumnGetter.GET_TIMESTAMP);
            }

            /**
             * Configures the builder to get an Object value from the specified column.
             *
             * @param columnName the column name
             * @return this builder instance
             * @deprecated default {@link #getObject(String)} if there is no {@code ColumnGetter} set for the target column
             */
            @Deprecated
            public BiRowMapperBuilder getObject(final String columnName) {
                return get(columnName, ColumnGetter.GET_OBJECT);
            }

            /**
             * Configures the builder to get an Object of specific type from the specified column.
             *
             * @param columnName the column name
             * @param type the class type to convert the column value to
             * @return this builder instance
             */
            public BiRowMapperBuilder getObject(final String columnName, final Class<?> type) {
                return get(columnName, ColumnGetter.get(type));
            }

            /**
             * Configures the builder to use a custom ColumnGetter for the specified column.
             *
             * @param columnName the column name
             * @param columnGetter the custom ColumnGetter to use
             * @return this builder instance
             * @throws IllegalArgumentException if columnName is null/empty or columnGetter is null
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
             * Builds a stateful BiRowMapper that maps to an instance of the target class.
             * Don't cache or reuse the returned BiRowMapper instance.
             *
             * @param <T> the target type
             * @param targetClass the class to map rows to
             * @return a stateful BiRowMapper. Don't save or cache for reuse or use it in parallel stream.
             */
            @SequentialOnly
            @Stateful
            public <T> BiRowMapper<T> to(final Class<? extends T> targetClass) {
                return to(targetClass, false);
            }

            /**
             * Builds a stateful BiRowMapper that maps to an instance of the target class with option to ignore non-matched columns.
             * Don't cache or reuse the returned BiRowMapper instance.
             *
             * @param <T> the target type
             * @param targetClass the class to map rows to
             * @param ignoreNonMatchedColumns if true, columns without matching properties are ignored
             * @return a stateful BiRowMapper. Don't save or cache for reuse or use it in parallel stream.
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
                } else if (ClassUtil.isBeanClass(targetClass)) {
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
     * A functional interface for consuming rows of a ResultSet without returning a value.
     * This interface is typically used for side effects like logging or collecting data.
     * 
     * <p>Don't use RowConsumer in PreparedQuery.forEach() or any place where multiple records 
     * will be consumed by it, if column labels/count are used in the accept() method.
     * Consider using BiRowConsumer instead because it's more efficient for consuming multiple 
     * records when column labels/count are needed.</p>
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * RowConsumer consumer = rs -> {
     *     System.out.println("ID: " + rs.getInt("id") + ", Name: " + rs.getString("name"));
     * };
     * }</pre>
     */
    @FunctionalInterface
    public interface RowConsumer extends Throwables.Consumer<ResultSet, SQLException> {

        /**
         * A no-operation consumer that does nothing.
         */
        RowConsumer DO_NOTHING = rs -> {
        };

        /**
         * Consumes a row from the ResultSet.
         *
         * @param rs the ResultSet positioned at a valid row
         * @throws SQLException if a database access error occurs
         */
        @Override
        void accept(ResultSet rs) throws SQLException;

        /**
         * Returns a composed RowConsumer that performs this operation followed by the after operation.
         *
         * @param after the operation to perform after this operation
         * @return a composed RowConsumer
         * @throws IllegalArgumentException if after is null
         */
        default RowConsumer andThen(final Throwables.Consumer<? super ResultSet, SQLException> after) {
            N.checkArgNotNull(after);

            return rs -> {
                accept(rs);
                after.accept(rs);
            };
        }

        /**
         * Converts this RowConsumer to a BiRowConsumer.
         * The resulting BiRowConsumer ignores the column labels parameter.
         *
         * @return a BiRowConsumer that delegates to this RowConsumer
         */
        default BiRowConsumer toBiRowConsumer() {
            return (rs, columnLabels) -> accept(rs);
        }

        /**
         * Creates a stateful RowConsumer that applies a consumer to all columns.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * RowConsumer consumer = RowConsumer.create((rs, colIndex) -> {
         *     System.out.println("Column " + colIndex + ": " + rs.getObject(colIndex));
         * });
         * }</pre>
         *
         * @param consumerForAll the consumer to apply to each column (ResultSet and column index)
         * @return a stateful RowConsumer. Don't save or cache for reuse or use it in parallel stream.
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
         * Creates a stateful RowConsumer that converts rows to DisposableObjArray and processes them.
         * This is useful for efficient row processing where the same array can be reused.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param consumer the consumer to process the DisposableObjArray
         * @return a stateful RowConsumer. Don't save or cache for reuse or use it in parallel stream.
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
         * Creates a stateful RowConsumer that converts rows to DisposableObjArray using entity class metadata.
         * The entity class is used to determine the appropriate type conversions for each column.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param entityClass used to fetch column/row value from ResultSet by the type of fields/columns defined in this class
         * @param consumer the consumer to process the DisposableObjArray
         * @return a stateful RowConsumer. Don't save or cache for reuse or use it in parallel stream.
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
     * A functional interface for consuming rows of a ResultSet with access to column labels.
     * This interface is more efficient than RowConsumer when processing multiple records
     * that need column information.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * BiRowConsumer consumer = (rs, columnLabels) -> {
     *     for (int i = 0; i < columnLabels.size(); i++) {
     *         System.out.println(columnLabels.get(i) + ": " + rs.getObject(i + 1));
     *     }
     * };
     * }</pre>
     */
    @FunctionalInterface
    public interface BiRowConsumer extends Throwables.BiConsumer<ResultSet, List<String>, SQLException> {

        /**
         * A no-operation consumer that does nothing.
         */
        BiRowConsumer DO_NOTHING = (rs, cls) -> {
        };

        /**
         * Consumes a row from the ResultSet with column label information.
         *
         * @param rs the ResultSet positioned at a valid row
         * @param columnLabels the list of column labels in the result set
         * @throws SQLException if a database access error occurs
         */
        @Override
        void accept(ResultSet rs, List<String> columnLabels) throws SQLException;

        /**
         * Returns a composed BiRowConsumer that performs this operation followed by the after operation.
         *
         * @param after the operation to perform after this operation
         * @return a composed BiRowConsumer
         * @throws IllegalArgumentException if after is null
         */
        default BiRowConsumer andThen(final Throwables.BiConsumer<? super ResultSet, ? super List<String>, SQLException> after) {
            N.checkArgNotNull(after);

            return (rs, cls) -> {
                accept(rs, cls);
                after.accept(rs, cls);
            };
        }

        /**
         * Creates a BiRowConsumer that applies a consumer to all columns.
         * 
         * <p>Example usage:</p>
         * <pre>{@code
         * BiRowConsumer consumer = BiRowConsumer.create((rs, colIndex) -> {
         *     System.out.println("Column " + colIndex + ": " + rs.getObject(colIndex));
         * });
         * }</pre>
         *
         * @param consumerForAll the consumer to apply to each column (ResultSet and column index)
         * @return a BiRowConsumer
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
         * Creates a stateful BiRowConsumer that converts rows to DisposableObjArray and processes them.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param consumer the bi-consumer to process column labels and DisposableObjArray
         * @return a stateful RowConsumer. Don't save or cache for reuse or use it in parallel stream.
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
         * Creates a stateful BiRowConsumer that converts rows to DisposableObjArray using entity class metadata.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param entityClass used to fetch column/row value from ResultSet by the type of fields/columns defined in this class
         * @param consumer the bi-consumer to process column labels and DisposableObjArray
         * @return a stateful RowConsumer. Don't save or cache for reuse or use it in parallel stream.
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
     * A functional interface for filtering rows of a ResultSet.
     * Generally, results should be filtered in the database side by SQL scripts.
     * Only use RowFilter/BiRowFilter if there is a specific reason or the filter can't be done by SQL scripts.
     * 
     * <p>Consider using BiRowFilter instead because it's more efficient to test multiple records 
     * when column labels/count are used.</p>
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * RowFilter filter = rs -> rs.getInt("age") >= 18 && rs.getBoolean("active");
     * }</pre>
     */
    @FunctionalInterface
    public interface RowFilter extends Throwables.Predicate<ResultSet, SQLException> {

        /** 
         * A filter that always returns true.
         */
        RowFilter ALWAYS_TRUE = rs -> true;

        /** 
         * A filter that always returns false.
         */
        RowFilter ALWAYS_FALSE = rs -> false;

        /**
         * Tests whether the current row should be included.
         *
         * @param rs the ResultSet positioned at a valid row
         * @return true if the row should be included, false otherwise
         * @throws SQLException if a database access error occurs
         */
        @Override
        boolean test(final ResultSet rs) throws SQLException;

        /**
         * Returns a filter that represents the logical negation of this filter.
         *
         * @return a negated filter
         */
        @Override
        default RowFilter negate() {
            return rs -> !test(rs);
        }

        /**
         * Returns a composed filter that represents a short-circuiting logical AND of this filter and another.
         *
         * @param other a filter that will be logically-ANDed with this filter
         * @return a composed filter
         * @throws IllegalArgumentException if other is null
         */
        default RowFilter and(final Throwables.Predicate<? super ResultSet, SQLException> other) {
            N.checkArgNotNull(other);

            return rs -> test(rs) && other.test(rs);
        }

        /**
         * Converts this RowFilter to a BiRowFilter.
         * The resulting BiRowFilter ignores the column labels parameter.
         *
         * @return a BiRowFilter that delegates to this RowFilter
         */
        default BiRowFilter toBiRowFilter() {
            return (rs, columnLabels) -> test(rs);
        }
    }

    /**
     * A functional interface for filtering rows of a ResultSet with access to column labels.
     * Generally, results should be filtered in the database side by SQL scripts.
     * Only use RowFilter/BiRowFilter if there is a specific reason or the filter can't be done by SQL scripts.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * BiRowFilter filter = (rs, columnLabels) -> {
     *     return columnLabels.contains("status") && rs.getString("status").equals("ACTIVE");
     * };
     * }</pre>
     */
    @FunctionalInterface
    public interface BiRowFilter extends Throwables.BiPredicate<ResultSet, List<String>, SQLException> {

        /** 
         * A filter that always returns true.
         */
        BiRowFilter ALWAYS_TRUE = (rs, columnLabels) -> true;

        /** 
         * A filter that always returns false.
         */
        BiRowFilter ALWAYS_FALSE = (rs, columnLabels) -> false;

        /**
         * Tests whether the current row should be included.
         *
         * @param rs the ResultSet positioned at a valid row
         * @param columnLabels the list of column labels in the result set
         * @return true if the row should be included, false otherwise
         * @throws SQLException if a database access error occurs
         */
        @Override
        boolean test(ResultSet rs, List<String> columnLabels) throws SQLException;

        /**
         * Returns a filter that represents the logical negation of this filter.
         *
         * @return a negated filter
         */
        default BiRowFilter negate() {
            return (rs, cls) -> !test(rs, cls);
        }

        /**
         * Returns a composed filter that represents a short-circuiting logical AND of this filter and another.
         *
         * @param other a filter that will be logically-ANDed with this filter
         * @return a composed filter
         * @throws IllegalArgumentException if other is null
         */
        default BiRowFilter and(final Throwables.BiPredicate<? super ResultSet, ? super List<String>, SQLException> other) {
            N.checkArgNotNull(other);

            return (rs, cls) -> test(rs, cls) && other.test(rs, cls);
        }
    }

    /**
     * A functional interface for extracting row data from a ResultSet into an array.
     * This interface provides efficient bulk extraction of row data with custom type handling.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * RowExtractor extractor = (rs, outputRow) -> {
     *     outputRow[0] = rs.getString(1);
     *     outputRow[1] = rs.getInt(2);
     *     outputRow[2] = rs.getDate(3);
     * };
     * }</pre>
     */
    @FunctionalInterface
    public interface RowExtractor extends Throwables.BiConsumer<ResultSet, Object[], SQLException> {

        /**
         * Extracts data from the current row of the ResultSet into the output array.
         *
         * @param rs the ResultSet positioned at a valid row
         * @param outputRow the array to populate with row data
         * @throws SQLException if a database access error occurs
         */
        @Override
        void accept(final ResultSet rs, final Object[] outputRow) throws SQLException;

        /**
         * Creates a stateful RowExtractor based on an entity class.
         * The extractor uses the entity class metadata to determine appropriate type conversions.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param entityClassForFetch the entity class to use for field/column mapping
         * @return a stateful RowExtractor. Don't save or cache for reuse or use it in parallel stream.
         */
        @SequentialOnly
        @Stateful
        static RowExtractor createBy(final Class<?> entityClassForFetch) {
            return createBy(entityClassForFetch, null, null);
        }

        /**
         * Creates a stateful RowExtractor with prefix and field name mapping.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param entityClassForFetch the entity class to use for field/column mapping
         * @param prefixAndFieldNameMap map of column prefixes to field name prefixes
         * @return a stateful RowExtractor. Don't save or cache for reuse or use it in parallel stream.
         */
        @SequentialOnly
        @Stateful
        static RowExtractor createBy(final Class<?> entityClassForFetch, final Map<String, String> prefixAndFieldNameMap) {
            return createBy(entityClassForFetch, null, prefixAndFieldNameMap);
        }

        /**
         * Creates a stateful RowExtractor with specific column labels.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param entityClassForFetch the entity class to use for field/column mapping
         * @param columnLabels the specific column labels to use
         * @return a stateful RowExtractor. Don't save or cache for reuse or use it in parallel stream.
         */
        @SequentialOnly
        @Stateful
        static RowExtractor createBy(final Class<?> entityClassForFetch, final List<String> columnLabels) {
            return createBy(entityClassForFetch, columnLabels, null);
        }

        /**
         * Creates a stateful RowExtractor with full customization options.
         * 
         * <p>This method is marked as stateful and should not be cached or used in parallel streams.</p>
         *
         * @param entityClassForFetch the entity class to use for field/column mapping
         * @param columnLabels the specific column labels to use (optional)
         * @param prefixAndFieldNameMap map of column prefixes to field name prefixes (optional)
         * @return a stateful RowExtractor. Don't save or cache for reuse or use it in parallel stream.
         */
        @SequentialOnly
        @Stateful
        static RowExtractor createBy(final Class<?> entityClassForFetch, final List<String> columnLabels, final Map<String, String> prefixAndFieldNameMap) {
            N.checkArgument(ClassUtil.isBeanClass(entityClassForFetch), "entityClassForFetch");

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
         * Creates a RowExtractorBuilder with the specified default column getter.
         *
         * @param defaultColumnGetter the default ColumnGetter to use for columns not explicitly configured
         * @return a new RowExtractorBuilder
         */
        static RowExtractorBuilder create(final ColumnGetter<?> defaultColumnGetter) {
            return new RowExtractorBuilder(defaultColumnGetter);
        }

        /**
         * Creates a RowExtractorBuilder with default column getter for object values.
         *
         * @return a new RowExtractorBuilder
         */
        static RowExtractorBuilder builder() {
            return builder(ColumnGetter.GET_OBJECT);
        }

        /**
         * Creates a RowExtractorBuilder with the specified default column getter.
         *
         * @param defaultColumnGetter the default ColumnGetter to use for columns not explicitly configured
         * @return a new RowExtractorBuilder
         */
        static RowExtractorBuilder builder(final ColumnGetter<?> defaultColumnGetter) {
            return new RowExtractorBuilder(defaultColumnGetter);
        }

        /**
         * A builder class for creating customized RowExtractor instances.
         * This builder allows specifying different column getters for specific columns.
         */
        class RowExtractorBuilder {
            private final Map<Integer, ColumnGetter<?>> columnGetterMap;

            RowExtractorBuilder(final ColumnGetter<?> defaultColumnGetter) {
                N.checkArgNotNull(defaultColumnGetter, cs.defaultColumnGetter);

                columnGetterMap = new HashMap<>(9);
                columnGetterMap.put(0, defaultColumnGetter);
            }

            /**
             * Configures the builder to get a boolean value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowExtractorBuilder getBoolean(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BOOLEAN);
            }

            /**
             * Configures the builder to get a byte value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowExtractorBuilder getByte(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BYTE);
            }

            /**
             * Configures the builder to get a short value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowExtractorBuilder getShort(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_SHORT);
            }

            /**
             * Configures the builder to get an int value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowExtractorBuilder getInt(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_INT);
            }

            /**
             * Configures the builder to get a long value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowExtractorBuilder getLong(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_LONG);
            }

            /**
             * Configures the builder to get a float value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowExtractorBuilder getFloat(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_FLOAT);
            }

            /**
             * Configures the builder to get a double value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowExtractorBuilder getDouble(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_DOUBLE);
            }

            /**
             * Configures the builder to get a BigDecimal value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowExtractorBuilder getBigDecimal(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BIG_DECIMAL);
            }

            /**
             * Configures the builder to get a String value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowExtractorBuilder getString(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_STRING);
            }

            /**
             * Configures the builder to get a Date value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowExtractorBuilder getDate(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_DATE);
            }

            /**
             * Configures the builder to get a Time value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowExtractorBuilder getTime(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_TIME);
            }

            /**
             * Configures the builder to get a Timestamp value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             */
            public RowExtractorBuilder getTimestamp(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_TIMESTAMP);
            }

            /**
             * Configures the builder to get an Object value from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @return this builder instance
             * @deprecated default {@link #getObject(int)} if there is no {@code ColumnGetter} set for the target column
             */
            @Deprecated
            public RowExtractorBuilder getObject(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_OBJECT);
            }

            /**
             * Configures the builder to get an Object of specific type from the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @param type the class type to convert the column value to
             * @return this builder instance
             */
            public RowExtractorBuilder getObject(final int columnIndex, final Class<?> type) {
                return get(columnIndex, ColumnGetter.get(type));
            }

            /**
             * Configures the builder to use a custom ColumnGetter for the specified column.
             *
             * @param columnIndex the column index (1-based)
             * @param columnGetter the custom ColumnGetter to use
             * @return this builder instance
             * @throws IllegalArgumentException if columnIndex is not positive or columnGetter is null
             */
            public RowExtractorBuilder get(final int columnIndex, final ColumnGetter<?> columnGetter) throws IllegalArgumentException {
                N.checkArgPositive(columnIndex, cs.columnIndex);
                N.checkArgNotNull(columnGetter, cs.columnGetter);

                columnGetterMap.put(columnIndex, columnGetter);
                return this;
            }

            /**
             * Builds a stateful RowExtractor based on the configured column getters.
             * Don't cache or reuse the returned RowExtractor instance.
             *
             * @return a stateful RowExtractor. Don't save or cache for reuse or use it in parallel stream.
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
     * A functional interface for extracting column values from a ResultSet.
     * This interface provides type-safe extraction of values from specific columns.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * ColumnGetter<String> upperCaseGetter = (rs, columnIndex) -> 
     *     rs.getString(columnIndex).toUpperCase();
     * }</pre>
     *
     * @param <V> the type of value extracted from the column
     */
    @FunctionalInterface
    public interface ColumnGetter<V> {

        /**
         * Pre-defined getter for boolean values.
         */
        ColumnGetter<Boolean> GET_BOOLEAN = ResultSet::getBoolean;

        /**
         * Pre-defined getter for byte values.
         */
        ColumnGetter<Byte> GET_BYTE = ResultSet::getByte;

        /**
         * Pre-defined getter for short values.
         */
        ColumnGetter<Short> GET_SHORT = ResultSet::getShort;

        /**
         * Pre-defined getter for int values.
         */
        ColumnGetter<Integer> GET_INT = ResultSet::getInt;

        /**
         * Pre-defined getter for long values.
         */
        ColumnGetter<Long> GET_LONG = ResultSet::getLong;

        /**
         * Pre-defined getter for float values.
         */
        ColumnGetter<Float> GET_FLOAT = ResultSet::getFloat;

        /**
         * Pre-defined getter for double values.
         */
        ColumnGetter<Double> GET_DOUBLE = ResultSet::getDouble;

        /**
         * Pre-defined getter for BigDecimal values.
         */
        ColumnGetter<BigDecimal> GET_BIG_DECIMAL = ResultSet::getBigDecimal;

        /**
         * Pre-defined getter for String values.
         */
        ColumnGetter<String> GET_STRING = ResultSet::getString;

        /**
         * Pre-defined getter for Date values.
         */
        ColumnGetter<Date> GET_DATE = ResultSet::getDate;

        /**
         * Pre-defined getter for Time values.
         */
        ColumnGetter<Time> GET_TIME = ResultSet::getTime;

        /**
         * Pre-defined getter for Timestamp values.
         */
        ColumnGetter<Timestamp> GET_TIMESTAMP = ResultSet::getTimestamp;

        /**
         * Pre-defined getter for byte array values.
         */
        ColumnGetter<byte[]> GET_BYTES = ResultSet::getBytes;

        /**
         * Pre-defined getter for InputStream values.
         */
        ColumnGetter<InputStream> GET_BINARY_STREAM = ResultSet::getBinaryStream;

        /**
         * Pre-defined getter for Reader values.
         */
        ColumnGetter<Reader> GET_CHARACTER_STREAM = ResultSet::getCharacterStream;

        /**
         * Pre-defined getter for Blob values.
         */
        ColumnGetter<Blob> GET_BLOB = ResultSet::getBlob;

        /**
         * Pre-defined getter for Clob values.
         */
        ColumnGetter<Clob> GET_CLOB = ResultSet::getClob;

        /**
         * Pre-defined getter for Object values using JdbcUtil.getColumnValue.
         */
        @SuppressWarnings("rawtypes")
        ColumnGetter GET_OBJECT = JdbcUtil::getColumnValue;

        /**
         * Extracts a value from the specified column of the ResultSet.
         *
         * @param rs the ResultSet to extract from
         * @param columnIndex the column index (1-based)
         * @return the extracted value
         * @throws SQLException if a database access error occurs
         */
        V apply(ResultSet rs, int columnIndex) throws SQLException;

        /**
         * Gets a ColumnGetter for the specified class type.
         * Uses the Type system to determine appropriate extraction method.
         *
         * @param <T> the target type
         * @param cls the class to get a ColumnGetter for
         * @return a ColumnGetter for the specified type
         */
        static <T> ColumnGetter<T> get(final Class<? extends T> cls) {
            return get(N.typeOf(cls));
        }

        /**
         * Gets a ColumnGetter for the specified Type.
         * Caches ColumnGetters for efficient reuse.
         *
         * @param <T> the target type
         * @param type the Type to get a ColumnGetter for
         * @return a ColumnGetter for the specified type
         */
        static <T> ColumnGetter<T> get(final Type<? extends T> type) {
            final ColumnGetter<?> columnGetter = COLUMN_GETTER_POOL.computeIfAbsent(type, k -> type::get);

            return (ColumnGetter<T>) columnGetter;
        }
    }

    /**
     * A utility class containing column-specific helper classes and methods.
     * This class provides convenient access to single-column operations.
     */
    public static final class Columns {
        private Columns() {
            // singleton for utility class
        }

        /**
         * A utility class for operations on the first column of a ResultSet.
         * Provides pre-defined getters and setters for common data types.
         */
        public static final class ColumnOne {
            /**
             * Pre-defined RowMapper for getting boolean values from the first column.
             */
            public static final RowMapper<Boolean> GET_BOOLEAN = rs -> rs.getBoolean(1);

            /**
             * Pre-defined RowMapper for getting byte values from the first column.
             */
            public static final RowMapper<Byte> GET_BYTE = rs -> rs.getByte(1);

            /**
             * Pre-defined RowMapper for getting short values from the first column.
             */
            public static final RowMapper<Short> GET_SHORT = rs -> rs.getShort(1);

            /**
             * Pre-defined RowMapper for getting int values from the first column.
             */
            public static final RowMapper<Integer> GET_INT = rs -> rs.getInt(1);

            /**
             * Pre-defined RowMapper for getting long values from the first column.
             */
            public static final RowMapper<Long> GET_LONG = rs -> rs.getLong(1);

            /**
             * Pre-defined RowMapper for getting float values from the first column.
             */
            public static final RowMapper<Float> GET_FLOAT = rs -> rs.getFloat(1);

            /**
             * Pre-defined RowMapper for getting double values from the first column.
             */
            public static final RowMapper<Double> GET_DOUBLE = rs -> rs.getDouble(1);

            /**
             * Pre-defined RowMapper for getting BigDecimal values from the first column.
             */
            public static final RowMapper<BigDecimal> GET_BIG_DECIMAL = rs -> rs.getBigDecimal(1);

            /**
             * Pre-defined RowMapper for getting String values from the first column.
             */
            public static final RowMapper<String> GET_STRING = rs -> rs.getString(1);

            /**
             * Pre-defined RowMapper for getting Date values from the first column.
             */
            public static final RowMapper<Date> GET_DATE = rs -> rs.getDate(1);

            /**
             * Pre-defined RowMapper for getting Time values from the first column.
             */
            public static final RowMapper<Time> GET_TIME = rs -> rs.getTime(1);

            /**
             * Pre-defined RowMapper for getting Timestamp values from the first column.
             */
            public static final RowMapper<Timestamp> GET_TIMESTAMP = rs -> rs.getTimestamp(1);

            /**
             * Pre-defined RowMapper for getting byte array values from the first column.
             */
            public static final RowMapper<byte[]> GET_BYTES = rs -> rs.getBytes(1);

            /**
             * Pre-defined RowMapper for getting InputStream values from the first column.
             */
            public static final RowMapper<InputStream> GET_BINARY_STREAM = rs -> rs.getBinaryStream(1);

            /**
             * Pre-defined RowMapper for getting Reader values from the first column.
             */
            public static final RowMapper<Reader> GET_CHARACTER_STREAM = rs -> rs.getCharacterStream(1);

            /**
             * Pre-defined RowMapper for getting Blob values from the first column.
             */
            public static final RowMapper<Blob> GET_BLOB = rs -> rs.getBlob(1);

            /**
             * Pre-defined RowMapper for getting Clob values from the first column.
             */
            public static final RowMapper<Clob> GET_CLOB = rs -> rs.getClob(1);

            /**
             * Pre-defined RowMapper for getting Object values from the first column.
             */
            public static final RowMapper<Object> GET_OBJECT = rs -> JdbcUtil.getColumnValue(rs, 1);

            /**
             * Pre-defined BiParametersSetter for setting boolean values in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Boolean> SET_BOOLEAN = (preparedQuery, x) -> preparedQuery.setBoolean(1, x);

            /**
             * Pre-defined BiParametersSetter for setting byte values in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Byte> SET_BYTE = (preparedQuery, x) -> preparedQuery.setByte(1, x);

            /**
             * Pre-defined BiParametersSetter for setting short values in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Short> SET_SHORT = (preparedQuery, x) -> preparedQuery.setShort(1, x);

            /**
             * Pre-defined BiParametersSetter for setting int values in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Integer> SET_INT = (preparedQuery, x) -> preparedQuery.setInt(1, x);

            /**
             * Pre-defined BiParametersSetter for setting long values in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Long> SET_LONG = (preparedQuery, x) -> preparedQuery.setLong(1, x);

            /**
             * Pre-defined BiParametersSetter for setting float values in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Float> SET_FLOAT = (preparedQuery, x) -> preparedQuery.setFloat(1, x);

            /**
             * Pre-defined BiParametersSetter for setting double values in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Double> SET_DOUBLE = (preparedQuery, x) -> preparedQuery.setDouble(1, x);

            /**
             * Pre-defined BiParametersSetter for setting BigDecimal values in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, BigDecimal> SET_BIG_DECIMAL = (preparedQuery, x) -> preparedQuery.setBigDecimal(1, x);

            /**
             * Pre-defined BiParametersSetter for setting String values in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, String> SET_STRING = (preparedQuery, x) -> preparedQuery.setString(1, x);

            /**
             * Pre-defined BiParametersSetter for setting Date values in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Date> SET_DATE = (preparedQuery, x) -> preparedQuery.setDate(1, x);

            /**
             * Pre-defined BiParametersSetter for setting Time values in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Time> SET_TIME = (preparedQuery, x) -> preparedQuery.setTime(1, x);

            /**
             * Pre-defined BiParametersSetter for setting Timestamp values in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Timestamp> SET_TIMESTAMP = (preparedQuery, x) -> preparedQuery.setTimestamp(1, x);

            /**
             * Pre-defined BiParametersSetter for setting java.util.Date as SQL Date in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, java.util.Date> SET_DATE_JU = (preparedQuery, x) -> preparedQuery.setDate(1, x);

            /**
             * Pre-defined BiParametersSetter for setting java.util.Date as SQL Time in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, java.util.Date> SET_TIME_JU = (preparedQuery, x) -> preparedQuery.setTime(1, x);

            /**
             * Pre-defined BiParametersSetter for setting java.util.Date as SQL Timestamp in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, java.util.Date> SET_TIMESTAMP_JU = (preparedQuery, x) -> preparedQuery.setTimestamp(1, x);

            /**
             * Pre-defined BiParametersSetter for setting byte array values in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, byte[]> SET_BYTES = (preparedQuery, x) -> preparedQuery.setBytes(1, x);

            /**
             * Pre-defined BiParametersSetter for setting InputStream values in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, InputStream> SET_BINARY_STREAM = (preparedQuery, x) -> preparedQuery.setBinaryStream(1, x);

            /**
             * Pre-defined BiParametersSetter for setting Reader values in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Reader> SET_CHARACTER_STREAM = (preparedQuery, x) -> preparedQuery.setCharacterStream(1, x);

            /**
             * Pre-defined BiParametersSetter for setting Blob values in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Blob> SET_BLOB = (preparedQuery, x) -> preparedQuery.setBlob(1, x);

            /**
             * Pre-defined BiParametersSetter for setting Clob values in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Clob> SET_CLOB = (preparedQuery, x) -> preparedQuery.setClob(1, x);

            /**
             * Pre-defined BiParametersSetter for setting Object values in the first parameter.
             */
            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Object> SET_OBJECT = (preparedQuery, x) -> preparedQuery.setObject(1, x);

            private ColumnOne() {
                // singleton for utility class
            }

            @SuppressWarnings("rawtypes")
            static final Map<Type<?>, RowMapper> rowMapperPool = new ObjectPool<>(1024);

            /**
             * Gets a RowMapper for Object values from the first column.
             *
             * @param <T> the target type
             * @return a RowMapper that extracts Object values from the first column
             */
            public static <T> RowMapper<T> getObject() {
                return (RowMapper<T>) GET_OBJECT;
            }

            /**
             * Gets a RowMapper for values from the first column with the specified type.
             * 
             * <p>Example usage:</p>
             * <pre>{@code
             * RowMapper<String> stringMapper = ColumnOne.get(String.class);
             * RowMapper<Integer> intMapper = ColumnOne.get(Integer.class);
             * }</pre>
             *
             * @param <T> the target type
             * @param firstColumnType the class of the first column type
             * @return a RowMapper for the specified type
             */
            public static <T> RowMapper<T> get(final Class<? extends T> firstColumnType) {
                return get(N.typeOf(firstColumnType));
            }

            /**
             * Gets a RowMapper for values from the first column with the specified Type.
             *
             * @param <T> the target type
             * @param type the Type of the first column
             * @return a RowMapper for the specified type
             */
            public static <T> RowMapper<T> get(final Type<? extends T> type) {
                RowMapper<T> result = rowMapperPool.get(type);

                if (result == null) {
                    result = rs -> type.get(rs, 1);

                    rowMapperPool.put(type, result);
                }

                return result;
            }

            /**
             * Creates a RowMapper that converts JSON string from the first column to the target type.
             * 
             * <p>Example usage:</p>
             * <pre>{@code
             * RowMapper<User> mapper = ColumnOne.readJson(User.class);
             * }</pre>
             *
             * @param <T> the target type
             * @param targetType the class to deserialize JSON to
             * @return a RowMapper that deserializes JSON from the first column
             */
            public static <T> RowMapper<T> readJson(final Class<? extends T> targetType) {
                return rs -> N.fromJson(rs.getString(1), targetType);
            }

            /**
             * Creates a RowMapper that converts XML string from the first column to the target type.
             * 
             * <p>Example usage:</p>
             * <pre>{@code
             * RowMapper<User> mapper = ColumnOne.readXml(User.class);
             * }</pre>
             *
             * @param <T> the target type
             * @param targetType the class to deserialize XML to
             * @return a RowMapper that deserializes XML from the first column
             */
            public static <T> RowMapper<T> readXml(final Class<? extends T> targetType) {
                return rs -> N.fromXml(rs.getString(1), targetType);
            }

            /**
             * Creates a BiParametersSetter for setting values of the specified type in the first parameter.
             *
             * @param <T> the parameter type
             * @param type the class of the parameter type
             * @return a BiParametersSetter for the specified type
             */
            @SuppressWarnings("rawtypes")
            public static <T> BiParametersSetter<AbstractQuery, T> set(final Class<T> type) {
                return set(N.typeOf(type));
            }

            /**
             * Creates a BiParametersSetter for setting values of the specified Type in the first parameter.
             *
             * @param <T> the parameter type
             * @param type the Type of the parameter
             * @return a BiParametersSetter for the specified type
             */
            @SuppressWarnings("rawtypes")
            public static <T> BiParametersSetter<AbstractQuery, T> set(final Type<T> type) {
                return (preparedQuery, x) -> type.set(preparedQuery.stmt, 1, x);
            }

        }
    }

    /**
     * Represents an output parameter for stored procedures.
     * Contains information about parameter position, name, SQL type, and scale.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * OutParam param = new OutParam(1, "result", Types.INTEGER, null, 0);
     * }</pre>
     */
    @NoArgsConstructor
    @AllArgsConstructor
    @Data
    public static final class OutParam {
        /**
         * The parameter index (1-based).
         */
        private int parameterIndex;

        /**
         * The parameter name (optional, can be null for positional parameters).
         */
        private String parameterName;

        /**
         * The SQL type as defined in java.sql.Types.
         */
        private int sqlType;

        /**
         * The fully-qualified SQL type name (for SQL3 types).
         */
        private String typeName;

        /**
         * The desired number of digits to the right of the decimal point (for NUMERIC or DECIMAL types).
         */
        private int scale;

        public static OutParam of(int parameterIndex, int sqlType) {
            final OutParam outParam = new OutParam();
            outParam.setParameterIndex(parameterIndex);
            outParam.setSqlType(sqlType);
            return outParam;
        }
    }

    /**
     * Represents the result of output parameters from a stored procedure call.
     * Provides access to output parameter values by index or name.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * OutParamResult result = ...;
     * Integer count = result.getOutParamValue(1);
     * String message = result.getOutParamValue("message");
     * }</pre>
     */
    @EqualsAndHashCode
    @ToString
    public static final class OutParamResult {
        private final List<OutParam> outParams;
        private final Map<Object, Object> outParamValues;

        OutParamResult(final List<OutParam> outParams, final Map<Object, Object> outParamValues) {
            this.outParams = outParams;
            this.outParamValues = outParamValues;
        }

        /**
         * Gets the value of an output parameter by its index.
         *
         * @param <T> the expected type of the parameter value
         * @param parameterIndex the parameter index (1-based)
         * @return the parameter value, or null if not set
         */
        public <T> T getOutParamValue(final int parameterIndex) {
            return (T) outParamValues.get(parameterIndex);
        }

        /**
         * Gets the value of an output parameter by its name.
         *
         * @param <T> the expected type of the parameter value
         * @param parameterName the parameter name
         * @return the parameter value, or null if not set
         */
        public <T> T getOutParamValue(final String parameterName) {
            return (T) outParamValues.get(parameterName);
        }

        /**
         * Gets all output parameter values as a map.
         * Keys can be either parameter indices (Integer) or parameter names (String).
         *
         * @return an unmodifiable map of parameter values
         */
        public Map<Object, Object> getOutParamValues() {
            return outParamValues;
        }

        /**
         * Gets the list of output parameter definitions.
         *
         * @return an unmodifiable list of OutParam objects
         */
        public List<OutParam> getOutParams() {
            return outParams;
        }
    }

    /**
     * A handler interface for intercepting method calls on DAO proxies.
     * This interface allows for pre- and post-processing of DAO method invocations.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * Handler<UserDao> handler = new Handler<UserDao>() {
     *     @Override
     *     public void beforeInvoke(UserDao proxy, Object[] args, 
     *             Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
     *         System.out.println("Before: " + methodSignature._1.getName());
     *     }
     *     
     *     @Override
     *     public void afterInvoke(Object result, UserDao proxy, Object[] args,
     *             Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
     *         System.out.println("After: " + methodSignature._1.getName() + " = " + result);
     *     }
     * };
     * }</pre>
     *
     * @param <P> the type of the proxy object
     */
    @Beta
    public interface Handler<P> {
        /**
         * Called before a method is invoked on the proxy.
         * This method can be used for logging, validation, or modifying arguments.
         *
         * @param proxy the proxy instance
         * @param args the method arguments
         * @param methodSignature a tuple containing: Method, parameter types (empty list if no parameters), and return type
         */
        @SuppressWarnings("unused")
        default void beforeInvoke(final P proxy, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            // empty action.
        }

        /**
         * Called after a method is invoked on the proxy.
         * This method can be used for logging, result processing, or cleanup.
         *
         * @param result the result returned by the method
         * @param proxy the proxy instance
         * @param args the method arguments
         * @param methodSignature a tuple containing: Method, parameter types (empty list if no parameters), and return type
         */
        @SuppressWarnings("unused")
        default void afterInvoke(final Object result, final P proxy, final Object[] args,
                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            // empty action.
        }
    }

    /**
     * A factory class for creating and managing Handler instances.
     * Provides registration, retrieval, and creation of handlers with Spring integration support.
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
         * Registers a handler class by creating a new instance.
         *
         * @param handlerClass the handler class to register
         * @return true if registration was successful, false if a handler with the same name already exists
         * @throws IllegalArgumentException if handlerClass is null
         */
        public static boolean register(final Class<? extends Handler<?>> handlerClass) throws IllegalArgumentException {
            N.checkArgNotNull(handlerClass, cs.handlerClass);

            return register(N.newInstance(handlerClass));
        }

        /**
         * Registers a handler instance using its class name as the qualifier.
         *
         * @param handler the handler to register
         * @return true if registration was successful, false if a handler with the same name already exists
         * @throws IllegalArgumentException if handler is null
         */
        public static boolean register(final Handler<?> handler) throws IllegalArgumentException {
            N.checkArgNotNull(handler, cs.handler);

            return register(ClassUtil.getCanonicalClassName(handler.getClass()), handler);
        }

        /**
         * Registers a handler instance with a specific qualifier.
         *
         * @param qualifier the unique identifier for the handler
         * @param handler the handler to register
         * @return true if registration was successful, false if a handler with the same qualifier already exists
         * @throws IllegalArgumentException if qualifier is empty or handler is null
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
         * Gets a handler by its qualifier.
         * Also checks Spring context if available and handler is not found in the pool.
         *
         * @param qualifier the unique identifier for the handler
         * @return the handler instance, or null if not found
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
         * Gets a handler by its class.
         * Also checks Spring context if available and handler is not found in the pool.
         *
         * @param handlerClass the handler class
         * @return the handler instance, or null if not found
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
         * Gets a handler by its class, creating a new instance if not found.
         *
         * @param handlerClass the handler class
         * @return the handler instance, never null
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
         * Creates a handler with a before-invoke action.
         *
         * @param <T> the proxy type
         * @param <E> the exception type
         * @param beforeInvokeAction the action to perform before method invocation
         * @return a new Handler instance
         * @throws IllegalArgumentException if beforeInvokeAction is null
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
         * Creates a handler with an after-invoke action.
         *
         * @param <T> the proxy type
         * @param <E> the exception type
         * @param afterInvokeAction the action to perform after method invocation
         * @return a new Handler instance
         * @throws IllegalArgumentException if afterInvokeAction is null
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
         * Creates a handler with both before and after invoke actions.
         *
         * @param <T> the proxy type
         * @param <E> the exception type
         * @param beforeInvokeAction the action to perform before method invocation
         * @param afterInvokeAction the action to perform after method invocation
         * @return a new Handler instance
         * @throws IllegalArgumentException if either action is null
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
     * Interface for caching DAO method results.
     * Implementations can provide custom caching strategies for database queries.
     * 
     * <p>The default cache key format is: fullMethodName#tableName#jsonArrayOfParameters</p>
     * <p>Example: com.example.UserDao.findById#users#[{"id": 123}]</p>
     */
    public interface DaoCache {

        /**
         * Creates a DaoCache with specified capacity and eviction delay.
         *
         * @param capacity the maximum number of entries in the cache
         * @param evictDelay the delay in milliseconds before entries are evicted
         * @return a new DaoCache instance
         */
        static DaoCache create(final int capacity, final long evictDelay) {
            return new DefaultDaoCache(capacity, evictDelay);
        }

        /**
         * Creates a DaoCache backed by a simple Map.
         * No automatic eviction is performed.
         *
         * @return a new DaoCache instance backed by a HashMap
         */
        static DaoCache createByMap() {
            return new DaoCacheByMap();
        }

        /**
         * Creates a DaoCache backed by the provided Map.
         * No automatic eviction is performed.
         *
         * @param map the map to use for caching
         * @return a new DaoCache instance backed by the provided map
         */
        static DaoCache createByMap(Map<String, Object> map) {
            return new DaoCacheByMap(map);
        }

        /**
         * Retrieves a cached result.
         * Implementations can customize the cache key based on the provided parameters.
         * 
         * <p>MUST NOT modify the input parameters.</p>
         *
         * @param defaultCacheKey the default cache key composed by: fullMethodName#tableName#jsonArrayOfParameters
         * @param daoProxy the DAO proxy instance
         * @param args the method arguments
         * @param methodSignature tuple containing Method, parameter types, and return type
         * @return the cached result, or null if not found
         */
        Object get(String defaultCacheKey, Object daoProxy, Object[] args, Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature);

        /**
         * Caches a result with default TTL settings.
         * Implementations can customize the cache key based on the provided parameters.
         * 
         * <p>MUST NOT modify the input parameters.</p>
         *
         * @param defaultCacheKey the default cache key composed by: fullMethodName#tableName#jsonArrayOfParameters
         * @param result the method result to cache
         * @param daoProxy the DAO proxy instance
         * @param args the method arguments
         * @param methodSignature tuple containing Method, parameter types, and return type
         * @return true if the result was cached successfully
         */
        boolean put(String defaultCacheKey, Object result, Object daoProxy, Object[] args, Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature);

        /**
         * Caches a result with custom TTL settings.
         * Implementations can customize the cache key based on the provided parameters.
         * 
         * <p>MUST NOT modify the input parameters.</p>
         *
         * @param defaultCacheKey the default cache key composed by: fullMethodName#tableName#jsonArrayOfParameters
         * @param result the method result to cache
         * @param liveTime the time in milliseconds that the entry should live
         * @param maxIdleTime the maximum idle time in milliseconds before eviction
         * @param daoProxy the DAO proxy instance
         * @param args the method arguments
         * @param methodSignature tuple containing Method, parameter types, and return type
         * @return true if the result was cached successfully
         */
        boolean put(String defaultCacheKey, Object result, final long liveTime, final long maxIdleTime, Object daoProxy, Object[] args,
                Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature);

        /**
         * Updates the cache after a modification operation.
         * Typically removes or refreshes entries that may have been affected by the operation.
         * 
         * <p>MUST NOT modify the input parameters.</p>
         *
         * @param defaultCacheKey the default cache key composed by: fullMethodName#tableName#jsonArrayOfParameters
         * @param result the result of the modification operation
         * @param daoProxy the DAO proxy instance
         * @param args the method arguments
         * @param methodSignature tuple containing Method, parameter types, and return type
         */
        void update(String defaultCacheKey, Object result, Object daoProxy, Object[] args, Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature);

    }

    /**
     * Default implementation of DaoCache using a LocalCache with TTL support.
     * Provides automatic eviction based on time-to-live and idle time settings.
     */
    public static final class DefaultDaoCache implements DaoCache {
        protected final LocalCache<String, Object> cache;

        /**
         * Creates a DefaultDaoCache with specified capacity and eviction delay.
         *
         * @param capacity the maximum number of entries in the cache
         * @param evictDelay the delay in milliseconds for the eviction scheduler
         */
        public DefaultDaoCache(final int capacity, final long evictDelay) {
            cache = CacheFactory.createLocalCache(capacity, evictDelay);
        }

        @Override
        @SuppressWarnings("unused")
        public Object get(final String defaultCacheKey, final Object daoProxy, final Object[] args,
                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            return cache.gett(defaultCacheKey);
        }

        @Override
        @SuppressWarnings("unused")
        public boolean put(final String defaultCacheKey, final Object result, final Object daoProxy, final Object[] args,
                final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            return cache.put(defaultCacheKey, result, JdbcUtil.DEFAULT_CACHE_LIVE_TIME, JdbcUtil.DEFAULT_CACHE_MAX_IDLE_TIME);
        }

        @Override
        public boolean put(String defaultCacheKey, Object result, long liveTime, long maxIdleTime, Object daoProxy, Object[] args,
                Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            return cache.put(defaultCacheKey, result, liveTime, maxIdleTime);
        }

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
                cache.keySet().stream().filter(k -> Strings.containsIgnoreCase(k, updatedTableName)).toList().forEach(cache::remove);
            }
        }
    }

    /**
     * Simple implementation of DaoCache using a plain Map.
     * Does not provide automatic eviction or TTL support.
     */
    static final class DaoCacheByMap implements DaoCache {
        protected final Map<String, Object> cache;

        /**
         * Creates a DaoCacheByMap with a new HashMap.
         */
        public DaoCacheByMap() {
            cache = new HashMap<>();
        }

        /**
         * Creates a DaoCacheByMap with a HashMap of specified initial capacity.
         *
         * @param capacity the initial capacity of the HashMap
         */
        public DaoCacheByMap(final int capacity) {
            this.cache = new HashMap<>(capacity);
        }

        /**
         * Creates a DaoCacheByMap backed by the provided map.
         *
         * @param cache the map to use for caching
         */
        public DaoCacheByMap(final Map<String, Object> cache) {
            this.cache = cache;
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