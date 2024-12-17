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
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import javax.sql.DataSource;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.Column;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.Table;
import com.landawn.abacus.annotation.Type.EnumBy;
import com.landawn.abacus.exception.UncheckedIOException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.BiMap;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.CodeGenerationUtil;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.IOUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NamingPolicy;
import com.landawn.abacus.util.Splitter;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.function.QuadFunction;
import com.landawn.abacus.util.function.TriFunction;
import com.landawn.abacus.util.stream.CharStream;
import com.landawn.abacus.util.stream.Stream;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * @see CodeGenerationUtil
 */
public final class JdbcCodeGenerationUtil {

    /**
     * Default name of class for field/prop names.
     */
    public static final String S = "s";

    /**
     * Default name of class for function field/prop names.
     */
    public static final String SF = "sf";

    /**
     * Default name of inner class for field names inside an entity class.
     */
    public static final String X = "x";

    public static final TriFunction<Class<?>, Class<?>, String, String> MIN_FUNC = (entityClass, propClass, propName) -> {
        if (Comparable.class.isAssignableFrom(propClass)) {
            return "min(" + propName + ")";
        }

        return null;
    };

    public static final TriFunction<Class<?>, Class<?>, String, String> MAX_FUNC = (entityClass, propClass, propName) -> {
        if (Comparable.class.isAssignableFrom(propClass)) {
            return "max(" + propName + ")";
        }

        return null;
    };

    private static final String LINE_SEPARATOR = IOUtil.LINE_SEPARATOR;

    private static final String eccImports = """
            import javax.persistence.Column;
            import javax.persistence.Id;
            import javax.persistence.Table;

            import com.landawn.abacus.annotation.Column;
            import com.landawn.abacus.annotation.Id;
            import com.landawn.abacus.annotation.JsonXmlConfig;
            import com.landawn.abacus.annotation.NonUpdatable;
            import com.landawn.abacus.annotation.ReadOnly;
            import com.landawn.abacus.annotation.Table;
            import com.landawn.abacus.annotation.Type;
            import com.landawn.abacus.annotation.Type.EnumBy;
            import com.landawn.abacus.util.NamingPolicy;

            import lombok.AllArgsConstructor;
            import lombok.Builder;
            import lombok.Data;
            import lombok.NoArgsConstructor;
            import lombok.experimental.Accessors;
            """;

    private static final String eccClassAnnos = """
            @Builder
            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            @Accessors(chain = true)
            """;

    private static final EntityCodeConfig defaultEntityCodeConfig = EntityCodeConfig.builder()
            .fieldNameConverter((tableName, columnName) -> Strings.toCamelCase(columnName))
            .build();

    @SuppressWarnings("deprecation")
    private static final BiMap<String, String> eccClassNameMap = BiMap.copyOf(N.asMap("Boolean", "boolean", "Character", "char", "Byte", "byte", "Short",
            "short", "Integer", "int", "Long", "long", "Float", "float", "Double", "double"));

    private JdbcCodeGenerationUtil() {
        // singleton.
    }

    /**
     * Generates the entity class for the specified table in the given data source.
     *
     * @param ds The data source to connect to the database.
     * @param tableName The name of the table for which the entity class is to be generated.
     * @return The generated entity class as a string.
     */
    public static String generateEntityClass(final DataSource ds, final String tableName) {
        return generateEntityClass(ds, tableName, (EntityCodeConfig) null);
    }

    /**
     * Generates the entity class for the specified table in the given data source with the provided configuration.
     *
     * @param ds The data source to connect to the database.
     * @param tableName The name of the table for which the entity class is to be generated.
     * @param config The configuration for generating the entity class.
     * @return The generated entity class as a string.
     */
    public static String generateEntityClass(final DataSource ds, final String tableName, final EntityCodeConfig config) {
        return generateEntityClass(ds, tableName, createQueryByTableName(tableName), config);
    }

    /**
     * Generates the entity class for the specified table in the given data source using the provided query.
     *
     * @param ds The data source to connect to the database.
     * @param entityName The name of the entity for which the class is to be generated.
     * @param query The SQL query to execute for retrieving the table metadata.
     * @return The generated entity class as a string.
     */
    public static String generateEntityClass(final DataSource ds, final String entityName, final String query) {
        return generateEntityClass(ds, entityName, query, null);
    }

