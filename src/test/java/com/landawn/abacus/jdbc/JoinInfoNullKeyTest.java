package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.landawn.abacus.TestBase;
import com.landawn.abacus.annotation.JoinedBy;

import lombok.Data;

class JoinInfoNullKeyTest extends TestBase {
    @ParameterizedTest(name = "{0} components, null at position {1}")
    @MethodSource("nullableCompositeKeys")
    void setJoinPropEntities_nullCompositeComponent_matchesNoSource(final int componentCount, final int nullPosition) {
        final String joinProperty = "children" + componentCount;
        final JoinInfo joinInfo = new JoinInfo(Parent.class, "nullable_composite_parent", joinProperty, true);
        final Parent nullableParent = parent(nullPosition);
        final Parent completeParent = parent(-1);
        final Child nullableChild = child(nullPosition);
        final Child completeChild = child(-1);

        // Equal Java tuples/lists containing null must still obey SQL's equality semantics.
        joinInfo.setJoinPropEntities(List.of(nullableParent, completeParent), List.of(nullableChild, completeChild));

        assertTrue(children(nullableParent, componentCount).isEmpty());
        assertEquals(List.of(completeChild), children(completeParent, componentCount));
    }

    private static Stream<Arguments> nullableCompositeKeys() {
        final List<Arguments> cases = new ArrayList<>();
        for (int count = 2; count <= 4; count++) {
            for (int position = 0; position < count; position++) {
                cases.add(Arguments.of(count, position));
            }
        }
        return cases.stream();
    }

    private static Parent parent(final int nullPosition) {
        final Parent parent = new Parent();
        parent.setA(nullPosition == 0 ? null : "a");
        parent.setB(nullPosition == 1 ? null : "b");
        parent.setC(nullPosition == 2 ? null : "c");
        parent.setD(nullPosition == 3 ? null : "d");
        return parent;
    }

    private static Child child(final int nullPosition) {
        final Child child = new Child();
        child.setA(nullPosition == 0 ? null : "a");
        child.setB(nullPosition == 1 ? null : "b");
        child.setC(nullPosition == 2 ? null : "c");
        child.setD(nullPosition == 3 ? null : "d");
        return child;
    }

    private static List<Child> children(final Parent parent, final int count) {
        return count == 2 ? parent.getChildren2() : count == 3 ? parent.getChildren3() : parent.getChildren4();
    }

    @Data
    public static class Parent {
        private String a;
        private String b;
        private String c;
        private String d;

        @JoinedBy({ "a", "b" })
        private List<Child> children2;

        @JoinedBy({ "a", "b", "c" })
        private List<Child> children3;

        @JoinedBy({ "a", "b", "c", "d" })
        private List<Child> children4;
    }

    @Data
    public static class Child {
        private String a;
        private String b;
        private String c;
        private String d;
    }
}
