package com.landawn.abacus.util;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import com.landawn.abacus.exception.UncheckedIOException;
import com.landawn.abacus.exception.UncheckedSQLException;

public final class CodingUtil {

    static final String str = new StringBuilder() //
            .append("import com.landawn.abacus.annotation.Column;\n\n")
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
        return writeEntityClass(ds, tableName, null, null, null);
    }

    public static String writeEntityClass(final Connection conn, final String tableName) {
        return writeEntityClass(conn, tableName, null, null, null);
    }

    public static String writeEntityClass(final DataSource ds, final String tableName, String className, String packageName) {
        return writeEntityClass(ds, tableName, className, packageName, null);
    }

    public static String writeEntityClass(final Connection conn, final String tableName, String className, String packageName) {
        return writeEntityClass(conn, tableName, className, packageName, null);
    }

    public static String writeEntityClass(final DataSource ds, final String tableName, String className, String packageName, String srcDir) {
        try (Connection conn = ds.getConnection()) {
            return writeEntityClass(conn, tableName, className, packageName, srcDir);

        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    public static String writeEntityClass(final Connection conn, final String tableName, String className, String packageName, String srcDir) {
        try (PreparedStatement stmt = conn.prepareStatement("select * from " + tableName + " where 1 > 2"); ResultSet rs = stmt.executeQuery()) {
            String finalClassName = N.isNullOrEmpty(className) ? StringUtil.capitalize(ClassUtil.formalizePropName(tableName)) : className;

            final StringBuilder sb = new StringBuilder();

            if (N.notNullOrEmpty(packageName)) {
                sb.append("package ").append(packageName + ";").append("\n").append("\n");
            }

            sb.append(str);
            sb.append("public class " + finalClassName).append(" {").append("\n");

            final ResultSetMetaData metaData = rs.getMetaData();
            final int columnCount = metaData.getColumnCount();

            for (int i = 1; i <= columnCount; i++) {
                String columnName = metaData.getColumnName(i);
                String columnClassName = getColumnClassName(metaData, i);

                sb.append("\n") //
                        .append("    @Column(\"" + columnName + "\")")
                        .append("\n") //
                        .append("    private " + columnClassName + " " + ClassUtil.formalizePropName(columnName) + ";")
                        .append("\n");
            }

            sb.append("\n").append("}").append("\n");

            String result = sb.toString();

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

    private static String getColumnClassName(final ResultSetMetaData metaData, int i) throws SQLException {
        String className = metaData.getColumnClassName(i).replace("java.lang.", "");

        return classNameMap.getOrDefault(className, className);
    }
}
