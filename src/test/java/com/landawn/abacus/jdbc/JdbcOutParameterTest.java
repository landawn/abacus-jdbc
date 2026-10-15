package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class JdbcOutParameterTest extends JdbcTestSupport {

    @Test
    public void testOutParamOf() {
        Jdbc.OutParam param = Jdbc.OutParam.of(1, Types.VARCHAR);

        assertEquals(1, param.parameterIndex());
        assertEquals(Types.VARCHAR, param.sqlType());
    }

    // Regression: OutParam.of(0, sqlType) used to be silently accepted. Downstream
    // JdbcUtil.getOutParameters then took the name-based fallback with a null name, producing
    // an opaque driver error. The fix rejects index <= 0 with a clear IllegalArgumentException.
    @Tag("2025")
    @Test
    public void testOutParamOf_ZeroIndex_ThrowsIAE() {
        assertThrows(IllegalArgumentException.class, () -> Jdbc.OutParam.of(0, java.sql.Types.INTEGER));
        assertThrows(IllegalArgumentException.class, () -> Jdbc.OutParam.of(-1, java.sql.Types.INTEGER));

        // Sanity: positive index still works.
        Jdbc.OutParam p = assertDoesNotThrow(() -> Jdbc.OutParam.of(1, java.sql.Types.INTEGER));
        assertEquals(1, p.parameterIndex());
        assertEquals(java.sql.Types.INTEGER, p.sqlType());
    }

    @Test
    public void testOutParamConstructors() {
        Jdbc.OutParam param2 = new Jdbc.OutParam(2, "name", Types.VARCHAR, "VARCHAR", 0);
        assertEquals(2, param2.parameterIndex());
        assertEquals("name", param2.parameterName());
        assertEquals(Types.VARCHAR, param2.sqlType());
    }

    // OutParamResult Tests
    @Test
    public void testOutParamResult() {
        List<Jdbc.OutParam> outParams = Arrays.asList(new Jdbc.OutParam(1, "result", Types.INTEGER, null, 0),
                new Jdbc.OutParam(2, "message", Types.VARCHAR, null, 0));

        Map<Object, Object> values = new HashMap<>();
        values.put(1, 100);
        values.put("message", "Success");

        Jdbc.OutParamResult result = new Jdbc.OutParamResult(outParams, values);

        assertEquals(100, (Integer) result.getOutParamValue(1));
        assertEquals("Success", result.getOutParamValue("message"));
        assertEquals(outParams, result.getOutParams());
        assertEquals(values, result.getOutParamValues());
    }

    @Test
    public void testOutParamResultSnapshotsMutableInputs() {
        final Jdbc.OutParam originalParam = new Jdbc.OutParam(1, "result", Types.INTEGER, null, 0);
        final List<Jdbc.OutParam> outParams = new ArrayList<>();
        outParams.add(originalParam);
        final Map<Object, Object> values = new HashMap<>();
        values.put(1, 100);

        final Jdbc.OutParamResult result = new Jdbc.OutParamResult(outParams, values);

        assertEquals(1, result.getOutParams().size());
        assertEquals(100, (Integer) result.getOutParamValue(1));
    }
}
