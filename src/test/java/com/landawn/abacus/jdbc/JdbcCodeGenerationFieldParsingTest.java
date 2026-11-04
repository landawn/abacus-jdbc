package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.landawn.abacus.TestBase;

class JdbcCodeGenerationFieldParsingTest extends TestBase {

    @ParameterizedTest
    @MethodSource("constructorExclusionCases")
    void hasOnlyFieldsExcludedFromAllArgsConstructor_validatesDeclarations(final String source, final boolean expected) throws Exception {
        final Method method = JdbcCodeGenerationUtil.class.getDeclaredMethod("hasOnlyFieldsExcludedFromAllArgsConstructor", String.class);
        method.setAccessible(true);

        assertEquals(expected, (boolean) method.invoke(null, source));
    }

    private static Stream<Arguments> constructorExclusionCases() {
        return Stream.of(Arguments.of("\n \n", true), Arguments.of(";", false), Arguments.of("private static final ;", false),
                Arguments.of("private final String x;", false),
                // Comments are ignored, also when they span lines or hold an '@' (a javadoc tag); they used to reject the body.
                Arguments.of("private static final long version = 1L; /* note */", true),
                Arguments.of("/** Version. @since 1 */\nprivate static final long version = 1L;", true),
                Arguments.of("/**\n * Version.\n */\nprivate static final long version = 1L;", true),
                Arguments.of("/* header\r\n */ private static final long version = 1L; // trailing", true),
                Arguments.of("private static final long version = 1L; /* starts\n private String ghost;\n */", true),
                // A field after a comment or a text block still counts.
                Arguments.of("/* header\n */ private String note;", false), Arguments.of("/* a */ private String note; /* b */", false),
                Arguments.of("private static final String S = \"\"\"\n  x\n  \"\"\"; private String note;", false),
                // A constant initialized by a text block is checked through its ';', whatever the text block holds; its opening
                // line, which does not end with ';', rejected it (Lombok then generated two no-argument constructors).
                Arguments.of("private static final String SQL = \"\"\"\n  SELECT * FROM t\n  \"\"\";", true),
                Arguments.of("private final String note = \"\"\"\r\n  @Deprecated { private int ghost; } \\\"\"\"\r\n  \"\"\"; // c", true),
                Arguments.of("private static final String A = \"\"\"\r  a\r  \"\"\", B = \"b\"; private static final long V = 1L;", true),
                // Delimiters in comments and literals open no text block; a statement continued on the next line stays rejected.
                Arguments.of("// \"\"\"\nprivate static final String Q = \"\\\"\\\"\\\"\"; /* \"\"\" */", true),
                Arguments.of("private static final String S = \"\"\"\n  x\n  \"\"\"\n  .strip();", false),
                Arguments.of("/** doc */ @Deprecated private static final long version = 1L;", false));
    }

    // BUG FIX: of a line that begins inside a text block, the code after the end of the text block's statement was dropped
    // too, so a declaration there got no java.util import and no copy() assignment (code after a block comment was kept).
    @Test
    void getSourceCodeLines_keepsCodeAfterTheStatementOfATextBlock() throws Exception {
        assertEquals(List.of("private String t = \"\"\"", "  private List<String> tags;", "private int after;"),
                sourceCodeLines("private String t = \"\"\"\n  a; b \\\"\"\"\n  \"\"\".strip(); private List<String> tags;\nprivate int after;"));
        // A statement that continues on the next line returns nothing of the closing line; the next line is returned as code.
        assertEquals(List.of("x = \"\"\"", " .strip(); private int y;"), sourceCodeLines("x = \"\"\"\n a\n \"\"\"\n .strip(); private int y;"));
        // A text block opened in the code returned after the statement is followed as any other.
        assertEquals(List.of("a = \"\"\"", "  private String b = \"\"\"", "  private int c;"),
                sourceCodeLines("a = \"\"\"\n x\n \"\"\"; private String b = \"\"\"\n y\n \"\"\"; private int c;"));
    }

