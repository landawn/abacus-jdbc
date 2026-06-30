package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedReadOnlyJoinEntityHelperTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedReadOnlyJoinEntityHelper.class.isInterface());
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(2, UncheckedReadOnlyJoinEntityHelper.class.getTypeParameters().length);
    }

    @Test
    public void testExtendsReadOnlyJoinEntityHelper() {
        assertTrue(ReadOnlyJoinEntityHelper.class.isAssignableFrom(UncheckedReadOnlyJoinEntityHelper.class));
    }

    @Test
    public void testExtendsUncheckedReadOpsButNotDeleteOps() {
        // The read-only unchecked join helper mixes in only the read side; the delete side is absent.
        assertTrue(UncheckedJoinEntityReadOps.class.isAssignableFrom(UncheckedReadOnlyJoinEntityHelper.class));
        assertTrue(JoinEntityReadOps.class.isAssignableFrom(UncheckedReadOnlyJoinEntityHelper.class));
        assertFalse(UncheckedJoinEntityDeleteOps.class.isAssignableFrom(UncheckedReadOnlyJoinEntityHelper.class));
        assertFalse(JoinEntityDeleteOps.class.isAssignableFrom(UncheckedReadOnlyJoinEntityHelper.class));
        assertFalse(UncheckedJoinEntityHelper.class.isAssignableFrom(UncheckedReadOnlyJoinEntityHelper.class));
    }

    @Test
    public void testDeleteMethodsAbsentLoadMethodsPresent() {
        // Delete-join operations are removed from the type (compile-time prevention), load operations remain.
        assertTrue(Arrays.stream(UncheckedReadOnlyJoinEntityHelper.class.getMethods())
                .noneMatch(m -> m.getName().equals("deleteJoinEntities") || m.getName().equals("deleteAllJoinEntities")));
        assertTrue(Arrays.stream(UncheckedReadOnlyJoinEntityHelper.class.getMethods()).anyMatch(m -> m.getName().equals("loadJoinEntities")));
        assertTrue(Arrays.stream(UncheckedReadOnlyJoinEntityHelper.class.getMethods()).anyMatch(m -> m.getName().equals("loadAllJoinEntities")));
    }
}
