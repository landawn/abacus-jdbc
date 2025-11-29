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

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.landawn.abacus.annotation.Internal;
import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.jdbc.annotation.DaoConfig;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Objectory;
import com.landawn.abacus.query.SQLBuilder;
import com.landawn.abacus.query.SQLBuilder.PAC;
import com.landawn.abacus.query.SQLBuilder.PLC;
import com.landawn.abacus.query.SQLBuilder.PSC;
import com.landawn.abacus.query.SQLParser;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.Tuple;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.Tuple.Tuple4;
import com.landawn.abacus.util.function.BiFunction;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.function.IntFunction;
import com.landawn.abacus.util.stream.Stream;
import com.landawn.abacus.util.stream.Stream.StreamEx;

/**
 * Manages join relationships between entities in JDBC operations.
 * This class handles both one-to-many and many-to-many join configurations,
 * generating appropriate SQL statements and managing parameter bindings for join operations.
 *
 * <p>The class supports joining entities through {@code @JoinedBy} annotations and provides
 * methods to retrieve joined entities and update join relationships. It automatically generates
 * optimized SQL queries for both single and batch join operations.</p>
 *
 * <p><b>Supported Join Types:</b></p>
 * <ul>
 *   <li><b>One-to-Many:</b> Direct foreign key relationship between entities</li>
 *   <li><b>Many-to-Many:</b> Relationship through an intermediate join table</li>
 * </ul>
 *
 * <p><b>Usage Examples:</b></p>
 * <pre>{@code
 * // Entity with one-to-many join annotation
 * @Table("employees")
 * public class Employee {
 *     @Id
 *     private Long employeeId;
 *
 *     @JoinedBy("employeeId")
 *     private List<Project> projects;
 * }
 *
 * // Entity with many-to-many join annotation
 * @Table("employees")
 * public class Employee {
 *     @Id
 *     private Long employeeId;
 *
 *     @JoinedBy("employeeId = EmployeeProject.employeeId, EmployeeProject.projectId = projectId")
 *     private List<Project> projects;
 * }
 *
 * // Get join info and load related entities
 * JoinInfo joinInfo = JoinInfo.getPropJoinInfo(EmployeeDao.class, Employee.class,
 *                                               "employees", "projects");
 * List<Employee> employees = employeeDao.list();
 * List<Project> projects = projectDao.list();
 * joinInfo.setJoinPropEntities(employees, projects);
 * }</pre>
 *
 */
@Internal
@SuppressWarnings({ "java:S1192", "resource" })
public final class JoinInfo {

    static final Map<Class<? extends SQLBuilder>, Tuple4<Function<Collection<String>, SQLBuilder>, Function<Class<?>, SQLBuilder>, Function<Class<?>, SQLBuilder>, Function<Class<?>, SQLBuilder>>> sqlBuilderFuncMap = new HashMap<>();

    static {
        sqlBuilderFuncMap.put(PSC.class, Tuple.of(PSC::select, PSC::selectFrom, PSC::update, PSC::deleteFrom));

        sqlBuilderFuncMap.put(PAC.class, Tuple.of(PAC::select, PAC::selectFrom, PAC::update, PAC::deleteFrom));

        sqlBuilderFuncMap.put(PLC.class, Tuple.of(PLC::select, PLC::selectFrom, PLC::update, PLC::deleteFrom));
    }

    final Class<?> entityClass;
    final String tableName;
    final BeanInfo entityInfo;
    final PropInfo joinPropInfo;
    final PropInfo[] srcPropInfos;
    final PropInfo[] referencedPropInfos;
    final Type<?> referencedEntityType;
    final Class<?> referencedEntityClass;
    final BeanInfo referencedBeanInfo;
    final Function<Object, Object> srcEntityKeyExtractor;
    final Function<Object, Object> referencedEntityKeyExtractor;
    final boolean isManyToManyJoin;
    final boolean allowJoiningByNullOrDefaultValue;

    private final Map<Class<? extends SQLBuilder>, Tuple2<Function<Collection<String>, String>, Jdbc.BiParametersSetter<PreparedStatement, Object>>> selectSQLBuilderAndParamSetterPool = new HashMap<>();

    private final Map<Class<? extends SQLBuilder>, Tuple2<BiFunction<Collection<String>, Integer, String>, Jdbc.BiParametersSetter<PreparedStatement, Collection<?>>>> batchSelectSQLBuilderAndParamSetterPool = new HashMap<>();

    private final Map<Class<? extends SQLBuilder>, Tuple2<String, Jdbc.BiParametersSetter<PreparedStatement, Object>>> setNullSqlAndParamSetterPool = new HashMap<>();

    private final Map<Class<? extends SQLBuilder>, Tuple3<String, String, Jdbc.BiParametersSetter<PreparedStatement, Object>>> deleteSqlAndParamSetterPool = new HashMap<>();

    private final Map<Class<? extends SQLBuilder>, Tuple3<IntFunction<String>, IntFunction<String>, Jdbc.BiParametersSetter<PreparedStatement, Collection<?>>>> batchDeleteSQLBuilderAndParamSetterForPool = new HashMap<>();

