package com.landawn.abacus.util;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.landawn.abacus.exception.UncheckedIOException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.Tuple.Tuple3;

public final class CodingUtil {

    static final String str = new StringBuilder() //
            .append("import com.landawn.abacus.annotation.Column;\n")
            .append("import com.landawn.abacus.annotation.Table;\n\n")
            .append("import lombok.AllArgsConstructor;\n")
            .append("import lombok.Builder;\n")
            .append("import lombok.Data;\n")
            .append("import lombok.NoArgsConstructor;\n")
            .append("\n")
            .append("@Builder\n")
            .append("@Data\n")
            .append("@NoArgsConstructor\n")
            .append("@AllArgsConstructor\n")
            .toString();

    private CodingUtil() {
        // singleton for utility class.
    }

    public static String writeEntityClass(final DataSource ds, final String tableName) {
        return writeEntityClass(ds, tableName, null, null);
    }

    public static String writeEntityClass(final Connection conn, final String tableName) {
        return writeEntityClass(conn, tableName, null, null);
    }

    public static String writeEntityClass(final DataSource ds, final String tableName, final String className, final String packageName) {
        return writeEntityClass(ds, tableName, className, packageName, null);
    }

    public static String writeEntityClass(final Connection conn, final String tableName, final String className, final String packageName) {
        return writeEntityClass(conn, tableName, className, packageName, null);
    }

    public static String writeEntityClass(final DataSource ds, final String tableName, final String className, final String packageName, final String srcDir) {
        return writeEntityClass(ds, tableName, className, packageName, srcDir, null);
    }

    public static String writeEntityClass(final Connection conn, final String tableName, final String className, final String packageName,
            final String srcDir) {
        return writeEntityClass(conn, tableName, className, packageName, srcDir, null);
    }

    public static String writeEntityClass(final DataSource ds, final String tableName, final String className, final String packageName, final String srcDir,
            final List<Tuple3<String, String, Class<?>>> customizedFields) {
        try (Connection conn = ds.getConnection()) {
            return writeEntityClass(conn, tableName, className, packageName, srcDir, customizedFields);

        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    public static String writeEntityClass(final Connection conn, final String tableName, final String className, final String packageName, final String srcDir,
            final List<Tuple3<String, String, Class<?>>> customizedFields) {

        final Map<String, Tuple3<String, String, Class<?>>> customizedFieldMap = Maps.newMap(customizedFields, tp -> tp._1);

        try (PreparedStatement stmt = conn.prepareStatement("select * from " + tableName + " where 1 > 2"); ResultSet rs = stmt.executeQuery()) {
            String finalClassName = N.isNullOrEmpty(className) ? StringUtil.capitalize(ClassUtil.formalizePropName(tableName)) : className;

            final StringBuilder sb = new StringBuilder();

            if (N.notNullOrEmpty(packageName)) {
                sb.append("package ").append(packageName + ";").append("\n").append("\n");
            }

            sb.append(str) //
                    .append("@Table(\"" + tableName + "\")")
                    .append("\n")
                    .append("public class " + finalClassName)
                    .append(" {")
                    .append("\n");

            final ResultSetMetaData metaData = rs.getMetaData();
            final int columnCount = metaData.getColumnCount();

            for (int i = 1; i <= columnCount; i++) {
                final String columnName = metaData.getColumnName(i);

                final Tuple3<String, String, Class<?>> customizedField = customizedFieldMap.getOrDefault(ClassUtil.formalizePropName(columnName),
                        customizedFieldMap.get(columnName));

                final String fieldName = customizedField == null || N.isNullOrEmpty(customizedField._2) ? ClassUtil.formalizePropName(columnName)
                        : customizedField._2;

                final String columnClassName = customizedField == null || customizedField._3 == null ? getColumnClassName(metaData.getColumnClassName(i))
                        : getColumnClassName(ClassUtil.getCanonicalClassName(customizedField._3));

                sb.append("\n") //
                        .append("    @Column(\"" + columnName + "\")")
                        .append("\n") //
                        .append("    private " + columnClassName + " " + fieldName + ";")
                        .append("\n");
            }

            sb.append("\n").append("}").append("\n");

            final String result = sb.toString();

            if (N.notNullOrEmpty(srcDir)) {
                String packageDir = srcDir;

                if (N.notNullOrEmpty(packageName)) {
                    if (!(packageDir.endsWith("/") || packageDir.endsWith("\\"))) {
                        packageDir += "/";
                    }

                    packageDir += StringUtil.replaceAll(packageName, ".", "/");
                }

                IOUtil.mkdirsIfNotExists(new File(packageDir));

                File file = new File(packageDir + "/" + finalClassName + ".java");

                IOUtil.createIfNotExists(file);

                IOUtil.write(file, result);
            }

            return result;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("deprecation")
    private static final Map<String, String> classNameMap = N.asMap("Boolean", "boolean", "Character", "char", "Byte", "byte", "Short", "short", "Integer",
            "int", "Long", "long", "Float", "float", "Double", "double");

    private static String getColumnClassName(final String columnClassName) {
        String className = columnClassName.replace("java.lang.", "");

        return classNameMap.getOrDefault(className, className);
    }
}
