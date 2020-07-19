/*
 * Copyright (c) 2015, Haiyang Li.
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
package com.landawn.abacus.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.landawn.abacus.DataSet;
import com.landawn.abacus.core.RowDataSet;
import com.landawn.abacus.exception.UncheckedIOException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.logging.Logger;
import com.landawn.abacus.logging.LoggerFactory;
import com.landawn.abacus.parser.JSONDeserializationConfig;
import com.landawn.abacus.parser.JSONDeserializationConfig.JDC;
import com.landawn.abacus.parser.JSONParser;
import com.landawn.abacus.parser.JSONSerializationConfig;
import com.landawn.abacus.parser.JSONSerializationConfig.JSC;
import com.landawn.abacus.parser.ParserFactory;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.EntityInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.type.Type;

/**
 *
 * @author Haiyang Li
 * @since 0.8
 */
public final class CSVUtil {

    private static final Logger logger = LoggerFactory.getLogger(CSVUtil.class);

    private static final int DEFAULT_BATCH_SIZE = JdbcUtil.DEFAULT_BATCH_SIZE;
    private static final int DEFAULT_FETCH_SIZE = JdbcUtil.DEFAULT_BATCH_SIZE;

    private static final JSONParser jsonParser = ParserFactory.createJSONParser();

    private static final JSONDeserializationConfig jdc = JDC.create().setElementType(String.class);

