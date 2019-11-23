package com.landawn.abacus.util;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.landawn.abacus.annotation.Column;
import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.condition.Condition;
import com.landawn.abacus.condition.ConditionFactory.CF;
import com.landawn.abacus.parser.ParserUtil;
import com.landawn.abacus.parser.ParserUtil.EntityInfo;
import com.landawn.abacus.parser.ParserUtil.PropInfo;
import com.landawn.abacus.type.Type;
import com.landawn.abacus.util.JdbcUtil.BiParametersSetter;
import com.landawn.abacus.util.SQLBuilder.PAC;
import com.landawn.abacus.util.SQLBuilder.PLC;
import com.landawn.abacus.util.SQLBuilder.PSC;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.function.Function;

final class JoinInfo {
    final Class<?> entityClass;
    final EntityInfo entityInfo;
    final PropInfo joinPropInfo;
    final PropInfo[] srcPropInfos;
    final PropInfo[] referencedPropInfos;
    final Type<?> referencedEntityType;
    final Class<?> referencedEntityClass;
    final EntityInfo referencedEntityInfo;

    final Map<Class<? extends SQLBuilder>, Tuple2<Function<Collection<String>, String>, BiParametersSetter<PreparedStatement, Object>>> sqlBuilderMap = new HashMap<>();

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
                : (stmt, entityParam) -> {
                    for (int i = 0, len = srcPropInfos.length; i < len; i++) {
                        srcPropInfos[i].dbType.set(stmt, i + 1, srcPropInfos[i].getPropValue(entityParam));
                    }
                };

        {
            final String sql = PSC.selectFrom(referencedEntityClass).where(cond).sql();

            sqlBuilderMap.put(PSC.class, Tuple.of(selectPropNames -> {
                if (N.isNullOrEmpty(selectPropNames)) {
                    return sql;
                } else {
                    return PSC.select(selectPropNames).from(referencedEntityClass).where(cond).sql();
                }
            }, paramSetter));
        }

        {
            final String sql = PAC.selectFrom(referencedEntityClass).where(cond).sql();

            sqlBuilderMap.put(PAC.class, Tuple.of(selectPropNames -> {
                if (N.isNullOrEmpty(selectPropNames)) {
                    return sql;
                } else {
                    return PAC.select(selectPropNames).from(referencedEntityClass).where(cond).sql();
                }
            }, paramSetter));
        }

        {
            final String sql = PLC.selectFrom(referencedEntityClass).where(cond).sql();

            sqlBuilderMap.put(PLC.class, Tuple.of(selectPropNames -> {
                if (N.isNullOrEmpty(selectPropNames)) {
                    return sql;
                } else {
                    return PLC.select(selectPropNames).from(referencedEntityClass).where(cond).sql();
                }
            }, paramSetter));
        }
    }

    private final static Map<Class<?>, Map<String, JoinInfo>> entityJoinInfoPool = new ConcurrentHashMap<>();

    public static Map<String, JoinInfo> getEntityJoinInfos(final Class<?> entityClass) {
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
        final JoinInfo joinInfo = getEntityJoinInfos(entityClass).get(joinEntityPropName);

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

            for (JoinInfo joinInfo : getEntityJoinInfos(entityClass).values()) {
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
