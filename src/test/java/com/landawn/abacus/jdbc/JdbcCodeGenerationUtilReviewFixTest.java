package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class JdbcCodeGenerationUtilReviewFixTest extends JdbcCodeGenerationUtilTestSupport {

    // Regression for the fix in JdbcCodeGenerationUtil.generateEntityClass(String, ResultSet, EntityCodeConfig):
    // ResultSetMetaData.getColumnLabel(i) was invoked twice per column (once for the emptiness check, once for
    // the value); drivers may round-trip per metadata call. The label must now be read exactly once per column.
    // This test fails on the pre-fix code (times(2)) and passes after the fix.
    @Test
    public void testGenerateEntityClassReadsColumnLabelOncePerColumn() throws Exception {
        final String code = JdbcCodeGenerationUtil.generateEntityClass("order_history", resultSet, null);

        verify(resultSetMetaData, times(1)).getColumnLabel(1);
        verify(resultSetMetaData, times(1)).getColumnLabel(2);
        verify(resultSetMetaData, times(1)).getColumnLabel(3);

        // Pin the generated source so the single-read refactor stays behavior-identical.
        assertTrue(code.contains("public class OrderHistory {"));
        assertTrue(code.contains("@Column(name = \"id\")"));
        assertTrue(code.contains("@Column(name = \"created_at\")"));
        assertTrue(code.contains("@Column(name = \"status\")"));
        assertTrue(code.contains("private Object id;"));
        assertTrue(code.contains("private Object createdAt;"));
        assertTrue(code.contains("private Object status;"));
    }
}
