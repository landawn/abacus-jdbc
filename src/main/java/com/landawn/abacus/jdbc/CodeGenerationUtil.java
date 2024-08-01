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
import com.landawn.abacus.exception.UncheckedIOException;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.BiMap;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.IOUtil;
import com.landawn.abacus.util.ListMultimap;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Splitter;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.function.QuadFunction;
import com.landawn.abacus.util.stream.CharStream;
import com.landawn.abacus.util.stream.Stream;

public final class CodeGenerationUtil {
    public static final String S = "s";
    public static final String X = "x";

    private static final String LINE_SEPERATOR = IOUtil.LINE_SEPARATOR;

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

    private CodeGenerationUtil() {
        // singleton.
    }

    /**
     *
     *
     * @param ds
     * @param tableName
     * @return
     */
    public static String generateEntityClass(final DataSource ds, final String tableName) {
        return generateEntityClass(ds, tableName, (EntityCodeConfig) null);
    }

    /**
     *
     *
     * @param ds
     * @param tableName
     * @param config
     * @return
     */
    public static String generateEntityClass(final DataSource ds, final String tableName, final EntityCodeConfig config) {
        return generateEntityClass(ds, tableName, createQueryByTableName(tableName), config);
    }

    public static String generateEntityClass(final DataSource ds, final String entityName, String query) {
        return generateEntityClass(ds, entityName, query, null);
    }

