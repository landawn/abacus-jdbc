package com.landawn.abacus.util;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.landawn.abacus.DirtyMarker;
import com.landawn.abacus.annotation.Column;
import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.condition.ConditionFactory.CF;
import com.landawn.abacus.core.DirtyMarkerUtil;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.EntityInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.JdbcUtil.BiParametersSetter;
import com.landawn.abacus.util.SQLBuilder.PAC;
import com.landawn.abacus.util.SQLBuilder.PLC;
import com.landawn.abacus.util.SQLBuilder.PSC;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.function.BiFunction;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.stream.Stream;

final class JoinInfo {
    final Class<?> entityClass;
    final EntityInfo entityInfo;
    final PropInfo joinPropInfo;
    final PropInfo[] srcPropInfos;
    final PropInfo[] referencedPropInfos;
    final Type<?> referencedEntityType;
    final Class<?> referencedEntityClass;
    final EntityInfo referencedEntityInfo;
    final Function<Object, Object> srcEntityKeyExtractor;
    final Function<Object, Object> referencedEntityKeyExtractor;

    private final Map<Class<? extends SQLBuilder>, Tuple2<Function<Collection<String>, String>, BiParametersSetter<PreparedStatement, Object>>> sqlBuilderSetterForSingleEntityMap = new HashMap<>();

    private final Map<Class<? extends SQLBuilder>, Tuple2<BiFunction<Collection<String>, Integer, String>, BiParametersSetter<PreparedStatement, Collection<?>>>> sqlBuilderSetterForEntitiesMap = new HashMap<>();

