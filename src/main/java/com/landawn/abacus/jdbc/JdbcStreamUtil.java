/*
 * Copyright (c) 2026, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.landawn.abacus.jdbc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.sql.SQLException;
import java.util.Set;
import java.util.function.Supplier;

import com.landawn.abacus.type.AsciiStreamType;
import com.landawn.abacus.type.BinaryStreamType;
import com.landawn.abacus.type.BlobInputStreamType;
import com.landawn.abacus.type.CharacterStreamType;
import com.landawn.abacus.type.ClobAsciiStreamType;
import com.landawn.abacus.type.ClobReaderType;
import com.landawn.abacus.type.InputStreamType;
import com.landawn.abacus.type.NCharacterStreamType;
import com.landawn.abacus.type.NClobReaderType;
import com.landawn.abacus.type.ReaderType;
import com.landawn.abacus.type.Type;

/** Independent cursors for JDBC bindings that repeat a caller-owned stream or reader. */
final class JdbcStreamUtil {
    // Exact classes preserve custom Type subclasses, whose setters may require the original value.
    private static final Set<Class<?>> STREAM_TYPES = Set.of(InputStreamType.class, BlobInputStreamType.class, AsciiStreamType.class, BinaryStreamType.class,
            ClobAsciiStreamType.class);
    private static final Set<Class<?>> READER_TYPES = Set.of(ReaderType.class, ClobReaderType.class, NClobReaderType.class, CharacterStreamType.class,
            NCharacterStreamType.class);

    private JdbcStreamUtil() {
        // Utility class.
    }

    /** Returns whether the selected built-in type consumes this stream or reader. */
    static boolean usesBuiltInBinding(final Object value, final Type<?> type) {
        return type != null && (value instanceof InputStream && STREAM_TYPES.contains(type.getClass())
                || value instanceof Reader && READER_TYPES.contains(type.getClass()));
    }

    /**
     * Buffers up to {@code length} bytes or characters without closing the original resource.
     * Drivers may read during binding or execution, so every call to the supplier returns a fresh cursor.
     * The caller must validate length and closed state before calling this method.
     *
     * @param value the non-null stream or reader to buffer
     * @param <T> the stream or reader type accepted by the selected built-in binding
     * @param length the non-negative maximum number of bytes or characters to read
     * @return a supplier of independent cursors over the buffered content
     * @throws SQLException if reading the caller's resource fails
     */
    @SuppressWarnings("unchecked")
    static <T> Supplier<T> buffer(final T value, final long length) throws SQLException {
        try {
            long remaining = length;
            if (value instanceof InputStream input) {
                final ByteArrayOutputStream output = new ByteArrayOutputStream();
                final byte[] buffer = new byte[8192];
                while (remaining > 0) {
                    final int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) {
                        break;
                    } else if (count == 0) {
                        final int next = input.read();
                        if (next < 0) {
                            break;
                        }
                        output.write(next);
                        remaining--;
                    } else {
                        output.write(buffer, 0, count);
                        remaining -= count;
                    }
                }
                final byte[] bytes = output.toByteArray();
                return () -> (T) new ByteArrayInputStream(bytes);
            }

            final Reader reader = (Reader) value;
            final StringBuilder output = new StringBuilder();
            final char[] buffer = new char[8192];
            while (remaining > 0) {
                final int count = reader.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) {
                    break;
                } else if (count == 0) {
                    final int next = reader.read();
                    if (next < 0) {
                        break;
                    }
                    output.append((char) next);
                    remaining--;
                } else {
                    output.append(buffer, 0, count);
                    remaining -= count;
                }
            }
            final String text = output.toString();
            return () -> (T) new StringReader(text);
        } catch (final IOException e) {
            throw new SQLException("Failed to buffer stream or reader for repeated JDBC parameter", e);
        }
    }
}
