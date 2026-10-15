package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class JdbcParameterBindingTest extends JdbcTestSupport {

    @Test
    public void testBiParametersSetterAccept() throws SQLException {
        Jdbc.BiParametersSetter<PreparedStatement, String> setter = (ps, param) -> ps.setString(1, param);
        assertDoesNotThrow(() -> setter.accept(mockPreparedStatement, "test"));
        verify(mockPreparedStatement).setString(1, "test");
    }

    // BiParametersSetter Tests
    @Test
    public void testBiParametersSetterDoNothing() throws SQLException {
        Jdbc.BiParametersSetter<Object, Object> setter = Jdbc.BiParametersSetter.DO_NOTHING;
        assertDoesNotThrow(() -> setter.accept(mockPreparedStatement, "param"));
        verifyNoInteractions(mockPreparedStatement);
    }

    @Test
    public void testBiParametersSetterCreateForArray() throws SQLException {
        List<String> fields = Arrays.asList("name", "age");
        Jdbc.BiParametersSetter<PreparedStatement, Object[]> setter = Jdbc.BiParametersSetter.forArray(fields, TestEntity.class);
        assertNotNull(setter);

        Object[] params = new Object[] { "John", 25 };
        assertDoesNotThrow(() -> setter.accept(mockPreparedStatement, params));

        verify(mockPreparedStatement, times(1)).setString(anyInt(), any());
        verify(mockPreparedStatement, times(1)).setInt(anyInt(), anyInt());
    }

    @Test
    public void testBiParametersSetterSnapshotsFieldNames() {
        final List<String> arrayFields = new ArrayList<>(Arrays.asList("name", "age"));
        final List<String> listFields = new ArrayList<>(Arrays.asList("name", "age"));
        final Jdbc.BiParametersSetter<PreparedStatement, Object[]> arraySetter = Jdbc.BiParametersSetter.forArray(arrayFields, TestEntity.class);
        final Jdbc.BiParametersSetter<PreparedStatement, List<Object>> listSetter = Jdbc.BiParametersSetter.forList(listFields, TestEntity.class);
        arrayFields.set(0, "notAProperty");
        listFields.set(0, "notAProperty");

        assertDoesNotThrow(() -> arraySetter.accept(mockPreparedStatement, new Object[] { "John", 25 }));
        assertDoesNotThrow(() -> listSetter.accept(mockPreparedStatement, Arrays.asList("Jane", 30)));
    }

    @Test
    public void testBiParametersSetterCreateForArray_InvalidField() {
        List<String> fields = Arrays.asList("nonexistentField");
        Jdbc.BiParametersSetter<PreparedStatement, Object[]> setter = Jdbc.BiParametersSetter.forArray(fields, TestEntity.class);
        assertNotNull(setter);
        assertThrows(IllegalArgumentException.class, () -> setter.accept(mockPreparedStatement, new Object[] { "value" }));
    }

    @Test
    public void testBiParametersSetterForArrayRejectsMismatchedParameterCount() {
        final Jdbc.BiParametersSetter<PreparedStatement, Object[]> setter = Jdbc.BiParametersSetter.forArray(Arrays.asList("name", "age"), TestEntity.class);

        assertThrows(IllegalArgumentException.class, () -> setter.accept(mockPreparedStatement, new Object[] { "John" }));
        assertThrows(IllegalArgumentException.class, () -> setter.accept(mockPreparedStatement, new Object[] { "John", 25, true }));
        assertThrows(IllegalArgumentException.class, () -> setter.accept(mockPreparedStatement, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testBiParametersSetterCreateForList() throws SQLException {
        List<String> fields = Arrays.asList("name", "age");
        Jdbc.BiParametersSetter<PreparedStatement, List<Object>> setter = Jdbc.BiParametersSetter.forList(fields, TestEntity.class);
        assertNotNull(setter);

        List<Object> params = Arrays.asList("John", 25);
        setter.accept(mockPreparedStatement, params);

        verify(mockPreparedStatement, times(1)).setString(anyInt(), any());
        verify(mockPreparedStatement, times(1)).setInt(anyInt(), anyInt());
    }

    @Test
    public void testBiParametersSetterCreateForList_InvalidField() {
        List<String> fields = Arrays.asList("nonexistentField");
        Jdbc.BiParametersSetter<PreparedStatement, List<Object>> setter = Jdbc.BiParametersSetter.forList(fields, TestEntity.class);
        assertNotNull(setter);
        assertThrows(IllegalArgumentException.class, () -> setter.accept(mockPreparedStatement, Arrays.asList("value")));
    }

    @Test
    public void testBiParametersSetterForListRejectsMismatchedParameterCount() {
        final Jdbc.BiParametersSetter<PreparedStatement, List<Object>> setter = Jdbc.BiParametersSetter.forList(Arrays.asList("name", "age"), TestEntity.class);

        assertThrows(IllegalArgumentException.class, () -> setter.accept(mockPreparedStatement, Arrays.asList("John")));
        assertThrows(IllegalArgumentException.class, () -> setter.accept(mockPreparedStatement, Arrays.asList("John", 25, true)));
        assertThrows(IllegalArgumentException.class, () -> setter.accept(mockPreparedStatement, null));
    }

    @Test
    public void testParametersSetterAccept() throws SQLException {
        Jdbc.ParametersSetter<PreparedStatement> setter = ps -> ps.setString(1, "test");
        assertDoesNotThrow(() -> setter.accept(mockPreparedStatement));
        verify(mockPreparedStatement).setString(1, "test");
    }

    // ParametersSetter Tests
    @Test
    public void testParametersSetterDoNothing() throws SQLException {
        Jdbc.ParametersSetter<Object> setter = Jdbc.ParametersSetter.DO_NOTHING;
        assertDoesNotThrow(() -> setter.accept(mockPreparedStatement));
        verifyNoInteractions(mockPreparedStatement);
    }

    @Test
    public void testTriParametersSetterAccept() throws SQLException {
        Jdbc.TriParametersSetter<PreparedStatement, String> setter = (parsedSql, ps, param) -> ps.setString(1, param);
        assertDoesNotThrow(() -> setter.accept(mockParsedSql, mockPreparedStatement, "test"));
        verify(mockPreparedStatement).setString(1, "test");
    }

    // TriParametersSetter Tests
    @Test
    public void testTriParametersSetterDoNothing() throws SQLException {
        Jdbc.TriParametersSetter<Object, Object> setter = Jdbc.TriParametersSetter.DO_NOTHING;
        assertDoesNotThrow(() -> setter.accept(mockParsedSql, mockPreparedStatement, "param"));
        verifyNoInteractions(mockPreparedStatement);
    }
}