    /**
     *
     * @param csvFile
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static DataSet loadCSV(final File csvFile) throws UncheckedIOException {
        return loadCSV(csvFile, (Collection<String>) null);
    }

    /**
     *
     * @param csvFile
     * @param selectColumnNames
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static DataSet loadCSV(final File csvFile, final Collection<String> selectColumnNames) throws UncheckedIOException {
        return loadCSV(csvFile, selectColumnNames, 0, Long.MAX_VALUE);
    }

    /**
     *
     * @param csvFile
     * @param selectColumnNames
     * @param offset
     * @param count
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static DataSet loadCSV(final File csvFile, final Collection<String> selectColumnNames, final long offset, final long count)
            throws UncheckedIOException {
        return loadCSV(csvFile, selectColumnNames, offset, count, Fn.<String[]> alwaysTrue());
    }

    /**
     * Load the data from CSV.
     *
     * @param <E>
     * @param csvFile
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param filter
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    public static <E extends Exception> DataSet loadCSV(final File csvFile, final Collection<String> selectColumnNames, final long offset, final long count,
            final Throwables.Predicate<String[], E> filter) throws UncheckedIOException, E {
        InputStream csvInputStream = null;

        try {
            csvInputStream = new FileInputStream(csvFile);

            return loadCSV(csvInputStream, selectColumnNames, offset, count, filter);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            IOUtil.closeQuietly(csvInputStream);
        }
    }

    /**
     *
     * @param csvInputStream
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static DataSet loadCSV(final InputStream csvInputStream) throws UncheckedIOException {
        return loadCSV(csvInputStream, (Collection<String>) null);
    }

    /**
     *
     * @param csvInputStream
     * @param selectColumnNames
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static DataSet loadCSV(final InputStream csvInputStream, final Collection<String> selectColumnNames) throws UncheckedIOException {
        return loadCSV(csvInputStream, selectColumnNames, 0, Long.MAX_VALUE);
    }

    /**
     *
     * @param csvInputStream
     * @param selectColumnNames
     * @param offset
     * @param count
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static DataSet loadCSV(final InputStream csvInputStream, final Collection<String> selectColumnNames, final long offset, final long count)
            throws UncheckedIOException {
        return loadCSV(csvInputStream, selectColumnNames, offset, count, Fn.<String[]> alwaysTrue());
    }

    /**
     * Load the data from CSV.
     *
     * @param <E>
     * @param csvInputStream
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param filter
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    public static <E extends Exception> DataSet loadCSV(final InputStream csvInputStream, final Collection<String> selectColumnNames, final long offset,
            final long count, final Throwables.Predicate<String[], E> filter) throws UncheckedIOException, E {
        final Reader csvReader = new InputStreamReader(csvInputStream);

        return loadCSV(csvReader, selectColumnNames, offset, count, filter);
    }

    /**
     *
     * @param csvReader
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static DataSet loadCSV(final Reader csvReader) throws UncheckedIOException {
        return loadCSV(csvReader, (Collection<String>) null);
    }

    /**
     *
     * @param csvReader
     * @param selectColumnNames
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static DataSet loadCSV(final Reader csvReader, final Collection<String> selectColumnNames) throws UncheckedIOException {
        return loadCSV(csvReader, selectColumnNames, 0, Long.MAX_VALUE);
    }

    /**
     *
     * @param csvReader
     * @param selectColumnNames
     * @param offset
     * @param count
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static DataSet loadCSV(final Reader csvReader, final Collection<String> selectColumnNames, long offset, long count) throws UncheckedIOException {
        return loadCSV(csvReader, selectColumnNames, offset, count, Fn.<String[]> alwaysTrue());
    }

    /**
     * Load the data from CSV.
     *
     * @param <E>
     * @param csvReader
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param filter
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    public static <E extends Exception> DataSet loadCSV(final Reader csvReader, final Collection<String> selectColumnNames, long offset, long count,
            final Throwables.Predicate<String[], E> filter) throws UncheckedIOException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);

        final BufferedReader br = csvReader instanceof BufferedReader ? (BufferedReader) csvReader : Objectory.createBufferedReader(csvReader);

        try {
            List<String> tmp = new ArrayList<>();
            String line = br.readLine();
            jsonParser.readString(tmp, line, jdc);
            final String[] titles = tmp.toArray(new String[tmp.size()]);

            final int columnCount = titles.length;
            final Type<?>[] columnTypes = new Type<?>[columnCount];
            final List<String> columnNameList = new ArrayList<>(selectColumnNames == null ? columnCount : selectColumnNames.size());
            final List<List<Object>> columnList = new ArrayList<>(selectColumnNames == null ? columnCount : selectColumnNames.size());
            final Set<String> selectPropNameSet = selectColumnNames == null ? null : N.newHashSet(selectColumnNames);

            for (int i = 0; i < columnCount; i++) {
                if (selectPropNameSet == null || selectPropNameSet.remove(titles[i])) {
                    columnNameList.add(titles[i]);
                    columnList.add(new ArrayList<>());
                    columnTypes[i] = N.typeOf(String.class);
                }
            }

            if (selectPropNameSet != null && selectPropNameSet.size() > 0) {
                throw new IllegalArgumentException(selectPropNameSet + " are not included in titles: " + N.toString(titles));
            }

            final String[] strs = new String[titles.length];

            while (offset-- > 0 && br.readLine() != null) {
            }

            while (count > 0 && (line = br.readLine()) != null) {
                jsonParser.readString(strs, line, jdc);

                if (filter != null && filter.test(strs) == false) {
                    continue;
                }

                for (int i = 0, columnIndex = 0; i < columnCount; i++) {
                    if (columnTypes[i] != null) {
                        columnList.get(columnIndex++).add(strs[i]);
                    }
                }

                N.fill(strs, null);

                count--;
            }

            return new RowDataSet(columnNameList, columnList);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (br != csvReader) {
                Objectory.recycle(br);
            }
        }
    }

    /**
     *
     * @param entityClass
     * @param csvFile
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static DataSet loadCSV(final Class<?> entityClass, final File csvFile) throws UncheckedIOException {
        return loadCSV(entityClass, csvFile, null);
    }

    /**
     *
     * @param entityClass
     * @param csvFile
     * @param selectColumnNames
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static DataSet loadCSV(final Class<?> entityClass, final File csvFile, final Collection<String> selectColumnNames) throws UncheckedIOException {
        return loadCSV(entityClass, csvFile, selectColumnNames, 0, Long.MAX_VALUE);
    }

    /**
     *
     * @param entityClass
     * @param csvFile
     * @param selectColumnNames
     * @param offset
     * @param count
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static DataSet loadCSV(final Class<?> entityClass, final File csvFile, final Collection<String> selectColumnNames, final long offset,
            final long count) throws UncheckedIOException {
        return loadCSV(entityClass, csvFile, selectColumnNames, offset, count, Fn.<String[]> alwaysTrue());
    }

    /**
     * Load the data from CSV.
     *
     * @param <E>
     * @param entityClass
     * @param csvFile
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param filter
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    public static <E extends Exception> DataSet loadCSV(final Class<?> entityClass, final File csvFile, final Collection<String> selectColumnNames,
            final long offset, final long count, final Throwables.Predicate<String[], E> filter) throws UncheckedIOException, E {
        InputStream csvInputStream = null;

        try {
            csvInputStream = new FileInputStream(csvFile);

            return loadCSV(entityClass, csvInputStream, selectColumnNames, offset, count, filter);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            IOUtil.closeQuietly(csvInputStream);
        }
    }

    /**
     *
     * @param entityClass
     * @param csvInputStream
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static DataSet loadCSV(final Class<?> entityClass, final InputStream csvInputStream) throws UncheckedIOException {
        return loadCSV(entityClass, csvInputStream, null);
    }

    /**
     *
     * @param entityClass
     * @param csvInputStream
     * @param selectColumnNames
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static DataSet loadCSV(final Class<?> entityClass, final InputStream csvInputStream, final Collection<String> selectColumnNames)
            throws UncheckedIOException {
        return loadCSV(entityClass, csvInputStream, selectColumnNames, 0, Long.MAX_VALUE);
    }

    /**
     *
     * @param entityClass
     * @param csvInputStream
     * @param selectColumnNames
     * @param offset
     * @param count
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static DataSet loadCSV(final Class<?> entityClass, final InputStream csvInputStream, final Collection<String> selectColumnNames, final long offset,
            final long count) throws UncheckedIOException {
        return loadCSV(entityClass, csvInputStream, selectColumnNames, offset, count, Fn.<String[]> alwaysTrue());
    }

    /**
     * Load the data from CSV.
     *
     * @param <E>
     * @param entityClass
     * @param csvInputStream
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param filter
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    public static <E extends Exception> DataSet loadCSV(final Class<?> entityClass, final InputStream csvInputStream,
            final Collection<String> selectColumnNames, final long offset, final long count, final Throwables.Predicate<String[], E> filter)
            throws UncheckedIOException, E {
        final Reader csvReader = new InputStreamReader(csvInputStream);
        return loadCSV(entityClass, csvReader, selectColumnNames, offset, count, filter);
    }

    /**
     *
     * @param entityClass
     * @param csvReader
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static DataSet loadCSV(final Class<?> entityClass, final Reader csvReader) throws UncheckedIOException {
        return loadCSV(entityClass, csvReader, null);
    }

    /**
     *
     * @param entityClass
     * @param csvReader
     * @param selectColumnNames
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static DataSet loadCSV(final Class<?> entityClass, final Reader csvReader, final Collection<String> selectColumnNames) throws UncheckedIOException {
        return loadCSV(entityClass, csvReader, selectColumnNames, 0, Long.MAX_VALUE);
    }

    /**
     *
     * @param entityClass
     * @param csvReader
     * @param selectColumnNames
     * @param offset
     * @param count
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static DataSet loadCSV(final Class<?> entityClass, final Reader csvReader, final Collection<String> selectColumnNames, long offset, long count)
            throws UncheckedIOException {
        return loadCSV(entityClass, csvReader, selectColumnNames, offset, count, Fn.<String[]> alwaysTrue());
    }

    /**
     * Load the data from CSV.
     *
     * @param <E>
     * @param entityClass
     * @param csvReader
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param filter
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    public static <E extends Exception> DataSet loadCSV(final Class<?> entityClass, final Reader csvReader, final Collection<String> selectColumnNames,
            long offset, long count, final Throwables.Predicate<String[], E> filter) throws UncheckedIOException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);

        final BufferedReader br = csvReader instanceof BufferedReader ? (BufferedReader) csvReader : Objectory.createBufferedReader(csvReader);
        final EntityInfo entityInfo = ParserUtil.getEntityInfo(entityClass);

        try {
            List<String> tmp = new ArrayList<>();
            String line = br.readLine();
            jsonParser.readString(tmp, line, jdc);
            final String[] titles = tmp.toArray(new String[tmp.size()]);

            final int columnCount = titles.length;
            final Type<?>[] columnTypes = new Type<?>[columnCount];
            final List<String> columnNameList = new ArrayList<>(selectColumnNames == null ? columnCount : selectColumnNames.size());
            final List<List<Object>> columnList = new ArrayList<>(selectColumnNames == null ? columnCount : selectColumnNames.size());
            final Set<String> selectPropNameSet = selectColumnNames == null ? null : N.newHashSet(selectColumnNames);

            for (int i = 0; i < columnCount; i++) {
                if (selectPropNameSet == null || selectPropNameSet.remove(titles[i])) {
                    PropInfo propInfo = entityInfo.getPropInfo(titles[i]);

                    if (propInfo == null && selectPropNameSet != null) {
                        throw new IllegalArgumentException(titles[i] + " is not defined in entity class: " + ClassUtil.getCanonicalClassName(entityClass));
                    }

                    if (propInfo != null) {
                        columnTypes[i] = propInfo.jsonXmlType;
                        columnNameList.add(titles[i]);
                        columnList.add(new ArrayList<>());
                    }
                }
            }

            if (selectPropNameSet != null && selectPropNameSet.size() > 0) {
                throw new IllegalArgumentException(selectColumnNames + " are not included in titles: " + N.toString(titles));
            }

            final String[] strs = new String[titles.length];

            while (offset-- > 0 && br.readLine() != null) {
            }

            while (count > 0 && (line = br.readLine()) != null) {
                jsonParser.readString(strs, line, jdc);

                if (filter != null && filter.test(strs) == false) {
                    continue;
                }

                for (int i = 0, columnIndex = 0; i < columnCount; i++) {
                    if (columnTypes[i] != null) {
                        columnList.get(columnIndex++).add(columnTypes[i].valueOf(strs[i]));
                    }
                }

                N.fill(strs, null);

                count--;
            }

            return new RowDataSet(columnNameList, columnList);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (br != csvReader) {
                Objectory.recycle(br);
            }
        }
    }

    /**
     *
     * @param csvFile
     * @param columnTypeMap
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static DataSet loadCSV(final File csvFile, final Map<String, ? extends Type> columnTypeMap) throws UncheckedIOException {
        return loadCSV(csvFile, 0, Long.MAX_VALUE, columnTypeMap);
    }

    /**
     *
     * @param csvFile
     * @param offset
     * @param count
     * @param columnTypeMap
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static DataSet loadCSV(final File csvFile, final long offset, final long count, final Map<String, ? extends Type> columnTypeMap)
            throws UncheckedIOException {
        return loadCSV(csvFile, offset, count, Fn.<String[]> alwaysTrue(), columnTypeMap);
    }

    /**
     * Load the data from CSV.
     *
     * @param <E>
     * @param csvFile
     * @param offset
     * @param count
     * @param filter
     * @param columnTypeMap
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> DataSet loadCSV(final File csvFile, final long offset, final long count, final Throwables.Predicate<String[], E> filter,
            final Map<String, ? extends Type> columnTypeMap) throws UncheckedIOException, E {
        InputStream csvInputStream = null;

        try {
            csvInputStream = new FileInputStream(csvFile);

            return loadCSV(csvInputStream, offset, count, filter, columnTypeMap);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            IOUtil.closeQuietly(csvInputStream);
        }
    }

    /**
     *
     * @param csvInputStream
     * @param columnTypeMap
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static DataSet loadCSV(final InputStream csvInputStream, final Map<String, ? extends Type> columnTypeMap) throws UncheckedIOException {
        return loadCSV(csvInputStream, 0, Long.MAX_VALUE, columnTypeMap);
    }

    /**
     *
     * @param csvInputStream
     * @param offset
     * @param count
     * @param columnTypeMap
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static DataSet loadCSV(final InputStream csvInputStream, final long offset, final long count, final Map<String, ? extends Type> columnTypeMap)
            throws UncheckedIOException {
        return loadCSV(csvInputStream, offset, count, Fn.<String[]> alwaysTrue(), columnTypeMap);
    }

    /**
     * Load the data from CSV.
     *
     * @param <E>
     * @param csvInputStream
     * @param offset
     * @param count
     * @param filter
     * @param columnTypeMap
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> DataSet loadCSV(final InputStream csvInputStream, final long offset, final long count,
            final Throwables.Predicate<String[], E> filter, final Map<String, ? extends Type> columnTypeMap) throws UncheckedIOException, E {
        final Reader csvReader = new InputStreamReader(csvInputStream);

        return loadCSV(csvReader, offset, count, filter, columnTypeMap);
    }

    /**
     *
     * @param csvReader
     * @param columnTypeMap
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static DataSet loadCSV(final Reader csvReader, final Map<String, ? extends Type> columnTypeMap) throws UncheckedIOException {
        return loadCSV(csvReader, 0, Long.MAX_VALUE, columnTypeMap);
    }

    /**
     *
     * @param csvReader
     * @param offset
     * @param count
     * @param columnTypeMap
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static DataSet loadCSV(final Reader csvReader, long offset, long count, final Map<String, ? extends Type> columnTypeMap)
            throws UncheckedIOException {
        return loadCSV(csvReader, offset, count, Fn.<String[]> alwaysTrue(), columnTypeMap);
    }

    /**
     * Load the data from CSV.
     *
     * @param <E>
     * @param csvReader
     * @param offset
     * @param count
     * @param filter
     * @param columnTypeMap
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> DataSet loadCSV(final Reader csvReader, long offset, long count, final Throwables.Predicate<String[], E> filter,
            final Map<String, ? extends Type> columnTypeMap) throws UncheckedIOException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);

        if (N.isNullOrEmpty(columnTypeMap)) {
            throw new IllegalArgumentException("columnTypeMap can't be null or empty");
        }

        final BufferedReader br = csvReader instanceof BufferedReader ? (BufferedReader) csvReader : Objectory.createBufferedReader(csvReader);

        try {
            List<String> tmp = new ArrayList<>();
            String line = br.readLine();
            jsonParser.readString(tmp, line, jdc);
            final String[] titles = tmp.toArray(new String[tmp.size()]);

            final int columnCount = titles.length;
            final Type<?>[] columnTypes = new Type<?>[columnCount];
            final List<String> columnNameList = new ArrayList<>(columnTypeMap.size());
            final List<List<Object>> columnList = new ArrayList<>(columnTypeMap.size());

            for (int i = 0; i < columnCount; i++) {
                if (columnTypeMap.containsKey(titles[i])) {
                    columnTypes[i] = columnTypeMap.get(titles[i]);
                    columnNameList.add(titles[i]);
                    columnList.add(new ArrayList<>());
                }
            }

            if (columnNameList.size() != columnTypeMap.size()) {
                final List<String> keys = new ArrayList<>(columnTypeMap.keySet());
                keys.removeAll(columnNameList);
                throw new IllegalArgumentException(keys + " are not included in titles: " + N.toString(titles));
            }

            final String[] strs = new String[titles.length];

            while (offset-- > 0 && br.readLine() != null) {
            }

            while (count > 0 && (line = br.readLine()) != null) {
                jsonParser.readString(strs, line, jdc);

                if (filter != null && filter.test(strs) == false) {
                    continue;
                }

                for (int i = 0, columnIndex = 0; i < columnCount; i++) {
                    if (columnTypes[i] != null) {
                        columnList.get(columnIndex++).add(columnTypes[i].valueOf(strs[i]));
                    }
                }

                N.fill(strs, null);

                count--;
            }

            return new RowDataSet(columnNameList, columnList);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (br != csvReader) {
                Objectory.recycle(br);
            }
        }
    }

    /**
     *
     * @param csvFile
     * @param columnTypeList
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static DataSet loadCSV(final File csvFile, final List<? extends Type> columnTypeList) throws UncheckedIOException {
        return loadCSV(csvFile, 0, Long.MAX_VALUE, columnTypeList);
    }

    /**
     *
     * @param csvFile
     * @param offset
     * @param count
     * @param columnTypeList
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static DataSet loadCSV(final File csvFile, final long offset, final long count, final List<? extends Type> columnTypeList)
            throws UncheckedIOException {
        return loadCSV(csvFile, offset, count, Fn.<String[]> alwaysTrue(), columnTypeList);
    }

    /**
     * Load the data from CSV.
     *
     * @param <E>
     * @param csvFile
     * @param offset
     * @param count
     * @param filter
     * @param columnTypeList set the column type to null to skip the column in CSV.
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> DataSet loadCSV(final File csvFile, final long offset, final long count, final Throwables.Predicate<String[], E> filter,
            final List<? extends Type> columnTypeList) throws UncheckedIOException, E {
        InputStream csvInputStream = null;

        try {
            csvInputStream = new FileInputStream(csvFile);

            return loadCSV(csvInputStream, offset, count, filter, columnTypeList);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            IOUtil.closeQuietly(csvInputStream);
        }
    }

    /**
     *
     * @param csvInputStream
     * @param columnTypeList
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static DataSet loadCSV(final InputStream csvInputStream, final List<? extends Type> columnTypeList) throws UncheckedIOException {
        return loadCSV(csvInputStream, 0, Long.MAX_VALUE, columnTypeList);
    }

    /**
     *
     * @param csvInputStream
     * @param offset
     * @param count
     * @param columnTypeList
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static DataSet loadCSV(final InputStream csvInputStream, final long offset, final long count, final List<? extends Type> columnTypeList)
            throws UncheckedIOException {
        return loadCSV(csvInputStream, offset, count, Fn.<String[]> alwaysTrue(), columnTypeList);
    }

    /**
     * Load the data from CSV.
     *
     * @param <E>
     * @param csvInputStream
     * @param offset
     * @param count
     * @param filter
     * @param columnTypeList set the column type to null to skip the column in CSV.
     * @return
     * @throws E the e
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> DataSet loadCSV(final InputStream csvInputStream, final long offset, final long count,
            final Throwables.Predicate<String[], E> filter, final List<? extends Type> columnTypeList) throws E {
        final Reader csvReader = new InputStreamReader(csvInputStream);

        return loadCSV(csvReader, offset, count, filter, columnTypeList);
    }

    /**
     *
     * @param csvReader
     * @param columnTypeList
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static DataSet loadCSV(final Reader csvReader, final List<? extends Type> columnTypeList) {
        return loadCSV(csvReader, 0, Long.MAX_VALUE, columnTypeList);
    }

    /**
     *
     * @param csvReader
     * @param offset
     * @param count
     * @param columnTypeList
     * @return
     */
    @SuppressWarnings("rawtypes")
    public static DataSet loadCSV(final Reader csvReader, long offset, long count, final List<? extends Type> columnTypeList) {
        return loadCSV(csvReader, offset, count, Fn.<String[]> alwaysTrue(), columnTypeList);
    }