    /**
     * Constructs a new JoinInfo instance for managing join relationships between entities.
     * This constructor performs comprehensive validation and initialization of join metadata,
     * including parsing the {@code @JoinedBy} annotation, determining join type (one-to-many or many-to-many),
     * and building optimized SQL statements for join operations.
     *
     * <p>The constructor processes the join configuration and creates cached SQL builders and parameter setters
     * for different SQL builder types (PSC, PAC, PLC). It supports two main join patterns:</p>
     * <ul>
     *   <li><b>One-to-Many Join:</b> Direct foreign key relationship (e.g., "employeeId" or "employeeId = id")</li>
     *   <li><b>Many-to-Many Join:</b> Relationship through intermediate table (e.g., "employeeId = EmployeeProject.employeeId, EmployeeProject.projectId = projectId")</li>
     * </ul>
     *
     * <p><b>Validation Performed:</b></p>
     * <ul>
     *   <li>Verifies the join property exists in the entity class</li>
     *   <li>Ensures the property is annotated with {@code @JoinedBy}</li>
     *   <li>Validates that the property is not annotated with {@code @Column} (join properties should not be persisted directly)</li>
     *   <li>Checks that the referenced type is a valid entity class</li>
     *   <li>Validates join column pairs and their type compatibility</li>
     *   <li>For many-to-many joins, verifies the intermediate entity class exists and is properly configured</li>
     * </ul>
     *
     * <p><b>Implementation Notes:</b></p>
     * <ul>
     *   <li>The constructor caches SQL builders for performance optimization</li>
     *   <li>Key extractors are created for efficient entity grouping during join operations</li>
     *   <li>Parameter setters are optimized based on the number of join columns</li>
     *   <li>For many-to-many joins, both main entity and intermediate table SQL statements are generated</li>
     * </ul>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // One-to-many join example
     * // Entity: Employee has a property annotated with @JoinedBy("employeeId")
     * JoinInfo oneToManyJoinInfo = new JoinInfo(
     *     Employee.class,
     *     "employees",
     *     "projects",
     *     false  // Don't allow null join values
     * );
     *
     * // Many-to-many join example
     * // Entity: Employee has a property annotated with
     * // @JoinedBy("employeeId = EmployeeProject.employeeId, EmployeeProject.projectId = projectId")
     * JoinInfo manyToManyJoinInfo = new JoinInfo(
     *     Employee.class,
     *     "employees",
     *     "projects",
     *     true  // Allow null join values
     * );
     * }</pre>
     *
     * @param entityClass the entity class containing the join property, must not be {@code null}
     * @param tableName the database table name for the entity, must not be {@code null}
     * @param joinEntityPropName the name of the property annotated with {@code @JoinedBy}, must not be {@code null}
     * @param allowJoiningByNullOrDefaultValue if {@code true}, allows join operations when join property values are {@code null} or default;
     *                                         if {@code false}, throws IllegalArgumentException for null/default join values.
     *                                         This flag is typically controlled by the {@code @DaoConfig} annotation on the DAO class
     * @throws IllegalArgumentException if the join property is not found, not properly annotated, or the join configuration is invalid
     * @throws IllegalArgumentException if the referenced entity type is not a valid bean/entity class
     * @throws IllegalArgumentException if join column types are incompatible between source and referenced entities
     * @throws IllegalArgumentException if the many-to-many intermediate entity class is not found or improperly configured
     *
     * @see JoinedBy
     * @see com.landawn.abacus.jdbc.annotation.DaoConfig
     * @see #isManyToManyJoin()
     */
    JoinInfo(final Class<?> entityClass, final String tableName, final String joinEntityPropName, final boolean allowJoiningByNullOrDefaultValue) {
        this.allowJoiningByNullOrDefaultValue = allowJoiningByNullOrDefaultValue;
        this.entityClass = entityClass;
        this.tableName = tableName;
        entityInfo = ParserUtil.getBeanInfo(entityClass);
        joinPropInfo = entityInfo.getPropInfo(joinEntityPropName);

        if (joinPropInfo == null) {
            throw new IllegalArgumentException(
                    "No property found by name: '" + joinEntityPropName + "' in class: " + ClassUtil.getCanonicalClassName(entityClass));
        } else if (!joinPropInfo.isAnnotationPresent(JoinedBy.class)) {
            throw new IllegalArgumentException("Property '" + joinPropInfo.name + "' in class: " + entityClass + " is not annotated by @JoinedBy");
        } else if (joinPropInfo.columnName.isPresent()) {
            throw new IllegalArgumentException("Property '" + joinPropInfo.name + "' in class: " + entityClass + " is annotated by @Column");
        }

        referencedEntityType = joinPropInfo.type.isMap() ? joinPropInfo.type.getParameterTypes()[1]
                : (joinPropInfo.type.isCollection() ? joinPropInfo.type.getElementType() : joinPropInfo.type);

        if (!referencedEntityType.isBean()) {
            throw new IllegalArgumentException("Property '" + joinPropInfo.name + "' in class: " + entityClass + " is not an entity type");
        }

        referencedEntityClass = referencedEntityType.clazz();
        referencedBeanInfo = ParserUtil.getBeanInfo(referencedEntityClass);

        final JoinedBy joinedByAnno = joinPropInfo.getAnnotation(JoinedBy.class);
        final boolean cascadeDeleteDefinedInDB = true; // joinedByAnno.cascadeDeleteDefinedInDB();   // TODO should be defined/implemented on DB server side.
        final String joinByVal = Strings.join(joinedByAnno.value(), ", ");

        if (Strings.isEmpty(joinByVal)) {
            throw new IllegalArgumentException(
                    "Invalid value: " + joinByVal + " for annotation @JoinedBy on property '" + joinPropInfo.name + "' in class: " + entityClass);
        }

        final String[] joinColumnPairs = Strings.split(joinByVal, ',', true);

        isManyToManyJoin = StreamEx.of(joinColumnPairs)
                .flattmap(it -> Strings.split(it, '=', true))
                .filter(it -> it.indexOf('.') > 0) //NOSONAR
                .map(it -> it.substring(0, it.indexOf('.')).trim())
                .anyMatch(it -> !(it.equalsIgnoreCase(entityInfo.simpleClassName) || it.equalsIgnoreCase(referencedBeanInfo.simpleClassName)));

        // Many to many joined by third table
        if (isManyToManyJoin) {
            if (joinColumnPairs.length != 2) {
                throw new IllegalArgumentException(
                        "Invalid value: " + joinByVal + " for annotation @JoinedBy on property '" + joinPropInfo.name + "' in class: " + entityClass
                                + ". The format for many-many join should be: employeeId = EmployeeProject.employeeId, EmployeeProject.projectId=projectId");
            }

            srcPropInfos = new PropInfo[1];
            referencedPropInfos = new PropInfo[1];

            final String[] left = Strings.split(joinColumnPairs[0], '=', true);
            final String[] right = Strings.split(joinColumnPairs[1], '=', true);

            if ((srcPropInfos[0] = entityInfo.getPropInfo(left[0])) == null) {
                throw new IllegalArgumentException("Invalid value: " + joinByVal + " for annotation @JoinedBy on property '" + joinPropInfo.name
                        + "' in class: " + entityClass + ". No property found with name: '" + left[0] + "' in the class: " + entityClass);
            }

            if ((referencedPropInfos[0] = referencedBeanInfo.getPropInfo(right[1])) == null) {
                throw new IllegalArgumentException("Invalid value: " + joinByVal + " for annotation @JoinedBy on property '" + joinPropInfo.name
                        + "' in class: " + entityClass + ". No referenced property found with name: '" + right[1] + "' in the class: " + referencedEntityClass);
            }

            final String middleEntity = left[1].substring(0, left[1].indexOf('.'));

            if (!right[0].startsWith(middleEntity + ".")) {
                throw new IllegalArgumentException(
                        "Invalid value: " + joinByVal + " for annotation @JoinedBy on property '" + joinPropInfo.name + "' in class: " + entityClass
                                + ". The format for many-many join should be: employeeId = EmployeeProject.employeeId, EmployeeProject.projectId=projectId");
            }

            final String entityPackageName = ClassUtil.getPackageName(entityClass);
            final String middleEntityClassName = Strings.isEmpty(entityPackageName) ? middleEntity : entityPackageName + "." + middleEntity;
            Class<?> tmpMiddleEntityClass = null;

            try {
                tmpMiddleEntityClass = ClassUtil.forClass(middleEntityClassName);
            } catch (final Throwable e) {
                throw new IllegalArgumentException(
                        "For many to many mapping/join, the join entity class is required but it's not defined or found by name: " + middleEntityClassName, e);
            }

            if (tmpMiddleEntityClass == null) {
                throw new IllegalArgumentException(
                        "For many to many mapping/join, the join entity class is required but it's not defined or found by name: " + middleEntityClassName);
            }

            final Class<?> middleEntityClass = tmpMiddleEntityClass;
            final ParserUtil.BeanInfo middleEntityInfo = ParserUtil.getBeanInfo(middleEntityClass);

            if (!ClassUtil.wrap(srcPropInfos[0].clazz).equals(ClassUtil.wrap(middleEntityInfo.getPropInfo(left[1]).clazz))
                    || !ClassUtil.wrap(referencedPropInfos[0].clazz).equals(ClassUtil.wrap(middleEntityInfo.getPropInfo(right[0]).clazz))) {
                throw new IllegalArgumentException("Invalid JoinedBy value: " + joinByVal + " for annotation @JoinedBy on property '" + joinPropInfo.name
                        + "' in the class: " + entityClass + ". The types of source property and referenced are not same: " + Stream
                                .of(srcPropInfos[0].clazz, middleEntityInfo.getPropInfo(left[1]).clazz, referencedPropInfos[0].clazz,
                                        middleEntityInfo.getPropInfo(right[0]).clazz)
                                .map(ClassUtil::getSimpleClassName)
                                .toList());
            }

            final List<Integer> dummyList = N.asList(1, 2, 3);
            final Condition cond = Filters.in(right[1], dummyList);   //
            final String inCondToReplace = Strings.repeat("?", dummyList.size(), ", ");

            final List<String> middleSelectPropNames = N.asList(right[0].substring(right[0].indexOf('.') + 1));
            final Condition middleEntityCond = Filters.eq(left[1].substring(left[1].indexOf('.') + 1));

            final Jdbc.BiParametersSetter<PreparedStatement, Object> paramSetter = (stmt, entity) -> srcPropInfos[0].dbType.set(stmt, 1,
                    getJoinPropValue(srcPropInfos[0], entity));

            final Jdbc.BiParametersSetter<PreparedStatement, Collection<?>> batchParaSetter = (stmt, entities) -> {
                int index = 1;

                for (final Object entity : entities) {
                    srcPropInfos[0].dbType.set(stmt, index++, getJoinPropValue(srcPropInfos[0], entity));
                }
            };

            final Jdbc.BiParametersSetter<PreparedStatement, Object> setNullParamSetterForUpdate = (stmt, entity) -> {
                referencedPropInfos[0].dbType.set(stmt, 1, referencedPropInfos[0].dbType.defaultValue());
                srcPropInfos[0].dbType.set(stmt, 2, getJoinPropValue(srcPropInfos[0], entity));
            };

            for (final Map.Entry<Class<? extends SQLBuilder>, Tuple4<Function<Collection<String>, SQLBuilder>, Function<Class<?>, SQLBuilder>, Function<Class<?>, SQLBuilder>, Function<Class<?>, SQLBuilder>>> entry : sqlBuilderFuncMap
                    .entrySet()) {

                final String middleSelectSql = entry.getValue()._1.apply(middleSelectPropNames).from(middleEntityClass).where(middleEntityCond).sql();
                final String leftSelectSql = entry.getValue()._2.apply(referencedEntityClass).where(cond).sql();

                final int whereIndex = leftSelectSql.lastIndexOf(" WHERE ");
                N.checkState(whereIndex >= 0, "SQL query does not contain ' WHERE ' clause: %s", leftSelectSql);
                final String middleSelectSqlWhereIn = leftSelectSql.substring(whereIndex).replace(inCondToReplace, middleSelectSql);
                final String selectSql = entry.getValue()._2.apply(referencedEntityClass).sql() + middleSelectSqlWhereIn;

                final Function<Collection<String>, String> sqlBuilder = selectPropNames -> {
                    if (N.isEmpty(selectPropNames)) {
                        return selectSql;
                    } else {
                        if (!selectPropNames.contains(referencedPropInfos[0].name)) {
                            final List<String> newSelectPropNames = new ArrayList<>(selectPropNames.size() + 1);
                            newSelectPropNames.add(referencedPropInfos[0].name);
                            newSelectPropNames.addAll(selectPropNames);

                            return entry.getValue()._1.apply(newSelectPropNames).from(referencedEntityClass).append(middleSelectSqlWhereIn).sql();
                        } else {
                            return entry.getValue()._1.apply(selectPropNames).from(referencedEntityClass).append(middleSelectSqlWhereIn).sql();
                        }
                    }
                };

                selectSQLBuilderAndParamSetterPool.put(entry.getKey(), Tuple.of(sqlBuilder, paramSetter));

                final List<String> middleSelectWords = SQLParser.parse(middleSelectSql);
                final String middleTableName = middleSelectWords.get(10);
                final String middleSelectPropName = middleTableName + "." + middleSelectWords.get(2);
                final String middleCondPropName = middleTableName + "." + middleSelectWords.get(14);

                final int fromIndex = leftSelectSql.lastIndexOf(" FROM ");
                final List<String> leftSelectLastWords = SQLParser.parse(leftSelectSql.substring(fromIndex + 6));
                final String leftTableName = leftSelectLastWords.get(0);
                final String leftCondPropName = leftTableName + "." + leftSelectLastWords.get(4);

                final String batchSelectFromToJoinOn = " FROM " + leftTableName + " INNER JOIN " + middleTableName + " ON " + leftCondPropName + " = "
                        + middleSelectPropName + " WHERE " + middleCondPropName + " IN (";

                final Collection<String> defaultSelectPropNames = JdbcUtil.getSelectPropNames(referencedEntityClass);

                // same column name in reference entity and middle entity
                final boolean hasSameColumnName = Stream.of(SQLParser.parse(leftSelectSql.substring(0, fromIndex)))
                        .skip(2)
                        .split(7)
                        .anyMatch(it -> middleSelectWords.get(2).equalsIgnoreCase(it.get(0)));

                final String leftSelectSqlForBatch = hasSameColumnName //
                        ? entry.getValue()._1.apply(defaultSelectPropNames).from(referencedEntityClass, leftTableName).sql()
                        : entry.getValue()._1.apply(defaultSelectPropNames).from(referencedEntityClass).sql();

                final int fromIndexInBatch = leftSelectSqlForBatch.lastIndexOf(" FROM ");
                N.checkState(fromIndexInBatch >= 0, "SQL query does not contain ' FROM ' clause: %s", leftSelectSqlForBatch);
                final int fromLength = leftSelectSqlForBatch.length() - fromIndexInBatch;

                final String batchSelectAllLeftSql = leftSelectSqlForBatch.substring(0, leftSelectSqlForBatch.length() - fromLength) + ", " + middleCondPropName
                        + batchSelectFromToJoinOn;

                final BiFunction<Collection<String>, Integer, String> batchSQLBuilder = (selectPropNames, size) -> {
                    if (N.isEmpty(selectPropNames)) {
                        return Strings.repeat("?", size, ", ", batchSelectAllLeftSql, ")");
                    } else {
                        Collection<String> newSelectPropNames = selectPropNames;

                        if (!selectPropNames.contains(referencedPropInfos[0].name)) {
                            newSelectPropNames = new ArrayList<>(selectPropNames.size() + 1);
                            newSelectPropNames.add(referencedPropInfos[0].name);
                            newSelectPropNames.addAll(selectPropNames);
                        }

                        final StringBuilder sb = Objectory.createStringBuilder();

                        final String tmpSql = hasSameColumnName //
                                ? entry.getValue()._1.apply(newSelectPropNames).from(referencedEntityClass, leftTableName).sql()
                                : entry.getValue()._1.apply(newSelectPropNames).from(referencedEntityClass).sql();

                        sb.append(tmpSql, 0, tmpSql.length() - fromLength).append(", ").append(middleCondPropName).append(batchSelectFromToJoinOn);

                        final String sql = sb.toString();

                        Objectory.recycle(sb);

                        return Strings.repeat("?", size, ", ", sql, ")");
                    }
                };

                batchSelectSQLBuilderAndParamSetterPool.put(entry.getKey(), Tuple.of(batchSQLBuilder, batchParaSetter));

                final List<String> referencedPropNames = Stream.of(referencedPropInfos).map(p -> p.name).toList();
                final String setNullSql = entry.getValue()._3.apply(referencedEntityClass).set(referencedPropNames).sql() + middleSelectSqlWhereIn;
                final String deleteSql = entry.getValue()._4.apply(referencedEntityClass).sql() + middleSelectSqlWhereIn;
                final String middleDeleteSql = entry.getValue()._4.apply(middleEntityClass).where(middleEntityCond).sql();

                setNullSqlAndParamSetterPool.put(entry.getKey(), Tuple.of(setNullSql, setNullParamSetterForUpdate));
                deleteSqlAndParamSetterPool.put(entry.getKey(), Tuple.of(deleteSql, cascadeDeleteDefinedInDB ? null : middleDeleteSql, paramSetter));

                final String batchDeleteSqlHeader = entry.getValue()._4.apply(referencedEntityClass)
                        .where(cond)
                        .sql()
                        .replace(inCondToReplace, middleSelectSql)
                        .replace(" = ?)", " IN (");

                final IntFunction<String> batchDeleteSQLBuilder = size -> {
                    if (size == 1) {
                        return deleteSql;
                    } else {
                        return Strings.repeat("?", size, ", ", batchDeleteSqlHeader, "))");
                    }
                };

                final String batchMiddleDeleteSql = entry.getValue()._4.apply(middleEntityClass).where(middleEntityCond).sql().replace(" = ?", " IN (");

                final IntFunction<String> batchMiddleDeleteSQLBuilder = size -> {
                    if (size == 1) {
                        return middleDeleteSql;
                    } else {
                        return Strings.repeat("?", size, ", ", batchMiddleDeleteSql, ")");
                    }
                };

                batchDeleteSQLBuilderAndParamSetterForPool.put(entry.getKey(),
                        Tuple.of(batchDeleteSQLBuilder, cascadeDeleteDefinedInDB ? null : batchMiddleDeleteSQLBuilder, batchParaSetter));
            }

            srcEntityKeyExtractor = entity -> getJoinPropValue(srcPropInfos[0], entity);
            referencedEntityKeyExtractor = referencedPropInfos[0]::getPropValue;
        } else {
            srcPropInfos = new PropInfo[joinColumnPairs.length];
            referencedPropInfos = new PropInfo[joinColumnPairs.length];

            final List<Condition> conds = new ArrayList<>(joinColumnPairs.length);

            for (int i = 0, len = joinColumnPairs.length; i < len; i++) {
                final String[] tmp = Strings.split(joinColumnPairs[i], '=', true);

                if (tmp.length > 2) {
                    throw new IllegalArgumentException(
                            "Invalid value: " + joinByVal + " for annotation @JoinedBy on property '" + joinPropInfo.name + "' in class: " + entityClass);
                }

                if ((srcPropInfos[i] = entityInfo.getPropInfo(tmp[0])) == null) {
                    throw new IllegalArgumentException("Invalid JoinedBy value: " + joinByVal + " for annotation @JoinedBy on property '" + joinPropInfo.name
                            + "' in class: " + entityClass + ". No property found with name: '" + tmp[0] + "' in the class: " + entityClass);
                }

                if ((referencedPropInfos[i] = referencedBeanInfo.getPropInfo(tmp.length == 1 ? tmp[0] : tmp[1])) == null) {
                    throw new IllegalArgumentException("Invalid JoinedBy value: " + joinByVal + " for annotation @JoinedBy on property '" + joinPropInfo.name
                            + "' in class: " + entityClass + ". No referenced property found with name: '" + (tmp.length == 1 ? tmp[0] : tmp[1])
                            + "' in the class: " + referencedEntityClass);
                }

                if (!ClassUtil.wrap(srcPropInfos[i].clazz).equals(ClassUtil.wrap(referencedPropInfos[i].clazz))) {
                    throw new IllegalArgumentException("Invalid JoinedBy value: " + joinByVal + " for annotation @JoinedBy on property '" + joinPropInfo.name
                            + "' in the class: " + entityClass + ". The types of source property and referenced are not same: "
                            + Stream.of(srcPropInfos[i].clazz, referencedPropInfos[i].clazz).map(ClassUtil::getSimpleClassName).toList());
                }

                conds.add(Filters.eq(referencedPropInfos[i].name));
            }

            final Condition cond = joinColumnPairs.length == 1 ? conds.get(0) : Filters.and(conds);

            final Jdbc.BiParametersSetter<PreparedStatement, Object> paramSetter = srcPropInfos.length == 1
                    ? (stmt, entity) -> srcPropInfos[0].dbType.set(stmt, 1, getJoinPropValue(srcPropInfos[0], entity))
                    : (srcPropInfos.length == 2 ? (stmt, entity) -> {
                        srcPropInfos[0].dbType.set(stmt, 1, getJoinPropValue(srcPropInfos[0], entity));
                        srcPropInfos[1].dbType.set(stmt, 2, getJoinPropValue(srcPropInfos[1], entity));
                    } : (stmt, entity) -> {
                        for (int i = 0, len = srcPropInfos.length; i < len; i++) {
                            srcPropInfos[i].dbType.set(stmt, i + 1, getJoinPropValue(srcPropInfos[i], entity));
                        }
                    });

            final Jdbc.BiParametersSetter<PreparedStatement, Collection<?>> batchParaSetter = srcPropInfos.length == 1 ? (stmt, entities) -> {
                int index = 1;

                for (final Object entity : entities) {
                    srcPropInfos[0].dbType.set(stmt, index++, getJoinPropValue(srcPropInfos[0], entity));
                }
            } : (srcPropInfos.length == 2 ? (stmt, entities) -> {
                int index = 1;

                for (final Object entity : entities) {
                    srcPropInfos[0].dbType.set(stmt, index++, getJoinPropValue(srcPropInfos[0], entity));
                    srcPropInfos[1].dbType.set(stmt, index++, getJoinPropValue(srcPropInfos[1], entity));
                }
            } : (stmt, entities) -> {
                int index = 1;

                for (final Object entity : entities) {
                    for (final PropInfo element : srcPropInfos) {
                        element.dbType.set(stmt, index++, getJoinPropValue(element, entity));
                    }
                }
            });

            final Jdbc.BiParametersSetter<PreparedStatement, Object> setNullParamSetterForUpdate = srcPropInfos.length == 1 ? (stmt, entity) -> {
                srcPropInfos[0].dbType.set(stmt, 1, srcPropInfos[0].dbType.defaultValue());
                srcPropInfos[0].dbType.set(stmt, 2, getJoinPropValue(srcPropInfos[0], entity));
            } : (srcPropInfos.length == 2 ? (stmt, entity) -> {
                srcPropInfos[0].dbType.set(stmt, 1, srcPropInfos[0].dbType.defaultValue());
                srcPropInfos[1].dbType.set(stmt, 2, srcPropInfos[1].dbType.defaultValue());
                srcPropInfos[0].dbType.set(stmt, 3, getJoinPropValue(srcPropInfos[0], entity));
                srcPropInfos[1].dbType.set(stmt, 4, getJoinPropValue(srcPropInfos[1], entity));
            } : (stmt, entity) -> {
                for (int i = 0, len = srcPropInfos.length; i < len; i++) {
                    srcPropInfos[i].dbType.set(stmt, i + 1, srcPropInfos[i].dbType.defaultValue());
                }

                for (int i = 0, len = srcPropInfos.length; i < len; i++) {
                    srcPropInfos[i].dbType.set(stmt, len + i + 1, getJoinPropValue(srcPropInfos[i], entity));
                }
            });

            for (final Map.Entry<Class<? extends SQLBuilder>, Tuple4<Function<Collection<String>, SQLBuilder>, Function<Class<?>, SQLBuilder>, Function<Class<?>, SQLBuilder>, Function<Class<?>, SQLBuilder>>> entry : sqlBuilderFuncMap
                    .entrySet()) {

                final String selectSql = entry.getValue()._2.apply(referencedEntityClass).where(cond).sql();

                final Function<Collection<String>, String> sqlBuilder = selectPropNames -> {
                    if (N.isEmpty(selectPropNames)) {
                        return selectSql;
                    } else {
                        return entry.getValue()._1.apply(selectPropNames).from(referencedEntityClass).where(cond).sql();
                    }
                };

                selectSQLBuilderAndParamSetterPool.put(entry.getKey(), Tuple.of(sqlBuilder, paramSetter));

                final BiFunction<SQLBuilder, Integer, SQLBuilder> appendWhereFunc = referencedPropInfos.length == 1
                        ? (sb, batchSize) -> sb.append(Filters.expr(referencedPropInfos[0].name)) //
                                .append(Strings.repeat("?", batchSize, ", ", " IN (", ")")) //
                        : (sb, batchSize) -> sb.where(Filters.or(N.repeat(cond, batchSize)));

                final BiFunction<Collection<String>, Integer, String> batchSelectSQLBuilder = (selectPropNames, size) -> {
                    if (size == 1) {
                        return sqlBuilder.apply(selectPropNames);
                    } else {
                        if (N.isEmpty(selectPropNames)) {
                            return appendWhereFunc.apply(entry.getValue()._2.apply(referencedEntityClass), size).sql();
                        } else {
                            if (!N.allMatch(referencedPropInfos, it -> selectPropNames.contains(it.name))) {
                                final Collection<String> newSelectPropNames = N.newLinkedHashSet(referencedPropInfos.length + selectPropNames.size());

                                for (final PropInfo propInfo : referencedPropInfos) {
                                    newSelectPropNames.add(propInfo.name);
                                }

                                newSelectPropNames.addAll(selectPropNames);
                            }

                            return appendWhereFunc.apply(entry.getValue()._1.apply(selectPropNames).from(referencedEntityClass), size).sql();
                        }
                    }
                };

                batchSelectSQLBuilderAndParamSetterPool.put(entry.getKey(), Tuple.of(batchSelectSQLBuilder, batchParaSetter));

                final List<String> referencedPropNames = Stream.of(referencedPropInfos).map(p -> p.name).toList();
                final String setNullSql = entry.getValue()._3.apply(referencedEntityClass).set(referencedPropNames).where(cond).sql();
                final String deleteSql = entry.getValue()._4.apply(referencedEntityClass).where(cond).sql();

                setNullSqlAndParamSetterPool.put(entry.getKey(), Tuple.of(setNullSql, setNullParamSetterForUpdate));
                deleteSqlAndParamSetterPool.put(entry.getKey(), Tuple.of(deleteSql, null, paramSetter));

                final IntFunction<String> batchDeleteSQLBuilder = size -> {
                    if (size == 1) {
                        return deleteSql;
                    } else {
                        return appendWhereFunc.apply(entry.getValue()._4.apply(referencedEntityClass), size).sql();
                    }
                };

                batchDeleteSQLBuilderAndParamSetterForPool.put(entry.getKey(), Tuple.of(batchDeleteSQLBuilder, null, batchParaSetter));
            }

            Function<Object, Object> srcEntityKeyExtractorTmp = null;
            Function<Object, Object> referencedEntityKeyExtractorTmp = null;

            if (srcPropInfos.length == 1) {
                final PropInfo srcPropInfo = srcPropInfos[0];
                final PropInfo referencedPropInfo = referencedPropInfos[0];

                srcEntityKeyExtractorTmp = entity -> getJoinPropValue(srcPropInfo, entity);
                referencedEntityKeyExtractorTmp = referencedPropInfo::getPropValue;
            } else if (srcPropInfos.length == 2) {
                final PropInfo srcPropInfo1 = srcPropInfos[0];
                final PropInfo srcPropInfo2 = srcPropInfos[1];
                final PropInfo referencedPropInfo1 = referencedPropInfos[0];
                final PropInfo referencedPropInfo2 = referencedPropInfos[1];

                srcEntityKeyExtractorTmp = entity -> Tuple.of(getJoinPropValue(srcPropInfo1, entity), getJoinPropValue(srcPropInfo2, entity));
                referencedEntityKeyExtractorTmp = entity -> Tuple.of(referencedPropInfo1.getPropValue(entity), referencedPropInfo2.getPropValue(entity));
            } else if (srcPropInfos.length == 3) {
                final PropInfo srcPropInfo1 = srcPropInfos[0];
                final PropInfo srcPropInfo2 = srcPropInfos[1];
                final PropInfo srcPropInfo3 = srcPropInfos[2];
                final PropInfo referencedPropInfo1 = referencedPropInfos[0];
                final PropInfo referencedPropInfo2 = referencedPropInfos[1];
                final PropInfo referencedPropInfo3 = referencedPropInfos[2];

                srcEntityKeyExtractorTmp = entity -> Tuple.of(getJoinPropValue(srcPropInfo1, entity), getJoinPropValue(srcPropInfo2, entity),
                        getJoinPropValue(srcPropInfo3, entity));

                referencedEntityKeyExtractorTmp = entity -> Tuple.of(referencedPropInfo1.getPropValue(entity), referencedPropInfo2.getPropValue(entity),
                        referencedPropInfo3.getPropValue(entity));
            } else {
                srcEntityKeyExtractorTmp = entity -> {
                    final List<Object> keys = new ArrayList<>(srcPropInfos.length);

                    for (final PropInfo srcPropInfo : srcPropInfos) {
                        keys.add(getJoinPropValue(srcPropInfo, entity));
                    }

                    return keys;
                };

                referencedEntityKeyExtractorTmp = entity -> {
                    final List<Object> keys = new ArrayList<>(referencedPropInfos.length);

                    for (final PropInfo referencedPropInfo : referencedPropInfos) {
                        keys.add(referencedPropInfo.getPropValue(entity));
                    }

                    return keys;
                };
            }

            srcEntityKeyExtractor = srcEntityKeyExtractorTmp;
            referencedEntityKeyExtractor = referencedEntityKeyExtractorTmp;
        }
    }

