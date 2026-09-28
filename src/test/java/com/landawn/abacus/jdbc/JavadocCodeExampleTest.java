package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.TestBase;

/**
 * Guards the Javadoc usage examples in {@code src/main/java}: inside a {@code <pre>{@code ...}</pre>} block, Javadoc
 * renders inline tags such as {@code {@code null}} and HTML such as {@code <i>name</i>} literally, so copied examples
 * would not compile.
 */
public class JavadocCodeExampleTest extends TestBase {

    private static final Path MAIN_SOURCES = Paths.get("src", "main", "java");
    private static final Pattern CODE_BLOCK_START = Pattern.compile("<pre>\\{@code");
    private static final Pattern CODE_BLOCK_END = Pattern.compile("^\\s*\\*?\\s*\\}</pre>");
    private static final Pattern MARKUP_IN_CODE = Pattern.compile("\\{@(code|link|literal) |</?(i|b|em|strong|code)>");

    @Test
    public void testCodeExamplesContainNoJavadocMarkup() throws IOException {
        final List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            for (final Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                boolean inCodeBlock = false;

                for (int i = 0; i < lines.size(); i++) {
                    final String line = lines.get(i);

                    if (!inCodeBlock) {
                        inCodeBlock = CODE_BLOCK_START.matcher(line).find();
                    } else if (CODE_BLOCK_END.matcher(line).find()) {
                        inCodeBlock = false;
                    } else if (MARKUP_IN_CODE.matcher(line).find()) {
                        violations.add(file + ":" + (i + 1) + ": " + line.trim());
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(), () -> violations.size() + " Javadoc code example line(s) contain markup that renders literally:\n"
                + String.join("\n", violations));
    }
}