    public static String generateEntityClass(final DataSource ds, final String entityName, String query, final EntityCodeConfig config) {
        try (Connection conn = ds.getConnection()) {
            return generateEntityClass(conn, entityName, query, config);

        } catch (SQLException e) {
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
    public static String generateEntityClass(final Connection conn, final String tableName) {
        return generateEntityClass(conn, tableName, (EntityCodeConfig) null);
    }

    public static String generateEntityClass(final Connection conn, final String tableName, final EntityCodeConfig config) {
        return generateEntityClass(conn, tableName, createQueryByTableName(tableName), config);
    }

    public static String generateEntityClass(final Connection conn, final String entityName, String query) {
        return generateEntityClass(conn, entityName, query, null);
    }

    public static String generateEntityClass(final Connection conn, final String entityName, String query, final EntityCodeConfig config) {
        try (PreparedStatement stmt = conn.prepareStatement(query); //
                ResultSet rs = stmt.executeQuery()) {

            return generateEntityClass(entityName, rs, config);
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    static String generateEntityClass(final String entityName, final ResultSet rs, final EntityCodeConfig config) {
        final EntityCodeConfig configToUse = config == null ? defaultEntityCodeConfig : config;

        final String className = configToUse.getClassName();
        final String packageName = configToUse.getPackageName();
        final String srcDir = configToUse.getSrcDir();

        final BiFunction<String, String, String> fieldNameConverter = configToUse.getFieldNameConverter() == null ? (tn, cn) -> Strings.toCamelCase(cn)
                : configToUse.getFieldNameConverter();

        final QuadFunction<String, String, String, String, String> fieldTypeConverter = configToUse.getFieldTypeConverter();

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

        final boolean isJavaPersistenceTable = "javax.persistence.Table".equals(ClassUtil.getCanonicalClassName(tableAnnotationClass))
                || "jakarta.persistence.Table".equals(ClassUtil.getCanonicalClassName(tableAnnotationClass));
        final boolean isJavaPersistenceColumn = "javax.persistence.Column".equals(ClassUtil.getCanonicalClassName(columnAnnotationClass))
                || "jakarta.persistence.Column".equals(ClassUtil.getCanonicalClassName(columnAnnotationClass));
        final boolean isJavaPersistenceId = "javax.persistence.Id".equals(ClassUtil.getCanonicalClassName(idAnnotationClass))
                || "jakarta.persistence.Id".equals(ClassUtil.getCanonicalClassName(idAnnotationClass));

        final Map<String, Tuple3<String, String, Class<?>>> customizedFieldMap = N.toMap(N.nullToEmpty(configToUse.getCustomizedFields()), tp -> tp._1);
        final Map<String, Tuple2<String, String>> customizedFieldDbTypeMap = N.toMap(N.nullToEmpty(configToUse.getCustomizedFieldDbTypes()), tp -> tp._1);

        try {
            String finalClassName = Strings.isEmpty(className) ? Strings.capitalize(Strings.toCamelCase(entityName)) : className;

            if (N.commonSet(readOnlyFields, nonUpdatableFields).size() > 0) {
                throw new RuntimeException("Fields: " + N.commonSet(readOnlyFields, nonUpdatableFields)
                        + " can't be read-only and non-updatable at the same time in entity class: " + finalClassName);
            }

            if (idFields.size() == 0) {
                try (ResultSet pkColumns = rs.getStatement().getConnection().getMetaData().getPrimaryKeys(null, null, entityName)) {
                    while (pkColumns.next()) {
                        idFields.add(pkColumns.getString("COLUMN_NAME"));
                    }
                }
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
                                int idx = it.lastIndexOf(' ');
                                return Tuple.of(it.substring(0, idx).trim(), it.substring(idx + 1).trim());
                            })
                            .toList();

            final StringBuilder sb = new StringBuilder();

            if (Strings.isNotEmpty(packageName)) {
                sb.append("package ").append(packageName + ";");
            }

            String headPart = "";

            for (Tuple2<String, String> tp : additionalFields) {
                if (tp._1.indexOf('<') > 0) { //NOSONAR
                    String clsName = tp._1.substring(0, tp._1.indexOf('<'));

                    try { //NOSONAR
                        if (ClassUtil.forClass("java.util." + clsName) != null) {
                            headPart += LINE_SEPERATOR + "import java.util." + clsName + ";"; //NOSONAR
                        }
                    } catch (Exception e) {
                        // ignore.
                    }
                }
            }

            if (Strings.isNotEmpty(headPart)) {
                headPart += LINE_SEPERATOR;
            }

            headPart += LINE_SEPERATOR + eccImports + LINE_SEPERATOR + eccClassAnnos;

            if (isJavaPersistenceColumn) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.Column;\n", "");
            } else {
                headPart = headPart.replace("import javax.persistence.Column;\n", "");
            }

            if (isJavaPersistenceTable) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.Table;\n", "");
            } else {
                headPart = headPart.replace("import javax.persistence.Table;\n", "");
            }

            if (N.isEmpty(idFields)) {
                headPart = headPart.replace("import javax.persistence.Id;\n", "");
                headPart = headPart.replace("import com.landawn.abacus.annotation.Id;\n", "");
            } else if (isJavaPersistenceId) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.Id;\n", "");
            } else {
                headPart = headPart.replace("import javax.persistence.Id;\n", "");
            }

            if (N.isEmpty(nonUpdatableFields)) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.NonUpdatable;\n", "");
            }

            if (N.isEmpty(readOnlyFields)) {
                headPart = headPart.replace("import com.landawn.abacus.annotation.ReadOnly;\n", "");
            }

            if (N.isEmpty(customizedFieldDbTypeMap)) {
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
                sb.append(LINE_SEPERATOR);
            }

            sb.append(headPart);

            if (configToUse.getJsonXmlConfig() != null) {
                EntityCodeConfig.JsonXmlConfig eccJsonXmlConfig = configToUse.getJsonXmlConfig();

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
                    tmp.add("enumerated = EnumBy." + eccJsonXmlConfig.getEnumerated().name() + "");
                }

                sb.append("@JsonXmlConfig" + Strings.join(tmp, ", ", "(", ")")).append(LINE_SEPERATOR);
            }

            sb.append(isJavaPersistenceTable ? "@Table(name = \"" + entityName + "\")" : "@Table(\"" + entityName + "\")")
                    .append(LINE_SEPERATOR)
                    .append("public class " + finalClassName)
                    .append(" {")
                    .append(LINE_SEPERATOR);

