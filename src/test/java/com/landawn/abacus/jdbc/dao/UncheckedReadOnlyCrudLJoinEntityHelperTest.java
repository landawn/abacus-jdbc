package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedReadOnlyCrudLJoinEntityHelperTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedReadOnlyCrudLJoinEntityHelper.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedReadOnlyCrudJoinEntityHelper() {
        assertTrue(UncheckedReadOnlyCrudJoinEntityHelper.class.isAssignableFrom(UncheckedReadOnlyCrudLJoinEntityHelper.class));
        assertTrue(UncheckedReadOnlyJoinEntityHelper.class.isAssignableFrom(UncheckedReadOnlyCrudLJoinEntityHelper.class));
    }

    @Test
    public void testHasReadOpsButNotDeleteOps() {
        // Read-only: it carries the unchecked read capabilities but not the delete side.
        assertTrue(UncheckedCrudJoinEntityReadOps.class.isAssignableFrom(UncheckedReadOnlyCrudLJoinEntityHelper.class));
        assertFalse(UncheckedJoinEntityDeleteOps.class.isAssignableFrom(UncheckedReadOnlyCrudLJoinEntityHelper.class));
        assertFalse(UncheckedCrudLJoinEntityHelper.class.isAssignableFrom(UncheckedReadOnlyCrudLJoinEntityHelper.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(2, UncheckedReadOnlyCrudLJoinEntityHelper.class.getTypeParameters().length);
    }

    @Test
    public void testDeleteMethodsAbsent() {
        // delete-join operations are removed from the read-only type (compile-time prevention).
        assertTrue(Arrays.stream(UncheckedReadOnlyCrudLJoinEntityHelper.class.getMethods())
                .noneMatch(m -> m.getName().equals("deleteJoinEntities") || m.getName().equals("deleteAllJoinEntities")));
    }
}
