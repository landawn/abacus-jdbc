package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.landawn.abacus.TestBase;

class JdbcCodeGenerationFieldParsingTest extends TestBase {

    @ParameterizedTest
    @MethodSource("constructorExclusionCases")
    void hasOnlyFieldsExcludedFromAllArgsConstructor_validatesDeclarations(final String source, final boolean expected) throws Exception {
        final Method method = JdbcCodeGenerationUtil.class.getDeclaredMethod("hasOnlyFieldsExcludedFromAllArgsConstructor", String.class);
        method.setAccessible(true);

        assertEquals(expected, (boolean) method.invoke(null, source));
    }

    private static Stream<Arguments> constructorExclusionCases() {
        return Stream.of(Arguments.of("\n \n", true), Arguments.of(";", false), Arguments.of("private static final ;", false),
                Arguments.of("private final String x;", false));
    }

    @Test
    void splitJavaFieldDeclarations_ignoresSemicolonsInCommentsAndLiterals() throws Exception {
        final Method method = JdbcCodeGenerationUtil.class.getDeclaredMethod("splitJavaFieldDeclarations", String.class);
        method.setAccessible(true);
        final String source = "a = \"x;\\\"y\"; /* ; */ b = 'c';";

        @SuppressWarnings("unchecked")
        final List<String> declarations = (List<String>) method.invoke(null, source);

        assertEquals(2, declarations.size());
        assertEquals("a = \"x;\\\"y\"", declarations.get(0));
        assertEquals("/* ; */ b = 'c'", declarations.get(1));
    }

    @Test
    void isGenericTypeArgumentStart_rejectsMissingOrInvalidTypeNames() throws Exception {
        assertFalse(invokeGenericTypePredicate("<", 0));
        assertFalse(invokeGenericTypePredicate("1 < 2", 2));
    }

    @Test
    void isGenericTypeArgumentStart_handlesQualifiedNamesAndNestedArguments() throws Exception {
        assertFalse(invokeGenericTypePredicate(" a . b <C>", " a . b <C>".indexOf('<')));
        assertTrue(invokeGenericTypePredicate("Type<A<B>, B>  ::method", "Type<A<B>, B>  ::method".indexOf('<')));
    }

    private static boolean invokeGenericTypePredicate(final String source, final int index) throws Exception {
        final Method method = JdbcCodeGenerationUtil.class.getDeclaredMethod("isGenericTypeArgumentStart", String.class, int.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, source, index);
    }
}