    // BUG FIX: only a ';' at the brace depth the closing line began at ended the text block's statement, so the code after a
    // statement that ends further out (closing an array initializer, an anonymous class or a lambda body) was dropped: the
    // declaration there got no java.util import, and the generated source did not compile.
    @Test
    void getSourceCodeLines_keepsCodeAfterATextBlockStatementEndingAtALowerDepth() throws Exception {
        final String arrayInitializer = "private static final String[] A = {\"\"\"\n x\n \"\"\"}; private List<String> tags;";
        assertEquals(List.of("private static final String[] A = {\"\"\"", "  private List<String> tags;"), sourceCodeLines(arrayInitializer));
        assertEquals(List.of("private static final String[] A = {\"\"\"", "  private List<String> tags;"), sourceCodeLines(arrayInitializer, true));

        // The code after the statement's own ';' is returned as well (members of the body need imports too); with
        // topLevelOnly, only the code after the top-level ';'.
        final String anonymousClass = "private Runnable r = new Runnable() {\n public void run() { String s = \"\"\"\n x\n \"\"\"; } }; private Set<String> s;";
        assertEquals(List.of("private Runnable r = new Runnable() {", " public void run() { String s = \"\"\"", "  } }; private Set<String> s;",
                "  private Set<String> s;"), sourceCodeLines(anonymousClass));
        assertEquals(List.of("private Runnable r = new Runnable() {", "  private Set<String> s;"), sourceCodeLines(anonymousClass, true));

        final String lambda = "private Runnable r = () -> { run(\"\"\"\n x\n \"\"\"); run(); }; private Map<String, String> m; int n;";
        assertEquals(List.of("private Runnable r = () -> { run(\"\"\"", "  run(); }; private Map<String, String> m; int n;",
                "  private Map<String, String> m; int n;"), sourceCodeLines(lambda));
        assertEquals(List.of("private Runnable r = () -> { run(\"\"\"", "  private Map<String, String> m; int n;"), sourceCodeLines(lambda, true));

        // With topLevelOnly, a text block's statement inside a nested type that stays open on the line yields nothing.
        assertEquals(List.of("class C {", "private int x;"), sourceCodeLines("class C {\n String s = \"\"\"\n x\n \"\"\"; int y;\n}\nprivate int x;", true));
    }

    // A '\r' alone ends a line, as in Java source; every other test uses "\r\n" or "\n".
    @Test
    void getSourceCodeLines_splitsAtCarriageReturnsAlone() throws Exception {
        assertEquals(List.of("// c", "private List<String> a;", "private int b;"), sourceCodeLines("// c\rprivate List<String> a;\rprivate int b;"));
        assertEquals(List.of("/* c", "  private List<String> a;"), sourceCodeLines("/* c\r */ private List<String> a;"));
        assertEquals(List.of("s = \"\"\"", "  private List<String> a;"), sourceCodeLines("s = \"\"\"\r x\r \"\"\"; private List<String> a;"));
    }

    private static List<String> sourceCodeLines(final String source) throws Exception {
        return sourceCodeLines(source, false);
    }

    @SuppressWarnings("unchecked")
    private static List<String> sourceCodeLines(final String source, final boolean topLevelOnly) throws Exception {
        final Method method = JdbcCodeGenerationUtil.class.getDeclaredMethod("getSourceCodeLines", String.class, boolean.class);
        method.setAccessible(true);
        return (List<String>) method.invoke(null, source, topLevelOnly);
    }

    @Test
    void splitJavaFieldDeclarations_ignoresSemicolonsInCommentsAndLiterals() throws Exception {
        final Method method = JdbcCodeGenerationUtil.class.getDeclaredMethod("splitJavaFieldDeclarations", String.class);
        method.setAccessible(true);
        final String source = "a = \"x;\\\"y\"; /* ; */ b = 'c';";

        @SuppressWarnings("unchecked")
        final List<String> declarations = (List<String>) method.invoke(null, source);

        assertEquals(2, declarations.size());
        assertEquals("a = \"x;\\\"y\"", declarations.get(0));
        assertEquals("/* ; */ b = 'c'", declarations.get(1));
    }

    @Test
    void isGenericTypeArgumentStart_rejectsMissingOrInvalidTypeNames() throws Exception {
        assertFalse(invokeGenericTypePredicate("<", 0));
        assertFalse(invokeGenericTypePredicate("1 < 2", 2));
    }

    @Test
    void isGenericTypeArgumentStart_handlesQualifiedNamesAndNestedArguments() throws Exception {
        assertFalse(invokeGenericTypePredicate(" a . b <C>", " a . b <C>".indexOf('<')));
        assertTrue(invokeGenericTypePredicate("Type<A<B>, B>  ::method", "Type<A<B>, B>  ::method".indexOf('<')));
    }

    private static boolean invokeGenericTypePredicate(final String source, final int index) throws Exception {
        final Method method = JdbcCodeGenerationUtil.class.getDeclaredMethod("isGenericTypeArgumentStart", String.class, int.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, source, index);
    }
}
