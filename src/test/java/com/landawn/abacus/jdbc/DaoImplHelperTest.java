package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.query.condition.Condition;
import com.landawn.abacus.query.condition.Criteria;
import com.landawn.abacus.query.Filters;
import com.landawn.abacus.query.condition.Limit;
import com.landawn.abacus.query.condition.SqlExpression;

class DaoImplHelperTest extends TestBase {

    @Test
    void declaredCollectionTypeReturnsAnAlreadyCompatibleInstance() throws Exception {
        final Method method = DaoImpl.class.getDeclaredMethod("toDeclaredCollectionType", Collection.class, Class.class);
        method.setAccessible(true);
        final Collection<String> values = new ArrayList<>();

        assertSame(values, method.invoke(null, values, List.class));
    }

    @Test
    void genericReturnTypesResolveParameterizedElementClasses() throws Exception {
        final Method second = DaoImpl.class.getDeclaredMethod("getSecondReturnEleType", Method.class);
        second.setAccessible(true);
        final Method third = DaoImpl.class.getDeclaredMethod("getFirstReturnEleEleEleType", Method.class);
        third.setAccessible(true);

        assertEquals(List.class, second.invoke(null, GenericReturns.class.getMethod("second")));
        assertEquals(List.class, third.invoke(null, GenericReturns.class.getMethod("third")));
    }

    @Test
    void handleLimitPreservesExistingLimitsAndLiteralLimits() throws Exception {
        final Method method = DaoImpl.class.getDeclaredMethod("handleLimit", Condition.class, int.class, boolean.class);
        method.setAccessible(true);
        final Limit limit = Filters.limit(5);
        final Condition criteria = Criteria.builder().limit(6).build();
        final SqlExpression expression = Filters.expr("id > 0 LIMIT 7");

        assertSame(limit, method.invoke(null, limit, 1, false));
        assertSame(criteria, method.invoke(null, criteria, 1, false));
        assertNotNull(method.invoke(null, expression, 1, false));
    }

    @Test
    void dollarQuoteTagRejectsPunctuationInTag() throws Exception {
        final Method method = DaoImpl.class.getDeclaredMethod("isDollarQuoteTag", String.class, int.class);
        method.setAccessible(true);

        assertFalse((boolean) method.invoke(null, "$bad-tag$", 0));
    }

    @Test
    void erasureSupportsGenericArraysWildcardsAndUnknownTypes() throws Exception {
        final Method method = DaoImpl.class.getDeclaredMethod("erasure", Type.class, Map.class);
        method.setAccessible(true);
        final GenericArrayType array = (GenericArrayType) GenericReturns.class.getMethod("array").getGenericReturnType();
        final ParameterizedType wildcardContainer = (ParameterizedType) GenericReturns.class.getMethod("wildcard").getGenericReturnType();
        final WildcardType wildcard = (WildcardType) wildcardContainer.getActualTypeArguments()[1];

        assertEquals(List[].class, method.invoke(null, array, Map.of()));
        assertEquals(Number.class, method.invoke(null, wildcard, Map.of()));
        assertEquals(Object.class, method.invoke(null, new Type() {
        }, Map.of()));
    }

    @Test
    void generatedIdRestoreFailureIsSuppressedOnPrimaryFailure() throws Exception {
        final Method method = DaoImpl.class.getDeclaredMethod("restoreGeneratedIds", Runnable.class, Throwable.class);
        method.setAccessible(true);
        final RuntimeException primary = new RuntimeException("write failed");
        final RuntimeException cleanup = new RuntimeException("restore failed");

        method.invoke(null, (Runnable) () -> {
            throw cleanup;
        }, primary);

        assertArrayEquals(new Throwable[] { cleanup }, primary.getSuppressed());
    }

    interface GenericReturns {
        Map<String, List<Long>> second();

        List<List<List<List<String>>>> third();

        List<String>[] array();

        Map<String, ? extends Number> wildcard();
    }
}