    JoinInfo(final Class<?> entityClass, final String joinEntityPropName) {
        this.entityClass = entityClass;
        entityInfo = ParserUtil.getEntityInfo(entityClass);
        this.joinPropInfo = entityInfo.getPropInfo(joinEntityPropName);

        if (joinPropInfo == null) {
            throw new IllegalArgumentException(
                    "No property found by name: '" + joinEntityPropName + "' in class: " + ClassUtil.getCanonicalClassName(entityClass));
        } else if (!joinPropInfo.isAnnotationPresent(JoinedBy.class)) {
            throw new IllegalArgumentException("Property '" + joinPropInfo.name + "' in class: " + entityClass + " is not annotated by @JoinedBy");
        } else if (joinPropInfo.isAnnotationPresent(Column.class)) {
            throw new IllegalArgumentException("Property '" + joinPropInfo.name + "' in class: " + entityClass + " is annotated by @Column");
        }

        referencedEntityType = joinPropInfo.type.isCollection() ? joinPropInfo.type.getElementType() : joinPropInfo.type;

        if (!referencedEntityType.isEntity() || joinPropInfo.isAnnotationPresent(Column.class)) {
            throw new IllegalArgumentException(
                    "Property '" + joinPropInfo.name + "' in class: " + entityClass + " is not an entity type or annotated by @Column");
        }

        referencedEntityClass = referencedEntityType.clazz();

        final String joinByVal = joinPropInfo.getAnnotation(JoinedBy.class).value();

        if (N.isNullOrEmpty(joinByVal)) {
            throw new IllegalArgumentException(
                    "Invalid value: " + joinByVal + " for annotation @JoinBy on property '" + joinPropInfo.name + "' in class: " + entityClass);
        }

        referencedEntityInfo = ParserUtil.getEntityInfo(referencedEntityClass);

        final String[] joinColumnPairs = StringUtil.split(joinByVal, ',', true);
        srcPropInfos = new PropInfo[joinColumnPairs.length];
        referencedPropInfos = new PropInfo[joinColumnPairs.length];

        final List<Condition> conds = new ArrayList<>(joinColumnPairs.length);

        for (int i = 0, len = joinColumnPairs.length; i < len; i++) {
            final String[] tmp = StringUtil.split(joinColumnPairs[i], '=', true);

            if (tmp.length > 2) {
                throw new IllegalArgumentException(
                        "Invalid value: " + joinByVal + " for annotation @JoinBy on property '" + joinPropInfo.name + "' in class: " + entityClass);
            }

            if ((srcPropInfos[i] = entityInfo.getPropInfo(tmp[0])) == null) {
                throw new IllegalArgumentException("Invalid value: " + joinByVal + " for annotation @JoinBy on property '" + joinPropInfo.name + "' in class: "
                        + entityClass + ". No property found with name: '" + tmp[0] + "' in the class: " + entityClass);
            }

            if ((referencedPropInfos[i] = referencedEntityInfo.getPropInfo(tmp.length == 1 ? tmp[0] : tmp[1])) == null) {
                throw new IllegalArgumentException("Invalid value: " + joinByVal + " for annotation @JoinBy on property '" + joinPropInfo.name + "' in class: "
                        + entityClass + ". No referenced property found with name: '" + (tmp.length == 1 ? tmp[0] : tmp[1]) + "' in the class: "
                        + referencedEntityClass);
            }

            conds.add(CF.eq(referencedPropInfos[i].name));
        }

        final Condition cond = joinColumnPairs.length == 1 ? conds.get(0) : CF.and(conds);

        final BiParametersSetter<PreparedStatement, Object> paramSetter = srcPropInfos.length == 1
                ? (stmt, entityParam) -> srcPropInfos[0].dbType.set(stmt, 1, srcPropInfos[0].getPropValue(entityParam))
                : (srcPropInfos.length == 2 ? (stmt, entityParam) -> {
                    srcPropInfos[0].dbType.set(stmt, 1, srcPropInfos[0].getPropValue(entityParam));
                    srcPropInfos[1].dbType.set(stmt, 2, srcPropInfos[1].getPropValue(entityParam));
                } : (stmt, entityParam) -> {
                    for (int i = 0, len = srcPropInfos.length; i < len; i++) {
                        srcPropInfos[i].dbType.set(stmt, i + 1, srcPropInfos[i].getPropValue(entityParam));
                    }
                });

        final BiParametersSetter<PreparedStatement, Collection<?>> paramSetter2 = srcPropInfos.length == 1 ? (stmt, entities) -> {
            int index = 1;

            for (Object entity : entities) {
                srcPropInfos[0].dbType.set(stmt, index++, srcPropInfos[0].getPropValue(entity));
            }
        } : (stmt, entities) -> {
            int index = 1;

            for (Object entity : entities) {
                for (int i = 0, len = srcPropInfos.length; i < len; i++) {
                    srcPropInfos[i].dbType.set(stmt, index++, srcPropInfos[i].getPropValue(entity));
                }
            }
        };

        {
            final String sql = PSC.selectFrom(referencedEntityClass).where(cond).sql();

            final Function<Collection<String>, String> sqlBuilder = selectPropNames -> {
                if (N.isNullOrEmpty(selectPropNames)) {
                    return sql;
                } else {
                    return PSC.select(selectPropNames).from(referencedEntityClass).where(cond).sql();
                }
            };

            sqlBuilderSetterForSingleEntityMap.put(PSC.class, Tuple.of(sqlBuilder, paramSetter));

            final BiFunction<Collection<String>, Integer, String> sqlBuilder2 = (selectPropNames, size) -> {
                if (size == 1) {
                    return sqlBuilder.apply(selectPropNames);
                } else {
                    if (N.isNullOrEmpty(selectPropNames)) {
                        return PSC.selectFrom(referencedEntityClass).where(CF.or(N.repeat(cond, size))).sql();
                    } else {
                        return PSC.select(selectPropNames).from(referencedEntityClass).where(CF.or(N.repeat(cond, size))).sql();
                    }
                }
            };

            sqlBuilderSetterForEntitiesMap.put(PSC.class, Tuple.of(sqlBuilder2, paramSetter2));
        }

        {
            final String sql = PAC.selectFrom(referencedEntityClass).where(cond).sql();

            final Function<Collection<String>, String> sqlBuilder = selectPropNames -> {
                if (N.isNullOrEmpty(selectPropNames)) {
                    return sql;
                } else {
                    return PAC.select(selectPropNames).from(referencedEntityClass).where(cond).sql();
                }
            };

            sqlBuilderSetterForSingleEntityMap.put(PAC.class, Tuple.of(sqlBuilder, paramSetter));

            final BiFunction<Collection<String>, Integer, String> sqlBuilder2 = (selectPropNames, size) -> {
                if (size == 1) {
                    return sqlBuilder.apply(selectPropNames);
                } else {
                    if (N.isNullOrEmpty(selectPropNames)) {
                        return PAC.selectFrom(referencedEntityClass).where(CF.or(N.repeat(cond, size))).sql();
                    } else {
                        return PAC.select(selectPropNames).from(referencedEntityClass).where(CF.or(N.repeat(cond, size))).sql();
                    }
                }
            };

            sqlBuilderSetterForEntitiesMap.put(PAC.class, Tuple.of(sqlBuilder2, paramSetter2));
        }

        {
            final String sql = PLC.selectFrom(referencedEntityClass).where(cond).sql();

            final Function<Collection<String>, String> sqlBuilder = selectPropNames -> {
                if (N.isNullOrEmpty(selectPropNames)) {
                    return sql;
                } else {
                    return PLC.select(selectPropNames).from(referencedEntityClass).where(cond).sql();
                }
            };

            sqlBuilderSetterForSingleEntityMap.put(PLC.class, Tuple.of(sqlBuilder, paramSetter));

            final BiFunction<Collection<String>, Integer, String> sqlBuilder2 = (selectPropNames, size) -> {
                if (size == 1) {
                    return sqlBuilder.apply(selectPropNames);
                } else {
                    if (N.isNullOrEmpty(selectPropNames)) {
                        return PLC.selectFrom(referencedEntityClass).where(CF.or(N.repeat(cond, size))).sql();
                    } else {
                        return PLC.select(selectPropNames).from(referencedEntityClass).where(CF.or(N.repeat(cond, size))).sql();
                    }
                }
            };

            sqlBuilderSetterForEntitiesMap.put(PLC.class, Tuple.of(sqlBuilder2, paramSetter2));
        }

        Function<Object, Object> srcEntityKeyExtractorTmp = null;
        Function<Object, Object> referencedEntityKeyExtractorTmp = null;

        if (srcPropInfos.length == 1) {
            final PropInfo srcPropInfo = srcPropInfos[0];
            final PropInfo referencedPropInfo = referencedPropInfos[0];

            srcEntityKeyExtractorTmp = entity -> srcPropInfo.getPropValue(entity);
            referencedEntityKeyExtractorTmp = entity -> referencedPropInfo.getPropValue(entity);
        } else if (srcPropInfos.length == 2) {
            final PropInfo srcPropInfo_1 = srcPropInfos[0];
            final PropInfo srcPropInfo_2 = srcPropInfos[1];
            final PropInfo referencedPropInfo_1 = referencedPropInfos[0];
            final PropInfo referencedPropInfo_2 = referencedPropInfos[1];

            srcEntityKeyExtractorTmp = entity -> Tuple.of(srcPropInfo_1.getPropValue(entity), srcPropInfo_2.getPropValue(entity));
            referencedEntityKeyExtractorTmp = entity -> Tuple.of(referencedPropInfo_1.getPropValue(entity), referencedPropInfo_2.getPropValue(entity));
        } else if (srcPropInfos.length == 3) {
            final PropInfo srcPropInfo_1 = srcPropInfos[0];
            final PropInfo srcPropInfo_2 = srcPropInfos[1];
            final PropInfo srcPropInfo_3 = srcPropInfos[2];
            final PropInfo referencedPropInfo_1 = referencedPropInfos[0];
            final PropInfo referencedPropInfo_2 = referencedPropInfos[1];
            final PropInfo referencedPropInfo_3 = referencedPropInfos[2];

            srcEntityKeyExtractorTmp = entity -> Tuple.of(srcPropInfo_1.getPropValue(entity), srcPropInfo_2.getPropValue(entity),
                    srcPropInfo_3.getPropValue(entity));

            referencedEntityKeyExtractorTmp = entity -> Tuple.of(referencedPropInfo_1.getPropValue(entity), referencedPropInfo_2.getPropValue(entity),
                    referencedPropInfo_3.getPropValue(entity));
        } else {
            srcEntityKeyExtractorTmp = entity -> {
                final List<Object> keys = new ArrayList<>(srcPropInfos.length);

                for (PropInfo srcPropInfo : srcPropInfos) {
                    keys.add(srcPropInfo.getPropValue(entity));
                }

                return keys;
            };

            referencedEntityKeyExtractorTmp = entity -> {
                final List<Object> keys = new ArrayList<>(referencedPropInfos.length);

                for (PropInfo referencedPropInfo : referencedPropInfos) {
                    keys.add(referencedPropInfo.getPropValue(entity));
                }

                return keys;
            };
        }

        srcEntityKeyExtractor = srcEntityKeyExtractorTmp;
        referencedEntityKeyExtractor = referencedEntityKeyExtractorTmp;
    }

