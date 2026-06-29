package com.landawn.abacus.jdbc.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

public class UncheckedReadOnlyCrudJoinEntityHelperLTest extends TestBase {

    @Test
    public void testIsInterface() {
        assertTrue(UncheckedReadOnlyCrudJoinEntityHelperL.class.isInterface());
    }

    @Test
    public void testExtendsUncheckedReadOnlyCrudJoinEntityHelper() {
        assertTrue(UncheckedReadOnlyCrudJoinEntityHelper.class.isAssignableFrom(UncheckedReadOnlyCrudJoinEntityHelperL.class));
        assertTrue(UncheckedReadOnlyJoinEntityHelper.class.isAssignableFrom(UncheckedReadOnlyCrudJoinEntityHelperL.class));
    }

    @Test
    public void testIsReadableButNotDeletable() {
        // Read-only: it carries the unchecked read capabilities but not the delete side.
        assertTrue(UncheckedReadableCrudJoinEntityHelper.class.isAssignableFrom(UncheckedReadOnlyCrudJoinEntityHelperL.class));
        assertFalse(UncheckedDeletableJoinEntityHelper.class.isAssignableFrom(UncheckedReadOnlyCrudJoinEntityHelperL.class));
        assertFalse(UncheckedCrudJoinEntityHelperL.class.isAssignableFrom(UncheckedReadOnlyCrudJoinEntityHelperL.class));
    }

    @Test
    public void testTypeParameterCount() {
        assertEquals(2, UncheckedReadOnlyCrudJoinEntityHelperL.class.getTypeParameters().length);
    }

    @Test
    public void testDeleteMethodsAbsent() {
        // delete-join operations are removed from the read-only type (compile-time prevention).
        assertTrue(Arrays.stream(UncheckedReadOnlyCrudJoinEntityHelperL.class.getMethods())
                .noneMatch(m -> m.getName().equals("deleteJoinEntities") || m.getName().equals("deleteAllJoinEntities")));
    }
}