    /**
     * Retrieves the SQL builder and parameter setter for single entity select operations.
     * This method is used for building SQL SELECT statements with join conditions for a single entity.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JoinInfo joinInfo = JoinInfo.getPropJoinInfo(EmployeeDao.class, Employee.class,
     *                                               "employees", "projects");
     * Tuple2<Function<Collection<String>, String>, Jdbc.BiParametersSetter<PreparedStatement, Object>>
     *     builder = joinInfo.getSelectSQLBuilderAndParamSetter(PSC.class);
     *
     * // Build SQL with specific columns
     * String sql = builder._1.apply(Arrays.asList("id", "name", "description"));
     * }</pre>
     *
     * @param sbc the SQL builder class type (PSC, PAC, or PLC)
     * @return a tuple containing a function to build SQL and a parameter setter for prepared statements
     * @throws IllegalArgumentException if the SQL builder class is not supported
     *
     * @see SQLBuilder.PSC
     * @see SQLBuilder.PAC
     * @see SQLBuilder.PLC
     */
    public Tuple2<Function<Collection<String>, String>, Jdbc.BiParametersSetter<PreparedStatement, Object>> getSelectSQLBuilderAndParamSetter(
            final Class<? extends SQLBuilder> sbc) {
        final Tuple2<Function<Collection<String>, String>, Jdbc.BiParametersSetter<PreparedStatement, Object>> tp = selectSQLBuilderAndParamSetterPool.get(sbc);

        if (tp == null) {
            throw new IllegalArgumentException("Not supported SQLBuilder class: " + ClassUtil.getCanonicalClassName(sbc));
        }

        return tp;
    }

