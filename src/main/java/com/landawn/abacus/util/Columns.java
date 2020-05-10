package com.landawn.abacus.util;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;

import com.landawn.abacus.util.JdbcUtil.BiParametersSetter;
import com.landawn.abacus.util.JdbcUtil.RowMapper;

public final class Columns {
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

        @SuppressWarnings("rawtypes")
        public static final RowMapper GET_OBJECT = rs -> rs.getObject(1);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Boolean> SET_BOOLEAN = (preparedQuery, x) -> preparedQuery.setBoolean(1, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Byte> SET_BYTE = (preparedQuery, x) -> preparedQuery.setByte(1, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Short> SET_SHORT = (preparedQuery, x) -> preparedQuery.setShort(1, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Integer> SET_INT = (preparedQuery, x) -> preparedQuery.setInt(1, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Long> SET_LONG = (preparedQuery, x) -> preparedQuery.setLong(1, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Float> SET_FLOAT = (preparedQuery, x) -> preparedQuery.setFloat(1, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Double> SET_DOUBLE = (preparedQuery, x) -> preparedQuery.setDouble(1, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, BigDecimal> SET_BIG_DECIMAL = (preparedQuery, x) -> preparedQuery.setBigDecimal(1, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, String> SET_STRING = (preparedQuery, x) -> preparedQuery.setString(1, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Date> SET_DATE = (preparedQuery, x) -> preparedQuery.setDate(1, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Time> SET_TIME = (preparedQuery, x) -> preparedQuery.setTime(1, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Timestamp> SET_TIMESTAMP = (preparedQuery, x) -> preparedQuery.setTimestamp(1, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, java.util.Date> SET_DATE_2 = (preparedQuery, x) -> preparedQuery.setDate(1, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, java.util.Date> SET_TIME_2 = (preparedQuery, x) -> preparedQuery.setTime(1, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, java.util.Date> SET_TIMESTAMP_2 = (preparedQuery, x) -> preparedQuery.setTimestamp(1, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, byte[]> SET_BYTES = (preparedQuery, x) -> preparedQuery.setBytes(1, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, InputStream> SET_BINARY_STREAM = (preparedQuery, x) -> preparedQuery.setBinaryStream(1,
                x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Reader> SET_CHARACTER_STREAM = (preparedQuery, x) -> preparedQuery.setCharacterStream(1,
                x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Blob> SET_BLOB = (preparedQuery, x) -> preparedQuery.setBlob(1, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Clob> SET_CLOB = (preparedQuery, x) -> preparedQuery.setClob(1, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Object> SET_OBJECT = (preparedQuery, x) -> preparedQuery.setObject(1, x);

        private ColumnOne() {
            // singleton for utility class
        }
    }

    public static final class ColumnTwo {
        public static final RowMapper<Boolean> GET_BOOLEAN = rs -> rs.getBoolean(2);

        public static final RowMapper<Byte> GET_BYTE = rs -> rs.getByte(2);

        public static final RowMapper<Short> GET_SHORT = rs -> rs.getShort(2);

        public static final RowMapper<Integer> GET_INT = rs -> rs.getInt(2);

        public static final RowMapper<Long> GET_LONG = rs -> rs.getLong(2);

        public static final RowMapper<Float> GET_FLOAT = rs -> rs.getFloat(2);

        public static final RowMapper<Double> GET_DOUBLE = rs -> rs.getDouble(2);

        public static final RowMapper<BigDecimal> GET_BIG_DECIMAL = rs -> rs.getBigDecimal(2);

        public static final RowMapper<String> GET_STRING = rs -> rs.getString(2);

        public static final RowMapper<Date> GET_DATE = rs -> rs.getDate(2);

        public static final RowMapper<Time> GET_TIME = rs -> rs.getTime(2);

        public static final RowMapper<Timestamp> GET_TIMESTAMP = rs -> rs.getTimestamp(2);

        public static final RowMapper<byte[]> GET_BYTES = rs -> rs.getBytes(2);

        public static final RowMapper<InputStream> GET_BINARY_STREAM = rs -> rs.getBinaryStream(2);

        public static final RowMapper<Reader> GET_CHARACTER_STREAM = rs -> rs.getCharacterStream(2);

        public static final RowMapper<Blob> GET_BLOB = rs -> rs.getBlob(2);

        public static final RowMapper<Clob> GET_CLOB = rs -> rs.getClob(2);

        @SuppressWarnings("rawtypes")
        public static final RowMapper GET_OBJECT = rs -> rs.getObject(2);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Boolean> SET_BOOLEAN = (preparedQuery, x) -> preparedQuery.setBoolean(2, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Byte> SET_BYTE = (preparedQuery, x) -> preparedQuery.setByte(2, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Short> SET_SHORT = (preparedQuery, x) -> preparedQuery.setShort(2, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Integer> SET_INT = (preparedQuery, x) -> preparedQuery.setInt(2, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Long> SET_LONG = (preparedQuery, x) -> preparedQuery.setLong(2, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Float> SET_FLOAT = (preparedQuery, x) -> preparedQuery.setFloat(2, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Double> SET_DOUBLE = (preparedQuery, x) -> preparedQuery.setDouble(2, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, BigDecimal> SET_BIG_DECIMAL = (preparedQuery, x) -> preparedQuery.setBigDecimal(2, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, String> SET_STRING = (preparedQuery, x) -> preparedQuery.setString(2, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Date> SET_DATE = (preparedQuery, x) -> preparedQuery.setDate(2, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Time> SET_TIME = (preparedQuery, x) -> preparedQuery.setTime(2, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Timestamp> SET_TIMESTAMP = (preparedQuery, x) -> preparedQuery.setTimestamp(2, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, java.util.Date> SET_DATE_2 = (preparedQuery, x) -> preparedQuery.setDate(2, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, java.util.Date> SET_TIME_2 = (preparedQuery, x) -> preparedQuery.setTime(2, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, java.util.Date> SET_TIMESTAMP_2 = (preparedQuery, x) -> preparedQuery.setTimestamp(2, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, byte[]> SET_BYTES = (preparedQuery, x) -> preparedQuery.setBytes(2, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, InputStream> SET_BINARY_STREAM = (preparedQuery, x) -> preparedQuery.setBinaryStream(2,
                x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Reader> SET_CHARACTER_STREAM = (preparedQuery, x) -> preparedQuery.setCharacterStream(2,
                x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Blob> SET_BLOB = (preparedQuery, x) -> preparedQuery.setBlob(2, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Clob> SET_CLOB = (preparedQuery, x) -> preparedQuery.setClob(2, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Object> SET_OBJECT = (preparedQuery, x) -> preparedQuery.setObject(2, x);

        private ColumnTwo() {
            // singleton for utility class
        }
    }

    public static final class ColumnThree {
        public static final RowMapper<Boolean> GET_BOOLEAN = rs -> rs.getBoolean(3);

        public static final RowMapper<Byte> GET_BYTE = rs -> rs.getByte(3);

        public static final RowMapper<Short> GET_SHORT = rs -> rs.getShort(3);

        public static final RowMapper<Integer> GET_INT = rs -> rs.getInt(3);

        public static final RowMapper<Long> GET_LONG = rs -> rs.getLong(3);

        public static final RowMapper<Float> GET_FLOAT = rs -> rs.getFloat(3);

        public static final RowMapper<Double> GET_DOUBLE = rs -> rs.getDouble(3);

        public static final RowMapper<BigDecimal> GET_BIG_DECIMAL = rs -> rs.getBigDecimal(3);

        public static final RowMapper<String> GET_STRING = rs -> rs.getString(3);

        public static final RowMapper<Date> GET_DATE = rs -> rs.getDate(3);

        public static final RowMapper<Time> GET_TIME = rs -> rs.getTime(3);

        public static final RowMapper<Timestamp> GET_TIMESTAMP = rs -> rs.getTimestamp(3);

        public static final RowMapper<byte[]> GET_BYTES = rs -> rs.getBytes(3);

        public static final RowMapper<InputStream> GET_BINARY_STREAM = rs -> rs.getBinaryStream(3);

        public static final RowMapper<Reader> GET_CHARACTER_STREAM = rs -> rs.getCharacterStream(3);

        public static final RowMapper<Blob> GET_BLOB = rs -> rs.getBlob(3);

        public static final RowMapper<Clob> GET_CLOB = rs -> rs.getClob(3);

        @SuppressWarnings("rawtypes")
        public static final RowMapper GET_OBJECT = rs -> rs.getObject(3);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Boolean> SET_BOOLEAN = (preparedQuery, x) -> preparedQuery.setBoolean(3, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Byte> SET_BYTE = (preparedQuery, x) -> preparedQuery.setByte(3, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Short> SET_SHORT = (preparedQuery, x) -> preparedQuery.setShort(3, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Integer> SET_INT = (preparedQuery, x) -> preparedQuery.setInt(3, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Long> SET_LONG = (preparedQuery, x) -> preparedQuery.setLong(3, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Float> SET_FLOAT = (preparedQuery, x) -> preparedQuery.setFloat(3, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Double> SET_DOUBLE = (preparedQuery, x) -> preparedQuery.setDouble(3, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, BigDecimal> SET_BIG_DECIMAL = (preparedQuery, x) -> preparedQuery.setBigDecimal(3, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, String> SET_STRING = (preparedQuery, x) -> preparedQuery.setString(3, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Date> SET_DATE = (preparedQuery, x) -> preparedQuery.setDate(3, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Time> SET_TIME = (preparedQuery, x) -> preparedQuery.setTime(3, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Timestamp> SET_TIMESTAMP = (preparedQuery, x) -> preparedQuery.setTimestamp(3, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, java.util.Date> SET_DATE_2 = (preparedQuery, x) -> preparedQuery.setDate(3, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, java.util.Date> SET_TIME_2 = (preparedQuery, x) -> preparedQuery.setTime(3, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, java.util.Date> SET_TIMESTAMP_2 = (preparedQuery, x) -> preparedQuery.setTimestamp(3, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, byte[]> SET_BYTES = (preparedQuery, x) -> preparedQuery.setBytes(3, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, InputStream> SET_BINARY_STREAM = (preparedQuery, x) -> preparedQuery.setBinaryStream(3,
                x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Reader> SET_CHARACTER_STREAM = (preparedQuery, x) -> preparedQuery.setCharacterStream(3,
                x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Blob> SET_BLOB = (preparedQuery, x) -> preparedQuery.setBlob(3, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Clob> SET_CLOB = (preparedQuery, x) -> preparedQuery.setClob(3, x);

        @SuppressWarnings("rawtypes")
        public static final BiParametersSetter<AbstractPreparedQuery, Object> SET_OBJECT = (preparedQuery, x) -> preparedQuery.setObject(3, x);

        private ColumnThree() {
            // singleton for utility class
        }
    }

}