            final Collection<String> excludedFields = configToUse.getExcludedFields();
            final List<String> columnNameList = new ArrayList<>();
            final List<String> fieldNameList = new ArrayList<>();

            final ResultSetMetaData rsmd = rs.getMetaData();
            final int columnCount = rsmd.getColumnCount();

            for (int i = 1; i <= columnCount; i++) {
                final String columnName = rsmd.getColumnName(i);

                final Tuple3<String, String, Class<?>> customizedField = customizedFieldMap.getOrDefault(Strings.toCamelCase(columnName),
                        customizedFieldMap.get(columnName));

                final String fieldName = customizedField == null || Strings.isEmpty(customizedField._2) ? fieldNameConverter.apply(entityName, columnName)
                        : customizedField._2;

                if (N.notEmpty(excludedFields) && (excludedFields.contains(fieldName) || excludedFields.contains(columnName))) {
                    continue;
                }

                final String columnClassName = customizedField == null || customizedField._3 == null
                        ? mapColumClassnName((fieldTypeConverter == null ? getColumnClassName(rsmd, i)
                                : fieldTypeConverter.apply(entityName, columnName, fieldName, getColumnClassName(rsmd, i))), false, configToUse)
                        : mapColumClassnName(ClassUtil.getCanonicalClassName(customizedField._3), true, configToUse);

                columnNameList.add(columnName);
                fieldNameList.add(fieldName);

                sb.append(LINE_SEPERATOR);

                if (idFields.remove(fieldName) || idFields.remove(columnName)) {
                    sb.append(isJavaPersistenceId ? "    @Id" : "    @Id").append(LINE_SEPERATOR); //NOSONAR
                }

                if (readOnlyFields.remove(fieldName) || readOnlyFields.remove(columnName)) {
                    sb.append("    @ReadOnly").append(LINE_SEPERATOR);
                } else if (nonUpdatableFields.remove(fieldName) || nonUpdatableFields.remove(columnName)) {
                    sb.append("    @NonUpdatable").append(LINE_SEPERATOR);
                }

                sb.append(isJavaPersistenceColumn ? "    @Column(name = \"" + columnName + "\")" : "    @Column(\"" + columnName + "\")")
                        .append(LINE_SEPERATOR);

                final Tuple2<String, String> dbType = customizedFieldDbTypeMap.getOrDefault(fieldName, customizedFieldDbTypeMap.get(columnName));

                if (dbType != null) {
                    sb.append("    @Type(name = \"" + dbType._2 + "\")").append(LINE_SEPERATOR);
                }

                sb.append("    private " + columnClassName + " " + fieldName + ";").append(LINE_SEPERATOR);
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
                sb.append(LINE_SEPERATOR).append(configToUse.getAdditionalFieldsOrLines());
            }

            if (configToUse.isGenerateCopyMethod()) {
                // TODO extract fields from additionalFieldsOrLines?

                sb.append(LINE_SEPERATOR)
                        .append("    public " + className + " copy() {")
                        .append(LINE_SEPERATOR) //
                        .append("        final " + className + " copy = new " + className + "();")
                        .append(LINE_SEPERATOR); //

                for (String fieldName : fieldNameList) {
                    sb.append("        copy." + fieldName + " = this." + fieldName + ";").append(LINE_SEPERATOR);
                }

                for (Tuple2<String, String> tp : additionalFields) {
                    sb.append("        copy." + tp._2 + " = this." + tp._2 + ";").append(LINE_SEPERATOR);
                }

                sb.append("        return copy;").append(LINE_SEPERATOR).append("    }").append(LINE_SEPERATOR);
            }