    /**
     * Retrieves the SQL builder and parameter setter for batch select operations.
     * This method is used for building SQL SELECT statements with join conditions for multiple entities.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JoinInfo joinInfo = JoinInfo.getPropJoinInfo(EmployeeDao.class, Employee.class,
     *                                               "employees", "projects");
     * Tuple2<BiFunction<Collection<String>, Integer, String>, Jdbc.BiParametersSetter<PreparedStatement, Collection<?>>>
     *     batchBuilder = joinInfo.getBatchSelectSQLBuilderAndParamSetter(PSC.class);
     *
     * // Build SQL for batch of entities
     * List<Employee> employees = getEmployees();
     * String sql = batchBuilder._1.apply(Arrays.asList("id", "name"), employees.size());
     * }</pre>
     *
     * @param sbc the SQL builder class type (PSC, PAC, or PLC)
     * @return a tuple containing a function to build SQL and a parameter setter for batch operations
     * @throws IllegalArgumentException if the SQL builder class is not supported
     *
     * @see SQLBuilder.PSC
     * @see SQLBuilder.PAC
     * @see SQLBuilder.PLC
     */
    public Tuple2<BiFunction<Collection<String>, Integer, String>, Jdbc.BiParametersSetter<PreparedStatement, Collection<?>>> getBatchSelectSQLBuilderAndParamSetter( //NOSONAR
            final Class<? extends SQLBuilder> sbc) {
        final Tuple2<BiFunction<Collection<String>, Integer, String>, Jdbc.BiParametersSetter<PreparedStatement, Collection<?>>> tp = batchSelectSQLBuilderAndParamSetterPool
                .get(sbc);

        if (tp == null) {
            throw new IllegalArgumentException("Not supported SQLBuilder class: " + ClassUtil.getCanonicalClassName(sbc));
        }

        return tp;
    }