    /**
     * Load the data from CSV.
     *
     * @param <E>
     * @param csvReader
     * @param offset
     * @param count
     * @param filter
     * @param columnTypeList set the column type to null to skip the column in CSV.
     * @return
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> DataSet loadCSV(final Reader csvReader, long offset, long count, final Throwables.Predicate<String[], E> filter,
            final List<? extends Type> columnTypeList) throws UncheckedIOException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);

        if (N.isNullOrEmpty(columnTypeList)) {
            throw new IllegalArgumentException("columnTypeList can't be null or empty");
        }

        final BufferedReader br = csvReader instanceof BufferedReader ? (BufferedReader) csvReader : Objectory.createBufferedReader(csvReader);
        final Type<?>[] columnTypes = columnTypeList.toArray(new Type[columnTypeList.size()]);

        try {
            List<String> tmp = new ArrayList<>();
            String line = br.readLine();
            jsonParser.readString(tmp, line, jdc);
            final String[] titles = tmp.toArray(new String[tmp.size()]);

            final int columnCount = titles.length;
            final List<String> columnNameList = new ArrayList<>(columnCount);
            final List<List<Object>> columnList = new ArrayList<>();

            for (int i = 0; i < columnCount; i++) {
                if (columnTypes[i] != null) {
                    columnNameList.add(titles[i]);
                    columnList.add(new ArrayList<>());
                }
            }

            final String[] strs = new String[titles.length];

            while (offset-- > 0 && br.readLine() != null) {
            }

            while (count > 0 && (line = br.readLine()) != null) {
                jsonParser.readString(strs, line, jdc);

                if (filter != null && filter.test(strs) == false) {
                    continue;
                }

                for (int i = 0, columnIndex = 0; i < columnCount; i++) {
                    if (columnTypes[i] != null) {
                        columnList.get(columnIndex++).add(columnTypes[i].valueOf(strs[i]));
                    }
                }

                N.fill(strs, null);

                count--;
            }

            return new RowDataSet(columnNameList, columnList);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (br != csvReader) {
                Objectory.recycle(br);
            }
        }
    }

    /**
     * Exports the data from database to CVS. Title will be added at the first line and columns will be quoted.
     *
     * @param out
     * @param conn
     * @param querySQL
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long exportCSV(final File out, final Connection conn, final String querySQL) throws UncheckedSQLException, UncheckedIOException {
        return exportCSV(out, conn, querySQL, 0, Long.MAX_VALUE, true, true);
    }

    /**
     * Exports the data from database to CVS.
     *
     * @param out
     * @param conn
     * @param querySQL
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long exportCSV(final File out, final Connection conn, final String querySQL, final long offset, final long count, final boolean writeTitle,
            final boolean quoted) throws UncheckedSQLException, UncheckedIOException {
        return exportCSV(out, conn, querySQL, null, offset, count, writeTitle, quoted);
    }

    /**
     * Exports the data from database to CVS.
     *
     * @param out
     * @param conn
     * @param querySQL
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long exportCSV(final File out, final Connection conn, final String querySQL, final Collection<String> selectColumnNames, final long offset,
            final long count, final boolean writeTitle, final boolean quoted) throws UncheckedSQLException, UncheckedIOException {
        final ParsedSql sql = ParsedSql.parse(querySQL);
        PreparedStatement stmt = null;

        try {
            stmt = conn.prepareStatement(sql.getParameterizedSql(), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            stmt.setFetchDirection(ResultSet.FETCH_FORWARD);
            stmt.setFetchSize(DEFAULT_FETCH_SIZE);

            return exportCSV(out, stmt, selectColumnNames, offset, count, writeTitle, quoted);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     * Exports the data from database to CVS. Title will be added at the first line and columns will be quoted.
     *
     * @param out
     * @param stmt
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("unchecked")
    public static long exportCSV(final File out, final PreparedStatement stmt) throws UncheckedSQLException, UncheckedIOException {
        return exportCSV(out, stmt, 0, Long.MAX_VALUE, true, true);
    }

    /**
     * Exports the data from database to CVS.
     *
     * @param out
     * @param stmt
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long exportCSV(final File out, final PreparedStatement stmt, final long offset, final long count, final boolean writeTitle,
            final boolean quoted) throws UncheckedSQLException, UncheckedIOException {
        return exportCSV(out, stmt, null, offset, count, writeTitle, quoted);
    }

    /**
     * Exports the data from database to CVS.
     *
     * @param out
     * @param stmt
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long exportCSV(final File out, final PreparedStatement stmt, final Collection<String> selectColumnNames, final long offset, final long count,
            final boolean writeTitle, final boolean quoted) throws UncheckedSQLException, UncheckedIOException {
        ResultSet rs = null;

        try {
            rs = stmt.executeQuery();
            // rs.setFetchSize(DEFAULT_FETCH_SIZE);

            return exportCSV(out, rs, selectColumnNames, offset, count, writeTitle, quoted);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(rs);
        }
    }

    /**
     * Exports the data from database to CVS. Title will be added at the first line and columns will be quoted.
     *
     * @param out
     * @param rs
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long exportCSV(final File out, final ResultSet rs) throws UncheckedSQLException, UncheckedIOException {
        return exportCSV(out, rs, 0, Long.MAX_VALUE, true, true);
    }

    /**
     * Exports the data from database to CVS.
     *
     * @param out
     * @param rs
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long exportCSV(final File out, final ResultSet rs, final long offset, final long count, final boolean writeTitle, final boolean quoted)
            throws UncheckedSQLException, UncheckedIOException {
        return exportCSV(out, rs, null, offset, count, writeTitle, quoted);
    }

    /**
     *
     * @param out
     * @param rs
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long exportCSV(final File out, final ResultSet rs, final Collection<String> selectColumnNames, final long offset, final long count,
            final boolean writeTitle, final boolean quoted) throws UncheckedSQLException, UncheckedIOException {
        OutputStream os = null;

        try {
            if (!out.exists()) {
                out.createNewFile();
            }

            os = new FileOutputStream(out);

            long result = exportCSV(os, rs, selectColumnNames, offset, count, writeTitle, quoted);

            os.flush();

            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            IOUtil.close(os);
        }
    }

    /**
     * Exports the data from database to CVS. Title will be added at the first line and columns will be quoted.
     *
     * @param out
     * @param rs
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long exportCSV(final OutputStream out, final ResultSet rs) throws UncheckedSQLException, UncheckedIOException {
        return exportCSV(out, rs, 0, Long.MAX_VALUE, true, true);
    }

    /**
     * Exports the data from database to CVS.
     *
     * @param out
     * @param rs
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long exportCSV(final OutputStream out, final ResultSet rs, final long offset, final long count, final boolean writeTitle,
            final boolean quoted) throws UncheckedSQLException, UncheckedIOException {
        return exportCSV(out, rs, null, offset, count, writeTitle, quoted);
    }

    /**
     * Exports the data from database to CVS.
     *
     * @param out
     * @param rs
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long exportCSV(final OutputStream out, final ResultSet rs, final Collection<String> selectColumnNames, final long offset, final long count,
            final boolean writeTitle, final boolean quoted) throws UncheckedSQLException, UncheckedIOException {
        Writer writer = null;

        try {
            writer = new OutputStreamWriter(out);

            long result = exportCSV(writer, rs, selectColumnNames, offset, count, writeTitle, quoted);

            writer.flush();

            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Exports the data from database to CVS. Title will be added at the first line and columns will be quoted.
     *
     * @param out
     * @param rs
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long exportCSV(final Writer out, final ResultSet rs) throws UncheckedSQLException, UncheckedIOException {
        return exportCSV(out, rs, 0, Long.MAX_VALUE, true, true);
    }

    /**
     * Exports the data from database to CVS.
     *
     * @param out
     * @param rs
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long exportCSV(final Writer out, final ResultSet rs, final long offset, final long count, final boolean writeTitle, final boolean quoted)
            throws UncheckedSQLException, UncheckedIOException {
        return exportCSV(out, rs, null, offset, count, writeTitle, quoted);
    }

    /**
     * Exports the data from database to CVS.
     *
     * @param out
     * @param rs
     * @param selectColumnNames
     * @param offset
     * @param count
     * @param writeTitle
     * @param quoted
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long exportCSV(final Writer out, final ResultSet rs, final Collection<String> selectColumnNames, long offset, final long count,
            final boolean writeTitle, final boolean quoted) throws UncheckedSQLException, UncheckedIOException {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);

        final JSONSerializationConfig config = JSC.create();
        config.setDateTimeFormat(DateTimeFormat.ISO_8601_TIMESTAMP);

        if (quoted) {
            config.setCharQuotation(WD._QUOTATION_D);
            config.setStringQuotation(WD._QUOTATION_D);
        } else {
            config.setCharQuotation((char) 0);
            config.setStringQuotation((char) 0);
        }

        long result = 0;
        final Type<Object> strType = N.typeOf(String.class);
        final BufferedJSONWriter bw = out instanceof BufferedJSONWriter ? (BufferedJSONWriter) out : Objectory.createBufferedJSONWriter(out);

        try {
            final ResultSetMetaData rsmd = rs.getMetaData();
            final int columnCount = rsmd.getColumnCount();
            final String[] columnNames = new String[columnCount];
            final Set<String> columnNameSet = selectColumnNames == null ? null : N.newHashSet(selectColumnNames);
            String label = null;

            for (int i = 0; i < columnCount; i++) {
                label = JdbcUtil.getColumnLabel(rsmd, i + 1);

                if (columnNameSet == null || columnNameSet.remove(label)) {
                    columnNames[i] = label;
                }
            }

            if (columnNameSet != null && columnNameSet.size() > 0) {
                throw new IllegalArgumentException(columnNameSet + " are not included in query result");
            }

            if (writeTitle) {
                for (int i = 0, j = 0, len = columnNames.length; i < len; i++) {
                    if (columnNames[i] == null) {
                        continue;
                    }

                    if (j++ > 0) {
                        bw.write(N.ELEMENT_SEPARATOR_CHAR_ARRAY);
                    }

                    bw.write(columnNames[i]);
                }

                bw.write(IOUtil.LINE_SEPARATOR);
            }

            final Type<Object>[] typeArray = new Type[columnCount];
            Type<Object> type = null;
            Object value = null;

            while (offset-- > 0 && rs.next()) {
            }

            while (result < count && rs.next()) {
                if (result++ > 0) {
                    bw.write(IOUtil.LINE_SEPARATOR);
                }

                for (int i = 0, j = 0; i < columnCount; i++) {
                    if (columnNames[i] == null) {
                        continue;
                    }

                    if (j++ > 0) {
                        bw.write(N.ELEMENT_SEPARATOR_CHAR_ARRAY);
                    }

                    type = typeArray[i];

                    if (type == null) {
                        value = JdbcUtil.getColumnValue(rs, i + 1);

                        if (value == null) {
                            bw.write(N.NULL_CHAR_ARRAY);
                        } else {
                            type = N.typeOf(value.getClass());
                            typeArray[i] = type;

                            if (type.isSerializable()) {
                                type.writeCharacter(bw, value, config);
                            } else {
                                type.writeCharacter(bw, jsonParser.serialize(value, config), config);
                            }
                        }
                    } else {
                        if (type.isSerializable()) {
                            type.writeCharacter(bw, type.get(rs, i + 1), config);
                        } else {
                            strType.writeCharacter(bw, jsonParser.serialize(type.get(rs, i + 1), config), config);
                        }
                    }
                }
            }

            bw.flush();
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (bw != out) {
                Objectory.recycle(bw);
            }
        }

        return result;
    }

    /**
     *
     * @param file
     * @param conn
     * @param insertSQL
     * @param columnTypeList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static long importCSV(final File file, final Connection conn, final String insertSQL, final List<? extends Type> columnTypeList)
            throws UncheckedSQLException, UncheckedIOException {
        return importCSV(file, 0, Long.MAX_VALUE, true, conn, insertSQL, DEFAULT_BATCH_SIZE, 0, columnTypeList);
    }

    /**
     *
     * @param file
     * @param offset
     * @param count
     * @param skipTitle
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param columnTypeList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static long importCSV(final File file, final long offset, final long count, final boolean skipTitle, final Connection conn, final String insertSQL,
            final int batchSize, final int batchInterval, final List<? extends Type> columnTypeList) throws UncheckedSQLException, UncheckedIOException {
        return importCSV(file, offset, count, skipTitle, Fn.<String[]> alwaysTrue(), conn, insertSQL, batchSize, batchInterval, columnTypeList);
    }

    /**
     * Imports the data from CSV to database.
     *
     * @param <E>
     * @param file
     * @param offset
     * @param count
     * @param skipTitle
     * @param filter
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the CSV file.
     * @param batchSize
     * @param batchInterval
     * @param columnTypeList set the column type to null to skip the column in CSV.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> long importCSV(final File file, final long offset, final long count, final boolean skipTitle,
            final Throwables.Predicate<String[], E> filter, final Connection conn, final String insertSQL, final int batchSize, final int batchInterval,
            final List<? extends Type> columnTypeList) throws UncheckedSQLException, UncheckedIOException, E {
        PreparedStatement stmt = null;

        try {
            stmt = conn.prepareStatement(insertSQL);

            return importCSV(file, offset, count, skipTitle, filter, stmt, batchSize, batchInterval, columnTypeList);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param file
     * @param stmt
     * @param columnTypeList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static long importCSV(final File file, final PreparedStatement stmt, final List<? extends Type> columnTypeList)
            throws UncheckedSQLException, UncheckedIOException {
        return importCSV(file, 0, Long.MAX_VALUE, true, stmt, DEFAULT_BATCH_SIZE, 0, columnTypeList);
    }

    /**
     *
     * @param file
     * @param offset
     * @param count
     * @param skipTitle
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param columnTypeList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static long importCSV(final File file, long offset, final long count, final boolean skipTitle, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final List<? extends Type> columnTypeList) throws UncheckedSQLException, UncheckedIOException {
        return importCSV(file, offset, count, skipTitle, Fn.<String[]> alwaysTrue(), stmt, batchSize, batchInterval, columnTypeList);
    }

    /**
     * Imports the data from CSV to database.
     *
     * @param <E>
     * @param file
     * @param offset
     * @param count
     * @param skipTitle
     * @param filter
     * @param stmt the column order in the sql must be consistent with the column order in the CSV file.
     * @param batchSize
     * @param batchInterval
     * @param columnTypeList set the column type to null to skip the column in CSV.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> long importCSV(final File file, final long offset, final long count, final boolean skipTitle,
            final Throwables.Predicate<String[], E> filter, final PreparedStatement stmt, final int batchSize, final int batchInterval,
            final List<? extends Type> columnTypeList) throws UncheckedSQLException, UncheckedIOException, E {
        Reader reader = null;

        try {
            reader = new FileReader(file);

            return importCSV(reader, offset, count, skipTitle, filter, stmt, batchSize, batchInterval, columnTypeList);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            IOUtil.close(reader);
        }
    }

    /**
     *
     * @param is
     * @param stmt
     * @param columnTypeList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static long importCSV(final InputStream is, final PreparedStatement stmt, final List<? extends Type> columnTypeList)
            throws UncheckedSQLException, UncheckedIOException {
        return importCSV(is, 0, Long.MAX_VALUE, true, stmt, DEFAULT_BATCH_SIZE, 0, columnTypeList);
    }

    /**
     *
     * @param is
     * @param offset
     * @param count
     * @param skipTitle
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param columnTypeList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static long importCSV(final InputStream is, long offset, final long count, final boolean skipTitle, final PreparedStatement stmt,
            final int batchSize, final int batchInterval, final List<? extends Type> columnTypeList) throws UncheckedSQLException, UncheckedIOException {
        return importCSV(is, offset, count, skipTitle, Fn.<String[]> alwaysTrue(), stmt, batchSize, batchInterval, columnTypeList);
    }

    /**
     * Imports the data from CSV to database.
     *
     * @param <E>
     * @param is
     * @param offset
     * @param count
     * @param skipTitle
     * @param filter
     * @param stmt the column order in the sql must be consistent with the column order in the CSV file.
     * @param batchSize
     * @param batchInterval
     * @param columnTypeList set the column type to null to skip the column in CSV.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> long importCSV(final InputStream is, final long offset, final long count, final boolean skipTitle,
            final Throwables.Predicate<String[], E> filter, final PreparedStatement stmt, final int batchSize, final int batchInterval,
            final List<? extends Type> columnTypeList) throws UncheckedSQLException, UncheckedIOException, E {
        final Reader reader = new InputStreamReader(is);

        return importCSV(reader, offset, count, skipTitle, filter, stmt, batchSize, batchInterval, columnTypeList);
    }

    /**
     *
     * @param reader
     * @param stmt
     * @param columnTypeList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static long importCSV(final Reader reader, final PreparedStatement stmt, final List<? extends Type> columnTypeList)
            throws UncheckedSQLException, UncheckedIOException {
        return importCSV(reader, 0, Long.MAX_VALUE, true, stmt, DEFAULT_BATCH_SIZE, 0, columnTypeList);
    }

    /**
     *
     * @param reader
     * @param offset
     * @param count
     * @param skipTitle
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param columnTypeList
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static long importCSV(final Reader reader, long offset, final long count, final boolean skipTitle, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final List<? extends Type> columnTypeList) throws UncheckedSQLException, UncheckedIOException {
        return importCSV(reader, offset, count, skipTitle, Fn.<String[]> alwaysTrue(), stmt, batchSize, batchInterval, columnTypeList);
    }

    /**
     * Imports the data from CSV to database.
     *
     * @param <E>
     * @param reader
     * @param offset
     * @param count
     * @param skipTitle
     * @param filter
     * @param stmt the column order in the sql must be consistent with the column order in the CSV file.
     * @param batchSize
     * @param batchInterval
     * @param columnTypeList set the column type to null to skip the column in CSV.
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static <E extends Exception> long importCSV(final Reader reader, long offset, final long count, final boolean skipTitle,
            final Throwables.Predicate<String[], E> filter, final PreparedStatement stmt, final int batchSize, final int batchInterval,
            final List<? extends Type> columnTypeList) throws UncheckedSQLException, UncheckedIOException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        long result = 0;
        final BufferedReader br = Objectory.createBufferedReader(reader);

        try {
            if (skipTitle) {
                br.readLine(); // skip the title line.
            }

            while (offset-- > 0 && br.readLine() != null) {
            }

            final Type<Object>[] columnTypes = columnTypeList.toArray(new Type[columnTypeList.size()]);
            final String[] strs = new String[columnTypeList.size()];
            String line = null;
            Type<Object> type = null;

            while (result < count && (line = br.readLine()) != null) {
                jsonParser.readString(strs, line, jdc);

                if (filter != null && filter.test(strs) == false) {
                    continue;
                }

                for (int i = 0, parameterIndex = 1, len = strs.length; i < len; i++) {
                    type = columnTypes[i];

                    if (type == null) {
                        continue;
                    }

                    type.set(stmt, parameterIndex++, (strs[i] == null) ? null : type.valueOf(strs[i]));
                }

                stmt.addBatch();

                result++;

                if ((result % batchSize) == 0) {
                    executeBatch(stmt);

                    if (batchInterval > 0) {
                        N.sleep(batchInterval);
                    }
                }

                N.fill(strs, null);
            }

            if ((result % batchSize) > 0) {
                executeBatch(stmt);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            Objectory.recycle(br);
        }

        return result;
    }

    private static int[] executeBatch(final PreparedStatement stmt) throws SQLException {
        try {
            return stmt.executeBatch();
        } finally {
            try {
                stmt.clearBatch();
            } catch (SQLException e) {
                logger.error("Failed to clear batch parameters after executeBatch", e);
            }
        }
    }

    /**
     * Imports the data from CSV to database.
     *
     * @param file
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the CSV file.
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static long importCSV(final File file, final Connection conn, final String insertSQL, final Map<String, ? extends Type> columnTypeMap)
            throws UncheckedSQLException, UncheckedIOException {
        return importCSV(file, 0, Long.MAX_VALUE, conn, insertSQL, DEFAULT_BATCH_SIZE, 0, columnTypeMap);
    }

    /**
     *
     * @param file
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static long importCSV(final File file, final long offset, final long count, final Connection conn, final String insertSQL, final int batchSize,
            final int batchInterval, final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException, UncheckedIOException {
        return importCSV(file, offset, count, Fn.<String[]> alwaysTrue(), conn, insertSQL, batchSize, batchInterval, columnTypeMap);
    }

    /**
     *
     * @param <E>
     * @param file
     * @param offset
     * @param count
     * @param filter
     * @param conn
     * @param insertSQL the column order in the sql must be consistent with the column order in the CSV file.
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> long importCSV(final File file, final long offset, final long count, final Throwables.Predicate<String[], E> filter,
            final Connection conn, final String insertSQL, final int batchSize, final int batchInterval, final Map<String, ? extends Type> columnTypeMap)
            throws UncheckedSQLException, UncheckedIOException, E {
        PreparedStatement stmt = null;

        try {
            stmt = conn.prepareStatement(insertSQL);

            return importCSV(file, offset, count, filter, stmt, batchSize, batchInterval, columnTypeMap);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param file
     * @param stmt
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static long importCSV(final File file, final PreparedStatement stmt, final Map<String, ? extends Type> columnTypeMap)
            throws UncheckedSQLException, UncheckedIOException {
        return importCSV(file, 0, Long.MAX_VALUE, stmt, DEFAULT_BATCH_SIZE, 0, columnTypeMap);
    }

    /**
     *
     * @param file
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static long importCSV(final File file, final long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException, UncheckedIOException {
        return importCSV(file, offset, count, Fn.<String[]> alwaysTrue(), stmt, batchSize, batchInterval, columnTypeMap);
    }

    /**
     * Imports the data from CSV to database.
     *
     * @param <E>
     * @param file
     * @param offset
     * @param count
     * @param filter
     * @param stmt the column order in the sql must be consistent with the column order in the CSV file.
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> long importCSV(final File file, final long offset, final long count, final Throwables.Predicate<String[], E> filter,
            final PreparedStatement stmt, final int batchSize, final int batchInterval, final Map<String, ? extends Type> columnTypeMap)
            throws UncheckedSQLException, UncheckedIOException, E {
        Reader reader = null;

        try {
            reader = new FileReader(file);

            return importCSV(reader, offset, count, filter, stmt, batchSize, batchInterval, columnTypeMap);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            IOUtil.close(reader);
        }
    }

    /**
     *
     * @param is
     * @param stmt
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static long importCSV(final InputStream is, final PreparedStatement stmt, final Map<String, ? extends Type> columnTypeMap)
            throws UncheckedSQLException, UncheckedIOException {
        return importCSV(is, 0, Long.MAX_VALUE, stmt, DEFAULT_BATCH_SIZE, 0, columnTypeMap);
    }

    /**
     *
     * @param is
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static long importCSV(final InputStream is, final long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException, UncheckedIOException {
        return importCSV(is, offset, count, Fn.<String[]> alwaysTrue(), stmt, batchSize, batchInterval, columnTypeMap);
    }

    /**
     * Imports the data from CSV to database.
     *
     * @param <E>
     * @param is
     * @param offset
     * @param count
     * @param filter
     * @param stmt the column order in the sql must be consistent with the column order in the CSV file.
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    @SuppressWarnings("rawtypes")
    public static <E extends Exception> long importCSV(final InputStream is, long offset, final long count, final Throwables.Predicate<String[], E> filter,
            final PreparedStatement stmt, final int batchSize, final int batchInterval, final Map<String, ? extends Type> columnTypeMap)
            throws UncheckedSQLException, UncheckedIOException, E {
        final Reader reader = new InputStreamReader(is);
        return importCSV(reader, offset, count, filter, stmt, batchSize, batchInterval, columnTypeMap);
    }

    /**
     *
     * @param reader
     * @param stmt
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings("rawtypes")
    public static long importCSV(final Reader reader, final PreparedStatement stmt, final Map<String, ? extends Type> columnTypeMap)
            throws UncheckedSQLException, UncheckedIOException {
        return importCSV(reader, 0, Long.MAX_VALUE, stmt, DEFAULT_BATCH_SIZE, 0, columnTypeMap);
    }

    /**
     *
     * @param reader
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static long importCSV(final Reader reader, long offset, final long count, final PreparedStatement stmt, final int batchSize, final int batchInterval,
            final Map<String, ? extends Type> columnTypeMap) throws UncheckedSQLException, UncheckedIOException {
        return importCSV(reader, offset, count, Fn.<String[]> alwaysTrue(), stmt, batchSize, batchInterval, columnTypeMap);
    }

    /**
     * Imports the data from CSV to database.
     *
     * @param <E>
     * @param reader
     * @param offset
     * @param count
     * @param filter
     * @param stmt the column order in the sql must be consistent with the column order in the CSV file.
     * @param batchSize
     * @param batchInterval
     * @param columnTypeMap
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public static <E extends Exception> long importCSV(final Reader reader, long offset, final long count, final Throwables.Predicate<String[], E> filter,
            final PreparedStatement stmt, final int batchSize, final int batchInterval, final Map<String, ? extends Type> columnTypeMap)
            throws UncheckedSQLException, UncheckedIOException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        long result = 0;
        final BufferedReader br = Objectory.createBufferedReader(reader);

        try {
            List<String> tmp = new ArrayList<>();
            String line = br.readLine();
            jsonParser.readString(tmp, line, jdc);
            final String[] titles = tmp.toArray(new String[tmp.size()]);

            final Type<Object>[] columnTypes = new Type[titles.length];
            final List<String> columnNameList = new ArrayList<>(columnTypeMap.size());

            for (int i = 0, columnCount = titles.length; i < columnCount; i++) {
                if (columnTypeMap.containsKey(titles[i])) {
                    columnTypes[i] = columnTypeMap.get(titles[i]);
                    columnNameList.add(titles[i]);
                }
            }

            if (columnNameList.size() != columnTypeMap.size()) {
                final List<String> keys = new ArrayList<>(columnTypeMap.keySet());
                keys.removeAll(columnNameList);
                throw new IllegalArgumentException(keys + " are not included in titles: " + N.toString(titles));
            }

            while (offset-- > 0 && br.readLine() != null) {
            }

            final boolean isNullOrEmptyTypes = N.isNullOrEmpty(columnTypes);
            final String[] strs = new String[titles.length];
            Type<Object> type = null;

            while (result < count && (line = br.readLine()) != null) {
                jsonParser.readString(strs, line, jdc);

                if (filter != null && filter.test(strs) == false) {
                    continue;
                }

                if (isNullOrEmptyTypes) {
                    for (int i = 0, len = strs.length; i < len; i++) {
                        stmt.setObject(i + 1, strs[i]);
                    }
                } else {
                    for (int i = 0, parameterIndex = 1, len = strs.length; i < len; i++) {
                        type = columnTypes[i];

                        if (type == null) {
                            continue;
                        }

                        type.set(stmt, parameterIndex++, (strs[i] == null) ? null : type.valueOf(strs[i]));
                    }
                }

                stmt.addBatch();

                result++;

                if ((result % batchSize) == 0) {
                    executeBatch(stmt);

                    if (batchInterval > 0) {
                        N.sleep(batchInterval);
                    }
                }

                N.fill(strs, null);
            }

            if ((result % batchSize) > 0) {
                executeBatch(stmt);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            Objectory.recycle(br);
        }

        return result;
    }

    /**
     * Imports the data from CSV to database.
     *
     * @param file
     * @param conn
     * @param insertSQL the column order in the sql should be consistent with the column order in the CSV file.
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long importCSV(final File file, final Connection conn, final String insertSQL,
            final Throwables.BiConsumer<? super PreparedStatement, ? super String[], SQLException> stmtSetter)
            throws UncheckedSQLException, UncheckedIOException {
        return importCSV(file, 0, Long.MAX_VALUE, conn, insertSQL, DEFAULT_BATCH_SIZE, 0, stmtSetter);
    }

    /**
     *
     * @param file
     * @param offset
     * @param count
     * @param conn
     * @param insertSQL
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long importCSV(final File file, final long offset, final long count, final Connection conn, final String insertSQL, final int batchSize,
            final int batchInterval, final Throwables.BiConsumer<? super PreparedStatement, ? super String[], SQLException> stmtSetter)
            throws UncheckedSQLException, UncheckedIOException {
        return importCSV(file, offset, count, Fn.<String[]> alwaysTrue(), conn, insertSQL, batchSize, batchInterval, stmtSetter);
    }

    /**
     *
     * @param <E>
     * @param file
     * @param offset
     * @param count
     * @param filter
     * @param conn
     * @param insertSQL the column order in the sql should be consistent with the column order in the CSV file.
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    public static <E extends Exception> long importCSV(final File file, final long offset, final long count, final Throwables.Predicate<String[], E> filter,
            final Connection conn, final String insertSQL, final int batchSize, final int batchInterval,
            final Throwables.BiConsumer<? super PreparedStatement, ? super String[], SQLException> stmtSetter)
            throws UncheckedSQLException, UncheckedIOException, E {
        PreparedStatement stmt = null;

        try {
            stmt = conn.prepareStatement(insertSQL);

            return importCSV(file, offset, count, filter, stmt, batchSize, batchInterval, stmtSetter);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } finally {
            JdbcUtil.closeQuietly(stmt);
        }
    }

    /**
     *
     * @param file
     * @param stmt
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long importCSV(final File file, final PreparedStatement stmt,
            final Throwables.BiConsumer<? super PreparedStatement, ? super String[], SQLException> stmtSetter)
            throws UncheckedSQLException, UncheckedIOException {
        return importCSV(file, 0, Long.MAX_VALUE, stmt, DEFAULT_BATCH_SIZE, 0, stmtSetter);
    }

    /**
     *
     * @param file
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long importCSV(final File file, final long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final Throwables.BiConsumer<? super PreparedStatement, ? super String[], SQLException> stmtSetter)
            throws UncheckedSQLException, UncheckedIOException {
        return importCSV(file, offset, count, Fn.<String[]> alwaysTrue(), stmt, batchSize, batchInterval, stmtSetter);
    }

    /**
     * Imports the data from CSV to database.
     *
     * @param <E>
     * @param file
     * @param offset
     * @param count
     * @param filter
     * @param stmt the column order in the sql should be consistent with the column order in the CSV file.
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    public static <E extends Exception> long importCSV(final File file, final long offset, final long count, final Throwables.Predicate<String[], E> filter,
            final PreparedStatement stmt, final int batchSize, final int batchInterval,
            final Throwables.BiConsumer<? super PreparedStatement, ? super String[], SQLException> stmtSetter)
            throws UncheckedSQLException, UncheckedIOException, E {
        Reader reader = null;

        try {
            reader = new FileReader(file);

            return importCSV(reader, offset, count, filter, stmt, batchSize, batchInterval, stmtSetter);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            IOUtil.close(reader);
        }
    }

    /**
     *
     * @param is
     * @param stmt
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long importCSV(final InputStream is, final PreparedStatement stmt,
            final Throwables.BiConsumer<? super PreparedStatement, ? super String[], SQLException> stmtSetter)
            throws UncheckedSQLException, UncheckedIOException {
        return importCSV(is, 0, Long.MAX_VALUE, stmt, DEFAULT_BATCH_SIZE, 0, stmtSetter);
    }

    /**
     *
     * @param is
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long importCSV(final InputStream is, final long offset, final long count, final PreparedStatement stmt, final int batchSize,
            final int batchInterval, final Throwables.BiConsumer<? super PreparedStatement, ? super String[], SQLException> stmtSetter)
            throws UncheckedSQLException, UncheckedIOException {
        return importCSV(is, offset, count, Fn.<String[]> alwaysTrue(), stmt, batchSize, batchInterval, stmtSetter);
    }

    /**
     * Imports the data from CSV to database.
     *
     * @param <E>
     * @param is
     * @param offset
     * @param count
     * @param filter
     * @param stmt the column order in the sql should be consistent with the column order in the CSV file.
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    public static <E extends Exception> long importCSV(final InputStream is, long offset, final long count, final Throwables.Predicate<String[], E> filter,
            final PreparedStatement stmt, final int batchSize, final int batchInterval,
            final Throwables.BiConsumer<? super PreparedStatement, ? super String[], SQLException> stmtSetter)
            throws UncheckedSQLException, UncheckedIOException, E {
        final Reader reader = new InputStreamReader(is);
        return importCSV(reader, offset, count, filter, stmt, batchSize, batchInterval, stmtSetter);
    }

    /**
     *
     * @param reader
     * @param stmt
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    public static long importCSV(final Reader reader, final PreparedStatement stmt,
            final Throwables.BiConsumer<? super PreparedStatement, ? super String[], SQLException> stmtSetter)
            throws UncheckedSQLException, UncheckedIOException {
        return importCSV(reader, 0, Long.MAX_VALUE, stmt, DEFAULT_BATCH_SIZE, 0, stmtSetter);
    }

    /**
     *
     * @param reader
     * @param offset
     * @param count
     * @param stmt
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     */
    @SuppressWarnings({ "unchecked" })
    public static long importCSV(final Reader reader, long offset, final long count, final PreparedStatement stmt, final int batchSize, final int batchInterval,
            final Throwables.BiConsumer<? super PreparedStatement, ? super String[], SQLException> stmtSetter)
            throws UncheckedSQLException, UncheckedIOException {
        return importCSV(reader, offset, count, Fn.<String[]> alwaysTrue(), stmt, batchSize, batchInterval, stmtSetter);
    }

