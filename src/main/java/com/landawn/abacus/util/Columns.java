package com.landawn.abacus.util;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Map;

import com.landawn.abacus.type.Type;
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

        public static final RowMapper<Object> GET_OBJECT = rs -> rs.getObject(1);

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

        @SuppressWarnings("rawtypes")
        static final Map<Type<?>, JdbcUtil.RowMapper> rowMapperPool = new ObjectPool<>(1024);

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

        @SuppressWarnings("rawtypes")
        public static <T> BiParametersSetter<AbstractPreparedQuery, T> set(final Class<T> type) {
            return set(N.typeOf(type));
        }

        @SuppressWarnings("rawtypes")
        public static <T> BiParametersSetter<AbstractPreparedQuery, T> set(final Type<T> type) {
            return (preparedQuery, x) -> type.set(preparedQuery.stmt, 1, x);
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

        public static final RowMapper<Object> GET_OBJECT = rs -> rs.getObject(2);

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

        @SuppressWarnings("rawtypes")
        static final Map<Type<?>, JdbcUtil.RowMapper> rowMapperPool = new ObjectPool<>(1024);

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
                result = rs -> type.get(rs, 2);

                rowMapperPool.put(type, result);
            }

            return result;
        }

        @SuppressWarnings("rawtypes")
        public static <T> BiParametersSetter<AbstractPreparedQuery, T> set(final Class<T> type) {
            return set(N.typeOf(type));
        }

        @SuppressWarnings("rawtypes")
        public static <T> BiParametersSetter<AbstractPreparedQuery, T> set(final Type<T> type) {
            return (preparedQuery, x) -> type.set(preparedQuery.stmt, 2, x);
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

        public static final RowMapper<Object> GET_OBJECT = rs -> rs.getObject(3);

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

        @SuppressWarnings("rawtypes")
        static final Map<Type<?>, JdbcUtil.RowMapper> rowMapperPool = new ObjectPool<>(1024);

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
                result = rs -> type.get(rs, 3);

                rowMapperPool.put(type, result);
            }

            return result;
        }

        @SuppressWarnings("rawtypes")
        public static <T> BiParametersSetter<AbstractPreparedQuery, T> set(final Class<T> type) {
            return set(N.typeOf(type));
        }

        @SuppressWarnings("rawtypes")
        public static <T> BiParametersSetter<AbstractPreparedQuery, T> set(final Type<T> type) {
            return (preparedQuery, x) -> type.set(preparedQuery.stmt, 3, x);
        }
    }

    public interface ColumnGetter<V> {

        ColumnGetter<Boolean> GET_BOOLEAN = (columnIndex, rs) -> rs.getBoolean(columnIndex);

        ColumnGetter<Byte> GET_BYTE = (columnIndex, rs) -> rs.getByte(columnIndex);

        ColumnGetter<Short> GET_SHORT = (columnIndex, rs) -> rs.getShort(columnIndex);

        ColumnGetter<Integer> GET_INT = (columnIndex, rs) -> rs.getInt(columnIndex);

        ColumnGetter<Long> GET_LONG = (columnIndex, rs) -> rs.getLong(columnIndex);

        ColumnGetter<Float> GET_FLOAT = (columnIndex, rs) -> rs.getFloat(columnIndex);

        ColumnGetter<Double> GET_DOUBLE = (columnIndex, rs) -> rs.getDouble(columnIndex);

        ColumnGetter<BigDecimal> GET_BIG_DECIMAL = (columnIndex, rs) -> rs.getBigDecimal(columnIndex);

        ColumnGetter<String> GET_STRING = (columnIndex, rs) -> rs.getString(columnIndex);

        ColumnGetter<Date> GET_DATE = (columnIndex, rs) -> rs.getDate(columnIndex);

        ColumnGetter<Time> GET_TIME = (columnIndex, rs) -> rs.getTime(columnIndex);

        ColumnGetter<Timestamp> GET_TIMESTAMP = (columnIndex, rs) -> rs.getTimestamp(columnIndex);

        ColumnGetter<byte[]> GET_BYTES = (columnIndex, rs) -> rs.getBytes(columnIndex);

        ColumnGetter<InputStream> GET_BINARY_STREAM = (columnIndex, rs) -> rs.getBinaryStream(columnIndex);

        ColumnGetter<Reader> GET_CHARACTER_STREAM = (columnIndex, rs) -> rs.getCharacterStream(columnIndex);

        ColumnGetter<Blob> GET_BLOB = (columnIndex, rs) -> rs.getBlob(columnIndex);

        ColumnGetter<Clob> GET_CLOB = (columnIndex, rs) -> rs.getClob(columnIndex);

        @SuppressWarnings("rawtypes")
        public static final ColumnGetter GET_OBJECT = new ColumnGetter<Object>() {
            @Override
            public Object apply(int columnIndex, ResultSet rs) throws SQLException {
                return rs.getObject(columnIndex);
            }
        };

        /**
         *
         * @param columnIndex start from 1
         * @param rs
         * @return
         * @throws SQLException
         */
        V apply(int columnIndex, ResultSet rs) throws SQLException;

        static <T> ColumnGetter<T> get(final Class<? extends T> cls) {
            return get(N.typeOf(cls));
        }

        static <T> ColumnGetter<T> get(final Type<? extends T> type) {
            ColumnGetter<?> columnGetter = JdbcUtil.COLUMN_GETTER_POOL.get(type);

            if (columnGetter == null) {
                columnGetter = new ColumnGetter<T>() {
                    @Override
                    public T apply(int columnIndex, ResultSet rs) throws SQLException {
                        return type.get(rs, columnIndex);
                    }
                };

                JdbcUtil.COLUMN_GETTER_POOL.put(type, columnGetter);
            }

            return (ColumnGetter<T>) columnGetter;
        }
    }

}
