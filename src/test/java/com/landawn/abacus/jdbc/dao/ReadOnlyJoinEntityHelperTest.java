package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class ReadOnlyJoinEntityHelperTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(ReadOnlyJoinEntityHelper.class.isInterface());
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(2, ReadOnlyJoinEntityHelper.class.getTypeParameters().length);
    }

    @Test
    public void testExtendsReadableButNotDeletable() {
        // The read-only join helper mixes in only the read side; the delete side is absent,
        // so it is no longer assignable to the full JoinEntityHelper / DeletableJoinEntityHelper.
        assertTrue(ReadableJoinEntityHelper.class.isAssignableFrom(ReadOnlyJoinEntityHelper.class));
        assertFalse(DeletableJoinEntityHelper.class.isAssignableFrom(ReadOnlyJoinEntityHelper.class));
        assertFalse(JoinEntityHelper.class.isAssignableFrom(ReadOnlyJoinEntityHelper.class));
    }

    @Test
    public void testDeleteMethodsAbsentLoadMethodsPresent() {
        // deleteJoinEntities / deleteAllJoinEntities are no longer part of the type: calling them is
        // a compile error rather than a runtime UnsupportedOperationException (capability removed, not overridden).
        assertTrue(Arrays.stream(ReadOnlyJoinEntityHelper.class.getMethods())
                .noneMatch(m -> m.getName().equals("deleteJoinEntities") || m.getName().equals("deleteAllJoinEntities")));
        // Load operations remain available.
        assertTrue(Arrays.stream(ReadOnlyJoinEntityHelper.class.getMethods()).anyMatch(m -> m.getName().equals("loadJoinEntities")));
        assertTrue(Arrays.stream(ReadOnlyJoinEntityHelper.class.getMethods()).anyMatch(m -> m.getName().equals("loadAllJoinEntities")));
    }
}