    /**
     * Imports the data from CSV to database.
     *
     * @param <E>
     * @param reader
     * @param offset
     * @param count
     * @param filter
     * @param stmt the column order in the sql should be consistent with the column order in the CSV file.
     * @param batchSize
     * @param batchInterval
     * @param stmtSetter
     * @return
     * @throws UncheckedSQLException the unchecked SQL exception
     * @throws UncheckedIOException the unchecked IO exception
     * @throws E the e
     */
    @SuppressWarnings({ "unchecked" })
    public static <E extends Exception> long importCSV(final Reader reader, long offset, final long count, final Throwables.Predicate<String[], E> filter,
            final PreparedStatement stmt, final int batchSize, final int batchInterval,
            final Throwables.BiConsumer<? super PreparedStatement, ? super String[], SQLException> stmtSetter)
            throws UncheckedSQLException, UncheckedIOException, E {
        N.checkArgument(offset >= 0 && count >= 0, "'offset'=%s and 'count'=%s can't be negative", offset, count);
        N.checkArgument(batchSize > 0 && batchInterval >= 0, "'batchSize'=%s must be greater than 0 and 'batchInterval'=%s can't be negative", batchSize,
                batchInterval);

        long result = 0;
        final BufferedReader br = Objectory.createBufferedReader(reader);

        try {
            List<String> tmp = new ArrayList<>();
            String line = br.readLine();
            jsonParser.readString(tmp, line, jdc);
            final String[] strs = new String[tmp.size()];

            while (offset-- > 0 && br.readLine() != null) {
            }

            while (result < count && (line = br.readLine()) != null) {
                jsonParser.readString(strs, line, jdc);

                if (filter != null && filter.test(strs) == false) {
                    continue;
                }

                stmtSetter.accept(stmt, strs);
                stmt.addBatch();

                if ((++result % batchSize) == 0) {
                    executeBatch(stmt);

                    if (batchInterval > 0) {
                        N.sleep(batchInterval);
                    }
                }

                N.fill(strs, null);
            }

            if ((result % batchSize) > 0) {
                executeBatch(stmt);
            }
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            Objectory.recycle(br);
        }

        return result;
    }
}
