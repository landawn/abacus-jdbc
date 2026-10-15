import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import com.sun.source.tree.AssertTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

/** Read-only Java source inventory. Uses the JDK parser; no project compilation or dependencies are required. */
public class TestInventory {
    public static void main(String[] args) throws IOException {
        final Path root = Path.of(args.length == 0 ? "src/test/java" : args[0]);
        final List<Path> paths;
        try (var files = Files.walk(root)) {
            paths = files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        final var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("Run with a JDK, not a JRE.");
        }
        final var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, java.nio.charset.StandardCharsets.UTF_8)) {
            final var task = (JavacTask) compiler.getTask(null, manager, diagnostics, List.of("-proc:none", "-Xlint:none"), null,
                    manager.getJavaFileObjectsFromPaths(paths));
            final SourcePositions positions = Trees.instance(task).getSourcePositions();
            final List<Object> files = new ArrayList<>();
            for (CompilationUnitTree unit : task.parse()) {
                final Map<String, Object> file = new LinkedHashMap<>();
                file.put("path", Path.of(unit.getSourceFile().toUri()).toString().replace('\\', '/'));
                file.put("package", unit.getPackageName() == null ? "" : unit.getPackageName().toString());
                final List<Object> classes = new ArrayList<>();
                for (Tree declaration : unit.getTypeDecls()) {
                    if (declaration instanceof ClassTree type) {
                        classes.add(type(unit, positions, type, ""));
                    }
                }
                file.put("classes", classes);
                files.add(file);
            }
            final var errors = diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList();
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("Cannot inventory invalid Java source: " + errors);
            }
            System.out.println(json(files));
        }
    }

    private static Map<String, Object> type(CompilationUnitTree unit, SourcePositions positions, ClassTree type, String parent) {
        final Map<String, Object> result = new LinkedHashMap<>();
        final String name = parent.isEmpty() ? type.getSimpleName().toString() : parent + "." + type.getSimpleName();
        result.put("name", name);
        result.put("start", positions.getStartPosition(unit, type));
        result.put("end", positions.getEndPosition(unit, type));
        result.put("extends", type.getExtendsClause() == null ? "" : type.getExtendsClause().toString());
        result.put("annotations", type.getModifiers().getAnnotations().stream().map(Object::toString).toList());
        final List<Object> members = new ArrayList<>();
        for (Tree member : type.getMembers()) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", member.getKind().toString());
            entry.put("start", positions.getStartPosition(unit, member));
            entry.put("end", positions.getEndPosition(unit, member));
            if (member instanceof MethodTree method) {
                entry.put("name", method.getName().toString());
                entry.put("annotations", method.getModifiers().getAnnotations().stream().map(Object::toString).toList());
                entry.put("parameters", method.getParameters().stream().map(Object::toString).toList());
                entry.put("bodyStart", method.getBody() == null ? -1 : positions.getStartPosition(unit, method.getBody()));
                entry.put("bodyEnd", method.getBody() == null ? -1 : positions.getEndPosition(unit, method.getBody()));
                entry.put("line", unit.getLineMap().getLineNumber(positions.getStartPosition(unit, method)));
                final List<String> calls = new ArrayList<>();
                final List<String> identifiers = new ArrayList<>();
                if (method.getBody() != null) {
                    new TreeScanner<Void, Void>() {
                        @Override public Void visitMethodInvocation(MethodInvocationTree call, Void unused) {
                            calls.add(call.getMethodSelect().toString());
                            return super.visitMethodInvocation(call, unused);
                        }
                        @Override public Void visitIdentifier(IdentifierTree identifier, Void unused) {
                            identifiers.add(identifier.getName().toString());
                            return super.visitIdentifier(identifier, unused);
                        }
                        @Override public Void visitMemberReference(MemberReferenceTree reference, Void unused) {
                            calls.add(reference.getQualifierExpression() + "." + reference.getName());
                            return super.visitMemberReference(reference, unused);
                        }
                        @Override public Void visitAssert(AssertTree assertion, Void unused) {
                            calls.add("<java-assert>");
                            return super.visitAssert(assertion, unused);
                        }
                    }.scan(method.getBody(), null);
                }
                entry.put("calls", calls.stream().distinct().sorted().toList());
                entry.put("identifiers", identifiers.stream().distinct().sorted().toList());
            } else if (member instanceof VariableTree variable) {
                entry.put("name", variable.getName().toString());
            } else if (member instanceof ClassTree nested) {
                entry.put("name", nested.getSimpleName().toString());
                entry.put("class", type(unit, positions, nested, name));
            }
            members.add(entry);
        }
        result.put("members", members);
        return result;
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            return "{" + String.join(",", map.entrySet().stream().map(e -> json(e.getKey().toString()) + ":" + json(e.getValue())).toList()) + "}";
        }
        if (value instanceof List<?> list) return "[" + String.join(",", list.stream().map(TestInventory::json).toList()) + "]";
        final StringBuilder out = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 32) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
