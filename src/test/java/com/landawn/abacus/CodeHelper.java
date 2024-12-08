package com.landawn.abacus;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.jdbc.s;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.IOUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.u.OptionalInt;
import com.landawn.abacus.util.stream.Stream;

public class CodeHelper {

    @Test
    public void compare_methods_in_UncheckedDaos() throws Exception {

        final Map<String, Class<?>> daoClsMap = Stream.of(ClassUtil.getClassesByPackage("com.landawn.abacus.jdbc.dao", true, true))
                .toMap(ClassUtil::getSimpleClassName, Fn.identity());

        Stream.ofValues(daoClsMap).filter(it -> ClassUtil.getSimpleClassName(it).startsWith("Unchecked")).forEach(Fn.println());

        Stream.of(daoClsMap).filter(entry -> !entry.getKey().startsWith("Unchecked") && daoClsMap.containsKey("Unchecked" + entry.getKey())).foreach(entry -> {
            final String key = entry.getKey();
            final Class<?> checkedDaoClass = entry.getValue();

            N.println("========================================================: " + key);

            final String uncheckedClsName = "Unchecked" + key;

            if (daoClsMap.containsKey(uncheckedClsName)) {
                final Class<?> uncheckedDaoClass = daoClsMap.get(uncheckedClsName);

                final List<String> methodsInCheckedDao = Stream.of(checkedDaoClass.getMethods())
                        .filter(it -> N.contains(it.getExceptionTypes(), SQLException.class))
                        .map(Method::getName)
                        .filter(it -> it.startsWith("prepare") == false)
                        .toList();

                final List<String> methodsInUncheckedDao = Stream.of(uncheckedDaoClass.getMethods())
                        .filter(it -> N.contains(it.getExceptionTypes(), UncheckedSQLException.class)
                                && !N.contains(it.getExceptionTypes(), SQLException.class))
                        .map(Method::getName)
                        .toList();

                // N.println(methods);
                // N.println(uncheckedMethods);

                final List<String> diff = N.symmetricDifference(methodsInCheckedDao, methodsInUncheckedDao);
                if (diff.size() > 0) {
                    N.println("diff: " + diff);
                    N.println(methodsInCheckedDao);
                    N.println(methodsInUncheckedDao);
                }
                assertTrue(N.isEmpty(diff));
            }
        });
    }

    @Test
    public void replace_parameter_string() throws Exception {
        final String clsName = "s";
        final File parentPath = new File("./src/main/java/");

        final Map<String, Field> map = Stream.of(s.class.getFields())
                .filter(it -> it.getType().equals(String.class) && Modifier.isPublic(it.getModifiers()) && Modifier.isStatic(it.getModifiers())
                        && Modifier.isFinal(it.getModifiers()))
                .toMap(it -> (String) it.get((Object) null), Fn.identity());

        final List<String> fieldNames = Stream.of(map.keySet()).map(it -> clsName + "." + it).toList();

        final Set<String> parameterNamesToAdd = new HashSet<>();

        final String path = ClassUtil.getPackageName(s.class).replace('.', '\\');

        Stream.listFiles(parentPath, true) //
                .filter(file -> file.isFile() && file.getName().endsWith(".java"))
                .peek(Fn.println())
                .forEach(file -> {

                    final List<String> lines = IOUtil.readAllLines(file);
                    boolean updated = false;

                    for (int i = 0, size = lines.size(); i < size; i++) {
                        String line = lines.get(i);
                        final String tmp = line;

                        fieldNames.removeAll(fieldNames.stream().filter(it -> tmp.contains(it)).toList());

                        final String stripedLine = Strings.strip(line);

                        if (Strings.startsWithAny(stripedLine, "N.check", "check") && stripedLine.endsWith("\");")) {
                            final String[] substrs = Strings.split(Strings.substringBetween(stripedLine, "(", ")"), ", ");

                            if (substrs.length == 2 && Strings.isValidJavaIdentifier(substrs[0])
                                    && Strings.isValidJavaIdentifier(Strings.unwrap(substrs[1], "\"")) && Strings.isWrappedWith(substrs[1], "\"")) {
                                final String paramName = Strings.unwrap(substrs[1], "\"");

                                if (!map.containsKey(paramName)) {
                                    parameterNamesToAdd.add(paramName);
                                }

                                line = Strings.replaceFirst(line, substrs[1], clsName + "." + paramName);

                                lines.set(i, line);
                                updated = true;
                            }
                        }
                    }

                    if (updated && !Strings.substringBeforeLast(file.getAbsolutePath(), "\\").endsWith(path)
                            && !lines.stream().anyMatch(it -> it.startsWith("import com.landawn.abacus.jdbc.s"))) {
                        for (int i = 0, size = lines.size(); i < size; i++) {
                            final String line = lines.get(i);

                            if (line.startsWith("package com.landawn.abacus")) {
                                final OptionalInt idx = N.findLastIndex(lines, it -> it.startsWith("import com.landawn.abacus.jdbc"));

                                if (idx.isPresent()) {
                                    lines.add(idx.orElseThrow() + 1, "import com.landawn.abacus.jdbc.s;");
                                } else {
                                    lines.add(i + 1, IOUtil.LINE_SEPARATOR + "import com.landawn.abacus.jdbc.s;");
                                }
                            }
                        }
                    }

                    if (updated) {
                        IOUtil.writeLines(lines, file);
                    }
                });

        if (parameterNamesToAdd.size() > 0 || fieldNames.size() > 0) {

            final File file = new File("./src/main/java/com/landawn/abacus/jdbc/s.java");

            final List<String> lines = IOUtil.readAllLines(file);

            final List<String> newLines = Stream.of(parameterNamesToAdd)
                    .sorted()
                    .map(it -> "    public static final String " + it + " = \"" + it + "\";")
                    .prepend(IOUtil.LINE_SEPARATOR)
                    .toList();

            lines.addAll(lines.size() - 1, newLines);

            if (fieldNames.size() > 0) {
                for (int i = 0, size = lines.size(); i < size; i++) {
                    final String line = lines.get(i);

                    if (fieldNames.stream().anyMatch(it -> line.startsWith("    public static final String " + it.substring(3)))) {
                        lines.set(i, "    // " + Strings.strip(line));
                    }

                }
            }

            IOUtil.writeLines(lines, file);
        }
    }
}