    //    public Tuple2<String, BiParametersSetter<PreparedStatement, Object>> getSetNullSqlAndParamSetter(final Class<? extends SQLBuilder> sbc) {
    //        final Tuple2<String, BiParametersSetter<PreparedStatement, Object>> tp = setNullSqlAndParamSetterPool.get(sbc);
    //
    //        if (tp == null) {
    //            throw new IllegalArgumentException("Not supported SQLBuilder class: " + ClassUtil.getCanonicalClassName(sbc));
    //        }
    //
    //        return tp;
    //    }

    /**
     * Retrieves the SQL and parameter setter for delete operations.
     * This method returns SQL statements for deleting joined entities and optionally the join table entries.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JoinInfo joinInfo = JoinInfo.getPropJoinInfo(EmployeeDao.class, Employee.class,
     *                                               "employees", "projects");
     * Tuple3<String, String, Jdbc.BiParametersSetter<PreparedStatement, Object>>
     *     deleteSql = joinInfo.getDeleteSqlAndParamSetter(PSC.class);
     *
     * String deleteSql = deleteSql._1;  // Main delete SQL
     * String middleTableDeleteSql = deleteSql._2;  // Join table delete SQL (if many-to-many)
     * Jdbc.BiParametersSetter<PreparedStatement, Object> paramSetter = deleteSql._3;
     * }</pre>
     *
     * @param sbc the SQL builder class type (PSC, PAC, or PLC)
     * @return a tuple containing the delete SQL, optional middle table delete SQL (null if not many-to-many), and parameter setter
     * @throws IllegalArgumentException if the SQL builder class is not supported
     *
     * @see SQLBuilder.PSC
     * @see SQLBuilder.PAC
     * @see SQLBuilder.PLC
     */
    public Tuple3<String, String, Jdbc.BiParametersSetter<PreparedStatement, Object>> getDeleteSqlAndParamSetter(final Class<? extends SQLBuilder> sbc) {
        final Tuple3<String, String, Jdbc.BiParametersSetter<PreparedStatement, Object>> tp = deleteSqlAndParamSetterPool.get(sbc);

        if (tp == null) {
            throw new IllegalArgumentException("Not supported SQLBuilder class: " + ClassUtil.getCanonicalClassName(sbc));
        }

        return tp;
    }

