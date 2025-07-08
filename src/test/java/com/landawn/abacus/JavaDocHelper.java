/*
 * Copyright (C) 2024 HaiYang Li
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.landawn.abacus;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.exception.UncheckedIOException;
import com.landawn.abacus.util.Fn;
import com.landawn.abacus.util.Fn.Suppliers;
import com.landawn.abacus.util.IOUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Strings;
import com.landawn.abacus.util.function.Function;
import com.landawn.abacus.util.stream.Stream;

public class JavaDocHelper {

    static final List<String> filesToSkipSet = N.asList("CodeGenerationUtil");

    @Test
    public void remove_since_author() throws Exception {

        final File parentPath = new File("./src/main/java/");

        Stream.listFiles(parentPath, true) //
                .filter(file -> file.isFile() && file.getName().endsWith(".java"))
                .filter(file -> N.noneMatch(filesToSkipSet, it -> file.getName().startsWith(it)))
                .peek(Fn.println())
                .forEach(file -> {
                    final List<String> lines = IOUtil.readAllLines(file);

                    for (int i = 0, size = lines.size(); i < size; i++) {
                        final String line = lines.get(i);

                        if (line.contains("@since") || Strings.containsAnyIgnoreCase(line, "@author Haiyang Li", "@author haiyangl")) {
                            lines.remove(i);
                            i--;
                            size--;
                        }
                    }

                    IOUtil.writeLines(lines, file);
                });
    }

    @Test
    public void add_code_for_words() throws Exception {
        final List<String> keyWords = N.asList("null", "<code>null</code>", "true", "<code>true</code>", "false", "<code>false</code>");

        final File parentPath = new File("./src/main/java/");

        Stream.listFiles(parentPath, true) //
                .filter(file -> file.isFile() && file.getName().endsWith(".java"))
                // .filter(file -> Strings.startsWithAny(file.getName(), "CommonUtil.java", "N.java"))
                .filter(file -> N.noneMatch(filesToSkipSet, it -> file.getName().startsWith(it)))
                .peek(Fn.println())
                .forEach(file -> {
                    final List<String> lines = IOUtil.readAllLines(file);

                    for (int i = 0, size = lines.size(); i < size; i++) {
                        String line = lines.get(i);

                        if (line.trim().startsWith("*")) {
                            if (line.endsWith("(auto-generated java doc for return)")) {
                                line = line.substring(0, line.indexOf("* @return") + 9);
                            }

                            line = Stream.of(Strings.splitPreserveAllTokens(line, " "))
                                    .map(it -> Strings.containsAll(it, "<code>", "</code>") ? it.replace("<code>", "{@code ").replace("</code>", "}") : it)
                                    .join(" ");

                            line = Stream.of(Strings.splitPreserveAllTokens(line, " "))
                                    .map(it -> Strings.isWrappedWith(it, "`") ? "<i>" + Strings.unwrap(it, "`") + "</i>" : it)
                                    .join(" ");

                            for (final String keyword : keyWords) {
                                line = line.replace(" " + keyword + " ", " {@code " + Strings.unwrap(keyword, "<code>", "</code>") + "} ");
                            }

                            for (final String keyword : keyWords) {
                                line = line.replace(" " + keyword + ".", " {@code " + Strings.unwrap(keyword, " ", ".") + "}.");
                            }

                            for (final String keyword : keyWords) {
                                line = line.replace(" " + keyword + ",", " {@code " + Strings.unwrap(keyword, " ", ",") + "},");
                            }

                            lines.set(i, line);
                        }
                    }

                    IOUtil.writeLines(lines, file);
                });
    }

    @Test
    public void format_jdoc() throws Exception {
        final File parentPath = new File("./src/main/java/");

        Stream.listFiles(parentPath, true) //
                .filter(file -> file.isFile() && file.getName().endsWith(".java"))
                .peek(Fn.println())
                .forEach(file -> {
                    final List<String> lines = IOUtil.readAllLines(file);
                    boolean updated = false;

                    for (int i = 0, size = lines.size(); i < size; i++) {
                        final String line = lines.get(i);

                        if (line.startsWith("    *") || line.startsWith("    */")) {
                            lines.set(i, " " + line);
                            updated = true;
                        }
                    }

                    if (updated) {
                        IOUtil.writeLines(lines, file);
                    }
                });

    }

    void declare_exception(final String sourceFolder, final Function<String, String> exceptionExtractor) throws Exception {

        final File parentPath = new File(sourceFolder);
        Stream.listFiles(parentPath, true) //
                .filter(file -> file.isFile() && file.getName().endsWith(".java"))
                .peek(Fn.println())
                .forEach(file -> {
                    final List<String> lines = IOUtil.readAllLines(file);
                    boolean modified = false;

                    for (int i = 0, len = lines.size(); i < len; i++) {
                        final String line = lines.get(i);
                        String exceptionName = null;

                        if (!line.startsWith("//") && N.notEmpty(exceptionName = exceptionExtractor.apply(line))) {
                            for (int j = i; j > 0; j--) {
                                String str = lines.get(j);

                                if (!str.trim().startsWith("//") && !str.trim().startsWith("if (") && !str.trim().startsWith("while (")
                                        && !str.trim().startsWith("for (") && !str.trim().startsWith("synchronized (") && str.endsWith("{")
                                        && (str.contains(") {") || str.contains(") throws ") || str.trim().startsWith("throws "))) {
                                    if ((str.trim().startsWith("public") || lines.get(j - 1).trim().startsWith("public")
                                            || lines.get(j - 2).trim().startsWith("public")) && !str.contains(exceptionName)) {
                                        if (str.contains(") throws ")) {
                                            str = str.replace(") throws ", ") throws " + exceptionName + ", ");
                                        } else {
                                            str = str.replace(") {", ") throws " + exceptionName + " {");
                                        }
                                    }

                                    lines.set(j, str);

                                    N.println(str);
                                    modified = true;

                                    break;
                                }
                            }
                        }
                    }

                    if (modified) {
                        IOUtil.writeLines(lines, file);
                    }
                });
    }

    void check_error_message(final String sourceFolder, final Function<String, String> errorMsgConverter) throws Exception {

        final File parentPath = new File(sourceFolder);
        Stream.listFiles(parentPath, true) //
                .filter(file -> file.isFile() && file.getName().endsWith(".java"))
                .peek(Fn.println())
                .forEach(file -> {
                    final List<String> lines = IOUtil.readAllLines(file);
                    boolean modified = false;

                    for (int i = 0, len = lines.size(); i < len; i++) {
                        final String newLine = errorMsgConverter.apply(lines.get(i));
                        if (!lines.get(i).equals(newLine)) {
                            lines.set(i, newLine);
                            modified = true;
                        }
                    }

                    if (modified) {
                        IOUtil.writeLines(lines, file);
                    }
                });
    }

    @Test
    public void remove_useless_comments() throws UncheckedIOException, IOException {
        final File parentPath = new File("./src/main/java/");

        //        final Function<String, String> returnMapper = line -> line.trim().startsWith("* @return the ") ? line.substring(0, line.indexOf("* @return the ") + 9)
        //                : line;

        final Function<String, String> paramMapper = line -> {
            if (line.trim().startsWith("* @param <") && line.indexOf("> the generic type") > 0) {
                return line.replace("> the generic type", ">");
            } else if (line.trim().startsWith("* @param <") && line.indexOf("> the element type") > 0) {
                return line.replace("> the element type", ">");
            } else if (line.trim().startsWith("* @param <") && line.indexOf("> the parameter type") > 0) {
                return line.replace("> the parameter type", ">");
            } else if (line.trim().startsWith("* @param ")) {
                final int index = line.indexOf(" the ");
                final int index2 = line.indexOf("* @param ");

                if (index > 0) {
                    final String paramName = line.substring(index2 + 8, index).trim();
                    final String doc = line.substring(index + 5).trim().replace(" ", "");
                    if (paramName.equalsIgnoreCase(doc)) {
                        return line.substring(0, index);
                    }
                }

                line = Strings.replaceAll(line, index2 + 8, "       ", " ");
                line = Strings.replaceAll(line, index2 + 8, "      ", " ");
                line = Strings.replaceAll(line, index2 + 8, "     ", " ");
                line = Strings.replaceAll(line, index2 + 8, "    ", " ");
                line = Strings.replaceAll(line, index2 + 8, "   ", " ");
                return Strings.replaceAll(line, index2 + 8, "  ", " ");
            } else {
                return line;
            }
        };

        Stream.listFiles(parentPath, true) //
                .filter(File::isFile)
                .filter(f -> f.getName().endsWith(".java"))
                .peek(Fn.println())
                .forEach(f -> {
                    final List<String> lines = Stream.ofLines(f) //
                            //  .map(returnMapper) //
                            .map(paramMapper)
                            .toList();
                    IOUtil.writeLines(lines, f);
                });

        final Set<String> exclude = N.asSet("* Lazy evaluation.");

        Stream.listFiles(parentPath, true) //
                .filter(File::isFile)
                .filter(f -> f.getName().endsWith(".java"))
                .filter(file -> N.noneMatch(filesToSkipSet, it -> file.getName().startsWith(it)))
                // .peek(Fn.println())
                .forEach(f -> {
                    final List<String> lines = Stream.ofLines(f) //
                            .collapse((p, n) -> !n.trim().startsWith("/**"), Suppliers.ofList())
                            .map(list -> {
                                if (list.size() >= 3 && list.get(0).trim().equals("/**") && list.get(2).trim().equals("*")) {
                                    final String str = list.get(1).trim();

                                    if (str.startsWith("*") && str.endsWith(".") && str.indexOf('<') < 0 && str.indexOf('{') < 0 && !exclude.contains(str)) {
                                        final String[] strs = str.split(" ");
                                        if (strs.length <= 3) {
                                            N.println(list.remove(1));
                                        }
                                    }
                                }

                                return list;
                            })
                            .flatmap(Fn.identity())
                            .toList();

                    IOUtil.writeLines(lines, f);
                });
    }

    @Test
    public void remove_empty_jdoc() throws Exception {
        final File parentPath = new File("./samples/");

        Stream.listFiles(parentPath, true) //
                .filter(file -> file.isFile() && file.getName().endsWith(".java"))
                // .filter(file -> Strings.startsWithAny(file.getName(), "CommonUtil.java", "N.java"))
                .filter(file -> N.noneMatch(filesToSkipSet, it -> file.getName().startsWith(it)))
                .peek(Fn.println())
                .forEach(file -> {
                    final List<String> lines = IOUtil.readAllLines(file);
                    boolean updated = false;

                    for (int i = 0, size = lines.size(); i < size; i++) {
                        final String line = lines.get(i);

                        if (line.contains("/**")) {
                            for (int j = i; j < size; j++) {
                                if (lines.get(j).contains("*/")) {
                                    if ((i != j) && Stream.of(lines.subList(i, j + 1))
                                            .map(Fn.strip())
                                            .flattmap(it -> Strings.split(it, " ", true))
                                            .map(Fn.strip())
                                            .allMatch(it -> it.startsWith("/**") || it.startsWith("*/") || it.startsWith("*") || it.startsWith("@"))) {

                                        lines.subList(i, j + 1).clear();
                                        size -= j - i + 1;
                                        updated = true;
                                    }
                                    break;
                                }
                            }
                        }
                    }

                    if (updated) {
                        IOUtil.writeLines(lines, file);
                    }
                });
    }
    //    @Test
    //    public void add_assertNotClosed() throws IOException {
    //        File file = new File("./src/main/java/com/landawn/abacus/util/stream/");
    //
    //        for (File javaFile : IOUtil.listFiles(file)) {
    //            if (!StringUtil.containsIgnoreCase(javaFile.getName(), "Stream") || "BaseStream.java".equals(javaFile.getName())) {
    //                continue;
    //            }
    //
    //            List<String> lines = IOUtil.readAllLines(javaFile);
    //            List<String> newLines = new ArrayList<>(lines.size());
    //
    //            for (int i = 0; i < lines.size(); i++) {
    //                String line = lines.get(i);
    //                newLines.add(line);
    //
    //                if (line.startsWith("    @Override")) {
    //                    while (!lines.get(++i).endsWith(" {")) {
    //                        newLines.add(lines.get(i));
    //                    }
    //
    //                    newLines.add(lines.get(i));
    //
    //                    for (int j = i; j < lines.size(); j++) {
    //                        if (lines.get(j).startsWith("        assertNotClosed();")) {
    //                            break;
    //                        } else if (lines.get(j).startsWith("    }")) {
    //                            newLines.add("        assertNotClosed();");
    //                            newLines.add("");
    //                            break;
    //                        }
    //                    }
    //                }
    //            }
    //
    //            IOUtil.writeLines(javaFile, newLines);
    //        }
    //    }

}