    /**
     * Generates the entity class for the specified table in the given data source using the provided query and configuration.
     *
     * @param ds The data source to connect to the database.
     * @param entityName The name of the entity for which the class is to be generated.
     * @param query The SQL query to execute for retrieving the table metadata.
     * @param config The configuration for generating the entity class.
     * @return The generated entity class as a string.
     */
    public static String generateEntityClass(final DataSource ds, final String entityName, final String query, final EntityCodeConfig config) {
        try (Connection conn = ds.getConnection()) {
            return generateEntityClass(conn, entityName, query, config);

        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Generates the entity class for the specified table in the given connection.
     *
     * @param conn The connection to the database.
     * @param tableName The name of the table for which the entity class is to be generated.
     * @return The generated entity class as a string.
     */
    public static String generateEntityClass(final Connection conn, final String tableName) {
        return generateEntityClass(conn, tableName, (EntityCodeConfig) null);
    }

    /**
     * Generates the entity class for the specified table in the given connection with the provided configuration.
     *
     * @param conn The connection to the database.
     * @param tableName The name of the table for which the entity class is to be generated.
     * @param config The configuration for generating the entity class.
     * @return The generated entity class as a string.
     */
    public static String generateEntityClass(final Connection conn, final String tableName, final EntityCodeConfig config) {
        return generateEntityClass(conn, tableName, createQueryByTableName(tableName), config);
    }

    /**
     * Generates the entity class for the specified table in the given connection using the provided query.
     *
     * @param conn The connection to the database.
     * @param entityName The name of the entity for which the class is to be generated.
     * @param query The SQL query to execute for retrieving the table metadata.
     * @return The generated entity class as a string.
     */
    public static String generateEntityClass(final Connection conn, final String entityName, final String query) {
        return generateEntityClass(conn, entityName, query, null);
    }

    /**
     * Generates the entity class for the specified table in the given connection using the provided query and configuration.
     *
     * @param conn The connection to the database.
     * @param entityName The name of the entity for which the class is to be generated.
     * @param query The SQL query to execute for retrieving the table metadata.
     * @param config The configuration for generating the entity class.
     * @return The generated entity class as a string.
     */
    public static String generateEntityClass(final Connection conn, final String entityName, final String query, final EntityCodeConfig config) {
        try (PreparedStatement stmt = JdbcUtil.prepareStatement(conn, query); //
                ResultSet rs = stmt.executeQuery()) {

            return generateEntityClass(entityName, rs, config);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    static String generateEntityClass(final String entityName, final ResultSet rs, final EntityCodeConfig config) {
        final EntityCodeConfig configToUse = N.defaultIfNull(config, defaultEntityCodeConfig);

        final String className = configToUse.getClassName();
        final String packageName = configToUse.getPackageName();
        final String srcDir = configToUse.getSrcDir();

        final BiFunction<String, String, String> fieldNameConverter = N.defaultIfNull(configToUse.getFieldNameConverter(), (tn, cn) -> Strings.toCamelCase(cn));

        final QuadFunction<String, String, String, String, String> fieldTypeConverter = configToUse.getFieldTypeConverter();

        final Map<String, Tuple3<String, String, Class<?>>> customizedFieldMap = N.toMap(N.nullToEmpty(configToUse.getCustomizedFields()),
                tp -> tp._1.toLowerCase());

        final Map<String, Tuple2<String, String>> customizedFieldDbTypeMap = N.toMap(N.nullToEmpty(configToUse.getCustomizedFieldDbTypes()), tp -> tp._1);

        final Set<String> readOnlyFields = configToUse.getReadOnlyFields() == null ? new HashSet<>() : new HashSet<>(configToUse.getReadOnlyFields());

        final Set<String> nonUpdatableFields = configToUse.getNonUpdatableFields() == null ? new HashSet<>()
                : new HashSet<>(configToUse.getNonUpdatableFields());

        final Set<String> idFields = configToUse.getIdFields() == null ? new HashSet<>() : new HashSet<>(configToUse.getIdFields());

        if (Strings.isNotEmpty(configToUse.getIdField())) {
            idFields.add(configToUse.getIdField());
        }

        final Class<? extends Annotation> tableAnnotationClass = configToUse.getTableAnnotationClass() == null ? Table.class
                : configToUse.getTableAnnotationClass();

        final Class<? extends Annotation> columnAnnotationClass = configToUse.getColumnAnnotationClass() == null ? Column.class
                : configToUse.getColumnAnnotationClass();

        final Class<? extends Annotation> idAnnotationClass = configToUse.getIdAnnotationClass() == null ? Id.class : configToUse.getIdAnnotationClass();

        final String tableAnnotationClassName = ClassUtil.getCanonicalClassName(tableAnnotationClass);
        final String columnAnnotationClassName = ClassUtil.getCanonicalClassName(columnAnnotationClass);
        final String idAnnotationClassName = ClassUtil.getCanonicalClassName(idAnnotationClass);

        final boolean isJavaPersistenceTable = "javax.persistence.Table".equals(tableAnnotationClassName)
                || "jakarta.persistence.Table".equals(ClassUtil.getCanonicalClassName(tableAnnotationClass));
        final boolean isJavaPersistenceColumn = "javax.persistence.Column".equals(columnAnnotationClassName)
                || "jakarta.persistence.Column".equals(ClassUtil.getCanonicalClassName(columnAnnotationClass));
        final boolean isJavaPersistenceId = "javax.persistence.Id".equals(idAnnotationClassName)
                || "jakarta.persistence.Id".equals(ClassUtil.getCanonicalClassName(idAnnotationClass));

        try {
            final String finalClassName = Strings.isEmpty(className) ? Strings.capitalize(Strings.toCamelCase(entityName)) : className;

            if (N.commonSet(readOnlyFields, nonUpdatableFields).size() > 0) {
                throw new RuntimeException("Fields: " + N.commonSet(readOnlyFields, nonUpdatableFields)
                        + " can't be read-only and non-updatable at the same time in entity class: " + finalClassName);
            }

            final List<Tuple2<String, String>> additionalFields = Strings.isEmpty(configToUse.getAdditionalFieldsOrLines()) ? new ArrayList<>()
                    : Stream.split(configToUse.getAdditionalFieldsOrLines(), "\n")
                            .map(it -> it.contains("//") ? Strings.substringBefore(it, "//") : it)
                            .map(Strings::strip)
                            .peek(Fn.println())
                            .filter(Fn.notEmpty())
                            .filter(it -> Strings.startsWithAny(it, "private ", "protected ", "public ") && it.endsWith(";"))
                            .map(it -> Strings.substringBetween(it, " ", ";").trim())
                            .map(it -> {
                                final int idx = it.lastIndexOf(' ');
                                return Tuple.of(it.substring(0, idx).trim(), it.substring(idx + 1).trim());
                            })
                            .toList();

            final Collection<String> excludedFields = configToUse.getExcludedFields();
            final List<String> columnNameList = new ArrayList<>();
            final List<String> columnClassNameList = new ArrayList<>();
            final List<String> fieldNameList = new ArrayList<>();

            final ResultSetMetaData rsmd = rs.getMetaData();
            final int columnCount = rsmd.getColumnCount();

            for (int i = 1; i <= columnCount; i++) {
                final String columnName = rsmd.getColumnName(i);

                final Tuple3<String, String, Class<?>> customizedField = customizedFieldMap.getOrDefault(columnName.toLowerCase(),
                        customizedFieldMap.get(Strings.toCamelCase(columnName)));

                final String fieldName = customizedField == null || Strings.isEmpty(customizedField._2) ? fieldNameConverter.apply(entityName, columnName)
                        : customizedField._2;

                if (N.notEmpty(excludedFields) && (excludedFields.contains(fieldName) || excludedFields.contains(columnName))) {
                    continue;
                }

                final String columnClassName = customizedField == null || customizedField._3 == null
                        ? mapColumClassName((fieldTypeConverter == null ? getColumnClassName(rsmd, i)
                                : fieldTypeConverter.apply(entityName, fieldName, columnName, getColumnClassName(rsmd, i))), false, configToUse)
                        : mapColumClassName(ClassUtil.getCanonicalClassName(customizedField._3), true, configToUse);

                columnNameList.add(columnName);
                fieldNameList.add(fieldName);
                columnClassNameList.add(columnClassName);
            }

            if (N.isEmpty(idFields) || N.intersection(idFields, fieldNameList).isEmpty()) {
                try (ResultSet pkColumns = rs.getStatement().getConnection().getMetaData().getPrimaryKeys(null, null, entityName)) {
                    while (pkColumns.next()) {
                        idFields.add(pkColumns.getString("COLUMN_NAME"));
                    }
                }
            }

            final StringBuilder sb = new StringBuilder();

            if (Strings.isNotEmpty(packageName)) {
                sb.append("package ").append(packageName).append(";");
            }

            String headPart = "";

            for (final Tuple2<String, String> tp : additionalFields) {
                if (tp._1.indexOf('<') > 0) { //NOSONAR
                    final String clsName = tp._1.substring(0, tp._1.indexOf('<'));

                    try { //NOSONAR
                        if (ClassUtil.forClass("java.util." + clsName) != null) {
                            //noinspection StringConcatenationInLoop
                            headPart += LINE_SEPARATOR + "import java.util." + clsName + ";"; //NOSONAR
                        }
                    } catch (final Exception e) {
                        // ignore.
                    }
                }
            }

            if (Strings.isNotEmpty(headPart)) {
                headPart += LINE_SEPARATOR;
            }

            headPart += LINE_SEPARATOR + eccImports + LINE_SEPARATOR + eccClassAnnos;

            if (isJavaPersistenceTable) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.Table;\n", "");
                headPart = headPart.replace("javax.persistence.Table", tableAnnotationClassName);
            } else {
                headPart = headPart.replace("import javax.persistence.Table;\n", "");
            }

            if (isJavaPersistenceColumn) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.Column;\n", "");
                headPart = headPart.replace("javax.persistence.Column", columnAnnotationClassName);
            } else {
                headPart = headPart.replace("import javax.persistence.Column;\n", "");
            }

            if (N.isEmpty(idFields) || N.intersection(idFields, fieldNameList).isEmpty()) {
                headPart = headPart.replace("import javax.persistence.Id;\n", "");
                headPart = headPart.replace("import com.landawn.abacus.annotation.Id;\n", "");
            } else if (isJavaPersistenceId) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.Id;\n", "");
                headPart = headPart.replace("javax.persistence.Id", idAnnotationClassName);
            } else {
                headPart = headPart.replace("import javax.persistence.Id;\n", "");
            }

            if (N.isEmpty(nonUpdatableFields) || N.intersection(nonUpdatableFields, fieldNameList).isEmpty()) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.NonUpdatable;\n", "");
            }

            if (N.isEmpty(readOnlyFields) || N.intersection(readOnlyFields, fieldNameList).isEmpty()) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.ReadOnly;\n", "");
            }

            if (N.isEmpty(customizedFieldDbTypeMap) || N.intersection(customizedFieldDbTypeMap.keySet(), fieldNameList).isEmpty()) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.Type;\n", "");
            }

