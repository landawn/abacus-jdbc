/*
 * Copyright (c) 2021, Haiyang Li.
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
import com.landawn.abacus.util.Objectory;
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
 * Utility class for generating JDBC-related code including entity classes and SQL statements.
 * This class provides methods to automatically generate entity classes from database tables,
 * as well as generate common SQL statements (SELECT, INSERT, UPDATE) for database operations.
 * 
 * <p>The generated entity classes can be customized using {@link EntityCodeConfig} to control
 * various aspects such as field naming conventions, type mappings, annotations, and more.</p>
 * 
 * <p>Example usage:</p>
 * <pre>{@code
 * // Generate entity class from a table
 * DataSource ds = getDataSource();
 * String entityCode = JdbcCodeGenerationUtil.generateEntityClass(ds, "user_table");
 * 
 * // Generate SQL statements
 * String selectSql = JdbcCodeGenerationUtil.generateSelectSql(ds, "user_table");
 * String insertSql = JdbcCodeGenerationUtil.generateInsertSql(ds, "user_table");
 * }</pre>
 * 
 * @see CodeGenerationUtil
 * @see EntityCodeConfig
 */
@SuppressWarnings("resource")
public final class JdbcCodeGenerationUtil {

    /**
     * Default name of class for field/prop names.
     * This constant is typically used when generating static field name constants.
     */
    public static final String S = "s";

    /**
     * Default name of class for function field/prop names.
     * This constant is typically used when generating functional field name constants.
     */
    public static final String SF = "sf";

    /**
     * Default name of inner class for field names inside an entity class.
     * This inner class is used to hold string constants representing field names,
     * providing type-safe field references.
     */
    public static final String X = "x";

    /**
     * Pre-defined function for generating MIN SQL aggregate function.
     * This function takes entity class, property class, and property name as parameters
     * and returns a MIN SQL expression if the property class implements Comparable.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * String minExpr = MIN_FUNC.apply(User.class, Integer.class, "age");
     * // Returns: "min(age)"
     * }</pre>
     */
    public static final TriFunction<Class<?>, Class<?>, String, String> MIN_FUNC = (entityClass, propClass, propName) -> {
        if (Comparable.class.isAssignableFrom(propClass)) {
            return "min(" + propName + ")";
        }

        return null;
    };

