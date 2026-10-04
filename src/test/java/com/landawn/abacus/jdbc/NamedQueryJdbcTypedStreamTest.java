package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.JDBCType;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("2025")
class NamedQueryJdbcTypedStreamTest {

    @Test
    void explicitJdbcTypesReplayByteStreams() {
        assertAll(IntStream.range(0, 4).mapToObj(variant -> () -> assertBinding(variant, true)));
    }

    @Test
    void explicitJdbcTypesReplayReaders() {
        assertAll(IntStream.range(0, 4).mapToObj(variant -> () -> assertBinding(variant, false)));
    }

    private static void assertBinding(final int variant, final boolean binary) throws Exception {
        final boolean hasLength = variant % 2 == 1;
        final ByteArrayInputStream input = new ByteArrayInputStream((hasLength ? "abcTAIL" : "abc").getBytes(StandardCharsets.US_ASCII));
        final StringReader reader = new StringReader(hasLength ? "a\u03b2cTAIL" : "a\u03b2c");
        final Object value = binary ? input : reader;
        final JDBCType sqlType = binary ? JDBCType.BLOB : JDBCType.CLOB;

        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:jdbc_typed_stream");
                NamedQuery query = JdbcUtil.prepareNamedQuery(connection, "select :payload, :payload")) {
            switch (variant) {
                case 0 -> query.setObject("payload", value, sqlType.getVendorTypeNumber());
                case 1 -> query.setObject("payload", value, sqlType.getVendorTypeNumber(), 3);
                case 2 -> query.setObject("payload", value, sqlType);
                default -> query.setObject("payload", value, sqlType, 3);
            }
            query.query((Jdbc.ResultExtractor<Void>) result -> {
                result.next();
                for (int column = 1; column <= 2; column++) {
                    if (binary) {
                        assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII), result.getBytes(column));
                    } else {
                        assertEquals("a\u03b2c", result.getString(column));
                    }
                }
                return null;
            });
        }
        if (binary) {
            assertEquals(hasLength ? 4 : 0, input.available());
        } else {
            assertEquals(hasLength ? 'T' : -1, reader.read());
        }
    }
}