    /**
     * Retrieves the SQL builder and parameter setter for batch delete operations.
     * This method is used for building SQL DELETE statements for multiple joined entities.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JoinInfo joinInfo = JoinInfo.getPropJoinInfo(EmployeeDao.class, Employee.class,
     *                                               "employees", "projects");
     * Tuple3<IntFunction<String>, IntFunction<String>, Jdbc.BiParametersSetter<PreparedStatement, Collection<?>>>
     *     batchDelete = joinInfo.getBatchDeleteSQLBuilderAndParamSetter(PSC.class);
     *
     * List<Employee> employees = getEmployeesToDelete();
     * String deleteSql = batchDelete._1.apply(employees.size());   // Main delete SQL
     * String middleTableDeleteSql = batchDelete._2 != null ? batchDelete._2.apply(employees.size()) : null;
     * Jdbc.BiParametersSetter<PreparedStatement, Collection<?>> paramSetter = batchDelete._3;
     * }</pre>
     *
     * @param sbc the SQL builder class type (PSC, PAC, or PLC)
     * @return a tuple containing SQL builders for delete operations (main and optional middle table) and a parameter setter
     * @throws IllegalArgumentException if the SQL builder class is not supported
     *
     * @see SQLBuilder.PSC
     * @see SQLBuilder.PAC
     * @see SQLBuilder.PLC
     */
    public Tuple3<IntFunction<String>, IntFunction<String>, Jdbc.BiParametersSetter<PreparedStatement, Collection<?>>> getBatchDeleteSQLBuilderAndParamSetter( //NOSONAR
            final Class<? extends SQLBuilder> sbc) {
        final Tuple3<IntFunction<String>, IntFunction<String>, Jdbc.BiParametersSetter<PreparedStatement, Collection<?>>> tp = batchDeleteSQLBuilderAndParamSetterForPool
                .get(sbc);

        if (tp == null) {
            throw new IllegalArgumentException("Not supported SQLBuilder class: " + ClassUtil.getCanonicalClassName(sbc));
        }

        return tp;
    }

