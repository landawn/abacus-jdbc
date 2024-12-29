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
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.condition.ConditionFactory.CF;
import com.landawn.abacus.jdbc.annotation.Config;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.BeanInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Objectory;
import com.landawn.abacus.util.SQLBuilder;
import com.landawn.abacus.util.SQLBuilder.PAC;
import com.landawn.abacus.util.SQLBuilder.PLC;
import com.landawn.abacus.util.SQLBuilder.PSC;
import com.landawn.abacus.util.SQLParser;
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
        final boolean cascadeDeleteDefinedInDB = true; // joinedByAnno.cascadeDeleteDefinedInDB(); // TODO should be defined/implemented on DB server side.
        final String joinByVal = Strings.join(joinedByAnno.value(), ", ");

        if (Strings.isEmpty(joinByVal)) {
            throw new IllegalArgumentException(
                    "Invalid value: " + joinByVal + " for annotation @JoinedBy on property '" + joinPropInfo.name + "' in class: " + entityClass);
        }

        final String[] joinColumnPairs = Strings.split(joinByVal, ',', true);

        isManyToManyJoin = StreamEx.of(joinColumnPairs)
                .flattMap(it -> Strings.split(it, '=', true))
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
            final Condition cond = CF.in(right[1], dummyList); //
            final String inCondToReplace = Strings.repeat("?", dummyList.size(), ", ");

            final List<String> middleSelectPropNames = N.asList(right[0].substring(right[0].indexOf('.') + 1));
            final Condition middleEntityCond = CF.eq(left[1].substring(left[1].indexOf('.') + 1));

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

                final String middleSelectSqlWhereIn = leftSelectSql.substring(leftSelectSql.lastIndexOf(" WHERE ")).replace(inCondToReplace, middleSelectSql);
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

                final int fromLength = leftSelectSqlForBatch.length() - leftSelectSqlForBatch.lastIndexOf(" FROM ");

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

                conds.add(CF.eq(referencedPropInfos[i].name));
            }

            final Condition cond = joinColumnPairs.length == 1 ? conds.get(0) : CF.and(conds);

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
                        ? (sb, batchSize) -> sb.append(CF.expr(referencedPropInfos[0].name)) //
                                .append(Strings.repeat("?", batchSize, ", ", " IN (", ")")) //
                        : (sb, batchSize) -> sb.where(CF.or(N.repeat(cond, batchSize)));

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
                final PropInfo srcPropInfo_1 = srcPropInfos[0];
                final PropInfo srcPropInfo_2 = srcPropInfos[1];
                final PropInfo referencedPropInfo_1 = referencedPropInfos[0];
                final PropInfo referencedPropInfo_2 = referencedPropInfos[1];

                srcEntityKeyExtractorTmp = entity -> Tuple.of(getJoinPropValue(srcPropInfo_1, entity), getJoinPropValue(srcPropInfo_2, entity));
                referencedEntityKeyExtractorTmp = entity -> Tuple.of(referencedPropInfo_1.getPropValue(entity), referencedPropInfo_2.getPropValue(entity));
            } else if (srcPropInfos.length == 3) {
                final PropInfo srcPropInfo_1 = srcPropInfos[0];
                final PropInfo srcPropInfo_2 = srcPropInfos[1];
                final PropInfo srcPropInfo_3 = srcPropInfos[2];
                final PropInfo referencedPropInfo_1 = referencedPropInfos[0];
                final PropInfo referencedPropInfo_2 = referencedPropInfos[1];
                final PropInfo referencedPropInfo_3 = referencedPropInfos[2];

                srcEntityKeyExtractorTmp = entity -> Tuple.of(getJoinPropValue(srcPropInfo_1, entity), getJoinPropValue(srcPropInfo_2, entity),
                        getJoinPropValue(srcPropInfo_3, entity));

                referencedEntityKeyExtractorTmp = entity -> Tuple.of(referencedPropInfo_1.getPropValue(entity), referencedPropInfo_2.getPropValue(entity),
                        referencedPropInfo_3.getPropValue(entity));
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
     *
     *
     * @param sbc
     * @return
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
     *
     *
     * @param sbc
     * @return
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
     *
     *
     * @param sbc
     * @return
     */
    public Tuple3<String, String, Jdbc.BiParametersSetter<PreparedStatement, Object>> getDeleteSqlAndParamSetter(final Class<? extends SQLBuilder> sbc) {
        final Tuple3<String, String, Jdbc.BiParametersSetter<PreparedStatement, Object>> tp = deleteSqlAndParamSetterPool.get(sbc);

        if (tp == null) {
            throw new IllegalArgumentException("Not supported SQLBuilder class: " + ClassUtil.getCanonicalClassName(sbc));
        }

        return tp;
    }

    /**
     *
     *
     * @param sbc
     * @return
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
     * For one-to-one or one-to-many join.
     *
     * @param entities
     * @param joinPropEntities
     */
    public void setJoinPropEntities(final Collection<?> entities, final Collection<?> joinPropEntities) {
        final Map<Object, List<Object>> groupedPropEntities = Stream.of((Collection<Object>) joinPropEntities).groupTo(referencedEntityKeyExtractor);
        setJoinPropEntities(entities, groupedPropEntities);
    }

    /**
     *
     *
     * @param entities
     * @param groupedPropEntities
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
     *
     *
     * @return
     */
    public boolean isManyToManyJoin() {
        return isManyToManyJoin;
    }

    private Object getJoinPropValue(final PropInfo propInfo, final Object entity) {
        final Object value = propInfo.getPropValue(entity);

        if (!allowJoiningByNullOrDefaultValue && JdbcUtil.isNullOrDefault(value)) {
            throw new IllegalArgumentException("The join property value can't be null or default for property: " + propInfo.name
                    + ". Annotated the Dao class of " + entityClass + " with @Config{allowJoiningByNullOrDefaultValue = true} to avoid this exception");
        }

        return value;
    }

    private static final Map<Class<?>, Map<Tuple2<Class<?>, String>, Map<String, JoinInfo>>> daoEntityJoinInfoPool = new ConcurrentHashMap<>();

    /**
     *
     *
     * @param daoClass
     * @param entityClass
     * @param tableName
     * @return
     */
    public static Map<String, JoinInfo> getEntityJoinInfo(final Class<?> daoClass, final Class<?> entityClass, final String tableName) {
        Map<Tuple2<Class<?>, String>, Map<String, JoinInfo>> entityJoinInfoMap = daoEntityJoinInfoPool.computeIfAbsent(daoClass,
                k -> new ConcurrentHashMap<>());

        final Tuple2<Class<?>, String> key = Tuple.of(entityClass, tableName);

        Map<String, JoinInfo> joinInfoMap = entityJoinInfoMap.get(key);

        if (joinInfoMap == null) {
            final Config anno = daoClass.getAnnotation(Config.class);
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
     *
     *
     * @param daoClass
     * @param entityClass
     * @param tableName
     * @param joinEntityPropName
     * @return
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
     *
     *
     * @param daoClass
     * @param entityClass
     * @param tableName
     * @param joinPropEntityClass
     * @return
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
