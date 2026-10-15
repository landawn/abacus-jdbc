package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.JDBCType;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.landawn.abacus.TestBase;

@Tag("integration")
@Tag("2025")
public class NamedQueryJdbcTypedStreamIntegrationTest extends TestBase {

    @ParameterizedTest(name = "{0}, binary={1}")
    @CsvSource({ "SQL_TYPE_NUMBER, true", "SQL_TYPE_NUMBER_WITH_LENGTH, true", "SQL_TYPE, true", "SQL_TYPE_WITH_LENGTH, true", "SQL_TYPE_NUMBER, false",
            "SQL_TYPE_NUMBER_WITH_LENGTH, false", "SQL_TYPE, false", "SQL_TYPE_WITH_LENGTH, false" })
    void setObject_repeatedStream_replaysEveryOccurrence(final Binding binding, final boolean binary) throws Exception {
        final boolean hasLength = binding == Binding.SQL_TYPE_NUMBER_WITH_LENGTH || binding == Binding.SQL_TYPE_WITH_LENGTH;
        final ByteArrayInputStream input = new ByteArrayInputStream((hasLength ? "abcTAIL" : "abc").getBytes(StandardCharsets.US_ASCII));
        final StringReader reader = new StringReader(hasLength ? "a\u03b2cTAIL" : "a\u03b2c");
        final Object value = binary ? input : reader;
        final JDBCType sqlType = binary ? JDBCType.BLOB : JDBCType.CLOB;

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:jdbc_typed_stream");
             NamedQuery query = JdbcUtil.prepareNamedQuery(connection, "select :payload, :payload")) {
            switch (binding) {
                case SQL_TYPE_NUMBER -> query.setObject("payload", value, sqlType.getVendorTypeNumber());
                case SQL_TYPE_NUMBER_WITH_LENGTH -> query.setObject("payload", value, sqlType.getVendorTypeNumber(), 3);
                case SQL_TYPE -> query.setObject("payload", value, sqlType);
                case SQL_TYPE_WITH_LENGTH -> query.setObject("payload", value, sqlType, 3);
            }
            query.query((Jdbc.ResultExtractor<Void>) result -> {
                assertTrue(result.next());
                for (int column = 1; column <= 2; column++) {
                    if (binary) {
                        assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII), result.getBytes(column));
                    } else {
                        assertEquals("a\u03b2c", result.getString(column));
                    }
                }
                assertFalse(result.next());
                return null;
            });
        }
        if (binary) {
            assertEquals(hasLength ? 4 : 0, input.available());
        } else {
            assertEquals(hasLength ? 'T' : -1, reader.read());
        }
    }

    private enum Binding {
        SQL_TYPE_NUMBER, SQL_TYPE_NUMBER_WITH_LENGTH, SQL_TYPE, SQL_TYPE_WITH_LENGTH
    }
}