    public Tuple2<Function<Collection<String>, String>, BiParametersSetter<PreparedStatement, Object>> getSQLBuilderSetterForSingleEntity(
            final Class<? extends SQLBuilder> sbc) {
        final Tuple2<Function<Collection<String>, String>, BiParametersSetter<PreparedStatement, Object>> tp = sqlBuilderSetterForSingleEntityMap.get(sbc);

        if (tp == null) {
            throw new IllegalArgumentException("Not supported SQLBuilder class: " + ClassUtil.getCanonicalClassName(sbc));
        }

        return tp;
    }

    public Tuple2<BiFunction<Collection<String>, Integer, String>, BiParametersSetter<PreparedStatement, Collection<?>>> getSQLBuilderSetterForEntities(
            final Class<? extends SQLBuilder> sbc) {
        final Tuple2<BiFunction<Collection<String>, Integer, String>, BiParametersSetter<PreparedStatement, Collection<?>>> tp = sqlBuilderSetterForEntitiesMap
                .get(sbc);

        if (tp == null) {
            throw new IllegalArgumentException("Not supported SQLBuilder class: " + ClassUtil.getCanonicalClassName(sbc));
        }

        return tp;
    }

    public void setJoinPropEntities(final Collection<?> entities, final Collection<?> joinPropEntities) {
        final Map<Object, List<Object>> groupedPropEntities = Stream.of((Collection<Object>) joinPropEntities).groupTo(referencedEntityKeyExtractor);
        final boolean isDirtyMarker = DirtyMarkerUtil.isDirtyMarker(entityClass);
        final boolean isCollectionProp = joinPropInfo.type.isCollection();
        final boolean isListProp = joinPropInfo.clazz.isAssignableFrom(List.class);

        List<Object> propEntities = null;

        for (Object entity : entities) {
            propEntities = groupedPropEntities.get(srcEntityKeyExtractor.apply(entity));

            if (propEntities != null) {
                if (isCollectionProp) {
                    if (isListProp || joinPropInfo.clazz.isAssignableFrom(propEntities.getClass())) {
                        joinPropInfo.setPropValue(entity, propEntities);
                    } else {
                        final Collection<Object> c = (Collection<Object>) N.newInstance(joinPropInfo.clazz);
                        c.addAll(propEntities);
                        joinPropInfo.setPropValue(entity, c);
                    }
                } else {
                    joinPropInfo.setPropValue(entity, propEntities.get(0));
                }

                if (isDirtyMarker) {
                    DirtyMarkerUtil.markDirty((DirtyMarker) entity, joinPropInfo.name, false);
                }
            }
        }
    }