            if (configToUse.isGenerateFieldNameTable()) {
                sb.append(LINE_SEPERATOR)
                        .append("    /*")
                        .append(LINE_SEPERATOR)
                        .append("     * Auto-generated class for property name table by abacus-jdbc.")
                        .append(LINE_SEPERATOR)
                        .append("     */");

                sb.append(LINE_SEPERATOR).append("    public interface ").append(X).append(" {").append(LINE_SEPERATOR).append(LINE_SEPERATOR); //

                for (String fieldName : fieldNameList) {
                    sb.append("        String " + fieldName + " = \"" + fieldName + "\";").append(LINE_SEPERATOR);
                }

                sb.append(LINE_SEPERATOR).append("    }").append(LINE_SEPERATOR);
            }

            sb.append(LINE_SEPERATOR).append("}").append(LINE_SEPERATOR);

            String result = sb.toString();

            if (Strings.isNotEmpty(srcDir)) {
                String packageDir = srcDir;

                if (Strings.isNotEmpty(packageName)) {
                    if (!(packageDir.endsWith("/") || packageDir.endsWith("\\"))) {
                        packageDir += "/";
                    }

                    packageDir += Strings.replaceAll(packageName, ".", "/");
                }

                IOUtil.mkdirsIfNotExists(new File(packageDir));

                File file = new File(packageDir + IOUtil.FILE_SEPARATOR + finalClassName + ".java");

                IOUtil.createIfNotExists(file);

                IOUtil.write(result, file);
            }

            return result;
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String createQueryByTableName(String tableName) {
        return "select * from " + tableName + " where 1 > 2";
    }

    private static String getColumnClassName(final ResultSetMetaData rsmd, final int columnIndex) throws SQLException {
        String className = rsmd.getColumnClassName(columnIndex);

        try {
            className = ClassUtil.getCanonicalClassName(ClassUtil.forClass(className));
        } catch (Throwable e) {
            // ignore.
        }

        if ("oracle.sql.TIMESTAMP".equals(className) || "oracle.sql.TIMESTAMPTZ".equals(className) || Strings.endsWithIgnoreCase(className, ".Timestamp")
                || Strings.endsWithIgnoreCase(className, ".DateTime")) {
            className = ClassUtil.getCanonicalClassName(java.sql.Timestamp.class);
        } else if ("oracle.sql.DATE".equals(className) || Strings.endsWithIgnoreCase(className, ".Date")) {
            className = ClassUtil.getCanonicalClassName(java.sql.Date.class);
        } else if ("oracle.sql.TIME".equals(className) || Strings.endsWithIgnoreCase(className, ".Time")) {
            className = ClassUtil.getCanonicalClassName(java.sql.Time.class);
        }

        className = className.replace("java.lang.", "");

        return eccClassNameMap.getOrDefault(className, className);
    }

