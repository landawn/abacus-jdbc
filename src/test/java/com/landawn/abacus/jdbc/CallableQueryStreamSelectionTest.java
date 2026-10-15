package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.sql.CallableStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.type.AbstractType;
import com.landawn.abacus.type.TypeFactory;

@Tag("2025")
class CallableQueryStreamSelectionTest {
    @Test
    void duplicateSelectionsPreserveCompleteValuesWithAnEagerDriver() throws Exception {
        final CallableStatement statement = mock(CallableStatement.class);
        final AtomicReference<byte[]> binaryValue = new AtomicReference<>();
        final AtomicReference<String> textValue = new AtomicReference<>();
        doAnswer(call -> {
            binaryValue.set(call.getArgument(1, InputStream.class).readAllBytes());
            return null;
        }).when(statement).setBinaryStream(eq("input"), any(InputStream.class));
        doAnswer(call -> {
            final StringWriter writer = new StringWriter();
            call.getArgument(1, Reader.class).transferTo(writer);
            textValue.set(writer.toString());
            return null;
        }).when(statement).setCharacterStream(eq("text"), any(Reader.class));

        final TrackingInputStream input = new TrackingInputStream(new byte[] { 1, 2, 3 });
        final TrackingReader text = new TrackingReader("a\u03b2c");
        final StreamBean bean = new StreamBean(input, text);
        try (CallableQuery query = new CallableQuery(statement)) {
            assertSame(query, query.setParameters(bean, List.of("input", "text", "input", "text", "input")));
            assertArrayEquals(new byte[] { 1, 2, 3 }, binaryValue.get());
            assertEquals("a\u03b2c", textValue.get());
        }
        assertFalse(input.closed);
        assertFalse(text.closed);
    }

    @Test
    void duplicateSelectionsKeepDeferredBindingsLazyAndCallerOwned() throws Exception {
        final CallableStatement statement = mock(CallableStatement.class);
        final TrackingInputStream input = new TrackingInputStream(new byte[] { 4, 5 });
        final TrackingReader text = new TrackingReader("deferred");
        final StreamBean bean = new StreamBean(input, text);
        try (CallableQuery query = new CallableQuery(statement)) {
            query.setParameters(bean, List.of("input", "input", "text", "text"));
            verify(statement).setBinaryStream("input", input);
            verify(statement).setCharacterStream("text", text);
            assertEquals(2, input.available());
            assertEquals('d', text.read());
        }
        assertFalse(input.closed);
        assertFalse(text.closed);
    }

    @Test
    void duplicateSelectionDoesNotOpenUnusedStreamsFromAGetter() throws Exception {
        final CallableStatement statement = mock(CallableStatement.class);
        final OpeningStreamBean bean = new OpeningStreamBean();
        try (CallableQuery query = new CallableQuery(statement)) {
            query.setParameters(bean, List.of("input", "input", "input"));
            assertEquals(1, bean.getterCalls);
            verify(statement).setBinaryStream("input", bean.openedInput);
            assertEquals(1, bean.openedInput.available());
        }
        assertFalse(bean.openedInput.closed);
    }

    @Test
    void separateBindingCallsCanReplaceAStreamValue() throws Exception {
        final CallableStatement statement = mock(CallableStatement.class);
        final OpeningStreamBean bean = new OpeningStreamBean();
        try (CallableQuery query = new CallableQuery(statement)) {
            query.setParameters(bean, List.of("input", "input"));
            final InputStream first = bean.openedInput;
            query.setParameters(bean, List.of("input", "input"));
            assertEquals(2, bean.getterCalls);
            verify(statement).setBinaryStream("input", first);
            verify(statement).setBinaryStream("input", bean.openedInput);
        }
    }

    @Test
    void duplicateScalarSelectionsStillReadAndBindEveryValueAlongsideStreams() throws Exception {
        final CallableStatement statement = mock(CallableStatement.class);
        final TrackingInputStream input = new TrackingInputStream(new byte[] { 1, 2 });
        final ScalarAndStreamBean bean = new ScalarAndStreamBean();
        bean.setInput(input);

        try (CallableQuery query = new CallableQuery(statement)) {
            query.setParameters(bean, List.of("input", "sequence", "sequence", "input", "sequence"));
            assertEquals(3, bean.scalarGetterCalls);
            verify(statement).setString("sequence", "value-1");
            verify(statement).setString("sequence", "value-2");
            verify(statement).setString("sequence", "value-3");
            verify(statement).setBinaryStream("input", input);
        }
        assertFalse(input.closed);
    }

    @Test
    void duplicateCustomTypeSelectionsStillInvokeEveryGetterAndSetter() throws Exception {
        final CallableStatement statement = mock(CallableStatement.class);
        final TrackingInputStream input = new TrackingInputStream(new byte[] { 4, 5, 6 });
        final CustomStreamBean bean = new CustomStreamBean();
        bean.setInput(input);

        try (CallableQuery query = new CallableQuery(statement)) {
            query.setParameters(bean, List.of("input", "input", "input"));
            assertEquals(3, bean.getterCalls);
            verify(statement, times(3)).setBinaryStream("input", input);
            assertEquals(3, input.available());
        }
        assertFalse(input.closed);
    }

    public static final class StreamBean {
        private InputStream input;
        private Reader text;

        public StreamBean() {
        }

        StreamBean(final InputStream input, final Reader text) {
            this.input = input;
            this.text = text;
        }

        public InputStream getInput() {
            return input;
        }

        public void setInput(final InputStream input) {
            this.input = input;
        }

        public Reader getText() {
            return text;
        }

        public void setText(final Reader text) {
            this.text = text;
        }
    }

    public static final class OpeningStreamBean {
        int getterCalls;
        TrackingInputStream openedInput;

        public InputStream getInput() {
            getterCalls++;
            openedInput = new TrackingInputStream(new byte[] { 7 });
            return openedInput;
        }

        public void setInput(final InputStream input) {
        }
    }

    public static final class ScalarAndStreamBean {
        private InputStream input;
        int scalarGetterCalls;

        public InputStream getInput() {
            return input;
        }

        public void setInput(final InputStream input) {
            this.input = input;
        }

        public String getSequence() {
            return "value-" + ++scalarGetterCalls;
        }

        public void setSequence(final String sequence) {
        }
    }

    public static final class CustomStreamBean {
        static {
            TypeFactory.registerType(new CustomStreamType());
        }

        // A distinct backing-field name forces this fixture through the observable property getter.
        private InputStream sourceInput;
        int getterCalls;

        @com.landawn.abacus.annotation.Type(name = "CallableQuerySelectionCustomStream")
        public InputStream getInput() {
            getterCalls++;
            return sourceInput;
        }

        public void setInput(final InputStream input) {
            sourceInput = input;
        }
    }

    private static final class CustomStreamType extends AbstractType<InputStream> {
        CustomStreamType() {
            super("CallableQuerySelectionCustomStream");
        }

        @Override
        public Class<InputStream> javaType() {
            return InputStream.class;
        }

        @Override
        public String stringOf(final InputStream input) {
            return input == null ? null : "custom-stream";
        }

        @Override
        public InputStream valueOf(final String str) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void set(final CallableStatement statement, final String name, final InputStream input) throws SQLException {
            statement.setBinaryStream(name, input);
        }
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        boolean closed;

        TrackingInputStream(final byte[] value) {
            super(value);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class TrackingReader extends StringReader {
        boolean closed;

        TrackingReader(final String value) {
            super(value);
        }

        @Override
        public void close() {
            closed = true;
            super.close();
        }
    }
}