    private final static Map<Class<?>, Map<String, JoinInfo>> entityJoinInfoPool = new ConcurrentHashMap<>();

    public static Map<String, JoinInfo> getEntityJoinInfo(final Class<?> entityClass) {
        Map<String, JoinInfo> joinInfoMap = entityJoinInfoPool.get(entityClass);

        if (joinInfoMap == null) {
            final EntityInfo entityInfo = ParserUtil.getEntityInfo(entityClass);
            joinInfoMap = new LinkedHashMap<>();

            for (PropInfo propInfo : entityInfo.propInfoList) {
                if (!propInfo.isAnnotationPresent(JoinedBy.class)) {
                    continue;
                }

                joinInfoMap.put(propInfo.name, new JoinInfo(entityClass, propInfo.name));
            }

            entityJoinInfoPool.put(entityClass, joinInfoMap);
        }

        return joinInfoMap;
    }

    public static JoinInfo getPropJoinInfo(final Class<?> entityClass, final String joinEntityPropName) {
        final JoinInfo joinInfo = getEntityJoinInfo(entityClass).get(joinEntityPropName);

        if (joinInfo == null) {
            throw new IllegalArgumentException(
                    "No join property found by name '" + joinEntityPropName + "' in class: " + ClassUtil.getCanonicalClassName(entityClass));
        }

        return joinInfo;
    }

    private final static Map<Class<?>, Map<Class<?>, List<String>>> joinEntityPropNamesByTypePool = new ConcurrentHashMap<>();

    public static List<String> getJoinEntityPropNamesByType(final Class<?> entityClass, final Class<?> joinPropEntityClass) {
        Map<Class<?>, List<String>> joinEntityPropNamesByTypeMap = joinEntityPropNamesByTypePool.get(entityClass);

        if (joinEntityPropNamesByTypeMap == null) {
            joinEntityPropNamesByTypeMap = new HashMap<>();
            List<String> joinPropNames = null;

            for (JoinInfo joinInfo : getEntityJoinInfo(entityClass).values()) {
                joinPropNames = joinEntityPropNamesByTypeMap.get(joinInfo.referencedEntityClass);

                if (joinPropNames == null) {
                    joinPropNames = new ArrayList<>(1);
                    joinEntityPropNamesByTypeMap.put(joinInfo.referencedEntityClass, joinPropNames);

                }

                joinPropNames.add(joinInfo.joinPropInfo.name);
            }

            joinEntityPropNamesByTypePool.put(entityClass, joinEntityPropNamesByTypeMap);
        }

        return joinEntityPropNamesByTypeMap.getOrDefault(joinPropEntityClass, N.<String> emptyList());
    }
}
