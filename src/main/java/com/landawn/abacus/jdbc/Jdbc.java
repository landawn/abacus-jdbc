/*
 * Copyright (c) 2022, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
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
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.Array;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.DataSet;
import com.landawn.abacus.util.EntityId;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.Fn.Factory;
import com.landawn.abacus.util.Fn.IntFunctions;
import com.landawn.abacus.util.Fn.Suppliers;
import com.landawn.abacus.util.ImmutableList;
import com.landawn.abacus.util.ListMultimap;
import com.landawn.abacus.util.Multimap;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NoCachingNoUpdating.DisposableObjArray;
import com.landawn.abacus.util.ObjectPool;
import com.landawn.abacus.util.ParsedSql;
import com.landawn.abacus.util.Seid;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Throwables;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

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
     * The Interface ParametersSetter.
     *
     * @param <QS>
     */
    @FunctionalInterface
    public interface ParametersSetter<QS> extends Throwables.Consumer<QS, SQLException> {
        @SuppressWarnings("rawtypes")
        ParametersSetter DO_NOTHING = preparedQuery -> {
            // Do nothing.
        };

        /**
         *
         *
         * @param preparedQuery
         * @throws SQLException
         */
        @Override
        void accept(QS preparedQuery) throws SQLException;
    }

    /**
     * The Interface BiParametersSetter.
     *
     * @param <QS>
     * @param <T>
     * @see Columns.ColumnOne
     * @see Columns.ColumnTwo
     * @see Columns.ColumnThree
     */
    @FunctionalInterface
    public interface BiParametersSetter<QS, T> extends Throwables.BiConsumer<QS, T, SQLException> {
        @SuppressWarnings("rawtypes")
        BiParametersSetter DO_NOTHING = (preparedQuery, param) -> {
            // Do nothing.
        };

        /**
         *
         *
         * @param preparedQuery
         * @param param
         * @throws SQLException
         */
        @Override
        void accept(QS preparedQuery, T param) throws SQLException;

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param <T>
         * @param fieldNameList
         * @param entityClass
         * @return
         */
        @Beta
        static <T> BiParametersSetter<PreparedStatement, T[]> createForArray(final List<String> fieldNameList, final Class<?> entityClass) {
            N.checkArgNotEmpty(fieldNameList, "'fieldNameList' can't be null or empty");
            N.checkArgument(ClassUtil.isBeanClass(entityClass), "{} is not a valid entity class with getter/setter methods", entityClass);

            return new BiParametersSetter<>() {
                private final int len = fieldNameList.size();
                @SuppressWarnings("rawtypes")
                private Type[] fieldTypes = null;

                @Override
                public void accept(PreparedStatement stmt, T[] params) throws SQLException {
                    if (fieldTypes == null) {
                        final BeanInfo entityInfo = ParserUtil.getBeanInfo(entityClass);
                        @SuppressWarnings("rawtypes")
                        final Type[] localFieldTypes = new Type[len];

                        for (int i = 0; i < len; i++) {
                            localFieldTypes[i] = entityInfo.getPropInfo(fieldNameList.get(i)).dbType;
                        }

                        this.fieldTypes = localFieldTypes;
                    }

                    for (int i = 0; i < len; i++) {
                        fieldTypes[i].set(stmt, i + 1, params[i]);
                    }
                }
            };
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param <T>
         * @param fieldNameList
         * @param entityClass
         * @return
         */
        @Beta
        static <T> BiParametersSetter<PreparedStatement, List<T>> createForList(final List<String> fieldNameList, final Class<?> entityClass) {
            N.checkArgNotEmpty(fieldNameList, "'fieldNameList' can't be null or empty");
            N.checkArgument(ClassUtil.isBeanClass(entityClass), "{} is not a valid entity class with getter/setter methods", entityClass);

            return new BiParametersSetter<>() {
                private final int len = fieldNameList.size();
                @SuppressWarnings("rawtypes")
                private Type[] fieldTypes = null;

                @Override
                public void accept(PreparedStatement stmt, List<T> params) throws SQLException {
                    if (fieldTypes == null) {
                        final BeanInfo entityInfo = ParserUtil.getBeanInfo(entityClass);
                        @SuppressWarnings("rawtypes")
                        final Type[] localFieldTypes = new Type[len];

                        for (int i = 0; i < len; i++) {
                            localFieldTypes[i] = entityInfo.getPropInfo(fieldNameList.get(i)).dbType;
                        }

                        this.fieldTypes = localFieldTypes;
                    }

                    for (int i = 0; i < len; i++) {
                        fieldTypes[i].set(stmt, i + 1, params.get(i));
                    }
                }
            };
        }
    }

    /**
     * The Interface TriParametersSetter.
     *
     * @param <QS>
     * @param <T>
     */
    @FunctionalInterface
    public interface TriParametersSetter<QS, T> extends Throwables.TriConsumer<ParsedSql, QS, T, SQLException> {
        @SuppressWarnings("rawtypes")
        TriParametersSetter DO_NOTHING = new TriParametersSetter<>() {
            @Override
            public void accept(ParsedSql parsedSql, Object preparedQuery, Object param) throws SQLException {
                // Do nothing.
            }
        };

        /**
         *
         *
         * @param parsedSql
         * @param preparedQuery
         * @param param
         * @throws SQLException
         */
        @Override
        void accept(ParsedSql parsedSql, QS preparedQuery, T param) throws SQLException;
    }

    /**
     * The Interface ResultExtractor.
     *
     * @param <T>
     */
    @FunctionalInterface
    public interface ResultExtractor<T> extends Throwables.Function<ResultSet, T, SQLException> {

        ResultExtractor<DataSet> TO_DATA_SET = rs -> {
            if (rs == null) {
                return N.newEmptyDataSet();
            }

            return JdbcUtil.extractData(rs);
        };

        /**
         * In a lot of scenarios, including PreparedQuery/Dao/SQLExecutor, the input {@code ResultSet} will be closed after {@code apply(rs)} call. So don't save/return the input {@code ResultSet}.
         *
         * @param rs
         * @return
         * @throws SQLException
         */
        @Override
        T apply(ResultSet rs) throws SQLException;

        /**
         *
         *
         * @param <R>
         * @param after
         * @return
         */
        default <R> ResultExtractor<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return rs -> after.apply(apply(rs));
        }

        /**
         *
         *
         * @return
         */
        default BiResultExtractor<T> toBiResultExtractor() {
            return (rs, columnLabels) -> this.apply(rs);
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> ResultExtractor<Map<K, V>> toMap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor) {
            return toMap(keyExtractor, valueExtractor, Suppliers.<K, V> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param supplier
         * @return
         */
        static <K, V, M extends Map<K, V>> ResultExtractor<M> toMap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final Supplier<? extends M> supplier) {
            return toMap(keyExtractor, valueExtractor, Fn.<V> throwingMerger(), supplier);
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @param mergeFunction
         * @return
         * @see {@link Fn.throwingMerger()}
         * @see {@link Fn.replacingMerger()}
         * @see {@link Fn.ignoringMerger()}
         */
        static <K, V> ResultExtractor<Map<K, V>> toMap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final BinaryOperator<V> mergeFunction) {
            return toMap(keyExtractor, valueExtractor, mergeFunction, Suppliers.<K, V> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param mergeFunction
         * @param supplier
         * @return
         * @see {@link Fn.throwingMerger()}
         * @see {@link Fn.replacingMerger()}
         * @see {@link Fn.ignoringMerger()}
         */
        static <K, V, M extends Map<K, V>> ResultExtractor<M> toMap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final BinaryOperator<V> mergeFunction, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(mergeFunction, "mergeFunction");
            N.checkArgNotNull(supplier, "supplier");

            return rs -> {
                final M result = supplier.get();

                while (rs.next()) {
                    Jdbc.merge(result, keyExtractor.apply(rs), valueExtractor.apply(rs), mergeFunction);
                }

                return result;
            };
        }

        /**
         *
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <D>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @return
         * @see #groupTo(RowMapper, RowMapper, Collector)
         * @deprecated replaced by {@code groupTo(RowMapper, RowMapper, Collector)}
         */
        @Deprecated
        static <K, V, D> ResultExtractor<Map<K, D>> toMap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final Collector<? super V, ?, D> downstream) {
            return toMap(keyExtractor, valueExtractor, downstream, Suppliers.<K, D> ofMap());
        }

        /**
         *
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <D>
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @param supplier
         * @return
         * @see #groupTo(RowMapper, RowMapper, Collector, Supplier)
         * @deprecated replaced by {@code groupTo(RowMapper, RowMapper, Collector, Supplier)}
         */
        @Deprecated
        static <K, V, D, M extends Map<K, D>> ResultExtractor<M> toMap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final Collector<? super V, ?, D> downstream, final Supplier<? extends M> supplier) {
            return groupTo(keyExtractor, valueExtractor, downstream, supplier);
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> ResultExtractor<ListMultimap<K, V>> toMultimap(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor) {
            return toMultimap(keyExtractor, valueExtractor, Suppliers.<K, V> ofListMultimap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <C>
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param multimapSupplier
         * @return
         */
        static <K, V, C extends Collection<V>, M extends Multimap<K, V, C>> ResultExtractor<M> toMultimap(final RowMapper<? extends K> keyExtractor,
                final RowMapper<? extends V> valueExtractor, final Supplier<? extends M> multimapSupplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(multimapSupplier, "multimapSupplier");

            return rs -> {
                final M result = multimapSupplier.get();

                while (rs.next()) {
                    result.put(keyExtractor.apply(rs), valueExtractor.apply(rs));
                }

                return result;
            };
        }

        /**
         *
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> ResultExtractor<Map<K, List<V>>> groupTo(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor) {
            return groupTo(keyExtractor, valueExtractor, Suppliers.<K, List<V>> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param supplier
         * @return
         */
        static <K, V, M extends Map<K, List<V>>> ResultExtractor<M> groupTo(final RowMapper<? extends K> keyExtractor,
                final RowMapper<? extends V> valueExtractor, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(supplier, "supplier");

            return rs -> {
                final M result = supplier.get();
                K key = null;
                List<V> value = null;

                while (rs.next()) {
                    key = keyExtractor.apply(rs);
                    value = result.get(key);

                    if (value == null) {
                        value = new ArrayList<>();
                        result.put(key, value);
                    }

                    value.add(valueExtractor.apply(rs));
                }

                return result;
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <D>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @return
         */
        static <K, V, D> ResultExtractor<Map<K, D>> groupTo(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final Collector<? super V, ?, D> downstream) {
            return groupTo(keyExtractor, valueExtractor, downstream, Suppliers.<K, D> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <D>
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @param supplier
         * @return
         */
        static <K, V, D, M extends Map<K, D>> ResultExtractor<M> groupTo(final RowMapper<? extends K> keyExtractor, final RowMapper<? extends V> valueExtractor,
                final Collector<? super V, ?, D> downstream, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(downstream, "downstream");
            N.checkArgNotNull(supplier, "supplier");

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

                for (Map.Entry<K, D> entry : result.entrySet()) {
                    entry.setValue(downstreamFinisher.apply(entry.getValue()));
                }

                return result;
            };
        }

        /**
         *
         *
         * @param <T>
         * @param rowMapper
         * @return
         */
        static <T> ResultExtractor<List<T>> toList(final RowMapper<? extends T> rowMapper) {
            return toList(RowFilter.ALWAYS_TRUE, rowMapper);
        }

        /**
         *
         *
         * @param <T>
         * @param rowFilter
         * @param rowMapper
         * @return
         */
        static <T> ResultExtractor<List<T>> toList(final RowFilter rowFilter, RowMapper<? extends T> rowMapper) {
            N.checkArgNotNull(rowFilter, "rowFilter");
            N.checkArgNotNull(rowMapper, "rowMapper");

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
         * @param <T>
         * @param targetClass
         * @return
         */
        static <T> ResultExtractor<List<T>> toList(final Class<? extends T> targetClass) {
            N.checkArgNotNull(targetClass, "targetClass");

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
         * @param <T>
         * @param targetClass
         * @return
         * @see DataSet#toMergedEntities(Class)
         */
        static <T> ResultExtractor<List<T>> toMergedList(final Class<? extends T> targetClass) {
            N.checkArgNotNull(targetClass, "targetClass");

            return rs -> {
                final RowExtractor rowExtractor = RowExtractor.createBy(targetClass);

                return JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, rowExtractor, false).toMergedEntities(targetClass);
            };
        }

        /**
         * @param <T>
         * @param targetClass
         * @param idPropNameForMerge
         * @return
         * @see DataSet#toMergedEntities(Collection, Collection, Class)
         */
        static <T> ResultExtractor<List<T>> toMergedList(final Class<? extends T> targetClass, final String idPropNameForMerge) {
            N.checkArgNotNull(targetClass, "targetClass");

            return rs -> {
                final RowExtractor rowExtractor = RowExtractor.createBy(targetClass);

                return JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, rowExtractor, false).toMergedEntities(idPropNameForMerge, targetClass);
            };
        }

        /**
         * @param <T>
         * @param targetClass
         * @param idPropNamesForMerge
         * @return
         * @see DataSet#toMergedEntities(Collection, Collection, Class)
         */
        static <T> ResultExtractor<List<T>> toMergedList(final Class<? extends T> targetClass, Collection<String> idPropNamesForMerge) {
            N.checkArgNotNull(targetClass, "targetClass");

            return rs -> {
                final RowExtractor rowExtractor = RowExtractor.createBy(targetClass);

                return JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, rowExtractor, false).toMergedEntities(idPropNamesForMerge, targetClass);
            };
        }

        /**
         * @param entityClass
         * @return
         */
        static ResultExtractor<DataSet> toDataSet(final Class<?> entityClass) {
            return rs -> JdbcUtil.extractData(rs, RowExtractor.createBy(entityClass));
        }

        /**
         *
         *
         * @param entityClass
         * @param prefixAndFieldNameMap
         * @return
         */
        static ResultExtractor<DataSet> toDataSet(final Class<?> entityClass, final Map<String, String> prefixAndFieldNameMap) {
            return rs -> JdbcUtil.extractData(rs, RowExtractor.createBy(entityClass, prefixAndFieldNameMap));
        }

        /**
         *
         *
         * @param rowFilter
         * @return
         */
        static ResultExtractor<DataSet> toDataSet(final RowFilter rowFilter) {
            return rs -> JdbcUtil.extractData(rs, rowFilter);
        }

        /**
         *
         *
         * @param rowExtractor
         * @return
         */
        static ResultExtractor<DataSet> toDataSet(final RowExtractor rowExtractor) {
            return rs -> JdbcUtil.extractData(rs, rowExtractor);
        }

        /**
         *
         *
         * @param rowFilter
         * @param rowExtractor
         * @return
         */
        static ResultExtractor<DataSet> toDataSet(final RowFilter rowFilter, final RowExtractor rowExtractor) {
            return rs -> JdbcUtil.extractData(rs, 0, Integer.MAX_VALUE, rowFilter, rowExtractor, false);
        }

        /**
         *
         *
         * @param <R>
         * @param after
         * @return
         */
        static <R> ResultExtractor<R> to(final Throwables.Function<DataSet, R, SQLException> after) {
            return rs -> after.apply(TO_DATA_SET.apply(rs));
        }
    }

    /**
     * The Interface BiResultExtractor.
     *
     * @param <T>
     */
    @FunctionalInterface
    public interface BiResultExtractor<T> extends Throwables.BiFunction<ResultSet, List<String>, T, SQLException> {

        BiResultExtractor<DataSet> TO_DATA_SET = (rs, columnLabels) -> {
            if (rs == null) {
                return N.newEmptyDataSet();
            }

            return JdbcUtil.extractData(rs);
        };

        /**
         * In a lot of scenarios, including PreparedQuery/Dao/SQLExecutor, the input {@code ResultSet} will be closed after {@code apply(rs)} call. So don't save/return the input {@code ResultSet}.
         *
         * @param rs
         * @param columnLabels
         * @return
         * @throws SQLException
         */
        @Override
        T apply(ResultSet rs, List<String> columnLabels) throws SQLException;

        /**
         *
         *
         * @param <R>
         * @param after
         * @return
         */
        default <R> BiResultExtractor<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return (rs, columnLabels) -> after.apply(apply(rs, columnLabels));
        }

        //    /**
        //     *
        //     *
        //     * @param <R>
        //     * @param resultExtractor
        //     * @return
        //     */
        //    static <R> BiResultExtractor<R> from(final ResultExtractor<? extends R> resultExtractor) {
        //        N.checkArgNotNull(resultExtractor);
        //
        //        return (rs, columnLabels) -> resultExtractor.apply(rs);
        //    }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> BiResultExtractor<Map<K, V>> toMap(final BiRowMapper<? extends K> keyExtractor, final BiRowMapper<? extends V> valueExtractor) {
            return toMap(keyExtractor, valueExtractor, Suppliers.<K, V> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param supplier
         * @return
         */
        static <K, V, M extends Map<K, V>> BiResultExtractor<M> toMap(final BiRowMapper<? extends K> keyExtractor,
                final BiRowMapper<? extends V> valueExtractor, final Supplier<? extends M> supplier) {
            return toMap(keyExtractor, valueExtractor, Fn.<V> throwingMerger(), supplier);
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @param mergeFunction
         * @return
         * @see {@link Fn.throwingMerger()}
         * @see {@link Fn.replacingMerger()}
         * @see {@link Fn.ignoringMerger()}
         */
        static <K, V> BiResultExtractor<Map<K, V>> toMap(final BiRowMapper<? extends K> keyExtractor, final BiRowMapper<? extends V> valueExtractor,
                final BinaryOperator<V> mergeFunction) {
            return toMap(keyExtractor, valueExtractor, mergeFunction, Suppliers.<K, V> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param mergeFunction
         * @param supplier
         * @return
         * @see {@link Fn.throwingMerger()}
         * @see {@link Fn.replacingMerger()}
         * @see {@link Fn.ignoringMerger()}
         */
        static <K, V, M extends Map<K, V>> BiResultExtractor<M> toMap(final BiRowMapper<? extends K> keyExtractor,
                final BiRowMapper<? extends V> valueExtractor, final BinaryOperator<V> mergeFunction, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(mergeFunction, "mergeFunction");
            N.checkArgNotNull(supplier, "supplier");

            return (rs, columnLabels) -> {
                final M result = supplier.get();

                while (rs.next()) {
                    Jdbc.merge(result, keyExtractor.apply(rs, columnLabels), valueExtractor.apply(rs, columnLabels), mergeFunction);
                }

                return result;
            };
        }

        /**
         *
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <D>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @return
         * @see #groupTo(BiRowMapper, BiRowMapper, Collector)
         * @deprecated replaced by {@code groupTo(BiRowMapper, BiRowMapper, Collector)}
         */
        @Deprecated
        static <K, V, D> BiResultExtractor<Map<K, D>> toMap(final BiRowMapper<? extends K> keyExtractor, final BiRowMapper<? extends V> valueExtractor,
                final Collector<? super V, ?, D> downstream) {
            return toMap(keyExtractor, valueExtractor, downstream, Suppliers.<K, D> ofMap());
        }

        /**
         *
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <D>
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @param supplier
         * @return
         * @see #groupTo(BiRowMapper, BiRowMapper, Collector, Supplier)
         * @deprecated replaced by {@code groupTo(BiRowMapper, BiRowMapper, Collector, Supplier)}
         */
        @Deprecated
        static <K, V, D, M extends Map<K, D>> BiResultExtractor<M> toMap(final BiRowMapper<? extends K> keyExtractor,
                final BiRowMapper<? extends V> valueExtractor, final Collector<? super V, ?, D> downstream, final Supplier<? extends M> supplier) {
            return groupTo(keyExtractor, valueExtractor, downstream, supplier);
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> BiResultExtractor<ListMultimap<K, V>> toMultimap(final BiRowMapper<? extends K> keyExtractor,
                final BiRowMapper<? extends V> valueExtractor) {
            return toMultimap(keyExtractor, valueExtractor, Suppliers.<K, V> ofListMultimap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <C>
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param multimapSupplier
         * @return
         */
        static <K, V, C extends Collection<V>, M extends Multimap<K, V, C>> BiResultExtractor<M> toMultimap(final BiRowMapper<? extends K> keyExtractor,
                final BiRowMapper<? extends V> valueExtractor, final Supplier<? extends M> multimapSupplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(multimapSupplier, "multimapSupplier");

            return (rs, columnLabels) -> {
                final M result = multimapSupplier.get();

                while (rs.next()) {
                    result.put(keyExtractor.apply(rs, columnLabels), valueExtractor.apply(rs, columnLabels));
                }

                return result;
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param keyExtractor
         * @param valueExtractor
         * @return
         */
        static <K, V> BiResultExtractor<Map<K, List<V>>> groupTo(final BiRowMapper<? extends K> keyExtractor, final BiRowMapper<? extends V> valueExtractor) {
            return groupTo(keyExtractor, valueExtractor, Suppliers.<K, List<V>> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param supplier
         * @return
         */
        static <K, V, M extends Map<K, List<V>>> BiResultExtractor<M> groupTo(final BiRowMapper<? extends K> keyExtractor,
                final BiRowMapper<? extends V> valueExtractor, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(supplier, "supplier");

            return (rs, columnLabels) -> {
                final M result = supplier.get();
                K key = null;
                List<V> value = null;

                while (rs.next()) {
                    key = keyExtractor.apply(rs, columnLabels);
                    value = result.get(key);

                    if (value == null) {
                        value = new ArrayList<>();
                        result.put(key, value);
                    }

                    value.add(valueExtractor.apply(rs, columnLabels));
                }

                return result;
            };
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <D>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @return
         */
        static <K, V, D> BiResultExtractor<Map<K, D>> groupTo(final BiRowMapper<? extends K> keyExtractor, final BiRowMapper<? extends V> valueExtractor,
                final Collector<? super V, ?, D> downstream) {
            return groupTo(keyExtractor, valueExtractor, downstream, Suppliers.<K, D> ofMap());
        }

        /**
         *
         * @param <K> the key type
         * @param <V> the value type
         * @param <D>
         * @param <M>
         * @param keyExtractor
         * @param valueExtractor
         * @param downstream
         * @param supplier
         * @return
         */
        static <K, V, D, M extends Map<K, D>> BiResultExtractor<M> groupTo(final BiRowMapper<? extends K> keyExtractor,
                final BiRowMapper<? extends V> valueExtractor, final Collector<? super V, ?, D> downstream, final Supplier<? extends M> supplier) {
            N.checkArgNotNull(keyExtractor, "keyExtractor");
            N.checkArgNotNull(valueExtractor, "valueExtractor");
            N.checkArgNotNull(downstream, "downstream");
            N.checkArgNotNull(supplier, "supplier");

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

                for (Map.Entry<K, D> entry : result.entrySet()) {
                    entry.setValue(downstreamFinisher.apply(entry.getValue()));
                }

                return result;
            };
        }

        /**
         *
         *
         * @param <T>
         * @param rowMapper
         * @return
         */
        static <T> BiResultExtractor<List<T>> toList(final BiRowMapper<? extends T> rowMapper) {
            return toList(BiRowFilter.ALWAYS_TRUE, rowMapper);
        }

        /**
         *
         *
         * @param <T>
         * @param rowFilter
         * @param rowMapper
         * @return
         */
        static <T> BiResultExtractor<List<T>> toList(final BiRowFilter rowFilter, final BiRowMapper<? extends T> rowMapper) {
            N.checkArgNotNull(rowFilter, "rowFilter");
            N.checkArgNotNull(rowMapper, "rowMapper");

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
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param <T>
         * @param targetClass
         * @return
         */
        @SequentialOnly
        static <T> BiResultExtractor<List<T>> toList(final Class<? extends T> targetClass) {
            N.checkArgNotNull(targetClass, "targetClass");

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
     * Don't use {@code RowMapper} in {@link PreparedQuery#list(RowMapper)} or any place where multiple records will be retrieved by it, if column labels/count are used in {@link RowMapper#apply(ResultSet)}.
     * Consider using {@code BiRowMapper} instead because it's more efficient to retrieve multiple records when column labels/count are used.
     *
     * @param <T>
     * @see Columns.ColumnOne
     * @see Columns.ColumnTwo
     * @see Columns.ColumnThree
     */
    @FunctionalInterface
    public interface RowMapper<T> extends Throwables.Function<ResultSet, T, SQLException> {

        /**
         *
         *
         * @param rs
         * @return
         * @throws SQLException
         */
        @Override
        T apply(ResultSet rs) throws SQLException;

        /**
         *
         *
         * @param <R>
         * @param after
         * @return
         */
        default <R> RowMapper<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return rs -> after.apply(apply(rs));
        }

        /**
         *
         *
         * @return
         */
        default BiRowMapper<T> toBiRowMapper() {
            return (rs, columnLabels) -> this.apply(rs);
        }

        //    /**
        //     * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
        //     *
        //     * @param <T>
        //     * @param biRowMapper
        //     * @return
        //     * @deprecated because it's stateful and may be misused easily and frequently
        //     */
        //    @Beta
        //    @Deprecated
        //    @SequentialOnly
        //    @Stateful
        //    static <T> RowMapper<T> from(final BiRowMapper<? extends T> biRowMapper) {
        //        N.checkArgNotNull(biRowMapper, "biRowMapper");
        //
        //        return new RowMapper<>() {
        //            private List<String> cls = null;
        //
        //            @Override
        //            public T apply(ResultSet rs) throws SQLException {
        //                if (cls == null) {
        //                    cls = JdbcUtil.getColumnLabelList(rs);
        //                }
        //
        //                return biRowMapper.apply(rs, cls);
        //            }
        //        };
        //    }

        /**
         *
         *
         * @param <T>
         * @param <U>
         * @param rowMapper1
         * @param rowMapper2
         * @return
         */
        static <T, U> RowMapper<Tuple2<T, U>> combine(final RowMapper<? extends T> rowMapper1, final RowMapper<? extends U> rowMapper2) {
            N.checkArgNotNull(rowMapper1, "rowMapper1");
            N.checkArgNotNull(rowMapper2, "rowMapper2");

            return rs -> Tuple.of(rowMapper1.apply(rs), rowMapper2.apply(rs));
        }

        /**
         *
         *
         * @param <A>
         * @param <B>
         * @param <C>
         * @param rowMapper1
         * @param rowMapper2
         * @param rowMapper3
         * @return
         */
        static <A, B, C> RowMapper<Tuple3<A, B, C>> combine(final RowMapper<? extends A> rowMapper1, final RowMapper<? extends B> rowMapper2,
                final RowMapper<? extends C> rowMapper3) {
            N.checkArgNotNull(rowMapper1, "rowMapper1");
            N.checkArgNotNull(rowMapper2, "rowMapper2");
            N.checkArgNotNull(rowMapper3, "rowMapper3");

            return rs -> Tuple.of(rowMapper1.apply(rs), rowMapper2.apply(rs), rowMapper3.apply(rs));
        }

        /**
         *
         *
         * @param columnGetterForAll
         * @return
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
         *
         *
         * @param columnGetterForAll
         * @return
         */
        @Beta
        @SequentialOnly
        @Stateful
        static RowMapper<List<Object>> toList(final ColumnGetter<?> columnGetterForAll) {
            return toCollection(columnGetterForAll, Factory.<Object> ofList());
        }

        /**
         *
         *
         * @param <C>
         * @param columnGetterForAll
         * @param supplier
         * @return
         */
        @Beta
        @SequentialOnly
        @Stateful
        static <C extends Collection<?>> RowMapper<C> toCollection(final ColumnGetter<?> columnGetterForAll, final IntFunction<C> supplier) {
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
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @return
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
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param entityClass used to fetch column/row value from {@code ResultSet} by the type of fields/columns defined in this class.
         * @return
         */
        @Beta
        @SequentialOnly
        @Stateful
        static RowMapper<DisposableObjArray> toDisposableObjArray(final Class<?> entityClass) {
            N.checkArgNotNull(entityClass, "entityClass");

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
         *
         *
         * @return
         */
        static RowMapperBuilder builder() {
            return builder(ColumnGetter.GET_OBJECT);
        }

        /**
         *
         *
         * @param defaultColumnGetter
         * @return
         */
        static RowMapperBuilder builder(final ColumnGetter<?> defaultColumnGetter) {
            return new RowMapperBuilder(defaultColumnGetter);
        }

        //    static RowMapperBuilder builder(final int columnCount) {
        //        return new RowMapperBuilder(columnCount);
        //    }

        @SequentialOnly
        public static class RowMapperBuilder {
            private final Map<Integer, ColumnGetter<?>> columnGetterMap;

            RowMapperBuilder(final ColumnGetter<?> defaultColumnGetter) {
                N.checkArgNotNull(defaultColumnGetter, "defaultColumnGetter");

                columnGetterMap = new HashMap<>(9);
                columnGetterMap.put(0, defaultColumnGetter);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowMapperBuilder getBoolean(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BOOLEAN);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowMapperBuilder getByte(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BYTE);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowMapperBuilder getShort(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_SHORT);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowMapperBuilder getInt(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_INT);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowMapperBuilder getLong(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_LONG);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowMapperBuilder getFloat(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_FLOAT);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowMapperBuilder getDouble(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_DOUBLE);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowMapperBuilder getBigDecimal(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BIG_DECIMAL);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowMapperBuilder getString(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_STRING);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowMapperBuilder getDate(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_DATE);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowMapperBuilder getTime(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_TIME);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowMapperBuilder getTimestamp(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_TIMESTAMP);
            }

            /**
             *
             * @param columnIndex
             * @return
             * @deprecated default {@link #getObject(int)} if there is no {@code ColumnGetter} set for the target column
             */
            @Deprecated
            public RowMapperBuilder getObject(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_OBJECT);
            }

            /**
             *
             *
             * @param columnIndex
             * @param type
             * @return
             */
            public RowMapperBuilder getObject(final int columnIndex, Class<?> type) {
                return get(columnIndex, ColumnGetter.get(type));
            }

            /**
             * 
             *
             * @param columnIndex 
             * @param columnGetter 
             * @return 
             * @throws IllegalArgumentException 
             */
            public RowMapperBuilder get(final int columnIndex, final ColumnGetter<?> columnGetter) throws IllegalArgumentException {
                N.checkArgPositive(columnIndex, "columnIndex");
                N.checkArgNotNull(columnGetter, "columnGetter");

                //        if (columnGetters == null) {
                //            columnGetterMap.put(columnIndex, columnGetter);
                //        } else {
                //            columnGetters[columnIndex] = columnGetter;
                //        }

                columnGetterMap.put(columnIndex, columnGetter);
                return this;
            }

            /**
             *
             * Set column getter function for column[columnIndex].
             *
             * @param columnIndex start from 1.
             * @param columnGetter
             * @return
             * @deprecated replaced by {@link #get(int, ColumnGetter)}
             */
            @Deprecated
            public RowMapperBuilder column(final int columnIndex, final ColumnGetter<?> columnGetter) {
                return get(columnIndex, columnGetter);
            }

            //    /**
            //     * Set default column getter function.
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder __(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(0, columnGetter);
            //        } else {
            //            columnGetters[0] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[columnIndex].
            //     *
            //     * @param columnIndex start from 1.
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder __(final int columnIndex, final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(columnIndex, columnGetter);
            //        } else {
            //            columnGetters[columnIndex] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     * Set column getter function for column[1].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _1(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(1, columnGetter);
            //        } else {
            //            columnGetters[1] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[1].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _2(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(2, columnGetter);
            //        } else {
            //            columnGetters[2] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[3].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _3(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(3, columnGetter);
            //        } else {
            //            columnGetters[3] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[4].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _4(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(4, columnGetter);
            //        } else {
            //            columnGetters[4] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[5].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _5(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(5, columnGetter);
            //        } else {
            //            columnGetters[5] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[6].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _6(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(6, columnGetter);
            //        } else {
            //            columnGetters[6] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[7].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _7(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(7, columnGetter);
            //        } else {
            //            columnGetters[7] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[8].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _8(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(8, columnGetter);
            //        } else {
            //            columnGetters[8] = columnGetter;
            //        }
            //
            //        return this;
            //    }
            //
            //    /**
            //     *
            //     * Set column getter function for column[9].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public RowMapperBuilder _9(final ColumnGetter<?> columnGetter) {
            //        if (columnGetters == null) {
            //            columnGetterMap.put(9, columnGetter);
            //        } else {
            //            columnGetters[9] = columnGetter;
            //        }
            //
            //        return this;
            //    }

            //    void setDefaultColumnGetter() {
            //        if (columnGetters != null) {
            //            for (int i = 1, len = columnGetters.length; i < len; i++) {
            //                if (columnGetters[i] == null) {
            //                    columnGetters[i] = columnGetters[0];
            //                }
            //            }
            //        }
            //    }

            ColumnGetter<?>[] initColumnGetter(ResultSet rs) throws SQLException { //NOSONAR
                return initColumnGetter(rs.getMetaData().getColumnCount());
            }

            ColumnGetter<?>[] initColumnGetter(final int columnCount) { //NOSONAR
                final ColumnGetter<?>[] rsColumnGetters = new ColumnGetter<?>[columnCount];
                final ColumnGetter<?> defaultColumnGetter = columnGetterMap.get(0);

                for (int i = 0, len = rsColumnGetters.length; i < len; i++) {
                    rsColumnGetters[i] = columnGetterMap.getOrDefault(i + 1, defaultColumnGetter);
                }

                return rsColumnGetters;
            }

            /**
             * Don't cache or reuse the returned {@code RowMapper} instance.
             *
             * @return
             */
            @SequentialOnly
            @Stateful
            public RowMapper<Object[]> toArray() {
                // setDefaultColumnGetter();

                return new RowMapper<>() {
                    private ColumnGetter<?>[] rsColumnGetters = null;
                    private int rsColumnCount = -1;

                    @Override
                    public Object[] apply(ResultSet rs) throws SQLException {
                        if (rsColumnGetters == null) {
                            rsColumnGetters = initColumnGetter(rs);
                            rsColumnCount = rsColumnGetters.length - 1;
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
             * Don't cache or reuse the returned {@code RowMapper} instance.
             *
             * @return
             */
            @SequentialOnly
            @Stateful
            public RowMapper<List<Object>> toList() {
                // setDefaultColumnGetter();

                return new RowMapper<>() {
                    private ColumnGetter<?>[] rsColumnGetters = null;
                    private int rsColumnCount = -1;

                    @Override
                    public List<Object> apply(ResultSet rs) throws SQLException {
                        if (rsColumnGetters == null) {
                            rsColumnGetters = initColumnGetter(rs);
                            rsColumnCount = rsColumnGetters.length - 1;
                        }

                        final List<Object> row = new ArrayList<>(rsColumnCount);

                        for (int i = 0; i < rsColumnCount; i++) {
                            row.add(rsColumnGetters[i].apply(rs, i + 1));
                        }

                        return row;
                    }
                };
            }

            /**
             * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
             *
             * @param <R>
             * @param finisher
             * @return
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
                    public R apply(ResultSet rs) throws SQLException {
                        if (rsColumnGetters == null) {
                            rsColumnGetters = initColumnGetter(rs);
                            this.rsColumnCount = rsColumnGetters.length - 1;
                            this.outputRow = new Object[rsColumnCount];
                            this.output = DisposableObjArray.wrap(outputRow);
                        }

                        for (int i = 0; i < rsColumnCount; i++) {
                            outputRow[i] = rsColumnGetters[i].apply(rs, i + 1);
                        }

                        return finisher.apply(output);
                    }
                };
            }
        }
    }

    /**
     * The Interface BiRowMapper.
     *
     * @param <T>
     */
    @FunctionalInterface
    public interface BiRowMapper<T> extends Throwables.BiFunction<ResultSet, List<String>, T, SQLException> {

        /** The Constant TO_ARRAY. */
        BiRowMapper<Object[]> TO_ARRAY = (rs, columnLabels) -> {
            final int columnCount = columnLabels.size();
            final Object[] result = new Object[columnCount];

            for (int i = 1; i <= columnCount; i++) {
                result[i - 1] = JdbcUtil.getColumnValue(rs, i);
            }

            return result;
        };

        /** The Constant TO_LIST. */
        BiRowMapper<List<Object>> TO_LIST = (rs, columnLabels) -> {
            final int columnCount = columnLabels.size();
            final List<Object> result = new ArrayList<>(columnCount);

            for (int i = 1; i <= columnCount; i++) {
                result.add(JdbcUtil.getColumnValue(rs, i));
            }

            return result;
        };

        /** The Constant TO_MAP. */
        BiRowMapper<Map<String, Object>> TO_MAP = (rs, columnLabels) -> {
            final int columnCount = columnLabels.size();
            final Map<String, Object> result = N.newHashMap(columnCount);

            for (int i = 1; i <= columnCount; i++) {
                result.put(columnLabels.get(i - 1), JdbcUtil.getColumnValue(rs, i));
            }

            return result;
        };

        /** The Constant TO_LINKED_HASH_MAP. */
        BiRowMapper<Map<String, Object>> TO_LINKED_HASH_MAP = (rs, columnLabels) -> {
            final int columnCount = columnLabels.size();
            final Map<String, Object> result = N.newLinkedHashMap(columnCount);

            for (int i = 1; i <= columnCount; i++) {
                result.put(columnLabels.get(i - 1), JdbcUtil.getColumnValue(rs, i));
            }

            return result;
        };

        BiRowMapper<EntityId> TO_ENTITY_ID = new BiRowMapper<>() {
            @SuppressWarnings("deprecation")
            @Override
            public EntityId apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                final int columnCount = columnLabels.size();
                final Seid entityId = Seid.of(Strings.EMPTY_STRING);

                for (int i = 1; i <= columnCount; i++) {
                    entityId.set(columnLabels.get(i - 1), JdbcUtil.getColumnValue(rs, i));
                }

                return entityId;
            }
        };

        /**
         *
         *
         * @param rs
         * @param columnLabels
         * @return
         * @throws SQLException
         */
        @Override
        T apply(ResultSet rs, List<String> columnLabels) throws SQLException;

        /**
         *
         *
         * @param <R>
         * @param after
         * @return
         */
        default <R> BiRowMapper<R> andThen(final Throwables.Function<? super T, ? extends R, SQLException> after) {
            N.checkArgNotNull(after);

            return (rs, columnLabels) -> after.apply(apply(rs, columnLabels));
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @return
         * @see RowMapper#toBiRowMapper()
         * @deprecated because it's stateful and may be misused easily and frequently
         */
        @Beta
        @Deprecated
        @Stateful
        default RowMapper<T> toRowMapper() {
            final BiRowMapper<T> biRowMapper = this;

            return new RowMapper<>() {
                private List<String> cls = null;

                @Override
                public T apply(ResultSet rs) throws IllegalArgumentException, SQLException {
                    if (cls == null) {
                        cls = JdbcUtil.getColumnLabelList(rs);
                    }

                    return biRowMapper.apply(rs, cls);
                }
            };
        }

        //    /**
        //     *
        //     *
        //     * @param <T>
        //     * @param rowMapper
        //     * @return
        //     */
        //    static <T> BiRowMapper<T> from(final RowMapper<? extends T> rowMapper) {
        //        N.checkArgNotNull(rowMapper, "rowMapper");
        //
        //        return (rs, columnLabels) -> rowMapper.apply(rs);
        //    }

        /**
         *
         *
         * @param <T>
         * @param <U>
         * @param rowMapper1
         * @param rowMapper2
         * @return
         */
        static <T, U> BiRowMapper<Tuple2<T, U>> combine(final BiRowMapper<? extends T> rowMapper1, final BiRowMapper<? extends U> rowMapper2) {
            N.checkArgNotNull(rowMapper1, "rowMapper1");
            N.checkArgNotNull(rowMapper2, "rowMapper2");

            return (rs, cls) -> Tuple.of(rowMapper1.apply(rs, cls), rowMapper2.apply(rs, cls));
        }

        /**
         *
         *
         * @param <A>
         * @param <B>
         * @param <C>
         * @param rowMapper1
         * @param rowMapper2
         * @param rowMapper3
         * @return
         */
        static <A, B, C> BiRowMapper<Tuple3<A, B, C>> combine(final BiRowMapper<? extends A> rowMapper1, final BiRowMapper<? extends B> rowMapper2,
                final BiRowMapper<? extends C> rowMapper3) {
            N.checkArgNotNull(rowMapper1, "rowMapper1");
            N.checkArgNotNull(rowMapper2, "rowMapper2");
            N.checkArgNotNull(rowMapper3, "rowMapper3");

            return (rs, cls) -> Tuple.of(rowMapper1.apply(rs, cls), rowMapper2.apply(rs, cls), rowMapper3.apply(rs, cls));
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param <T>
         * @param targetClass
         * @return
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(final Class<? extends T> targetClass) {
            return to(targetClass, false);
        }

        /**
         * Don't cache or reuse the returned {@code BiRowMapper} instance. It's stateful.
         *
         * @param <T>
         * @param targetClass
         * @param ignoreNonMatchedColumns
         * @return
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(final Class<? extends T> targetClass, final boolean ignoreNonMatchedColumns) {
            return to(targetClass, Fn.alwaysTrue(), Fn.identity(), ignoreNonMatchedColumns);
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param <T>
         * @param targetClass
         * @param columnNameFilter
         * @param columnNameConverter
         * @return
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(final Class<? extends T> targetClass, final Predicate<? super String> columnNameFilter,
                final Function<? super String, String> columnNameConverter) {
            return to(targetClass, columnNameFilter, columnNameConverter, false);
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param <T>
         * @param targetClass
         * @param columnNameFilter
         * @param columnNameConverter
         * @param ignoreNonMatchedColumns
         * @return
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(final Class<? extends T> targetClass, final Predicate<? super String> columnNameFilter,
                final Function<? super String, String> columnNameConverter, final boolean ignoreNonMatchedColumns) {
            N.checkArgNotNull(targetClass, "targetClass");

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
                    return new BiRowMapper<>() {
                        @Override
                        public T apply(final ResultSet rs, final List<String> columnLabelList) throws SQLException {
                            final int columnCount = columnLabelList.size();
                            @SuppressWarnings("rawtypes")
                            final Collection<Object> c = N.newCollection((Class<Collection>) targetClass, columnCount);

                            for (int i = 0; i < columnCount; i++) {
                                c.add(JdbcUtil.getColumnValue(rs, i + 1));
                            }

                            return (T) c;
                        }
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
                                        final String newColumnName = Jdbc.checkPrefix(entityInfo, columnLabels[i], null, columnLabelList);
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
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param <T>
         * @param entityClass
         * @param prefixAndFieldNameMap
         * @return
         */
        @SequentialOnly
        @Stateful
        static <T> BiRowMapper<T> to(final Class<? extends T> entityClass, final Map<String, String> prefixAndFieldNameMap) {
            return to(entityClass, prefixAndFieldNameMap, false);
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param <T>
         * @param entityClass
         * @param prefixAndFieldNameMap
         * @param ignoreNonMatchedColumns
         * @return
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
                                final String newColumnName = Jdbc.checkPrefix(entityInfo, columnLabels[i], prefixAndFieldNameMap, columnLabelList);
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

        //        Map<Class<?>, Tuple2<? extends Supplier<?>, ? extends Function<?, ?>>> buildFuncMap = new ConcurrentHashMap<>();
        //
        //        static <T> BiRowMapper<T> toEntityByBuilder(final Class<T> entityClass) {
        //            @SuppressWarnings("rawtypes")
        //            Tuple2<Supplier<?>, Function<?, T>> builderFuncs = (Tuple2) buildFuncMap.get(entityClass);
        //
        //            if (builderFuncs == null) {
        //                Class<?> builderClass = null;
        //                Method builderMethod = null;
        //                Method buildMethod = null;
        //
        //                try {
        //                    builderMethod = entityClass.getDeclaredMethod("builder");
        //                } catch (Exception e) {
        //                    // ignore
        //                }
        //
        //                if (builderMethod == null || !(Modifier.isStatic(builderMethod.getModifiers()) && Modifier.isPublic(builderMethod.getModifiers()))) {
        //                    try {
        //                        builderMethod = entityClass.getDeclaredMethod("newBuilder");
        //                    } catch (Exception e) {
        //                        // ignore
        //                    }
        //                }
        //
        //                if (builderMethod == null || !(Modifier.isStatic(builderMethod.getModifiers()) && Modifier.isPublic(builderMethod.getModifiers()))) {
        //                    try {
        //                        builderMethod = entityClass.getDeclaredMethod("createBuilder");
        //                    } catch (Exception e) {
        //                        // ignore
        //                    }
        //                }
        //
        //                if (builderMethod == null || !(Modifier.isStatic(builderMethod.getModifiers()) && Modifier.isPublic(builderMethod.getModifiers()))) {
        //                    throw new IllegalArgumentException("No static builder method found in entity class: " + entityClass);
        //                }
        //
        //                builderClass = builderMethod.getReturnType();
        //
        //                try {
        //                    buildMethod = builderClass.getDeclaredMethod("build");
        //                } catch (Exception e) {
        //                    // ignore
        //                }
        //
        //                if (buildMethod == null || !Modifier.isPublic(buildMethod.getModifiers())) {
        //                    try {
        //                        buildMethod = builderClass.getDeclaredMethod("create");
        //                    } catch (Exception e) {
        //                        // ignore
        //                    }
        //                }
        //
        //                if (buildMethod == null || !Modifier.isPublic(buildMethod.getModifiers())) {
        //                    throw new IllegalArgumentException("No build method found in builder class: " + builderClass);
        //                }
        //
        //                final Method finalBuilderMethod = builderMethod;
        //                final Method finalBuildMethod = buildMethod;
        //
        //                final Supplier<?> builderSupplier = () -> ClassUtil.invokeMethod(finalBuilderMethod);
        //                final Function<?, T> buildFunc = instance -> ClassUtil.invokeMethod(instance, finalBuildMethod);
        //
        //                builderFuncs = Tuple.of(builderSupplier, buildFunc);
        //
        //                buildFuncMap.put(entityClass, builderFuncs);
        //            }
        //
        //            return toEntityByBuilder(builderFuncs._1, builderFuncs._2);
        //        }
        //
        //        static <T> BiRowMapper<T> toEntityByBuilder(final Supplier<?> builderSupplier) {
        //            // TODO
        //
        //            return null;
        //        }
        //
        //        static <T> BiRowMapper<T> toEntityByBuilder(final Supplier<?> builderSupplier, final Function<?, T> buildFunction) {
        //            // TODO
        //
        //            return null;
        //        }

        /**
         *
         *
         * @param valueFilter
         * @return
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
         *
         *
         * @param valueFilter
         * @param mapSupplier
         * @return
         */
        static BiRowMapper<Map<String, Object>> toMap(final BiPredicate<String, Object> valueFilter, final IntFunction<Map<String, Object>> mapSupplier) {
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
         *
         * @param rowExtractor
         * @param valueFilter
         * @param mapSupplier
         * @return
         */
        @SequentialOnly
        @Stateful
        static BiRowMapper<Map<String, Object>> toMap(final RowExtractor rowExtractor, final BiPredicate<String, Object> valueFilter,
                final IntFunction<Map<String, Object>> mapSupplier) {
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
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param columnNameConverter
         * @return
         */
        @SequentialOnly
        @Stateful
        static BiRowMapper<Map<String, Object>> toMap(final Function<? super String, String> columnNameConverter) {
            return toMap(columnNameConverter, IntFunctions.<String, Object> ofMap());
        }

        /**
         *
         * @param columnNameConverter
         * @param mapSupplier
         * @return
         */
        @SequentialOnly
        @Stateful
        static BiRowMapper<Map<String, Object>> toMap(final Function<? super String, String> columnNameConverter,
                final IntFunction<Map<String, Object>> mapSupplier) {
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
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param rowExtractor
         * @param columnNameConverter
         * @param mapSupplier
         * @return
         */
        @SequentialOnly
        @Stateful
        static BiRowMapper<Map<String, Object>> toMap(final RowExtractor rowExtractor, final Function<? super String, String> columnNameConverter,
                final IntFunction<Map<String, Object>> mapSupplier) {
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
         *
         *
         * @param rowExtractor
         * @return
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
         *
         *
         * @param columnGetterForAll
         * @return
         */
        @Beta
        static BiRowMapper<Object[]> toArray(final ColumnGetter<?> columnGetterForAll) {
            return new BiRowMapper<>() {

                @Override
                public Object[] apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    final int columnCount = columnLabels.size();
                    final Object[] result = new Object[columnCount];

                    for (int i = 0; i < columnCount; i++) {
                        result[i] = columnGetterForAll.apply(rs, i + 1);
                    }

                    return result;
                }
            };
        }

        /**
         *
         *
         * @param columnGetterForAll
         * @return
         */
        @Beta
        static BiRowMapper<List<Object>> toList(final ColumnGetter<?> columnGetterForAll) {
            return toCollection(columnGetterForAll, Factory.<Object> ofList());
        }

        /**
         *
         *
         * @param <C>
         * @param columnGetterForAll
         * @param supplier
         * @return
         */
        @Beta
        static <C extends Collection<?>> BiRowMapper<C> toCollection(final ColumnGetter<?> columnGetterForAll, final IntFunction<C> supplier) {
            return new BiRowMapper<>() {

                @Override
                public C apply(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    final int columnCount = columnLabels.size();

                    final Collection<Object> result = (Collection<Object>) supplier.apply(columnCount);

                    for (int i = 0; i < columnCount; i++) {
                        result.add(columnGetterForAll.apply(rs, i + 1));
                    }

                    return (C) result;
                }
            };
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @return
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
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param entityClass used to fetch column/row value from {@code ResultSet} by the type of fields/columns defined in this class.
         * @return
         */
        @Beta
        @SequentialOnly
        @Stateful
        static BiRowMapper<DisposableObjArray> toDisposableObjArray(final Class<?> entityClass) {
            N.checkArgNotNull(entityClass, "entityClass");

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
         *
         *
         * @return
         */
        static BiRowMapperBuilder builder() {
            return builder(ColumnGetter.GET_OBJECT);
        }

        /**
         *
         *
         * @param defaultColumnGetter
         * @return
         */
        static BiRowMapperBuilder builder(final ColumnGetter<?> defaultColumnGetter) {
            return new BiRowMapperBuilder(defaultColumnGetter);
        }

        //    static BiRowMapperBuilder builder(final int columnCount) {
        //        return new BiRowMapperBuilder(columnCount);
        //    }

        @SequentialOnly
        public static class BiRowMapperBuilder {
            private final ColumnGetter<?> defaultColumnGetter;
            private final Map<String, ColumnGetter<?>> columnGetterMap;

            BiRowMapperBuilder(final ColumnGetter<?> defaultColumnGetter) {
                this.defaultColumnGetter = defaultColumnGetter;

                columnGetterMap = new HashMap<>(9);
            }

            /**
             *
             *
             * @param columnName
             * @return
             */
            public BiRowMapperBuilder getBoolean(final String columnName) {
                return get(columnName, ColumnGetter.GET_BOOLEAN);
            }

            /**
             *
             *
             * @param columnName
             * @return
             */
            public BiRowMapperBuilder getByte(final String columnName) {
                return get(columnName, ColumnGetter.GET_BYTE);
            }

            /**
             *
             *
             * @param columnName
             * @return
             */
            public BiRowMapperBuilder getShort(final String columnName) {
                return get(columnName, ColumnGetter.GET_SHORT);
            }

            /**
             *
             *
             * @param columnName
             * @return
             */
            public BiRowMapperBuilder getInt(final String columnName) {
                return get(columnName, ColumnGetter.GET_INT);
            }

            /**
             *
             *
             * @param columnName
             * @return
             */
            public BiRowMapperBuilder getLong(final String columnName) {
                return get(columnName, ColumnGetter.GET_LONG);
            }

            /**
             *
             *
             * @param columnName
             * @return
             */
            public BiRowMapperBuilder getFloat(final String columnName) {
                return get(columnName, ColumnGetter.GET_FLOAT);
            }

            /**
             *
             *
             * @param columnName
             * @return
             */
            public BiRowMapperBuilder getDouble(final String columnName) {
                return get(columnName, ColumnGetter.GET_DOUBLE);
            }

            /**
             *
             *
             * @param columnName
             * @return
             */
            public BiRowMapperBuilder getBigDecimal(final String columnName) {
                return get(columnName, ColumnGetter.GET_BIG_DECIMAL);
            }

            /**
             *
             *
             * @param columnName
             * @return
             */
            public BiRowMapperBuilder getString(final String columnName) {
                return get(columnName, ColumnGetter.GET_STRING);
            }

            /**
             *
             *
             * @param columnName
             * @return
             */
            public BiRowMapperBuilder getDate(final String columnName) {
                return get(columnName, ColumnGetter.GET_DATE);
            }

            /**
             *
             *
             * @param columnName
             * @return
             */
            public BiRowMapperBuilder getTime(final String columnName) {
                return get(columnName, ColumnGetter.GET_TIME);
            }

            /**
             *
             *
             * @param columnName
             * @return
             */
            public BiRowMapperBuilder getTimestamp(final String columnName) {
                return get(columnName, ColumnGetter.GET_TIMESTAMP);
            }

            /**
             *
             * @param columnName
             * @return
             * @deprecated default {@link #getObject(String)} if there is no {@code ColumnGetter} set for the target column
             */
            @Deprecated
            public BiRowMapperBuilder getObject(final String columnName) {
                return get(columnName, ColumnGetter.GET_OBJECT);
            }

            /**
             *
             *
             * @param columnName
             * @param type
             * @return
             */
            public BiRowMapperBuilder getObject(final String columnName, Class<?> type) {
                return get(columnName, ColumnGetter.get(type));
            }

            /**
             * 
             *
             * @param columnName 
             * @param columnGetter 
             * @return 
             * @throws IllegalArgumentException 
             */
            public BiRowMapperBuilder get(final String columnName, final ColumnGetter<?> columnGetter) throws IllegalArgumentException {
                N.checkArgNotNull(columnName, "columnName");
                N.checkArgNotNull(columnGetter, "columnGetter");

                columnGetterMap.put(columnName, columnGetter);

                return this;
            }

            /**
             *
             * @param columnName
             * @param columnGetter
             * @return
             * @deprecated replaced by {@link #get(String, ColumnGetter)}
             */
            @Deprecated
            public BiRowMapperBuilder column(final String columnName, final ColumnGetter<?> columnGetter) {
                return get(columnName, columnGetter);
            }

            //    /**
            //     * Set default column getter function.
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public BiRowMapperBuilder __(final ColumnGetter<?> columnGetter) {
            //        defaultColumnGetter = columnGetter;
            //
            //        return this;
            //    }
            //
            //    /**
            //     * Set column getter function for column[columnName].
            //     *
            //     * @param columnGetter
            //     * @return
            //     */
            //    public BiRowMapperBuilder __(final String columnName, final ColumnGetter<?> columnGetter) {
            //        columnGetterMap.put(columnName, columnGetter);
            //
            //        return this;
            //    }

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
             * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
             *
             * @param <T>
             * @param targetClass
             * @return
             */
            @SequentialOnly
            @Stateful
            public <T> BiRowMapper<T> to(final Class<? extends T> targetClass) {
                return to(targetClass, false);
            }

            /**
             * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
             *
             * @param <T>
             * @param targetClass
             * @param ignoreNonMatchedColumns
             * @return
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

                                this.propInfos = localPropInfos;
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
     * Don't use {@code RowConsumer} in {@link PreparedQuery#forEach(RowConsumer)} or any place where multiple records will be consumed by it, if column labels/count are used in {@link RowConsumer#accept(ResultSet)}.
     * Consider using {@code BiRowConsumer} instead because it's more efficient to consume multiple records when column labels/count are used.
     *
     */
    @FunctionalInterface
    public interface RowConsumer extends Throwables.Consumer<ResultSet, SQLException> {

        RowConsumer DO_NOTHING = rs -> {
        };

        /**
         *
         *
         * @param rs
         * @throws SQLException
         */
        @Override
        void accept(ResultSet rs) throws SQLException;

        /**
         *
         *
         * @param after
         * @return
         */
        default RowConsumer andThen(final Throwables.Consumer<? super ResultSet, SQLException> after) {
            N.checkArgNotNull(after);

            return rs -> {
                accept(rs);
                after.accept(rs);
            };
        }

        /**
         *
         *
         * @return
         */
        default BiRowConsumer toBiRowConsumer() {
            return (rs, columnLabels) -> this.accept(rs);
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param consumerForAll
         * @return
         */
        @Beta
        @SequentialOnly
        @Stateful
        static RowConsumer create(Throwables.ObjIntConsumer<ResultSet, SQLException> consumerForAll) {
            N.checkArgNotNull(consumerForAll, "consumerForAll");

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
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param consumer
         * @return
         */
        @Beta
        @SequentialOnly
        @Stateful
        static RowConsumer oneOff(final Consumer<DisposableObjArray> consumer) {
            N.checkArgNotNull(consumer, "consumer");

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
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param entityClass used to fetch column/row value from {@code ResultSet} by the type of fields/columns defined in this class.
         * @param consumer
         * @return
         */
        @Beta
        @SequentialOnly
        @Stateful
        static RowConsumer oneOff(final Class<?> entityClass, final Consumer<DisposableObjArray> consumer) {
            N.checkArgNotNull(entityClass, "entityClass");
            N.checkArgNotNull(consumer, "consumer");

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
     * The Interface BiRowConsumer.
     */
    @FunctionalInterface
    public interface BiRowConsumer extends Throwables.BiConsumer<ResultSet, List<String>, SQLException> {

        BiRowConsumer DO_NOTHING = (rs, cls) -> {
        };

        /**
         *
         *
         * @param rs
         * @param columnLabels
         * @throws SQLException
         */
        @Override
        void accept(ResultSet rs, List<String> columnLabels) throws SQLException;

        /**
         *
         *
         * @param after
         * @return
         */
        default BiRowConsumer andThen(final Throwables.BiConsumer<? super ResultSet, ? super List<String>, SQLException> after) {
            N.checkArgNotNull(after);

            return (rs, cls) -> {
                accept(rs, cls);
                after.accept(rs, cls);
            };
        }

        //    /**
        //     *
        //     *
        //     * @param rowConsumer
        //     * @return
        //     */
        //    static BiRowConsumer from(final RowConsumer rowConsumer) {
        //        N.checkArgNotNull(rowConsumer, "rowConsumer");
        //
        //        return (rs, columnLabels) -> rowConsumer.accept(rs);
        //    }

        /**
         *
         *
         * @param consumerForAll
         * @return
         */
        @Beta
        static BiRowConsumer create(Throwables.ObjIntConsumer<ResultSet, SQLException> consumerForAll) {
            N.checkArgNotNull(consumerForAll, "consumerForAll");

            return new BiRowConsumer() {
                @Override
                public void accept(final ResultSet rs, final List<String> columnLabels) throws SQLException {
                    final int columnCount = columnLabels.size();

                    for (int i = 0; i < columnCount; i++) {
                        consumerForAll.accept(rs, i + 1);
                    }
                }
            };
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param consumer
         * @return
         */
        @Beta
        @SequentialOnly
        @Stateful
        static BiRowConsumer oneOff(final BiConsumer<List<String>, DisposableObjArray> consumer) {
            N.checkArgNotNull(consumer, "consumer");

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
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param entityClass used to fetch column/row value from {@code ResultSet} by the type of fields/columns defined in this class.
         * @param consumer
         * @return
         */
        @Beta
        @SequentialOnly
        @Stateful
        static BiRowConsumer oneOff(final Class<?> entityClass, final BiConsumer<List<String>, DisposableObjArray> consumer) {
            N.checkArgNotNull(entityClass, "entityClass");
            N.checkArgNotNull(consumer, "consumer");

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
     * Generally, the result should be filtered in database side by SQL scripts.
     * Only user {@code RowFilter/BiRowFilter} if there is a specific reason or the filter can't be done by SQL scripts in database server side.
     * Consider using {@code BiRowConsumer} instead because it's more efficient to test multiple records when column labels/count are used.
     *
     */
    @FunctionalInterface
    public interface RowFilter extends Throwables.Predicate<ResultSet, SQLException> {

        /** The Constant ALWAYS_TRUE. */
        RowFilter ALWAYS_TRUE = rs -> true;

        /** The Constant ALWAYS_FALSE. */
        RowFilter ALWAYS_FALSE = rs -> false;

        /**
         *
         *
         * @param rs
         * @return
         * @throws SQLException
         */
        @Override
        boolean test(final ResultSet rs) throws SQLException;

        /**
         *
         *
         * @return
         */
        @Override
        default RowFilter negate() {
            return rs -> !test(rs);
        }

        /**
         *
         *
         * @param other
         * @return
         */
        default RowFilter and(final Throwables.Predicate<? super ResultSet, SQLException> other) {
            N.checkArgNotNull(other);

            return rs -> test(rs) && other.test(rs);
        }

        /**
         *
         *
         * @return
         */
        default BiRowFilter toBiRowFilter() {
            return (rs, columnLabels) -> this.test(rs);
        }
    }

    /**
     * Generally, the result should be filtered in database side by SQL scripts.
     * Only user {@code RowFilter/BiRowFilter} if there is a specific reason or the filter can't be done by SQL scripts in database server side.
     *
     */
    @FunctionalInterface
    public interface BiRowFilter extends Throwables.BiPredicate<ResultSet, List<String>, SQLException> {

        /** The Constant ALWAYS_TRUE. */
        BiRowFilter ALWAYS_TRUE = (rs, columnLabels) -> true;

        /** The Constant ALWAYS_FALSE. */
        BiRowFilter ALWAYS_FALSE = (rs, columnLabels) -> false;

        /**
         *
         *
         * @param rs
         * @param columnLabels
         * @return
         * @throws SQLException
         */
        @Override
        boolean test(ResultSet rs, List<String> columnLabels) throws SQLException;

        /**
         *
         *
         * @return
         */
        default BiRowFilter negate() {
            return (rs, cls) -> !test(rs, cls);
        }

        /**
         *
         *
         * @param other
         * @return
         */
        default BiRowFilter and(final Throwables.BiPredicate<? super ResultSet, ? super List<String>, SQLException> other) {
            N.checkArgNotNull(other);

            return (rs, cls) -> test(rs, cls) && other.test(rs, cls);
        }

        //    /**
        //     *
        //     *
        //     * @param rowFilter
        //     * @return
        //     */
        //    static BiRowFilter from(final RowFilter rowFilter) {
        //        N.checkArgNotNull(rowFilter, "rowFilter");
        //
        //        return (rs, columnLabels) -> rowFilter.test(rs);
        //    }
    }

    @FunctionalInterface
    public interface RowExtractor extends Throwables.BiConsumer<ResultSet, Object[], SQLException> {

        /**
         *
         *
         * @param rs
         * @param outputRow
         * @throws SQLException
         */
        @Override
        void accept(final ResultSet rs, final Object[] outputRow) throws SQLException;

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param entityClassForFetch
         * @return
         */
        @SequentialOnly
        @Stateful
        static RowExtractor createBy(final Class<?> entityClassForFetch) {
            return createBy(entityClassForFetch, null, null);
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param entityClassForFetch
         * @param prefixAndFieldNameMap
         * @return
         */
        @SequentialOnly
        @Stateful
        static RowExtractor createBy(final Class<?> entityClassForFetch, final Map<String, String> prefixAndFieldNameMap) {
            return createBy(entityClassForFetch, null, prefixAndFieldNameMap);
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param entityClassForFetch
         * @param columnLabels
         * @return
         */
        @SequentialOnly
        @Stateful
        static RowExtractor createBy(final Class<?> entityClassForFetch, final List<String> columnLabels) {
            return createBy(entityClassForFetch, columnLabels, null);
        }

        /**
         * It's stateful. Don't save or cache the returned instance for reuse or use it in parallel stream.
         *
         * @param entityClassForFetch
         * @param columnLabels
         * @param prefixAndFieldNameMap
         * @return
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
                public void accept(ResultSet rs, Object[] outputRow) throws SQLException {
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
                                final String newColumnName = Jdbc.checkPrefix(entityInfo, columnLabels[i], prefixAndFieldNameMap, columnLabelList);

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
         *
         *
         * @param defaultColumnGetter
         * @return
         */
        static RowExtractorBuilder create(final ColumnGetter<?> defaultColumnGetter) {
            return new RowExtractorBuilder(defaultColumnGetter);
        }

        /**
         *
         *
         * @return
         */
        static RowExtractorBuilder builder() {
            return builder(ColumnGetter.GET_OBJECT);
        }

        /**
         *
         *
         * @param defaultColumnGetter
         * @return
         */
        static RowExtractorBuilder builder(final ColumnGetter<?> defaultColumnGetter) {
            return new RowExtractorBuilder(defaultColumnGetter);
        }

        public static class RowExtractorBuilder {
            private final Map<Integer, ColumnGetter<?>> columnGetterMap;

            RowExtractorBuilder(final ColumnGetter<?> defaultColumnGetter) {
                N.checkArgNotNull(defaultColumnGetter, "defaultColumnGetter");

                columnGetterMap = new HashMap<>(9);
                columnGetterMap.put(0, defaultColumnGetter);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowExtractorBuilder getBoolean(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BOOLEAN);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowExtractorBuilder getByte(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BYTE);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowExtractorBuilder getShort(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_SHORT);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowExtractorBuilder getInt(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_INT);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowExtractorBuilder getLong(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_LONG);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowExtractorBuilder getFloat(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_FLOAT);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowExtractorBuilder getDouble(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_DOUBLE);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowExtractorBuilder getBigDecimal(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_BIG_DECIMAL);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowExtractorBuilder getString(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_STRING);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowExtractorBuilder getDate(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_DATE);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowExtractorBuilder getTime(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_TIME);
            }

            /**
             *
             *
             * @param columnIndex
             * @return
             */
            public RowExtractorBuilder getTimestamp(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_TIMESTAMP);
            }

            /**
             *
             * @param columnIndex
             * @return
             * @deprecated default {@link #getObject(int)} if there is no {@code ColumnGetter} set for the target column
             */
            @Deprecated
            public RowExtractorBuilder getObject(final int columnIndex) {
                return get(columnIndex, ColumnGetter.GET_OBJECT);
            }

            /**
             *
             *
             * @param columnIndex
             * @param type
             * @return
             */
            public RowExtractorBuilder getObject(final int columnIndex, Class<?> type) {
                return get(columnIndex, ColumnGetter.get(type));
            }

            /**
             * 
             *
             * @param columnIndex 
             * @param columnGetter 
             * @return 
             * @throws IllegalArgumentException 
             */
            public RowExtractorBuilder get(final int columnIndex, final ColumnGetter<?> columnGetter) throws IllegalArgumentException {
                N.checkArgPositive(columnIndex, "columnIndex");
                N.checkArgNotNull(columnGetter, "columnGetter");

                //        if (columnGetters == null) {
                //            columnGetterMap.put(columnIndex, columnGetter);
                //        } else {
                //            columnGetters[columnIndex] = columnGetter;
                //        }

                columnGetterMap.put(columnIndex, columnGetter);
                return this;
            }

            ColumnGetter<?>[] initColumnGetter(ResultSet rs) throws SQLException { //NOSONAR
                return initColumnGetter(rs.getMetaData().getColumnCount());
            }

            ColumnGetter<?>[] initColumnGetter(final int columnCount) { //NOSONAR
                final ColumnGetter<?>[] rsColumnGetters = new ColumnGetter<?>[columnCount];
                final ColumnGetter<?> defaultColumnGetter = columnGetterMap.get(0);

                for (int i = 0, len = rsColumnGetters.length; i < len; i++) {
                    rsColumnGetters[i] = columnGetterMap.getOrDefault(i + 1, defaultColumnGetter);
                }

                return rsColumnGetters;
            }

            /**
             * Don't cache or reuse the returned {@code RowExtractor} instance.
             *
             * @return
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
                            rsColumnGetters = initColumnGetter(outputRow.length);
                            rsColumnCount = rsColumnGetters.length - 1;
                        }

                        for (int i = 0; i < rsColumnCount; i++) {
                            outputRow[i] = rsColumnGetters[i].apply(rs, i + 1);
                        }
                    }
                };
            }
        }
    }

    @FunctionalInterface
    public interface ColumnGetter<V> {

        ColumnGetter<Boolean> GET_BOOLEAN = ResultSet::getBoolean;

        ColumnGetter<Byte> GET_BYTE = ResultSet::getByte;

        ColumnGetter<Short> GET_SHORT = ResultSet::getShort;

        ColumnGetter<Integer> GET_INT = ResultSet::getInt;

        ColumnGetter<Long> GET_LONG = ResultSet::getLong;

        ColumnGetter<Float> GET_FLOAT = ResultSet::getFloat;

        ColumnGetter<Double> GET_DOUBLE = ResultSet::getDouble;

        ColumnGetter<BigDecimal> GET_BIG_DECIMAL = ResultSet::getBigDecimal;

        ColumnGetter<String> GET_STRING = ResultSet::getString;

        ColumnGetter<Date> GET_DATE = ResultSet::getDate;

        ColumnGetter<Time> GET_TIME = ResultSet::getTime;

        ColumnGetter<Timestamp> GET_TIMESTAMP = ResultSet::getTimestamp;

        ColumnGetter<byte[]> GET_BYTES = ResultSet::getBytes;

        ColumnGetter<InputStream> GET_BINARY_STREAM = ResultSet::getBinaryStream;

        ColumnGetter<Reader> GET_CHARACTER_STREAM = ResultSet::getCharacterStream;

        ColumnGetter<Blob> GET_BLOB = ResultSet::getBlob;

        ColumnGetter<Clob> GET_CLOB = ResultSet::getClob;

        @SuppressWarnings("rawtypes")
        ColumnGetter GET_OBJECT = JdbcUtil::getColumnValue;

        /**
         *
         * @param rs
         * @param columnIndex start from 1
         * @return
         * @throws SQLException
         */
        V apply(ResultSet rs, int columnIndex) throws SQLException;

        /**
         *
         *
         * @param <T>
         * @param cls
         * @return
         */
        static <T> ColumnGetter<T> get(final Class<? extends T> cls) {
            return get(N.typeOf(cls));
        }

        /**
         *
         *
         * @param <T>
         * @param type
         * @return
         */
        static <T> ColumnGetter<T> get(final Type<? extends T> type) {
            ColumnGetter<?> columnGetter = COLUMN_GETTER_POOL.get(type);

            if (columnGetter == null) {
                columnGetter = type::get;

                COLUMN_GETTER_POOL.put(type, columnGetter);
            }

            return (ColumnGetter<T>) columnGetter;
        }
    }

    public static final class Columns {
        private Columns() {
            // singleton for utility class
        }

        public static final class ColumnOne {
            public static final RowMapper<Boolean> GET_BOOLEAN = rs -> rs.getBoolean(1);

            public static final RowMapper<Byte> GET_BYTE = rs -> rs.getByte(1);

            public static final RowMapper<Short> GET_SHORT = rs -> rs.getShort(1);

            public static final RowMapper<Integer> GET_INT = rs -> rs.getInt(1);

            public static final RowMapper<Long> GET_LONG = rs -> rs.getLong(1);

            public static final RowMapper<Float> GET_FLOAT = rs -> rs.getFloat(1);

            public static final RowMapper<Double> GET_DOUBLE = rs -> rs.getDouble(1);

            public static final RowMapper<BigDecimal> GET_BIG_DECIMAL = rs -> rs.getBigDecimal(1);

            public static final RowMapper<String> GET_STRING = rs -> rs.getString(1);

            public static final RowMapper<Date> GET_DATE = rs -> rs.getDate(1);

            public static final RowMapper<Time> GET_TIME = rs -> rs.getTime(1);

            public static final RowMapper<Timestamp> GET_TIMESTAMP = rs -> rs.getTimestamp(1);

            public static final RowMapper<byte[]> GET_BYTES = rs -> rs.getBytes(1);

            public static final RowMapper<InputStream> GET_BINARY_STREAM = rs -> rs.getBinaryStream(1);

            public static final RowMapper<Reader> GET_CHARACTER_STREAM = rs -> rs.getCharacterStream(1);

            public static final RowMapper<Blob> GET_BLOB = rs -> rs.getBlob(1);

            public static final RowMapper<Clob> GET_CLOB = rs -> rs.getClob(1);

            //        [INFO] Compiling 42 source files to C:\Users\haiyangl\Landawn\abacus-jdbc\trunk\target\classes
            //        An exception has occurred in the compiler (1.8.0_231). Please file a bug against the Java compiler via the Java bug reporting page (http://bugreport.java.com) a
            //        fter checking the Bug Database (http://bugs.java.com) for duplicates. Include your program and the following diagnostic in your report. Thank you.
            //        java.lang.AssertionError
            //                at com.sun.tools.javac.util.Assert.error(Assert.java:126)
            //                at com.sun.tools.javac.util.Assert.check(Assert.java:45)
            //                at com.sun.tools.javac.code.Types.functionalInterfaceBridges(Types.java:659)
            //                at com.sun.tools.javac.comp.LambdaToMethod$LambdaAnalyzerPreprocessor$TranslationContext.<init>(LambdaToMethod.java:1770)
            //                at com.sun.tools.javac.comp.LambdaToMethod$LambdaAnalyzerPreprocessor$LambdaTranslationContext.<init>(LambdaToMethod.java:1853)
            //                at com.sun.tools.javac.comp.LambdaToMethod$LambdaAnalyzerPreprocessor.analyzeLambda(LambdaToMethod.java:1337)
            //                at com.sun.tools.javac.comp.LambdaToMethod$LambdaAnalyzerPreprocessor.visitLambda(LambdaToMethod.java:1322)
            //                at com.sun.tools.javac.tree.JCTree$JCLambda.accept(JCTree.java:1624)
            //        .............
            //                at org.apache.maven.plugin.compiler.AbstractCompilerMojo.execute(AbstractCompilerMojo.java:785)
            //                at org.apache.maven.plugin.compiler.CompilerMojo.execute(CompilerMojo.java:129)
            //                at org.apache.maven.plugin.DefaultBuildPluginManager.executeMojo(DefaultBuildPluginManager.java:137)
            //                at org.apache.maven.lifecycle.internal.MojoExecutor.execute(MojoExecutor.java:210)
            //                at org.apache.maven.lifecycle.internal.MojoExecutor.execute(MojoExecutor.java:156)
            //                at org.apache.maven.lifecycle.internal.MojoExecutor.execute(MojoExecutor.java:148)
            //                at org.apache.maven.lifecycle.internal.LifecycleModuleBuilder.buildProject(LifecycleModuleBuilder.java:117)
            //                at org.apache.maven.lifecycle.internal.LifecycleModuleBuilder.buildProject(LifecycleModuleBuilder.java:81)
            //                at org.apache.maven.lifecycle.internal.builder.singlethreaded.SingleThreadedBuilder.build(SingleThreadedBuilder.java:56)
            //                at org.apache.maven.lifecycle.internal.LifecycleStarter.execute(LifecycleStarter.java:128)
            //                at org.apache.maven.DefaultMaven.doExecute(DefaultMaven.java:305)
            //                at org.apache.maven.DefaultMaven.doExecute(DefaultMaven.java:192)
            //                at org.apache.maven.DefaultMaven.execute(DefaultMaven.java:105)
            //                at org.apache.maven.cli.MavenCli.execute(MavenCli.java:957)
            //                at org.apache.maven.cli.MavenCli.doMain(MavenCli.java:289)
            //                at org.apache.maven.cli.MavenCli.main(MavenCli.java:193)
            //                at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method)
            //                at sun.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:62)
            //                at sun.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:43)
            //                at java.lang.reflect.Method.invoke(Method.java:498)
            //                at org.codehaus.plexus.classworlds.launcher.Launcher.launchEnhanced(Launcher.java:282)
            //                at org.codehaus.plexus.classworlds.launcher.Launcher.launch(Launcher.java:225)
            //                at org.codehaus.plexus.classworlds.launcher.Launcher.mainWithExitCode(Launcher.java:406)
            //                at org.codehaus.plexus.classworlds.launcher.Launcher.main(Launcher.java:347)
            //        [INFO] -------------------------------------------------------------
            //        [ERROR] COMPILATION ERROR :
            //        [INFO] -------------------------------------------------------------
            //        [ERROR] An unknown compilation problem occurred
            //        [INFO] 1 error
            //        [INFO] -------------------------------------------------------------
            //        [INFO] ------------------------------------------------------------------------
            //        [INFO] BUILD FAILURE
            //        [INFO] ------------------------------------------------------------------------
            //        [INFO] Total time:  12.852 s
            //        [INFO] Finished at: 2020-05-10T15:12:38-07:00
            //        [INFO] ------------------------------------------------------------------------
            //        [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.1:compile (default-compile) on project abacus-jdbc: Compilation failure
            //        [ERROR] An unknown compilation problem occurred
            //        [ERROR]
            //        [ERROR] -> [Help 1]
            //        [ERROR]
            //        [ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
            //        [ERROR] Re-run Maven using the -X switch to enable full debug logging.
            //        [ERROR]
            //        [ERROR] For more information about the errors and possible solutions, please read the following articles:
            //        [ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException

            //    @SuppressWarnings("rawtypes")
            //    public static final RowMapper<Object> GET_OBJECT = rs -> rs.getObject(1);

            public static final RowMapper<Object> GET_OBJECT = rs -> JdbcUtil.getColumnValue(rs, 1);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Boolean> SET_BOOLEAN = (preparedQuery, x) -> preparedQuery.setBoolean(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Byte> SET_BYTE = (preparedQuery, x) -> preparedQuery.setByte(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Short> SET_SHORT = (preparedQuery, x) -> preparedQuery.setShort(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Integer> SET_INT = (preparedQuery, x) -> preparedQuery.setInt(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Long> SET_LONG = (preparedQuery, x) -> preparedQuery.setLong(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Float> SET_FLOAT = (preparedQuery, x) -> preparedQuery.setFloat(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Double> SET_DOUBLE = (preparedQuery, x) -> preparedQuery.setDouble(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, BigDecimal> SET_BIG_DECIMAL = (preparedQuery, x) -> preparedQuery.setBigDecimal(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, String> SET_STRING = (preparedQuery, x) -> preparedQuery.setString(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Date> SET_DATE = (preparedQuery, x) -> preparedQuery.setDate(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Time> SET_TIME = (preparedQuery, x) -> preparedQuery.setTime(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Timestamp> SET_TIMESTAMP = (preparedQuery, x) -> preparedQuery.setTimestamp(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, java.util.Date> SET_DATE_JU = (preparedQuery, x) -> preparedQuery.setDate(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, java.util.Date> SET_TIME_JU = (preparedQuery, x) -> preparedQuery.setTime(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, java.util.Date> SET_TIMESTAMP_JU = (preparedQuery, x) -> preparedQuery.setTimestamp(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, byte[]> SET_BYTES = (preparedQuery, x) -> preparedQuery.setBytes(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, InputStream> SET_BINARY_STREAM = (preparedQuery, x) -> preparedQuery.setBinaryStream(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Reader> SET_CHARACTER_STREAM = (preparedQuery, x) -> preparedQuery.setCharacterStream(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Blob> SET_BLOB = (preparedQuery, x) -> preparedQuery.setBlob(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Clob> SET_CLOB = (preparedQuery, x) -> preparedQuery.setClob(1, x);

            @SuppressWarnings("rawtypes")
            public static final BiParametersSetter<AbstractQuery, Object> SET_OBJECT = (preparedQuery, x) -> preparedQuery.setObject(1, x);

            private ColumnOne() {
                // singleton for utility class
            }

            @SuppressWarnings("rawtypes")
            static final Map<Type<?>, RowMapper> rowMapperPool = new ObjectPool<>(1024);

            /**
             *
             *
             * @param <T>
             * @return
             */
            public static <T> RowMapper<T> getObject() {
                return (RowMapper<T>) GET_OBJECT;
            }

            /**
             * Gets the values from the first column.
             *
             * @param <T>
             * @param firstColumnType
             * @return
             */
            public static <T> RowMapper<T> get(final Class<? extends T> firstColumnType) {
                return get(N.typeOf(firstColumnType));
            }

            /**
             * Gets the values from the first column.
             *
             * @param <T>
             * @param type
             * @return
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
             * Convert the JSON string from the first column to instance of target type.
             *
             * @param <T>
             * @param targetType
             * @return
             */
            public static <T> RowMapper<T> readJson(final Class<? extends T> targetType) {
                return rs -> N.fromJson(rs.getString(1), targetType);
            }

            /**
             * Convert the JSON string from the first column to instance of target type.
             *
             * @param <T>
             * @param targetType
             * @return
             */
            public static <T> RowMapper<T> readXml(final Class<? extends T> targetType) {
                return rs -> N.fromXml(rs.getString(1), targetType);
            }

            /**
             *
             *
             * @param <T>
             * @param type
             * @return
             */
            @SuppressWarnings("rawtypes")
            public static <T> BiParametersSetter<AbstractQuery, T> set(final Class<T> type) {
                return set(N.typeOf(type));
            }

            /**
             *
             *
             * @param <T>
             * @param type
             * @return
             */
            @SuppressWarnings("rawtypes")
            public static <T> BiParametersSetter<AbstractQuery, T> set(final Type<T> type) {
                return (preparedQuery, x) -> type.set(preparedQuery.stmt, 1, x);
            }

        }

        //    public static final class ColumnTwo {
        //        public static final RowMapper<Boolean> GET_BOOLEAN = rs -> rs.getBoolean(2);
        //
        //        public static final RowMapper<Byte> GET_BYTE = rs -> rs.getByte(2);
        //
        //        public static final RowMapper<Short> GET_SHORT = rs -> rs.getShort(2);
        //
        //        public static final RowMapper<Integer> GET_INT = rs -> rs.getInt(2);
        //
        //        public static final RowMapper<Long> GET_LONG = rs -> rs.getLong(2);
        //
        //        public static final RowMapper<Float> GET_FLOAT = rs -> rs.getFloat(2);
        //
        //        public static final RowMapper<Double> GET_DOUBLE = rs -> rs.getDouble(2);
        //
        //        public static final RowMapper<BigDecimal> GET_BIG_DECIMAL = rs -> rs.getBigDecimal(2);
        //
        //        public static final RowMapper<String> GET_STRING = rs -> rs.getString(2);
        //
        //        public static final RowMapper<Date> GET_DATE = rs -> rs.getDate(2);
        //
        //        public static final RowMapper<Time> GET_TIME = rs -> rs.getTime(2);
        //
        //        public static final RowMapper<Timestamp> GET_TIMESTAMP = rs -> rs.getTimestamp(2);
        //
        //        public static final RowMapper<byte[]> GET_BYTES = rs -> rs.getBytes(2);
        //
        //        public static final RowMapper<InputStream> GET_BINARY_STREAM = rs -> rs.getBinaryStream(2);
        //
        //        public static final RowMapper<Reader> GET_CHARACTER_STREAM = rs -> rs.getCharacterStream(2);
        //
        //        public static final RowMapper<Blob> GET_BLOB = rs -> rs.getBlob(2);
        //
        //        public static final RowMapper<Clob> GET_CLOB = rs -> rs.getClob(2);
        //
        //        public static final RowMapper<Object> GET_OBJECT = rs -> JdbcUtil.getColumnValue(rs, 2);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Boolean> SET_BOOLEAN = (preparedQuery, x) -> preparedQuery.setBoolean(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Byte> SET_BYTE = (preparedQuery, x) -> preparedQuery.setByte(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Short> SET_SHORT = (preparedQuery, x) -> preparedQuery.setShort(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Integer> SET_INT = (preparedQuery, x) -> preparedQuery.setInt(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Long> SET_LONG = (preparedQuery, x) -> preparedQuery.setLong(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Float> SET_FLOAT = (preparedQuery, x) -> preparedQuery.setFloat(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Double> SET_DOUBLE = (preparedQuery, x) -> preparedQuery.setDouble(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, BigDecimal> SET_BIG_DECIMAL = (preparedQuery, x) -> preparedQuery
        //                .setBigDecimal(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, String> SET_STRING = (preparedQuery, x) -> preparedQuery.setString(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Date> SET_DATE = (preparedQuery, x) -> preparedQuery.setDate(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Time> SET_TIME = (preparedQuery, x) -> preparedQuery.setTime(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Timestamp> SET_TIMESTAMP = (preparedQuery, x) -> preparedQuery.setTimestamp(2,
        //                x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, java.util.Date> SET_DATE_JU = (preparedQuery, x) -> preparedQuery.setDate(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, java.util.Date> SET_TIME_JU = (preparedQuery, x) -> preparedQuery.setTime(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, java.util.Date> SET_TIMESTAMP_JU = (preparedQuery, x) -> preparedQuery
        //                .setTimestamp(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, byte[]> SET_BYTES = (preparedQuery, x) -> preparedQuery.setBytes(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, InputStream> SET_BINARY_STREAM = (preparedQuery, x) -> preparedQuery
        //                .setBinaryStream(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Reader> SET_CHARACTER_STREAM = (preparedQuery, x) -> preparedQuery
        //                .setCharacterStream(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Blob> SET_BLOB = (preparedQuery, x) -> preparedQuery.setBlob(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Clob> SET_CLOB = (preparedQuery, x) -> preparedQuery.setClob(2, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Object> SET_OBJECT = (preparedQuery, x) -> preparedQuery.setObject(2, x);
        //
        //        private ColumnTwo() {
        //            // singleton for utility class
        //        }
        //
        //        @SuppressWarnings("rawtypes")
        //        static final Map<Type<?>, RowMapper> rowMapperPool = new ObjectPool<>(1024);
        //
        //        public static <T> RowMapper<T> getObject() {
        //            return (RowMapper<T>) GET_OBJECT;
        //        }
        //
        //        /**
        //         * Gets the values from the first column.
        //         *
        //         * @param <T>
        //         * @param firstColumnType
        //         * @return
        //         */
        //        public static <T> RowMapper<T> get(final Class<? extends T> firstColumnType) {
        //            return get(N.typeOf(firstColumnType));
        //        }
        //
        //        /**
        //         * Gets the values from the first column.
        //         *
        //         * @param <T>
        //         * @param type
        //         * @return
        //         */
        //        public static <T> RowMapper<T> get(final Type<? extends T> type) {
        //            RowMapper<T> result = rowMapperPool.get(type);
        //
        //            if (result == null) {
        //                result = rs -> type.get(rs, 2);
        //
        //                rowMapperPool.put(type, result);
        //            }
        //
        //            return result;
        //        }
        //
        //        @SuppressWarnings("rawtypes")
        //        public static <T> BiParametersSetter<AbstractQuery, T> set(final Class<T> type) {
        //            return set(N.typeOf(type));
        //        }
        //
        //        @SuppressWarnings("rawtypes")
        //        public static <T> BiParametersSetter<AbstractQuery, T> set(final Type<T> type) {
        //            return (preparedQuery, x) -> type.set(preparedQuery.stmt, 2, x);
        //        }
        //    }
        //
        //    public static final class ColumnThree {
        //        public static final RowMapper<Boolean> GET_BOOLEAN = rs -> rs.getBoolean(3);
        //
        //        public static final RowMapper<Byte> GET_BYTE = rs -> rs.getByte(3);
        //
        //        public static final RowMapper<Short> GET_SHORT = rs -> rs.getShort(3);
        //
        //        public static final RowMapper<Integer> GET_INT = rs -> rs.getInt(3);
        //
        //        public static final RowMapper<Long> GET_LONG = rs -> rs.getLong(3);
        //
        //        public static final RowMapper<Float> GET_FLOAT = rs -> rs.getFloat(3);
        //
        //        public static final RowMapper<Double> GET_DOUBLE = rs -> rs.getDouble(3);
        //
        //        public static final RowMapper<BigDecimal> GET_BIG_DECIMAL = rs -> rs.getBigDecimal(3);
        //
        //        public static final RowMapper<String> GET_STRING = rs -> rs.getString(3);
        //
        //        public static final RowMapper<Date> GET_DATE = rs -> rs.getDate(3);
        //
        //        public static final RowMapper<Time> GET_TIME = rs -> rs.getTime(3);
        //
        //        public static final RowMapper<Timestamp> GET_TIMESTAMP = rs -> rs.getTimestamp(3);
        //
        //        public static final RowMapper<byte[]> GET_BYTES = rs -> rs.getBytes(3);
        //
        //        public static final RowMapper<InputStream> GET_BINARY_STREAM = rs -> rs.getBinaryStream(3);
        //
        //        public static final RowMapper<Reader> GET_CHARACTER_STREAM = rs -> rs.getCharacterStream(3);
        //
        //        public static final RowMapper<Blob> GET_BLOB = rs -> rs.getBlob(3);
        //
        //        public static final RowMapper<Clob> GET_CLOB = rs -> rs.getClob(3);
        //
        //        public static final RowMapper<Object> GET_OBJECT = rs -> JdbcUtil.getColumnValue(rs, 3);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Boolean> SET_BOOLEAN = (preparedQuery, x) -> preparedQuery.setBoolean(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Byte> SET_BYTE = (preparedQuery, x) -> preparedQuery.setByte(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Short> SET_SHORT = (preparedQuery, x) -> preparedQuery.setShort(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Integer> SET_INT = (preparedQuery, x) -> preparedQuery.setInt(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Long> SET_LONG = (preparedQuery, x) -> preparedQuery.setLong(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Float> SET_FLOAT = (preparedQuery, x) -> preparedQuery.setFloat(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Double> SET_DOUBLE = (preparedQuery, x) -> preparedQuery.setDouble(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, BigDecimal> SET_BIG_DECIMAL = (preparedQuery, x) -> preparedQuery
        //                .setBigDecimal(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, String> SET_STRING = (preparedQuery, x) -> preparedQuery.setString(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Date> SET_DATE = (preparedQuery, x) -> preparedQuery.setDate(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Time> SET_TIME = (preparedQuery, x) -> preparedQuery.setTime(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Timestamp> SET_TIMESTAMP = (preparedQuery, x) -> preparedQuery.setTimestamp(3,
        //                x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, java.util.Date> SET_DATE_JU = (preparedQuery, x) -> preparedQuery.setDate(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, java.util.Date> SET_TIME_JU = (preparedQuery, x) -> preparedQuery.setTime(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, java.util.Date> SET_TIMESTAMP_JU = (preparedQuery, x) -> preparedQuery
        //                .setTimestamp(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, byte[]> SET_BYTES = (preparedQuery, x) -> preparedQuery.setBytes(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, InputStream> SET_BINARY_STREAM = (preparedQuery, x) -> preparedQuery
        //                .setBinaryStream(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Reader> SET_CHARACTER_STREAM = (preparedQuery, x) -> preparedQuery
        //                .setCharacterStream(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Blob> SET_BLOB = (preparedQuery, x) -> preparedQuery.setBlob(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Clob> SET_CLOB = (preparedQuery, x) -> preparedQuery.setClob(3, x);
        //
        //        @SuppressWarnings("rawtypes")
        //        public static final BiParametersSetter<AbstractQuery, Object> SET_OBJECT = (preparedQuery, x) -> preparedQuery.setObject(3, x);
        //
        //        private ColumnThree() {
        //            // singleton for utility class
        //        }
        //
        //        @SuppressWarnings("rawtypes")
        //        static final Map<Type<?>, RowMapper> rowMapperPool = new ObjectPool<>(1024);
        //
        //        public static <T> RowMapper<T> getObject() {
        //            return (RowMapper<T>) GET_OBJECT;
        //        }
        //
        //        /**
        //         * Gets the values from the first column.
        //         *
        //         * @param <T>
        //         * @param firstColumnType
        //         * @return
        //         */
        //        public static <T> RowMapper<T> get(final Class<? extends T> firstColumnType) {
        //            return get(N.typeOf(firstColumnType));
        //        }
        //
        //        /**
        //         * Gets the values from the first column.
        //         *
        //         * @param <T>
        //         * @param type
        //         * @return
        //         */
        //        public static <T> RowMapper<T> get(final Type<? extends T> type) {
        //            RowMapper<T> result = rowMapperPool.get(type);
        //
        //            if (result == null) {
        //                result = rs -> type.get(rs, 3);
        //
        //                rowMapperPool.put(type, result);
        //            }
        //
        //            return result;
        //        }
        //
        //        @SuppressWarnings("rawtypes")
        //        public static <T> BiParametersSetter<AbstractQuery, T> set(final Class<T> type) {
        //            return set(N.typeOf(type));
        //        }
        //
        //        @SuppressWarnings("rawtypes")
        //        public static <T> BiParametersSetter<AbstractQuery, T> set(final Type<T> type) {
        //            return (preparedQuery, x) -> type.set(preparedQuery.stmt, 3, x);
        //        }
        //    }
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @Data
    public static final class OutParam {
        private int parameterIndex;
        private String parameterName;
        private int sqlType;
        private String typeName;
        private int scale;
    }

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
         *
         *
         * @param <T>
         * @param parameterIndex
         * @return
         */
        public <T> T getOutParamValue(final int parameterIndex) {
            return (T) outParamValues.get(parameterIndex);
        }

        /**
         *
         *
         * @param <T>
         * @param parameterName
         * @return
         */
        public <T> T getOutParamValue(final String parameterName) {
            return (T) outParamValues.get(parameterName);
        }

        /**
         *
         *
         * @return
         */
        public Map<Object, Object> getOutParamValues() {
            return outParamValues;
        }

        /**
         *
         *
         * @return
         */
        public List<OutParam> getOutParams() {
            return outParams;
        }
    }

    @Beta
    public interface Handler<P> {
        /**
         *
         * @param proxy
         * @param args
         * @param methodSignature The first element is {@code Method}, The second element is {@code parameterTypes}(it will be an empty Class<?> List if there is no parameter), the third element is {@code returnType}
         */
        @SuppressWarnings("unused")
        default void beforeInvoke(final P proxy, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            // empty action.
        }

        /**
         *
         *
         * @param result
         * @param proxy
         * @param args
         * @param methodSignature The first element is {@code Method}, The second element is {@code parameterTypes}(it will be an empty Class<?> List if there is no parameter), the third element is {@code returnType}
         */
        @SuppressWarnings("unused")
        default void afterInvoke(final Object result, final P proxy, final Object[] args, Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
            // empty action.
        }
    }

    public final class HandlerFactory {

        @SuppressWarnings("rawtypes")
        static final Handler EMPTY = new Handler() {
            // Do nothing.
        };

        private static final Map<String, Handler<?>> handlerPool = new ConcurrentHashMap<>();
        private static final SpringApplicationContext spingAppContext;

        static {
            handlerPool.put(ClassUtil.getCanonicalClassName(Handler.class), EMPTY);
            handlerPool.put(ClassUtil.getClassName(EMPTY.getClass()), EMPTY);

            SpringApplicationContext tmp = null;

            try {
                tmp = new SpringApplicationContext();
            } catch (Throwable e) {
                // ignore.
            }

            spingAppContext = tmp;
        }

        /**
         * 
         *
         * @param handlerClass 
         * @return 
         * @throws IllegalArgumentException 
         */
        public static boolean register(final Class<? extends Handler<?>> handlerClass) throws IllegalArgumentException {
            N.checkArgNotNull(handlerClass, "handlerClass");

            return register(N.newInstance(handlerClass));
        }

        /**
         * 
         *
         * @param handler 
         * @return 
         * @throws IllegalArgumentException 
         */
        public static boolean register(final Handler<?> handler) throws IllegalArgumentException {
            N.checkArgNotNull(handler, "handler");

            return register(ClassUtil.getCanonicalClassName(handler.getClass()), handler);
        }

        /**
         * 
         *
         * @param qualifier 
         * @param handler 
         * @return 
         * @throws IllegalArgumentException 
         */
        public static boolean register(final String qualifier, final Handler<?> handler) throws IllegalArgumentException {
            N.checkArgNotEmpty(qualifier, "qualifier");
            N.checkArgNotNull(handler, "handler");

            if (handlerPool.containsKey(qualifier)) {
                return false;
            }

            handlerPool.put(qualifier, handler);

            return true;
        }

        /**
         *
         *
         * @param qualifier
         * @return
         */
        public static Handler<?> get(final String qualifier) { //NOSONAR
            N.checkArgNotEmpty(qualifier, "qualifier");

            Handler<?> result = handlerPool.get(qualifier);

            if (result == null && spingAppContext != null) {
                Object bean = spingAppContext.getBean(qualifier);

                if (bean instanceof Handler) {
                    result = (Handler<?>) bean;

                    handlerPool.put(qualifier, result);
                }
            }

            return result;
        }

        /**
         *
         *
         * @param handlerClass
         * @return
         */
        public static Handler<?> get(final Class<? extends Handler<?>> handlerClass) { //NOSONAR
            N.checkArgNotNull(handlerClass, "handlerClass");

            final String qualifier = ClassUtil.getCanonicalClassName(handlerClass);

            Handler<?> result = handlerPool.get(qualifier);

            if (result == null && spingAppContext != null) {
                result = spingAppContext.getBean(handlerClass);

                if (result == null) {
                    Object bean = spingAppContext.getBean(qualifier);

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
         *
         *
         * @param handlerClass
         * @return
         */
        public static Handler<?> getOrCreate(final Class<? extends Handler<?>> handlerClass) { //NOSONAR
            N.checkArgNotNull(handlerClass, "handlerClass");

            Handler<?> result = get(handlerClass);

            if (result == null) {
                try {
                    result = N.newInstance(handlerClass);

                    if (result != null) {
                        register(result);
                    }
                } catch (Throwable e) {
                    // ignore
                }
            }

            return result;
        }

        /**
         * 
         *
         * @param <T> 
         * @param <E> 
         * @param beforeInvokeAction 
         * @return 
         * @throws IllegalArgumentException 
         */
        public static <T, E extends RuntimeException> Handler<T> create(
                final Throwables.TriConsumer<T, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, E> beforeInvokeAction)
                throws IllegalArgumentException {
            N.checkArgNotNull(beforeInvokeAction, "beforeInvokeAction");

            return new Handler<>() {
                @Override
                public void beforeInvoke(final T targetObject, final Object[] args, final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {
                    beforeInvokeAction.accept(targetObject, args, methodSignature);
                }
            };
        }

        /**
         * 
         *
         * @param <T> 
         * @param <E> 
         * @param afterInvokeAction 
         * @return 
         * @throws IllegalArgumentException 
         */
        public static <T, E extends RuntimeException> Handler<T> create(
                final Throwables.QuadConsumer<Object, T, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, E> afterInvokeAction)
                throws IllegalArgumentException {
            N.checkArgNotNull(afterInvokeAction, "afterInvokeAction");

            return new Handler<>() {
                @Override
                public void afterInvoke(final Object result, final T targetObject, final Object[] args,
                        final Tuple3<Method, ImmutableList<Class<?>>, Class<?>> methodSignature) {

                    afterInvokeAction.accept(result, targetObject, args, methodSignature);
                }
            };
        }

        /**
         * 
         *
         * @param <T> 
         * @param <E> 
         * @param beforeInvokeAction 
         * @param afterInvokeAction 
         * @return 
         * @throws IllegalArgumentException 
         */
        public static <T, E extends RuntimeException> Handler<T> create(
                final Throwables.TriConsumer<T, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, E> beforeInvokeAction,
                final Throwables.QuadConsumer<Object, T, Object[], Tuple3<Method, ImmutableList<Class<?>>, Class<?>>, E> afterInvokeAction)
                throws IllegalArgumentException {
            N.checkArgNotNull(beforeInvokeAction, "beforeInvokeAction");
            N.checkArgNotNull(afterInvokeAction, "afterInvokeAction");

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

    static <K, V> void merge(Map<K, V> map, K key, V value, BinaryOperator<V> remappingFunction) {
        final V oldValue = map.get(key);

        if (oldValue == null && !map.containsKey(key)) {
            map.put(key, value);
        } else {
            map.put(key, remappingFunction.apply(oldValue, value));
        }
    }

    static String checkPrefix(final BeanInfo entityInfo, final String columnName, final Map<String, String> prefixAndFieldNameMap,
            final List<String> columnLabelList) {

        final int idx = columnName.indexOf('.');

        if (idx <= 0) {
            return columnName;
        }

        final String prefix = columnName.substring(0, idx);
        PropInfo propInfo = entityInfo.getPropInfo(prefix);

        if (propInfo != null) {
            return columnName;
        }

        if (N.notEmpty(prefixAndFieldNameMap) && prefixAndFieldNameMap.containsKey(prefix)) {
            propInfo = entityInfo.getPropInfo(prefixAndFieldNameMap.get(prefix));

            if (propInfo != null) {
                return propInfo.name + columnName.substring(idx);
            }
        }

        propInfo = entityInfo.getPropInfo(prefix + "s"); // Trying to do something smart?
        final int len = prefix.length() + 1;

        if (propInfo != null && (propInfo.type.isBean() || (propInfo.type.isCollection() && propInfo.type.getElementType().isBean()))
                && N.noneMatch(columnLabelList, it -> it.length() > len && it.charAt(len) == '.' && Strings.startsWithIgnoreCase(it, prefix + "s."))) {
            // good
        } else {
            propInfo = entityInfo.getPropInfo(prefix + "es"); // Trying to do something smart?
            final int len2 = prefix.length() + 2;

            if (propInfo != null && (propInfo.type.isBean() || (propInfo.type.isCollection() && propInfo.type.getElementType().isBean()))
                    && N.noneMatch(columnLabelList, it -> it.length() > len2 && it.charAt(len2) == '.' && Strings.startsWithIgnoreCase(it, prefix + "es."))) {
                // good
            } else {
                // Sorry, have done all I can do.
                propInfo = null;
            }
        }

        if (propInfo != null) {
            return propInfo.name + columnName.substring(idx);
        }

        return columnName;
    }

    //    // TODO it will be removed after below logic is handled in RowDataSet.
    //    static List<String> checkMergedByIds(final BeanInfo entityInfo, final DataSet dataSet, final List<String> mergedByIds) {
    //        if (dataSet.containsAllColumns(mergedByIds)) {
    //            return mergedByIds;
    //        }
    //
    //        final List<String> columnNameList = dataSet.columnNameList();
    //        final List<String> tmp = new ArrayList<>(mergedByIds.size());
    //        PropInfo propInfo = null;
    //
    //        outer: for (String idPropName : mergedByIds) {
    //            if (columnNameList.contains(idPropName)) {
    //                tmp.add(idPropName);
    //            } else {
    //                propInfo = entityInfo.getPropInfo(idPropName);
    //
    //                if (propInfo != null && propInfo.columnName.isPresent() && columnNameList.contains(propInfo.columnName.get())) {
    //                    tmp.add(propInfo.columnName.get());
    //                } else {
    //                    for (String columnName : columnNameList) {
    //                        if (columnName.equalsIgnoreCase(idPropName)) {
    //                            tmp.add(columnName);
    //
    //                            continue outer;
    //                        }
    //                    }
    //
    //                    if (propInfo != null) {
    //                        for (String columnName : columnNameList) {
    //                            if (propInfo.equals(entityInfo.getPropInfo(columnName))) {
    //                                tmp.add(columnName);
    //
    //                                continue outer;
    //                            }
    //                        }
    //                    }
    //
    //                    return mergedByIds;
    //                }
    //            }
    //        }
    //
    //        if (columnNameList.containsAll(tmp)) {
    //            return tmp;
    //        }
    //
    //        return mergedByIds;
    //    }
}
