/*
 * Copyright (c) 2021, Haiyang Li.
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

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import com.landawn.abacus.annotation.Column;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.exception.UncheckedIOException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.BiMap;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.IOUtil;
import com.landawn.abacus.util.Maps;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Splitter;
import com.landawn.abacus.util.StringUtil;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.function.BiFunction;
import com.landawn.abacus.util.function.QuadFunction;

final class CodeGenerationUtil {
    private static final String eccImports = "import javax.persistence.Column;\r\n" + "import javax.persistence.Id;\r\n" + "import javax.persistence.Table;\r\n"
            + "\r\n" + "import com.landawn.abacus.annotation.Column;\r\n" + "import com.landawn.abacus.annotation.Id;\r\n"
            + "import com.landawn.abacus.annotation.JsonXmlConfig;\r\n" + "import com.landawn.abacus.annotation.NonUpdatable;\r\n"
            + "import com.landawn.abacus.annotation.ReadOnly;\r\n" + "import com.landawn.abacus.annotation.Table;\r\n"
            + "import com.landawn.abacus.annotation.Type;\r\n" + "import com.landawn.abacus.annotation.Type.EnumBy;\r\n"
            + "import com.landawn.abacus.util.NamingPolicy;\r\n" + "\r\n" + "import lombok.AllArgsConstructor;\r\n" + "import lombok.Builder;\r\n"
            + "import lombok.Data;\r\n" + "import lombok.NoArgsConstructor;\r\n" + "import lombok.experimental.Accessors;\r\n";

    private static final String eccClassAnnos = "@Builder\r\n" + "@Data\r\n" + "@NoArgsConstructor\r\n" + "@AllArgsConstructor\r\n"
            + "@Accessors(chain = true)\r\n";

    private static final EntityCodeConfig defaultEntityCodeConfig = EntityCodeConfig.builder()
            .fieldNameConverter((tableName, columnName) -> StringUtil.toCamelCase(columnName))
            .build();

    @SuppressWarnings("deprecation")
    private static final BiMap<String, String> eccClassNameMap = BiMap.from(N.asMap("Boolean", "boolean", "Character", "char", "Byte", "byte", "Short", "short",
            "Integer", "int", "Long", "long", "Float", "float", "Double", "double"));

    private CodeGenerationUtil() {
        // singleton.
    }

    static String generateEntityClass(final DataSource ds, final String tableName) {
        return generateEntityClass(ds, tableName, null);
    }

    static String generateEntityClass(final Connection conn, final String tableName) {
        return generateEntityClass(conn, tableName, null);
    }

    static String generateEntityClass(final DataSource ds, final String tableName, final EntityCodeConfig config) {
        try (Connection conn = ds.getConnection()) {
            return generateEntityClass(conn, tableName, config);

        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    static String generateEntityClass(final Connection conn, final String tableName, final EntityCodeConfig config) {
        final EntityCodeConfig configToUse = config == null ? defaultEntityCodeConfig : config;

        final String className = configToUse.getClassName();
        final String packageName = configToUse.getPackageName();
        final String srcDir = configToUse.getSrcDir();

        final BiFunction<String, String, String> fieldNameConverter = configToUse.getFieldNameConverter() == null ? (tn, cn) -> StringUtil.toCamelCase(cn)
                : configToUse.getFieldNameConverter();
        final QuadFunction<String, String, String, String, String> fieldTypeConverter = configToUse.getFieldTypeConverter();

        final Set<String> readOnlyFields = configToUse.getReadOnlyFields() == null ? new HashSet<>() : new HashSet<>(configToUse.getReadOnlyFields());

        final Set<String> nonUpdatableFields = configToUse.getNonUpdatableFields() == null ? new HashSet<>()
                : new HashSet<>(configToUse.getNonUpdatableFields());

        final Set<String> idFields = configToUse.getIdFields() == null ? new HashSet<>() : new HashSet<>(configToUse.getIdFields());

        if (N.notNullOrEmpty(configToUse.getIdField())) {
            idFields.add(configToUse.getIdField());
        }

        final Class<? extends Annotation> tableAnnotationClass = configToUse.getTableAnnotationClass() == null ? Table.class
                : configToUse.getTableAnnotationClass();

        final Class<? extends Annotation> columnAnnotationClass = configToUse.getColumnAnnotationClass() == null ? Column.class
                : configToUse.getColumnAnnotationClass();

        final Class<? extends Annotation> idAnnotationClass = configToUse.getIdAnnotationClass() == null ? Id.class : configToUse.getIdAnnotationClass();

        final boolean isJavaPersistenceTable = "javax.persistence.Table".equals(ClassUtil.getCanonicalClassName(tableAnnotationClass));
        final boolean isJavaPersistenceColumn = "javax.persistence.Column".equals(ClassUtil.getCanonicalClassName(columnAnnotationClass));
        final boolean isJavaPersistenceId = "javax.persistence.Id".equals(ClassUtil.getCanonicalClassName(idAnnotationClass));

        final Map<String, Tuple3<String, String, Class<?>>> customizedFieldMap = Maps.newMap(N.nullToEmpty(configToUse.getCustomizedFields()), tp -> tp._1);
        final Map<String, Tuple2<String, String>> customizedFieldDbTypeMap = Maps.newMap(N.nullToEmpty(configToUse.getCustomizedFieldDbTypes()), tp -> tp._1);

        try (PreparedStatement stmt = conn.prepareStatement("select * from " + tableName + " where 1 > 2"); //
                ResultSet rs = stmt.executeQuery()) {
            String finalClassName = N.isNullOrEmpty(className) ? StringUtil.capitalize(StringUtil.toCamelCase(tableName)) : className;

            if (N.commonSet(readOnlyFields, nonUpdatableFields).size() > 0) {
                throw new RuntimeException("Fields: " + N.commonSet(readOnlyFields, nonUpdatableFields)
                        + " can't be read-only and non-updatable at the same time in entity class: " + finalClassName);
            }

            if (idFields.size() == 0) {
                try (ResultSet pkColumns = conn.getMetaData().getPrimaryKeys(null, null, tableName)) {
                    while (pkColumns.next()) {
                        idFields.add(pkColumns.getString("COLUMN_NAME"));
                    }
                }
            }

            final StringBuilder sb = new StringBuilder();

            if (N.notNullOrEmpty(packageName)) {
                sb.append("package ").append(packageName + ";").append("\r\n");
            }

            String headPart = eccImports + "\r\n" + eccClassAnnos;

            if (isJavaPersistenceColumn) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.Column;\r\n", "");
            } else {
                headPart = headPart.replace("import javax.persistence.Column;\r\n", "");
            }

            if (isJavaPersistenceTable) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.Table;\r\n", "");
            } else {
                headPart = headPart.replace("import javax.persistence.Table;\r\n", "");
            }

            if (N.isNullOrEmpty(idFields)) {
                headPart = headPart.replace("import javax.persistence.Id;\r\n", "");
                headPart = headPart.replace("import com.landawn.abacus.annotation.Id;\r\n", "");
            } else if (isJavaPersistenceId) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.Id;\r\n", "");
            } else {
                headPart = headPart.replace("import javax.persistence.Id;\r\n", "");
            }

            if (N.isNullOrEmpty(nonUpdatableFields)) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.NonUpdatable;\r\n", "");
            }

            if (N.isNullOrEmpty(readOnlyFields)) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.ReadOnly;\r\n", "");
            }

            if (N.isNullOrEmpty(customizedFieldDbTypeMap)) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.Type;\r\n", "");
            }

            if (configToUse.getJsonXmlConfig() == null || configToUse.getJsonXmlConfig().getNamingPolicy() == null) {
                headPart = headPart.replace("import com.landawn.abacus.util.NamingPolicy;\r\n", "");
            }

            if (configToUse.getJsonXmlConfig() == null || configToUse.getJsonXmlConfig().getEnumerated() == null) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.Type.EnumBy;\r\n", "");
            }

            if (configToUse.getJsonXmlConfig() == null) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.JsonXmlConfig;\r\n", "");
            }

            if (!configToUse.isGenerateBuilder()) {
                headPart = headPart.replace("import lombok.Builder;\r\n", "").replace("@Builder\r\n", "");
            }

            if (!configToUse.isChainAccessor()) {
                headPart = headPart.replace("import lombok.experimental.Accessors;\r\n", "").replace("@Accessors(chain = true)\r\n", "");
            }

            if (headPart.contains("javax.persistence.")) {
                sb.append("\r\n");
            }

            sb.append(headPart);

            if (configToUse.getJsonXmlConfig() != null) {
                EntityCodeConfig.JsonXmlConfig eccJsonXmlConfig = configToUse.getJsonXmlConfig();

                final List<String> tmp = new ArrayList<>();

                if (eccJsonXmlConfig.getNamingPolicy() != null) {
                    tmp.add("namingPolicy = NamingPolicy." + eccJsonXmlConfig.getNamingPolicy().name());
                }

                if (N.notNullOrEmpty(eccJsonXmlConfig.getIgnoredFields())) {
                    tmp.add("ignoredFields = " + Splitter.with(",")
                            .trimResults()
                            .splitToStream(eccJsonXmlConfig.getIgnoredFields())
                            .map(it -> '\"' + it + '\"')
                            .join(", ", "{ ", " }"));
                }

                if (N.notNullOrEmpty(eccJsonXmlConfig.getDateFormat())) {
                    tmp.add("dateFormat = \"" + eccJsonXmlConfig.getDateFormat() + "\"");
                }

                if (N.notNullOrEmpty(eccJsonXmlConfig.getTimeZone())) {
                    tmp.add("timeZone = \"" + eccJsonXmlConfig.getTimeZone() + "\"");
                }

                if (N.notNullOrEmpty(eccJsonXmlConfig.getNumberFormat())) {
                    tmp.add("numberFormat = \"" + eccJsonXmlConfig.getNumberFormat() + "\"");
                }

                if (eccJsonXmlConfig.getEnumerated() != null) {
                    tmp.add("enumerated = EnumBy." + eccJsonXmlConfig.getEnumerated().name() + "");
                }

                sb.append("@JsonXmlConfig" + StringUtil.join(tmp, ", ", "(", ")")).append("\r\n");
            }

            sb.append(isJavaPersistenceTable ? "@Table(name = \"" + tableName + "\")" : "@Table(\"" + tableName + "\")")
                    .append("\r\n")
                    .append("public class " + finalClassName)
                    .append(" {")
                    .append("\r\n");

            final List<String> columnNameList = new ArrayList<>();
            final List<String> fieldNameList = new ArrayList<>();

            final ResultSetMetaData rsmd = rs.getMetaData();
            final int columnCount = rsmd.getColumnCount();

            for (int i = 1; i <= columnCount; i++) {
                final String columnName = rsmd.getColumnName(i);

                final Tuple3<String, String, Class<?>> customizedField = customizedFieldMap.getOrDefault(StringUtil.toCamelCase(columnName),
                        customizedFieldMap.get(columnName));

                final String fieldName = customizedField == null || N.isNullOrEmpty(customizedField._2) ? fieldNameConverter.apply(tableName, columnName)
                        : customizedField._2;

                final String columnClassName = customizedField == null || customizedField._3 == null
                        ? (fieldTypeConverter != null ? fieldTypeConverter.apply(tableName, columnName, fieldName, getColumnClassName(rsmd, i))
                                : getClassName(getColumnClassName(rsmd, i), false, configToUse))
                        : getClassName(ClassUtil.getCanonicalClassName(customizedField._3), true, configToUse);

                sb.append("\r\n");

                columnNameList.add(columnName);
                fieldNameList.add(fieldName);

                if (idFields.remove(fieldName) || idFields.remove(columnName)) {
                    sb.append(isJavaPersistenceId ? "    @Id" : "    @Id").append("\r\n");
                }

                if (readOnlyFields.remove(fieldName) || readOnlyFields.remove(columnName)) {
                    sb.append("    @ReadOnly").append("\r\n");
                } else if (nonUpdatableFields.remove(fieldName) || nonUpdatableFields.remove(columnName)) {
                    sb.append("    @NonUpdatable").append("\r\n");
                }

                sb.append(isJavaPersistenceColumn ? "    @Column(name = \"" + columnName + "\")" : "    @Column(\"" + columnName + "\")").append("\r\n");

                final Tuple2<String, String> dbType = customizedFieldDbTypeMap.getOrDefault(fieldName, customizedFieldDbTypeMap.get(columnName));

                if (dbType != null) {
                    sb.append("    @Type(name = \"" + dbType._2 + "\")").append("\r\n");
                }

                sb.append("    private " + columnClassName + " " + fieldName + ";").append("\r\n");
            }

            //    if (idFields.size() > 0) {
            //        throw new RuntimeException("Id fields: " + idFields + " are not found in entity class: " + finalClassName + ", table: " + tableName
            //                + ": with columns: " + columnNameList);
            //    }
            //
            //    if (readOnlyFields.size() > 0) {
            //        throw new RuntimeException("Read-only fields: " + readOnlyFields + " are not found in entity class: " + finalClassName + ", table: " + tableName
            //                + ": with columns: " + columnNameList);
            //    }
            //
            //    if (nonUpdatableFields.size() > 0) {
            //        throw new RuntimeException("Non-updatable fields: " + nonUpdatableFields + " are not found in entity class: " + finalClassName + ", table: "
            //                + tableName + ": with columns: " + columnNameList);
            //    }

            if (configToUse.isGenerateCopyMethod()) {
                sb.append("\r\n")
                        .append("    public " + className + " copy() {")
                        .append("\r\n") //
                        .append("        final " + className + " copy = new " + className + "();")
                        .append("\r\n"); //

                for (String fieldName : fieldNameList) {
                    sb.append("        copy." + fieldName + " = this." + fieldName + ";").append("\r\n");
                }

                sb.append("        return copy;").append("\r\n").append("    }").append("\r\n");

            }

            sb.append("\r\n").append("}").append("\r\n");

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

                File file = new File(packageDir + IOUtil.FILE_SEPARATOR + finalClassName + ".java");

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

    private static String getColumnClassName(final ResultSetMetaData rsmd, final int columnIndex) throws SQLException {
        String className = rsmd.getColumnClassName(columnIndex);

        try {
            className = ClassUtil.getCanonicalClassName(ClassUtil.forClass(className));
        } catch (Throwable e) {
            // ignore.
        }

        className = className.replace("java.lang.", "");

        return eccClassNameMap.getOrDefault(className, className);
    }

    private static String getClassName(final String columnClassName, final boolean isCustomizedType, final EntityCodeConfig configToUse) {
        String className = columnClassName.replace("java.lang.", "");

        if (isCustomizedType) {
            return className;
        } else if (configToUse.isMapBigIntegerToLong() && ClassUtil.getCanonicalClassName(BigInteger.class).equals(columnClassName)) {
            return "long";
        } else if (configToUse.isMapBigDecimalToDouble() && ClassUtil.getCanonicalClassName(BigDecimal.class).equals(columnClassName)) {
            return "double";
        } else if (configToUse.isUseBoxedType()) {
            return eccClassNameMap.containsValue(className) ? eccClassNameMap.getByValue(className) : className;
        } else {
            return className;
        }
    }
}