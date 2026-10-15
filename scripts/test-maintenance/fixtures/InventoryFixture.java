package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// Parsed only, outside Maven's test source directory. This fixture deliberately has no dependencies.
@Tag("base-test")
abstract class FixtureBase {
}

class InventoryFixtureTest extends FixtureBase {
    @Test
    @SuppressWarnings({ "unchecked", "rawtypes" })
    void annotationArrayDoesNotEndTheSignature() {
        assertEquals("{ // not a comment }", "{ // not a comment }");
    }

    @org.junit.jupiter.api.Test
    void assertionsMayLiveInHelpers() {
        checkValue();
    }

    @Test
    void aCommentIsNotAnAssertion() {
        // assertTrue(true);
        final String text = "assertEquals(1, 1)";
    }

    @ParameterizedTest
    @ValueSource(strings = { "a  b" })
    void firstInput(String value) {
        assertTrue(value.length() > 0);
    }

    @ParameterizedTest
    @ValueSource(strings = { "a b" })
    void differentInput(String value) {
        assertTrue(value.length() > 0);
    }

    @TestFactory
    Stream<DynamicTest> dynamicCases() {
        return Stream.of("value").map(this::caseFor);
    }

    private DynamicTest caseFor(String value) {
        return dynamicTest(value, () -> assertTrue(value.length() > 0));
    }

    private void checkValue() {
        assertEquals(2, 1 + 1);
    }

    @Nested
    class NestedCases {
        @Tag("integration")
        @Test
        void textBlocksDoNotIntroduceTestMethods() {
            final String source = """
                    @Test void imaginaryTest() { fail(); }
                    """;
            assertTrue(source.contains("imaginaryTest"));
        }
    }
}