            if (configToUse.getJsonXmlConfig() == null || configToUse.getJsonXmlConfig().getNamingPolicy() == null) {
                headPart = headPart.replace("import com.landawn.abacus.util.NamingPolicy;\n", "");
            }

            if (configToUse.getJsonXmlConfig() == null || configToUse.getJsonXmlConfig().getEnumerated() == null) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.Type.EnumBy;\n", "");
            }

            if (configToUse.getJsonXmlConfig() == null) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.JsonXmlConfig;\n", "");
            }

            if (!configToUse.isGenerateBuilder()) {
                headPart = headPart.replace("import lombok.Builder;\n", "").replace("@Builder\n", "");
            }

            if (!configToUse.isChainAccessor()) {
                headPart = headPart.replace("import lombok.experimental.Accessors;\n", "").replace("@Accessors(chain = true)\n", "");
            }

            if (headPart.contains("javax.persistence.")) {
                sb.append(LINE_SEPARATOR);
            }

            sb.append(headPart);

            if (configToUse.getJsonXmlConfig() != null) {
                final EntityCodeConfig.JsonXmlConfig eccJsonXmlConfig = configToUse.getJsonXmlConfig();

                final List<String> tmp = new ArrayList<>();

                if (eccJsonXmlConfig.getNamingPolicy() != null) {
                    tmp.add("namingPolicy = NamingPolicy." + eccJsonXmlConfig.getNamingPolicy().name());
                }

                if (Strings.isNotEmpty(eccJsonXmlConfig.getIgnoredFields())) {
                    tmp.add("ignoredFields = " + Splitter.with(",")
                            .trimResults()
                            .splitToStream(eccJsonXmlConfig.getIgnoredFields())
                            .map(it -> '\"' + it + '\"')
                            .join(", ", "{ ", " }"));
                }

                if (Strings.isNotEmpty(eccJsonXmlConfig.getDateFormat())) {
                    tmp.add("dateFormat = \"" + eccJsonXmlConfig.getDateFormat() + "\"");
                }

                if (Strings.isNotEmpty(eccJsonXmlConfig.getTimeZone())) {
                    tmp.add("timeZone = \"" + eccJsonXmlConfig.getTimeZone() + "\"");
                }

                if (Strings.isNotEmpty(eccJsonXmlConfig.getNumberFormat())) {
                    tmp.add("numberFormat = \"" + eccJsonXmlConfig.getNumberFormat() + "\"");
                }

                if (eccJsonXmlConfig.getEnumerated() != null) {
                    tmp.add("enumerated = EnumBy." + eccJsonXmlConfig.getEnumerated().name());
                }

                sb.append("@JsonXmlConfig").append(Strings.join(tmp, ", ", "(", ")")).append(LINE_SEPARATOR);
            }

            sb.append(isJavaPersistenceTable ? "@Table(name = \"" + entityName + "\")" : "@Table(\"" + entityName + "\")")
                    .append(LINE_SEPARATOR)
                    .append("public class ")
                    .append(finalClassName)
                    .append(" {")
                    .append(LINE_SEPARATOR);

            for (int i = 0, size = columnNameList.size(); i < size; i++) {
                final String columnName = columnNameList.get(i);
                final String fieldName = fieldNameList.get(i);
                final String columnClassName = columnClassNameList.get(i);

                sb.append(LINE_SEPARATOR);

                if (idFields.remove(fieldName) || idFields.remove(columnName)) {
                    sb.append(isJavaPersistenceId ? "    @Id" : "    @Id").append(LINE_SEPARATOR); //NOSONAR
                }

                if (readOnlyFields.remove(fieldName) || readOnlyFields.remove(columnName)) {
                    sb.append("    @ReadOnly").append(LINE_SEPARATOR);
                } else if (nonUpdatableFields.remove(fieldName) || nonUpdatableFields.remove(columnName)) {
                    sb.append("    @NonUpdatable").append(LINE_SEPARATOR);
                }

                sb.append(isJavaPersistenceColumn ? "    @Column(name = \"" + columnName + "\")" : "    @Column(\"" + columnName + "\")")
                        .append(LINE_SEPARATOR);

                final Tuple2<String, String> dbType = customizedFieldDbTypeMap.getOrDefault(fieldName, customizedFieldDbTypeMap.get(columnName));

                if (dbType != null) {
                    sb.append("    @Type(name = \"").append(dbType._2).append("\")").append(LINE_SEPARATOR);
                }

                sb.append("    private ").append(columnClassName).append(" ").append(fieldName).append(";").append(LINE_SEPARATOR);
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

            if (Strings.isNotEmpty(configToUse.getAdditionalFieldsOrLines())) {
                sb.append(LINE_SEPARATOR).append(configToUse.getAdditionalFieldsOrLines());
            }

            if (configToUse.isGenerateCopyMethod()) {
                // TODO extract fields from additionalFieldsOrLines?

                //
                sb.append(LINE_SEPARATOR)
                        .append("    public ")
                        .append(className)
                        .append(" copy() {")
                        .append(LINE_SEPARATOR)
                        .append("        final ")
                        .append(className)
                        .append(" copy = new ")
                        .append(className)
                        .append("();")
                        .append(LINE_SEPARATOR); //

                for (final String fieldName : fieldNameList) {
                    sb.append("        copy.").append(fieldName).append(" = this.").append(fieldName).append(";").append(LINE_SEPARATOR);
                }

                for (final Tuple2<String, String> tp : additionalFields) {
                    sb.append("        copy.").append(tp._2).append(" = this.").append(tp._2).append(";").append(LINE_SEPARATOR);
                }

                sb.append("        return copy;").append(LINE_SEPARATOR).append("    }").append(LINE_SEPARATOR);
            }

            if (configToUse.isGenerateFieldNameTable()) {
                sb.append(LINE_SEPARATOR)
                        .append("    /*")
                        .append(LINE_SEPARATOR)
                        .append("     * Auto-generated class for property(field) name table by abacus-jdbc.")
                        .append(LINE_SEPARATOR)
                        .append("     */");

                sb.append(LINE_SEPARATOR)
                        .append("    public interface ")
                        .append(X)
                        .append(" {")
                        .append(Character.isLowerCase(X.charAt(0)) ? " // NOSONAR" : "")
                        .append(LINE_SEPARATOR)
                        .append(LINE_SEPARATOR); //

                for (final String fieldName : fieldNameList) {
                    sb.append("        /** Property(field) name {@code \"")
                            .append(fieldName)
                            .append("\"} */")
                            .append(LINE_SEPARATOR)
                            .append("        String ")
                            .append(fieldName)
                            .append(" = \"")
                            .append(fieldName)
                            .append("\";")
                            .append(LINE_SEPARATOR)
                            .append(LINE_SEPARATOR);
                }

                sb.append("    }").append(LINE_SEPARATOR);
            }

            sb.append(LINE_SEPARATOR).append("}").append(LINE_SEPARATOR);

            final String result = sb.toString();

            if (Strings.isNotEmpty(srcDir)) {
                String packageDir = srcDir;

                if (Strings.isNotEmpty(packageName)) {
                    if (!(packageDir.endsWith("/") || packageDir.endsWith("\\"))) {
                        packageDir += "/";
                    }

                    packageDir += Strings.replaceAll(packageName, ".", "/");
                }

                IOUtil.mkdirsIfNotExists(new File(packageDir));

                final File file = new File(packageDir + IOUtil.DIR_SEPARATOR + finalClassName + ".java");

                IOUtil.createIfNotExists(file);

                IOUtil.write(result, file);
            }

            return result;
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String createQueryByTableName(final String tableName) {
        return "select * from " + tableName + " where 1 > 2"; // NOSONAR
    }

    private static String getColumnClassName(final ResultSetMetaData rsmd, final int columnIndex) throws SQLException {
        String columnClassName = rsmd.getColumnClassName(columnIndex);

        try {
            columnClassName = ClassUtil.getCanonicalClassName(ClassUtil.forClass(columnClassName));
        } catch (final Throwable e) {
            // ignore.
        }

        if ("oracle.sql.TIMESTAMP".equals(columnClassName) || "oracle.sql.TIMESTAMPTZ".equals(columnClassName)
                || Strings.endsWithIgnoreCase(columnClassName, ".Timestamp") || Strings.endsWithIgnoreCase(columnClassName, ".DateTime")) {
            columnClassName = ClassUtil.getCanonicalClassName(java.sql.Timestamp.class);
        } else if ("oracle.sql.DATE".equals(columnClassName) || Strings.endsWithIgnoreCase(columnClassName, ".Date")) {
            columnClassName = ClassUtil.getCanonicalClassName(java.sql.Date.class);
        } else if ("oracle.sql.TIME".equals(columnClassName) || Strings.endsWithIgnoreCase(columnClassName, ".Time")) {
            columnClassName = ClassUtil.getCanonicalClassName(java.sql.Time.class);
        }

        columnClassName = columnClassName.replace("java.lang.", "");

        return eccClassNameMap.getOrDefault(columnClassName, columnClassName);
    }

    private static String mapColumClassName(final String columnClassName, final boolean isCustomizedType, final EntityCodeConfig configToUse) {
        String className = columnClassName.replace("java.lang.", "");

        if (isCustomizedType) {
            return className;
        }

        if (configToUse.isMapBigIntegerToLong() && ClassUtil.getCanonicalClassName(BigInteger.class).equals(columnClassName)) {
            className = "long";
        } else if (configToUse.isMapBigDecimalToDouble() && ClassUtil.getCanonicalClassName(BigDecimal.class).equals(columnClassName)) {
            className = "double";
        }

        if (configToUse.isUseBoxedType() && eccClassNameMap.containsValue(className)) {
            className = eccClassNameMap.getByValue(className);
        }

        return className;
    }

    /**
     *
     *
     * @param dataSource
     * @param tableName
     * @return
     * @throws UncheckedSQLException
     */
    public static String generateSelectSql(final DataSource dataSource, final String tableName) throws UncheckedSQLException {
        try (Connection conn = dataSource.getConnection()) {
            return generateSelectSql(conn, tableName);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     *
     * @param conn
     * @param tableName
     * @return
     */
    public static String generateSelectSql(final Connection conn, final String tableName) {
        final String query = "select * from " + tableName + " where 1 > 2";

        try (final PreparedStatement stmt = JdbcUtil.prepareStatement(conn, query); //
                final ResultSet rs = stmt.executeQuery()) {

            final List<String> columnLabelList = JdbcUtil.getColumnLabelList(rs);

            return Strings.join(checkColumnName(columnLabelList), ", ", "select ", " from " + checkTableName(tableName));
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     *
     * @param dataSource
     * @param tableName
     * @return
     * @throws UncheckedSQLException
     */
    public static String generateInsertSql(final DataSource dataSource, final String tableName) throws UncheckedSQLException {
        try (Connection conn = dataSource.getConnection()) {
            return generateInsertSql(conn, tableName);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     *
     * @param conn
     * @param tableName
     * @return
     */
    public static String generateInsertSql(final Connection conn, final String tableName) {
        final String query = "select * from " + tableName + " where 1 > 2";

        try (final PreparedStatement stmt = JdbcUtil.prepareStatement(conn, query); //
                final ResultSet rs = stmt.executeQuery()) {

            final List<String> columnLabelList = JdbcUtil.getColumnLabelList(rs);

            return Strings.join(checkColumnName(columnLabelList), ", ", "insert into " + checkTableName(tableName) + "(",
                    ") values (" + Strings.repeat("?", columnLabelList.size(), ", ") + ")");
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     *
     * @param dataSource
     * @param tableName
     * @return
     * @throws UncheckedSQLException
     */
    public static String generateNamedInsertSql(final DataSource dataSource, final String tableName) throws UncheckedSQLException {
        try (Connection conn = dataSource.getConnection()) {
            return generateNamedInsertSql(conn, tableName);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     *
     * @param conn
     * @param tableName
     * @return
     */
    public static String generateNamedInsertSql(final Connection conn, final String tableName) {
        final String query = "select * from " + tableName + " where 1 > 2";

        try (final PreparedStatement stmt = JdbcUtil.prepareStatement(conn, query); //
                final ResultSet rs = stmt.executeQuery()) {

            final List<String> columnLabelList = JdbcUtil.getColumnLabelList(rs);

            return Strings.join(checkColumnName(columnLabelList), ", ", "insert into " + checkTableName(tableName) + "(",
                    Stream.of(columnLabelList).map(it -> ":" + Strings.toCamelCase(it)).join(", ", ") values (", ")"));
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     *
     * @param dataSource
     * @param tableName
     * @return
     * @throws UncheckedSQLException
     */
    public static String generateUpdateSql(final DataSource dataSource, final String tableName) throws UncheckedSQLException {
        try (Connection conn = dataSource.getConnection()) {
            return generateUpdateSql(conn, tableName);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     *
     * @param conn
     * @param tableName
     * @return
     */
    public static String generateUpdateSql(final Connection conn, final String tableName) {
        final String query = "select * from " + tableName + " where 1 > 2";

        try (final PreparedStatement stmt = JdbcUtil.prepareStatement(conn, query); //
                final ResultSet rs = stmt.executeQuery()) {

            final List<String> columnLabelList = JdbcUtil.getColumnLabelList(rs);

            return "update " + checkTableName(tableName) + " set "
                    + Stream.of(columnLabelList).map(columnLabel -> checkColumnName(columnLabel) + " = ?").join(", ");
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     *
     * @param dataSource
     * @param tableName
     * @return
     * @throws UncheckedSQLException
     */
    public static String generateNamedUpdateSql(final DataSource dataSource, final String tableName) throws UncheckedSQLException {
        try (Connection conn = dataSource.getConnection()) {
            return generateNamedUpdateSql(conn, tableName);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     *
     *
     * @param conn
     * @param tableName
     * @return
     */
    public static String generateNamedUpdateSql(final Connection conn, final String tableName) {
        final String query = "select * from " + tableName + " where 1 > 2";

        try (final PreparedStatement stmt = JdbcUtil.prepareStatement(conn, query); //
                final ResultSet rs = stmt.executeQuery()) {

            final List<String> columnLabelList = JdbcUtil.getColumnLabelList(rs);

            return "update " + checkTableName(tableName) + " set "
                    + Stream.of(columnLabelList).map(columnLabel -> checkColumnName(columnLabel) + " = :" + Strings.toCamelCase(columnLabel)).join(", ");
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    private static String checkTableName(final String tableName) {
        return CharStream.of(tableName).allMatch(ch -> Strings.isAsciiAlpha(ch) || Strings.isAsciiNumeric(ch) || ch == '_') ? tableName
                : Strings.wrap(tableName, "`");
    }

    private static String checkColumnName(final String columnLabel) {
        return CharStream.of(columnLabel).allMatch(ch -> Strings.isAsciiAlpha(ch) || Strings.isAsciiNumeric(ch) || ch == '_') ? columnLabel
                : Strings.wrap(columnLabel, "`");
    }

    private static List<String> checkColumnName(final List<String> columnLabelList) {
        return N.map(columnLabelList, JdbcCodeGenerationUtil::checkColumnName);
    }

    /**
     * A sample, just a sample, not a general configuration required.
     * <pre>
     * EntityCodeConfig ecc = EntityCodeConfig.builder()
     *        .className("User")
     *        .packageName("codes.entity")
     *        .srcDir("./samples")
     *        .fieldNameConverter((entityOrTableName, columnName) -> StringUtil.toCamelCase(columnName))
     *        .fieldTypeConverter((entityOrTableName, fieldName, columnName, columnClassName) -> columnClassName // columnClassName <- resultSetMetaData.getColumnClassName(columnIndex);
     *                .replace("java.lang.", ""))
     *        .useBoxedType(false)
     *        .readOnlyFields(N.asSet("id"))
     *        .nonUpdatableFields(N.asSet("create_time"))
     *        // .idAnnotationClass(javax.persistence.Id.class)
     *        // .columnAnnotationClass(javax.persistence.Column.class)
     *        // .tableAnnotationClass(javax.persistence.Table.class)
     *        .customizedFields(N.asList(Tuple.of("columnName", "fieldName", java.util.Date.class)))
     *        .customizedFieldDbTypes(N.asList(Tuple.of("fieldName", "List<String>")))
     *        .build();
     * </pre>
     *
     */
    @Builder
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    public static final class EntityCodeConfig {
        private String srcDir;
        private String packageName;
        private String className;

        /**
         * First parameter in the function is entity/table name, 2nd is column name.
         */
        private BiFunction<String, String, String> fieldNameConverter;
        /**
         * First parameter in the function is entity/table name, 2nd is field name, 3rd is column name, 4th is column class name
         */
        private QuadFunction<String, String, String, String, String> fieldTypeConverter;

        /**
         *
         * First parameter in the Tuple is column name, 2nd is field name, 3rd is column class.
         *
         */
        private List<Tuple3<String, String, Class<?>>> customizedFields;

        /**
         * First parameter in the Tuple is field name, 2nd is db type.
         *
         */
        private List<Tuple2<String, String>> customizedFieldDbTypes;

        private boolean useBoxedType;
        private boolean mapBigIntegerToLong;
        private boolean mapBigDecimalToDouble;

        private Collection<String> readOnlyFields;
        private Collection<String> nonUpdatableFields;
        private Collection<String> idFields;
        private String idField;

        private Collection<String> excludedFields;
        private String additionalFieldsOrLines;

        private Class<? extends Annotation> tableAnnotationClass;
        private Class<? extends Annotation> columnAnnotationClass;
        private Class<? extends Annotation> idAnnotationClass;

        private boolean chainAccessor;
        private boolean generateBuilder;
        private boolean generateCopyMethod;
        private boolean generateFieldNameTable;
        private boolean extendFieldNameTableClassName;
        // private String fieldNameTableClassName; // Always be "NT";

        // private List<Tuple2<String, String>> customizedJsonFields;
        @Beta
        private JsonXmlConfig jsonXmlConfig;

        @Builder
        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @Accessors(chain = true)
        public static class JsonXmlConfig {
            private NamingPolicy namingPolicy;

            private String ignoredFields;

            private String dateFormat;

            private String timeZone;

            private String numberFormat;

            private EnumBy enumerated;
        }
    }
}