    private static String mapColumClassnName(final String columnClassName, final boolean isCustomizedType, final EntityCodeConfig configToUse) {
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
     * @param entityClass
     * @return
     */
    @Beta
    public static String generatePropNameTableClass(final Class<?> entityClass) {
        return generatePropNameTableClass(entityClass, X);
    }

    /**
     *
     * @param entityClass
     * @param propNameTableClassName
     * @return
     */
    @Beta
    public static String generatePropNameTableClass(final Class<?> entityClass, final String propNameTableClassName) {
        return generatePropNameTableClass(entityClass, propNameTableClassName, null);
    }

    /**
     *
     * @param entityClass
     * @param propNameTableClassName
     * @param srcDir
     * @return
     */
    @Beta
    public static String generatePropNameTableClass(final Class<?> entityClass, final String propNameTableClassName, final String srcDir) {
        final StringBuilder sb = new StringBuilder();

        final String interfaceName = "public interface " + propNameTableClassName;

        sb.append(LINE_SEPERATOR)
                .append("    /*")
                .append(LINE_SEPERATOR)
                .append("     * Auto-generated class for property name table by abacus-jdbc.")
                .append(LINE_SEPERATOR)
                .append("     */");

        if (Character.isLowerCase(propNameTableClassName.charAt(0))) {
            sb.append(LINE_SEPERATOR).append("    @SuppressWarnings(\"java:S1192\")");
        }

        sb.append(LINE_SEPERATOR).append("    ").append(interfaceName).append(" {").append(LINE_SEPERATOR).append(LINE_SEPERATOR); //

        for (String propName : ClassUtil.getPropNameList(entityClass)) {
            sb.append("        String " + propName + " = \"" + propName + "\";").append(LINE_SEPERATOR);
        }

        sb.append(LINE_SEPERATOR).append("    }").append(LINE_SEPERATOR);

        String ret = sb.toString();

        if (Strings.isNotEmpty(srcDir)) {

            String packageDir = srcDir;
            String packageName = ClassUtil.getPackageName(entityClass);

            if (Strings.isNotEmpty(packageName)) {
                if (!(packageDir.endsWith("/") || packageDir.endsWith("\\"))) {
                    packageDir += "/";
                }

                packageDir += Strings.replaceAll(packageName, ".", "/");
            }

            File file = new File(packageDir + IOUtil.FILE_SEPARATOR + ClassUtil.getSimpleClassName(entityClass) + ".java");
            List<String> lines = IOUtil.readAllLines(file);

            for (int i = 0, size = lines.size(); i < size; i++) {
                if (Strings.startsWithAny(lines.get(i).trim(), interfaceName, "* Auto-generated class for property name table by abacus-jdbc")) {
                    if (Strings.startsWith(lines.get(i).trim(), "* Auto-generated class for property name table by abacus-jdbc")) {
                        i--;
                    }

                    for (int j = i; j < size; j++) {
                        if ("}".equals(Strings.strip(lines.get(j)))) {
                            N.deleteRange(lines, Strings.isBlank(lines.get(i - 1)) ? i - 1 : i, Strings.isBlank(lines.get(j + 1)) ? j + 2 : j + 1);
                            break;
                        }
                    }

                    break;
                }
            }

            for (int i = lines.size() - 1; i > 0; i--) {
                if ("}".equals(Strings.strip(lines.get(i)))) {
                    lines.add(i, ret);
                    break;
                }
            }

            try {
                IOUtil.writeLines(lines, file);
            } catch (IOException e) {
                throw N.toRuntimeException(e);
            }
        }

        return ret;
    }

    /**
     *
     * @param entityClasses
     * @return
     */
    @Beta
    public static String generatePropNameTableClasses(final Collection<? extends Class<?>> entityClasses) {
        return generatePropNameTableClasses(entityClasses, S);
    }

    /**
     *
     * @param entityClasses
     * @param propNameTableClassName
     * @return
     */
    @Beta
    public static String generatePropNameTableClasses(final Collection<? extends Class<?>> entityClasses, final String propNameTableClassName) {
        return generatePropNameTableClasses(entityClasses, propNameTableClassName, null, null);
    }

    private static final BiFunction<Class<?>, String, String> identityPropNameConverter = (cls, propName) -> propName;

    /**
     *
     * @param entityClasses
     * @param propNameTableClassName
     * @param propNameTableClassPackageName
     * @param srcDir
     * @return
     */
    @Beta
    public static String generatePropNameTableClasses(final Collection<? extends Class<?>> entityClasses, final String propNameTableClassName,
            final String propNameTableClassPackageName, final String srcDir) {
        return generatePropNameTableClasses(entityClasses, propNameTableClassName, propNameTableClassPackageName, srcDir, identityPropNameConverter);
    }

    /**
     *
     * @param entityClasses
     * @param propNameTableClassName
     * @param propNameTableClassPackageName
     * @param srcDir
     * @param propNameConverter to filter out a property by returning {@code null}.
     * @return
     */
    @Beta
    public static String generatePropNameTableClasses(final Collection<? extends Class<?>> entityClasses, final String propNameTableClassName,
            final String propNameTableClassPackageName, final String srcDir, final BiFunction<Class<?>, String, String> propNameConverter) {
        N.checkArgNotEmpty(entityClasses, "entityClasses");

        final StringBuilder sb = new StringBuilder();

        final String interfaceName = "public interface " + propNameTableClassName;
        final ListMultimap<String, String> propNameMap = N.newListMultimap();

        for (Class<?> cls : entityClasses) {
            if (cls.isInterface()) {
                continue;
            }

            if (cls.isMemberClass() && ClassUtil.getSimpleClassName(cls).endsWith("Builder") && cls.getDeclaringClass() != null) {
                try {
                    if (cls.getDeclaringClass().isAnnotationPresent(lombok.Builder.class)) {
                        continue;
                    }
                } catch (Throwable e) {
                    // ignore

                    try {
                        if (Stream.of(cls.getDeclaringClass().getAnnotations()).anyMatch(it -> "Builder".equals(it.annotationType().getSimpleName()))) {
                            continue;
                        }
                    } catch (Throwable e2) {
                        // ignore
                    }
                }
            }

            final String simpleClassName = ClassUtil.getSimpleClassName(cls);

            for (String propName : ClassUtil.getPropNameList(cls)) {
                String newPropName = propNameConverter.apply(cls, propName);

                if (Strings.isEmpty(newPropName)) {
                    continue;
                }

                if (newPropName.equals(propName)) {
                    propNameMap.put(newPropName, simpleClassName);
                } else {
                    propNameMap.put(newPropName, simpleClassName + "." + propName);
                }
            }
        }

        List<String> propNames = new ArrayList<>(propNameMap.keySet());
        N.sort(propNames);

        final String allClsNameList = Stream.of(entityClasses)
                .filter(cls -> !(cls.isInterface() || (cls.isMemberClass() && ClassUtil.getSimpleClassName(cls).endsWith("Builder"))))
                .map(ClassUtil::getSimpleClassName)
                .sorted()
                .join(", ", "[", "]");

        sb.append(LINE_SEPERATOR)
                .append("/*")
                .append(LINE_SEPERATOR)
                .append(" * Auto-generated class for property name table by abacus-jdbc for classes: ")
                .append(allClsNameList)
                .append(LINE_SEPERATOR)
                .append(" */");

        if (Character.isLowerCase(propNameTableClassName.charAt(0))) {
            sb.append(LINE_SEPERATOR).append("@SuppressWarnings(\"java:S1192\")");
        }

        sb.append(LINE_SEPERATOR).append(interfaceName).append(" {").append(LINE_SEPERATOR); //

        for (String propName : propNames) {
            final String clsNameList = Stream.of(propNameMap.get(propName)).sorted().join(", ", "[", "]");

            sb.append(LINE_SEPERATOR)
                    .append("    /* Property name for classes: ")
                    .append(clsNameList)
                    .append(" */")
                    .append(LINE_SEPERATOR)
                    .append("    String " + propName + " = \"" + propName + "\";")
                    .append(LINE_SEPERATOR);
        }

        sb.append(LINE_SEPERATOR).append("}").append(LINE_SEPERATOR);

        String ret = sb.toString();

        final Class<?> entityClass = N.firstElement(entityClasses).orElseThrow();

        if (Strings.isNotEmpty(srcDir)) {
            String packageDir = srcDir;
            String packageName = Strings.isEmpty(propNameTableClassPackageName) ? ClassUtil.getPackageName(entityClass) : propNameTableClassPackageName;

            if (Strings.isNotEmpty(packageName)) {
                if (!(packageDir.endsWith("/") || packageDir.endsWith("\\"))) {
                    packageDir += "/";
                }

                packageDir += Strings.replaceAll(packageName, ".", "/");
            }

            final List<String> lines = new ArrayList<>();

            if (Strings.isNotEmpty(packageName)) {
                lines.add("package " + packageName + ";");
            }

            lines.add(ret);

            IOUtil.mkdirsIfNotExists(new File(packageDir));
            File file = new File(packageDir + IOUtil.FILE_SEPARATOR + propNameTableClassName + ".java");
            IOUtil.createIfNotExists(file);
            try {
                IOUtil.writeLines(lines, file);
            } catch (IOException e) {
                throw N.toRuntimeException(e);
            }
        }

        return ret;
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
        } catch (SQLException e) {
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
        String query = "select * from " + tableName + " where 1 > 2";

        try (final PreparedStatement stmt = JdbcUtil.prepareStatement(conn, query); //
                final ResultSet rs = stmt.executeQuery()) {

            final List<String> columnLabelList = JdbcUtil.getColumnLabelList(rs);

            return Strings.join(checkColumnName(columnLabelList), ", ", "select ", " from " + checkTableName(tableName));
        } catch (SQLException e) {
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
        } catch (SQLException e) {
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
        String query = "select * from " + tableName + " where 1 > 2";

        try (final PreparedStatement stmt = JdbcUtil.prepareStatement(conn, query); //
                final ResultSet rs = stmt.executeQuery()) {

            final List<String> columnLabelList = JdbcUtil.getColumnLabelList(rs);

            return Strings.join(checkColumnName(columnLabelList), ", ", "insert into " + checkTableName(tableName) + "(",
                    ") values (" + Strings.repeat("?", columnLabelList.size(), ", ") + ")");
        } catch (SQLException e) {
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
        } catch (SQLException e) {
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
        String query = "select * from " + tableName + " where 1 > 2";

        try (final PreparedStatement stmt = JdbcUtil.prepareStatement(conn, query); //
                final ResultSet rs = stmt.executeQuery()) {

            final List<String> columnLabelList = JdbcUtil.getColumnLabelList(rs);

            return Strings.join(checkColumnName(columnLabelList), ", ", "insert into " + checkTableName(tableName) + "(",
                    Stream.of(columnLabelList).map(it -> ":" + Strings.toCamelCase(it)).join(", ", ") values (", ")"));
        } catch (SQLException e) {
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
        } catch (SQLException e) {
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
        String query = "select * from " + tableName + " where 1 > 2";

        try (final PreparedStatement stmt = JdbcUtil.prepareStatement(conn, query); //
                final ResultSet rs = stmt.executeQuery()) {

            final List<String> columnLabelList = JdbcUtil.getColumnLabelList(rs);

            return "update " + checkTableName(tableName) + " set "
                    + Stream.of(columnLabelList).map(columnLabel -> checkColumnName(columnLabel) + " = ?").join(", ");
        } catch (SQLException e) {
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
        } catch (SQLException e) {
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
        String query = "select * from " + tableName + " where 1 > 2";

        try (final PreparedStatement stmt = JdbcUtil.prepareStatement(conn, query); //
                final ResultSet rs = stmt.executeQuery()) {

            final List<String> columnLabelList = JdbcUtil.getColumnLabelList(rs);

            return "update " + checkTableName(tableName) + " set "
                    + Stream.of(columnLabelList).map(columnLabel -> checkColumnName(columnLabel) + " = :" + Strings.toCamelCase(columnLabel)).join(", ");
        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    private static String checkTableName(final String tableName) {
        return CharStream.of(tableName).allMatch(ch -> Strings.isAsciiAlpha(ch) || Strings.isAsciiNumeric(ch) || ch == '_') ? tableName
                : Strings.wrap(tableName, "`");
    }

    private static String checkColumnName(final String columnLabel) {
        return CharStream.of(columnLabel).allMatch(ch -> Strings.isAsciiAlpha(ch) || Strings.isAsciiNumeric(ch) || ch == '_') ? columnLabel
                : Strings.wrap(columnLabel, "'");
    }

    private static List<String> checkColumnName(final List<String> columnLabelList) {
        return N.map(columnLabelList, CodeGenerationUtil::checkColumnName);
    }
}