    /**
     * Sets join property entities for a collection of source entities.
     * This method populates the join properties of the source entities with the provided joined entities
     * based on the join key relationships.
     *
     * <p>For one-to-one or one-to-many joins, the method groups the joined entities by their keys
     * and assigns them to the corresponding source entities.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Load employees
     * List<Employee> employees = employeeDao.list();
     *
     * // Load projects
     * List<Project> projects = projectDao.list();
     *
     * // Get join info and populate relationships
     * JoinInfo joinInfo = JoinInfo.getPropJoinInfo(EmployeeDao.class, Employee.class,
     *                                               "employees", "projects");
     * joinInfo.setJoinPropEntities(employees, projects);
     * // Now each employee has their projects populated
     * }</pre>
     *
     * @param entities the source entities to populate with joined entities
     * @param joinPropEntities the joined entities to be set on the source entities
     *
     * @see #setJoinPropEntities(Collection, Map)
     */
    public void setJoinPropEntities(final Collection<?> entities, final Collection<?> joinPropEntities) {
        final Map<Object, List<Object>> groupedPropEntities = Stream.of((Collection<Object>) joinPropEntities).groupTo(referencedEntityKeyExtractor);
        setJoinPropEntities(entities, groupedPropEntities);
    }

    /**
     * Sets join property entities for a collection of source entities using pre-grouped entities.
     * This method populates the join properties of the source entities with the provided grouped entities.
     *
     * <p>The method handles both collection properties (List, Set, etc.) and single entity properties,
     * automatically adapting the assignment based on the property type.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Load employees
     * List<Employee> employees = employeeDao.list();
     *
     * // Load and group projects by employee ID
     * List<Project> projects = projectDao.list();
     * Map<Object, List<Object>> projectsByEmployeeId = Stream.of((Collection<Object>) projects)
     *     .groupTo(p -> ((Project) p).getEmployeeId());
     *
     * // Get join info and populate relationships with pre-grouped data
     * JoinInfo joinInfo = JoinInfo.getPropJoinInfo(EmployeeDao.class, Employee.class,
     *                                               "employees", "projects");
     * joinInfo.setJoinPropEntities(employees, projectsByEmployeeId);
     * }</pre>
     *
     * @param entities the source entities to populate with joined entities
     * @param groupedPropEntities a map of grouped entities keyed by their join keys
     */
    public void setJoinPropEntities(final Collection<?> entities, final Map<Object, List<Object>> groupedPropEntities) {
        final boolean isCollectionProp = joinPropInfo.type.isCollection();
        final boolean isListProp = joinPropInfo.clazz.isAssignableFrom(List.class);

        List<Object> propEntities = null;

        for (final Object entity : entities) {
            propEntities = groupedPropEntities.get(srcEntityKeyExtractor.apply(entity));

            if (propEntities != null) {
                if (isCollectionProp) {
                    if (isListProp || joinPropInfo.clazz.isAssignableFrom(propEntities.getClass())) {
                        joinPropInfo.setPropValue(entity, propEntities);
                    } else {
                        @SuppressWarnings("rawtypes")
                        final Collection<Object> c = N.newCollection((Class) joinPropInfo.clazz);
                        c.addAll(propEntities);
                        joinPropInfo.setPropValue(entity, c);
                    }
                } else {
                    joinPropInfo.setPropValue(entity, propEntities.get(0));
                }
            }
        }
    }

    /**
     * Checks if this join relationship is a many-to-many join.
     * A many-to-many join involves an intermediate join table connecting two entities.
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * JoinInfo joinInfo = JoinInfo.getPropJoinInfo(EmployeeDao.class, Employee.class,
     *                                               "employees", "projects");
     *
     * if (joinInfo.isManyToManyJoin()) {
     *     // Handle many-to-many relationship with join table
     *     System.out.println("This is a many-to-many relationship");
     * } else {
     *     // Handle one-to-many or one-to-one relationship
     *     System.out.println("This is a direct relationship");
     * }
     * }</pre>
     *
     * @return {@code true} if this is a many-to-many join, {@code false} otherwise
     */
    public boolean isManyToManyJoin() {
        return isManyToManyJoin;
    }

    private Object getJoinPropValue(final PropInfo propInfo, final Object entity) {
        final Object value = propInfo.getPropValue(entity);

        if (!allowJoiningByNullOrDefaultValue && JdbcUtil.isNullOrDefault(value)) {
            throw new IllegalArgumentException("The join property value can't be null or default for property: " + propInfo.name
                    + ". Annotated the Dao class of " + entityClass + " with @DaoConfig{allowJoiningByNullOrDefaultValue = true} to avoid this exception");
        }

        return value;
    }

    private static final Map<Class<?>, Map<Tuple2<Class<?>, String>, Map<String, JoinInfo>>> daoEntityJoinInfoPool = new ConcurrentHashMap<>();