    /**
     * Pre-defined function for generating MAX SQL aggregate function.
     * This function takes entity class, property class, and property name as parameters
     * and returns a MAX SQL expression if the property class implements Comparable.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * String maxExpr = MAX_FUNC.apply(User.class, BigDecimal.class, "salary");
     * // Returns: "max(salary)"
     * }</pre>
     */
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
     * Generates an entity class for the specified table using default configuration.
     * The generated class includes Lombok annotations, field mappings, and JPA/Abacus annotations.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * DataSource ds = getDataSource();
     * String entityCode = JdbcCodeGenerationUtil.generateEntityClass(ds, "user_table");
     * System.out.println(entityCode);
     * }</pre>
     *
     * @param ds The data source to connect to the database
     * @param tableName The name of the table for which to generate the entity class
     * @return The generated entity class as a string containing the complete Java source code
     * @throws UncheckedSQLException if a database access error occurs
     */
    public static String generateEntityClass(final DataSource ds, final String tableName) {
        return generateEntityClass(ds, tableName, (EntityCodeConfig) null);
    }

    /**
     * Generates an entity class for the specified table with custom configuration.
     * The configuration allows customization of field naming, type conversion, annotations, and more.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * EntityCodeConfig config = EntityCodeConfig.builder()
     *     .className("User")
     *     .packageName("com.example.entity")
     *     .useBoxedType(true)
     *     .build();
     * String entityCode = JdbcCodeGenerationUtil.generateEntityClass(ds, "user_table", config);
     * }</pre>
     *
     * @param ds The data source to connect to the database
     * @param tableName The name of the table for which to generate the entity class
     * @param config The configuration for customizing the generated entity class. If null, default configuration is used
     * @return The generated entity class as a string containing the complete Java source code
     * @throws UncheckedSQLException if a database access error occurs
     */
    public static String generateEntityClass(final DataSource ds, final String tableName, final EntityCodeConfig config) {
        return generateEntityClass(ds, tableName, createQueryByTableName(tableName), config);
    }

    /**
     * Generates an entity class using a custom SQL query to determine the entity structure.
     * This method allows using complex queries (e.g., joins, views) to define the entity.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * String query = "SELECT u.id, u.name, p.profile_data FROM users u JOIN profiles p ON u.id = p.user_id";
     * String entityCode = JdbcCodeGenerationUtil.generateEntityClass(ds, "UserProfile", query);
     * }</pre>
     *
     * @param ds The data source to connect to the database
     * @param entityName The name of the entity class to generate
     * @param query The SQL query to execute for retrieving the table metadata. The query should return an empty result set
     * @return The generated entity class as a string containing the complete Java source code
     * @throws UncheckedSQLException if a database access error occurs
     */
    public static String generateEntityClass(final DataSource ds, final String entityName, final String query) {
        return generateEntityClass(ds, entityName, query, null);
    }

    /**
     * Generates an entity class using a custom SQL query and configuration.
     * This method provides maximum flexibility by allowing both custom queries and configuration.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * String query = "SELECT * FROM user_view WHERE 1=0";
     * EntityCodeConfig config = EntityCodeConfig.builder()
     *     .idFields(Arrays.asList("userId"))
     *     .readOnlyFields(Arrays.asList("createdDate"))
     *     .build();
     * String entityCode = JdbcCodeGenerationUtil.generateEntityClass(ds, "UserView", query, config);
     * }</pre>
     *
     * @param ds The data source to connect to the database
     * @param entityName The name of the entity class to generate
     * @param query The SQL query to execute for retrieving the table metadata. The query should return an empty result set
     * @param config The configuration for customizing the generated entity class. If null, default configuration is used
     * @return The generated entity class as a string containing the complete Java source code
     * @throws UncheckedSQLException if a database access error occurs
     */
    public static String generateEntityClass(final DataSource ds, final String entityName, final String query, final EntityCodeConfig config) {
        try (Connection conn = ds.getConnection()) {
            return generateEntityClass(conn, entityName, query, config);

        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Generates an entity class for the specified table using an existing database connection.
     * This method is useful when you already have an open connection and want to avoid creating a new one.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     String entityCode = JdbcCodeGenerationUtil.generateEntityClass(conn, "product_table");
     *     System.out.println(entityCode);
     * }
     * }</pre>
     *
     * @param conn The database connection to use
     * @param tableName The name of the table for which to generate the entity class
     * @return The generated entity class as a string containing the complete Java source code
     * @throws UncheckedSQLException if a database access error occurs
     */
    public static String generateEntityClass(final Connection conn, final String tableName) {
        return generateEntityClass(conn, tableName, (EntityCodeConfig) null);
    }

    /**
     * Generates an entity class for the specified table using an existing connection and custom configuration.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * EntityCodeConfig config = EntityCodeConfig.builder()
     *     .generateBuilder(true)
     *     .generateCopyMethod(true)
     *     .build();
     * String entityCode = JdbcCodeGenerationUtil.generateEntityClass(conn, "order_table", config);
     * }</pre>
     *
     * @param conn The database connection to use
     * @param tableName The name of the table for which to generate the entity class
     * @param config The configuration for customizing the generated entity class. If null, default configuration is used
     * @return The generated entity class as a string containing the complete Java source code
     * @throws UncheckedSQLException if a database access error occurs
     */
    public static String generateEntityClass(final Connection conn, final String tableName, final EntityCodeConfig config) {
        return generateEntityClass(conn, tableName, createQueryByTableName(tableName), config);
    }

    /**
     * Generates an entity class using an existing connection and a custom SQL query.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * String query = "SELECT id, name, email FROM users WHERE 1=0";
     * String entityCode = JdbcCodeGenerationUtil.generateEntityClass(conn, "SimpleUser", query);
     * }</pre>
     *
     * @param conn The database connection to use
     * @param entityName The name of the entity class to generate
     * @param query The SQL query to execute for retrieving the table metadata. The query should return an empty result set
     * @return The generated entity class as a string containing the complete Java source code
     * @throws UncheckedSQLException if a database access error occurs
     */
    public static String generateEntityClass(final Connection conn, final String entityName, final String query) {
        return generateEntityClass(conn, entityName, query, null);
    }

    /**
     * Generates an entity class using an existing connection, custom SQL query, and configuration.
     * This is the most flexible method, allowing full control over all aspects of entity generation.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * String query = "SELECT * FROM complex_view WHERE 1=0";
     * EntityCodeConfig config = EntityCodeConfig.builder()
     *     .srcDir("./src/main/java")
     *     .packageName("com.example.entity")
     *     .fieldNameConverter((table, column) -> StringUtil.toCamelCase(column.toLowerCase()))
     *     .build();
     * String entityCode = JdbcCodeGenerationUtil.generateEntityClass(conn, "ComplexEntity", query, config);
     * }</pre>
     *
     * @param conn The database connection to use
     * @param entityName The name of the entity class to generate
     * @param query The SQL query to execute for retrieving the table metadata. The query should return an empty result set
     * @param config The configuration for customizing the generated entity class. If null, default configuration is used
     * @return The generated entity class as a string containing the complete Java source code
     * @throws UncheckedSQLException if a database access error occurs
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
     * Generates a SELECT SQL statement for the specified table.
     * The generated SQL includes all columns from the table.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * DataSource ds = getDataSource();
     * String selectSql = JdbcCodeGenerationUtil.generateSelectSql(ds, "employee");
     * // Returns: "select id, name, department, salary from employee"
     * }</pre>
     *
     * @param dataSource The data source to connect to the database
     * @param tableName The name of the table for which to generate the SELECT statement
     * @return A SELECT SQL statement string with all columns from the table
     * @throws UncheckedSQLException if a database access error occurs
     */
    public static String generateSelectSql(final DataSource dataSource, final String tableName) throws UncheckedSQLException {
        try (Connection conn = dataSource.getConnection()) {
            return generateSelectSql(conn, tableName);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Generates a SELECT SQL statement for the specified table using an existing connection.
     * Column names are properly escaped with backticks if they contain special characters.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     String selectSql = JdbcCodeGenerationUtil.generateSelectSql(conn, "user_profile");
     *     // Returns: "select user_id, first_name, last_name, `created-date` from user_profile"
     * }
     * }</pre>
     *
     * @param conn The database connection to use
     * @param tableName The name of the table for which to generate the SELECT statement
     * @return A SELECT SQL statement string with all columns from the table
     * @throws UncheckedSQLException if a database access error occurs
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
     * Generates an INSERT SQL statement for the specified table.
     * The generated SQL uses positional parameters (?) for all column values.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * DataSource ds = getDataSource();
     * String insertSql = JdbcCodeGenerationUtil.generateInsertSql(ds, "product");
     * // Returns: "insert into product(id, name, price, category) values (?, ?, ?, ?)"
     * }</pre>
     *
     * @param dataSource The data source to connect to the database
     * @param tableName The name of the table for which to generate the INSERT statement
     * @return An INSERT SQL statement string with positional parameters for all columns
     * @throws UncheckedSQLException if a database access error occurs
     */
    public static String generateInsertSql(final DataSource dataSource, final String tableName) throws UncheckedSQLException {
        try (Connection conn = dataSource.getConnection()) {
            return generateInsertSql(conn, tableName);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Generates an INSERT SQL statement for the specified table using an existing connection.
     * Column names are properly escaped with backticks if they contain special characters.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     String insertSql = JdbcCodeGenerationUtil.generateInsertSql(conn, "order_items");
     *     // Returns: "insert into order_items(order_id, item_id, quantity, price) values (?, ?, ?, ?)"
     * }
     * }</pre>
     *
     * @param conn The database connection to use
     * @param tableName The name of the table for which to generate the INSERT statement
     * @return An INSERT SQL statement string with positional parameters for all columns
     * @throws UncheckedSQLException if a database access error occurs
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
     * Generates a named INSERT SQL statement for the specified table.
     * The generated SQL uses named parameters (:paramName) based on camelCase column names.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * DataSource ds = getDataSource();
     * String insertSql = JdbcCodeGenerationUtil.generateNamedInsertSql(ds, "customer");
     * // Returns: "insert into customer(customer_id, first_name, last_name) values (:customerId, :firstName, :lastName)"
     * }</pre>
     *
     * @param dataSource The data source to connect to the database
     * @param tableName The name of the table for which to generate the named INSERT statement
     * @return An INSERT SQL statement string with named parameters based on camelCase column names
     * @throws UncheckedSQLException if a database access error occurs
     */
    public static String generateNamedInsertSql(final DataSource dataSource, final String tableName) throws UncheckedSQLException {
        try (Connection conn = dataSource.getConnection()) {
            return generateNamedInsertSql(conn, tableName);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Generates a named INSERT SQL statement for the specified table using an existing connection.
     * Column names with underscores are converted to camelCase for parameter names.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     String insertSql = JdbcCodeGenerationUtil.generateNamedInsertSql(conn, "user_settings");
     *     // Returns: "insert into user_settings(user_id, theme_preference, notification_enabled) 
     *     //           values (:userId, :themePreference, :notificationEnabled)"
     * }
     * }</pre>
     *
     * @param conn The database connection to use
     * @param tableName The name of the table for which to generate the named INSERT statement
     * @return An INSERT SQL statement string with named parameters based on camelCase column names
     * @throws UncheckedSQLException if a database access error occurs
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
     * Generates an UPDATE SQL statement for the specified table.
     * The generated SQL uses positional parameters (?) for all column values and does not include a WHERE clause.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * DataSource ds = getDataSource();
     * String updateSql = JdbcCodeGenerationUtil.generateUpdateSql(ds, "employee");
     * // Returns: "update employee set name = ?, department = ?, salary = ?, updated_date = ?"
     * }</pre>
     *
     * @param dataSource The data source to connect to the database
     * @param tableName The name of the table for which to generate the UPDATE statement
     * @return An UPDATE SQL statement string with positional parameters for all columns (no WHERE clause)
     * @throws UncheckedSQLException if a database access error occurs
     */
    public static String generateUpdateSql(final DataSource dataSource, final String tableName) throws UncheckedSQLException {
        try (Connection conn = dataSource.getConnection()) {
            return generateUpdateSql(conn, tableName);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Generates an UPDATE SQL statement for the specified table using an existing connection.
     * The generated SQL includes all columns in the SET clause with positional parameters.
     * Note: Users should append an appropriate WHERE clause before executing.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     String updateSql = JdbcCodeGenerationUtil.generateUpdateSql(conn, "product");
     *     // Returns: "update product set name = ?, description = ?, price = ?, stock_quantity = ?"
     *     // Usage: updateSql += " WHERE id = ?";
     * }
     * }</pre>
     *
     * @param conn The database connection to use
     * @param tableName The name of the table for which to generate the UPDATE statement
     * @return An UPDATE SQL statement string with positional parameters for all columns (no WHERE clause)
     * @throws UncheckedSQLException if a database access error occurs
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
     * Generates a named UPDATE SQL statement for the specified table.
     * The generated SQL uses named parameters (:paramName) based on camelCase column names.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * DataSource ds = getDataSource();
     * String updateSql = JdbcCodeGenerationUtil.generateNamedUpdateSql(ds, "user_profile");
     * // Returns: "update user_profile set first_name = :firstName, last_name = :lastName, 
     * //           email = :email, phone_number = :phoneNumber"
     * }</pre>
     *
     * @param dataSource The data source to connect to the database
     * @param tableName The name of the table for which to generate the named UPDATE statement
     * @return An UPDATE SQL statement string with named parameters based on camelCase column names (no WHERE clause)
     * @throws UncheckedSQLException if a database access error occurs
     */
    public static String generateNamedUpdateSql(final DataSource dataSource, final String tableName) throws UncheckedSQLException {
        try (Connection conn = dataSource.getConnection()) {
            return generateNamedUpdateSql(conn, tableName);
        } catch (final SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    /**
     * Generates a named UPDATE SQL statement for the specified table using an existing connection.
     * Column names with underscores are converted to camelCase for parameter names.
     * Note: Users should append an appropriate WHERE clause before executing.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * try (Connection conn = dataSource.getConnection()) {
     *     String updateSql = JdbcCodeGenerationUtil.generateNamedUpdateSql(conn, "order_status");
     *     // Returns: "update order_status set status = :status, updated_by = :updatedBy, 
     *     //           updated_date = :updatedDate"
     *     // Usage: updateSql += " WHERE order_id = :orderId";
     * }
     * }</pre>
     *
     * @param conn The database connection to use
     * @param tableName The name of the table for which to generate the named UPDATE statement
     * @return An UPDATE SQL statement string with named parameters based on camelCase column names (no WHERE clause)
     * @throws UncheckedSQLException if a database access error occurs
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

    /**
     * Converts an INSERT SQL statement to an UPDATE SQL statement.
     * This method is marked as @Beta and may change in future versions.
     * The generated UPDATE statement will have a WHERE clause placeholder that needs to be completed.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * String insertSql = "insert into users(name, email) values ('John', 'john@example.com')";
     * String updateSql = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(insertSql);
     * // Returns: "UPDATE users SET name = 'John', email = 'john@example.com' WHERE ;"
     * }</pre>
     *
     * @param insertSql The INSERT SQL statement to convert
     * @return An UPDATE SQL statement derived from the INSERT statement with an incomplete WHERE clause
     * @throws IllegalArgumentException if the INSERT SQL cannot be parsed or converted
     */
    @Beta
    public static String convertInsertSqlToUpdateSql(final String insertSql) {
        return convertInsertSqlToUpdateSql(insertSql, null);
    }

    /**
     * Converts an INSERT SQL statement to an UPDATE SQL statement with a specified WHERE clause.
     * This method is marked as @Beta and may change in future versions.
     * The method parses the INSERT statement and reconstructs it as an UPDATE statement.
     * 
     * <p>Example usage:</p>
     * <pre>{@code
     * String insertSql = "insert into products(name, price, stock) values ('Widget', 19.99, 100)";
     * String updateSql = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(insertSql, "id = 123");
     * // Returns: "UPDATE products SET name = 'Widget', price = 19.99, stock = 100 WHERE id = 123;"
     * }</pre>
     *
     * @param insertSql The INSERT SQL statement to convert
     * @param whereClause The WHERE clause to append to the UPDATE statement (without the WHERE keyword)
     * @return An UPDATE SQL statement derived from the INSERT statement with the specified WHERE clause
     * @throws IllegalArgumentException if the INSERT SQL cannot be parsed or converted
     */
    @Beta
    public static String convertInsertSqlToUpdateSql(final String insertSql, final String whereClause) {
        final String insertInto = "insert into ";
        final int insertIntoLen = insertInto.length();
        final StringBuilder sb = Objectory.createStringBuilder();

        try {
            int idx = Strings.indexOfIgnoreCase(insertSql, insertInto);
            int idx2 = insertSql.indexOf('(', idx + insertIntoLen);
            String tableName = insertSql.substring(idx + insertIntoLen, idx2).trim();

            int idx3 = insertSql.indexOf(')', idx2 + 1);
            String[] columnNames = N.fromJson(insertSql.substring(idx2 + 1, idx3), String[].class);

            int idx4 = insertSql.indexOf('(', idx3 + 1);
            int idx5 = insertSql.lastIndexOf(')');
            List<Object> values = N.fromJson(insertSql.substring(idx4 + 1, idx5), List.class);

            sb.append("UPDATE ").append(tableName).append(" SET ");

            for (int i = 0, len = columnNames.length; i < len; i++) {
                if (i > 0) {
                    sb.append(", ");
                }

                sb.append(checkColumnName(columnNames[i]));

                if (values.get(i) instanceof String) {
                    sb.append(" = '").append(N.stringOf(values.get(i))).append('\'');
                } else {
                    sb.append(" = ").append(N.stringOf(values.get(i)));
                }
            }

            sb.append(" WHERE ");

            if (Strings.isNotEmpty(whereClause)) {
                sb.append(whereClause);
            }

            sb.append(";");

            return sb.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to convert insert SQL to update SQL: " + insertSql, e);
        } finally {
            Objectory.recycle(sb);
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
     * Configuration class for customizing entity code generation.
     * This class provides extensive options for controlling how entity classes are generated from database tables.
     * 
     * <p>A sample configuration example:</p>
     * <pre>{@code
     * EntityCodeConfig ecc = EntityCodeConfig.builder()
     *        .className("User")
     *        .packageName("codes.entity")
     *        .srcDir("./samples")
     *        .fieldNameConverter((entityOrTableName, columnName) -> StringUtil.toCamelCase(columnName))
     *        .fieldTypeConverter((entityOrTableName, fieldName, columnName, columnClassName) -> columnClassName
     *                .replace("java.lang.", ""))
     *        .useBoxedType(false)
     *        .readOnlyFields(N.asSet("id"))
     *        .nonUpdatableFields(N.asSet("create_time"))
     *        .idAnnotationClass(javax.persistence.Id.class)
     *        .columnAnnotationClass(javax.persistence.Column.class)
     *        .tableAnnotationClass(javax.persistence.Table.class)
     *        .customizedFields(N.asList(Tuple.of("columnName", "fieldName", java.util.Date.class)))
     *        .customizedFieldDbTypes(N.asList(Tuple.of("fieldName", "List<String>")))
     *        .generateBuilder(true)
     *        .generateCopyMethod(true)
     *        .build();
     * }</pre>
     *
     * @see JdbcCodeGenerationUtil#generateEntityClass(DataSource, String, EntityCodeConfig)
     */
    @Builder
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Accessors(chain = true)
    public static final class EntityCodeConfig {
        /**
         * The source directory where the generated entity class file will be saved.
         * If specified, the generated class will be written to this directory following the package structure.
         * Example: "./src/main/java"
         */
        private String srcDir;

        /**
         * The package name for the generated entity class.
         * Example: "com.example.entity"
         */
        private String packageName;

        /**
         * The class name for the generated entity.
         * If not specified, it will be derived from the table name using camelCase conversion.
         */
        private String className;

        /**
         * Function to convert database column names to Java field names.
         * First parameter is entity/table name, second is column name.
         * Default implementation converts column names to camelCase.
         * Example: (tableName, columnName) -> Strings.toCamelCase(columnName)
         */
        private BiFunction<String, String, String> fieldNameConverter;

        /**
         * Function to convert database column types to Java field types.
         * Parameters: entity/table name, field name, column name, column class name (from ResultSetMetaData).
         * Example: (entity, field, column, className) -> className.replace("java.lang.", "")
         */
        private QuadFunction<String, String, String, String, String> fieldTypeConverter;

        /**
         * List of customized field mappings.
         * Each tuple contains: (column name, field name, field class).
         * Allows overriding default field names and types for specific columns.
         */
        private List<Tuple3<String, String, Class<?>>> customizedFields;

        /**
         * List of customized database type annotations.
         * Each tuple contains: (field name, database type).
         * Used to generate @Type annotations for special database types.
         */
        private List<Tuple2<String, String>> customizedFieldDbTypes;

        /**
         * Whether to use boxed types (Integer, Long, etc.) instead of primitives (int, long, etc.).
         * Default is false (uses primitives where possible).
         */

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