    /**
     * Retrieves all join information for the specified entity class.
     * This method returns a map of property names to their corresponding JoinInfo objects
     * for all properties annotated with {@code @JoinedBy} in the entity class.
     *
     * <p>The result is cached for performance, so subsequent calls with the same parameters
     * will return the cached map without re-parsing the entity class.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get all join info for Employee entity
     * Map<String, JoinInfo> joinInfoMap = JoinInfo.getEntityJoinInfo(
     *     EmployeeDao.class,
     *     Employee.class,
     *     "employees"
     * );
     *
     * // Iterate through all join properties
     * for (Map.Entry<String, JoinInfo> entry : joinInfoMap.entrySet()) {
     *     String propName = entry.getKey();
     *     JoinInfo joinInfo = entry.getValue();
     *     System.out.println("Join property: " + propName);
     *
     *     if (joinInfo.isManyToManyJoin()) {
     *         System.out.println("  - Many-to-many relationship");
     *     } else {
     *         System.out.println("  - One-to-many relationship");
     *     }
     * }
     * }</pre>
     *
     * @param daoClass the DAO class associated with the entity, must not be {@code null}
     * @param entityClass the entity class to inspect for join properties, must not be {@code null}
     * @param tableName the database table name for the entity, must not be {@code null}
     * @return an unmodifiable map of property names to JoinInfo objects, never {@code null}, empty if no join properties exist
     *
     * @see JoinedBy
     * @see DaoConfig
     */
    public static Map<String, JoinInfo> getEntityJoinInfo(final Class<?> daoClass, final Class<?> entityClass, final String tableName) {
        Map<Tuple2<Class<?>, String>, Map<String, JoinInfo>> entityJoinInfoMap = daoEntityJoinInfoPool.computeIfAbsent(daoClass,
                k -> new ConcurrentHashMap<>());

        final Tuple2<Class<?>, String> key = Tuple.of(entityClass, tableName);

        Map<String, JoinInfo> joinInfoMap = entityJoinInfoMap.get(key);

        if (joinInfoMap == null) {
            final DaoConfig anno = daoClass.getAnnotation(DaoConfig.class);
            final boolean allowJoiningByNullOrDefaultValue = !(anno == null || !anno.allowJoiningByNullOrDefaultValue());
            final BeanInfo entityInfo = ParserUtil.getBeanInfo(entityClass);

            joinInfoMap = new LinkedHashMap<>();

            for (final PropInfo propInfo : entityInfo.propInfoList) {
                if (!propInfo.isAnnotationPresent(JoinedBy.class)) {
                    continue;
                }

                joinInfoMap.put(propInfo.name, new JoinInfo(entityClass, tableName, propInfo.name, allowJoiningByNullOrDefaultValue));
            }

            entityJoinInfoMap.put(key, joinInfoMap);
        }

        return joinInfoMap;
    }

    /**
     * Retrieves join information for a specific property in an entity.
     * This method returns the JoinInfo for a single property annotated with {@code @JoinedBy}.
     *
     * <p>This is a convenience method that calls {@link #getEntityJoinInfo(Class, Class, String)}
     * and retrieves the specific property from the result.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Get join info for the 'projects' property
     * JoinInfo joinInfo = JoinInfo.getPropJoinInfo(
     *     EmployeeDao.class,
     *     Employee.class,
     *     "employees",
     *     "projects"
     * );
     *
     * // Use the join info to load related entities for a single employee
     * Employee employee = employeeDao.findById(123);
     * Tuple2<Function<Collection<String>, String>, Jdbc.BiParametersSetter<PreparedStatement, Object>>
     *     builder = joinInfo.getSelectSQLBuilderAndParamSetter(PSC.class);
     * String sql = builder._1.apply(null);   // Use default columns
     * List<Project> projects = JdbcUtil.prepareQuery(dataSource, sql)
     *                                   .setParameters(builder._2, employee)
     *                                   .list(Project.class);
     *
     * // Or use batch loading for multiple employees
     * List<Employee> employees = employeeDao.list();
     * List<Project> allProjects = projectDao.list();
     * joinInfo.setJoinPropEntities(employees, allProjects);
     * }</pre>
     *
     * @param daoClass the DAO class associated with the entity, must not be {@code null}
     * @param entityClass the entity class containing the join property, must not be {@code null}
     * @param tableName the database table name for the entity, must not be {@code null}
     * @param joinEntityPropName the name of the property with the {@code @JoinedBy} annotation, must not be {@code null}
     * @return the JoinInfo for the specified property, never {@code null}
     * @throws IllegalArgumentException if no join property is found with the given name
     *
     * @see JoinedBy
     * @see #getEntityJoinInfo(Class, Class, String)
     */
    public static JoinInfo getPropJoinInfo(final Class<?> daoClass, final Class<?> entityClass, final String tableName, final String joinEntityPropName) {
        final JoinInfo joinInfo = getEntityJoinInfo(daoClass, entityClass, tableName).get(joinEntityPropName);

        if (joinInfo == null) {
            throw new IllegalArgumentException(
                    "No join property found by name '" + joinEntityPropName + "' in class: " + ClassUtil.getCanonicalClassName(entityClass));
        }

        return joinInfo;
    }

    private static final Map<Tuple2<Class<?>, String>, Map<Class<?>, List<String>>> joinEntityPropNamesByTypePool = new ConcurrentHashMap<>();

    /**
     * Retrieves all property names in an entity that join to a specific entity type.
     * This method finds all properties annotated with {@code @JoinedBy} that reference
     * the specified entity class.
     *
     * <p>This is useful when you need to discover all relationships between two entity types,
     * especially when there might be multiple join properties pointing to the same entity class.</p>
     *
     * <p>The result is cached for performance, so subsequent calls with the same parameters
     * will return the cached list.</p>
     *
     * <p><b>Usage Examples:</b></p>
     * <pre>{@code
     * // Find all properties in Employee that join to Project
     * List<String> projectJoinProps = JoinInfo.getJoinEntityPropNamesByType(
     *     EmployeeDao.class,
     *     Employee.class,
     *     "employees",
     *     Project.class
     * );
     *
     * // Result might be: ["projects", "archivedProjects"]
     * for (String propName : projectJoinProps) {
     *     System.out.println("Found join property: " + propName);
     *
     *     // Load each join property separately
     *     JoinInfo joinInfo = JoinInfo.getPropJoinInfo(
     *         EmployeeDao.class, Employee.class, "employees", propName);
     *     // ... use joinInfo
     * }
     *
     * // Check if entity has any joins to a specific type
     * if (!projectJoinProps.isEmpty()) {
     *     System.out.println("Employee has " + projectJoinProps.size() +
     *                        " relationship(s) with Project");
     * }
     * }</pre>
     *
     * @param daoClass the DAO class associated with the entity, must not be {@code null}
     * @param entityClass the entity class to search for join properties, must not be {@code null}
     * @param tableName the database table name for the entity, must not be {@code null}
     * @param joinPropEntityClass the class of the joined entity to search for, must not be {@code null}
     * @return an unmodifiable list of property names that join to the specified entity class, never {@code null}, empty if none found
     *
     * @see JoinedBy
     * @see #getEntityJoinInfo(Class, Class, String)
     */
    public static List<String> getJoinEntityPropNamesByType(final Class<?> daoClass, final Class<?> entityClass, final String tableName,
            final Class<?> joinPropEntityClass) {
        final Tuple2<Class<?>, String> key = Tuple.of(entityClass, tableName);
        Map<Class<?>, List<String>> joinEntityPropNamesByTypeMap = joinEntityPropNamesByTypePool.get(key);

        if (joinEntityPropNamesByTypeMap == null) {
            joinEntityPropNamesByTypeMap = new HashMap<>();
            List<String> joinPropNames = null;

            for (final JoinInfo joinInfo : getEntityJoinInfo(daoClass, entityClass, tableName).values()) {
                joinPropNames = joinEntityPropNamesByTypeMap.computeIfAbsent(joinInfo.referencedEntityClass, k -> new ArrayList<>(1));

                joinPropNames.add(joinInfo.joinPropInfo.name);
            }

            joinEntityPropNamesByTypePool.put(key, joinEntityPropNamesByTypeMap);
        }

        return joinEntityPropNamesByTypeMap.getOrDefault(joinPropEntityClass, N.emptyList());
    }
}
