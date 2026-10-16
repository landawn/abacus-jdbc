package com.landawn.abacus.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.io.TempDir;

import com.landawn.abacus.TestBase;

/**
 * Regression tests for resolving the intermediate entity class of a many-to-many {@code @JoinedBy} join. It used to be
 * looked up with {@code ClassUtil.forName}, i.e. through abacus-common's own class loader. When the library is
 * deployed in a parent class loader (e.g. an application server's shared library) and the entities in a child one
 * (e.g. a web application), that loader cannot see the intermediate class, so a valid join failed DAO creation with
 * "the intermediate entity class is required but not found". It also caught {@code Throwable}, so a failing static
 * initializer of the intermediate class was reported as "not found".
 *
 * <p>The fixture entities are compiled at test time and loaded by a child class loader that the test class loader
 * (which also loaded abacus-common) cannot see. The join properties are single-valued on purpose: a generic property
 * such as {@code List<IsoRole>} makes abacus-common 8.1.0 resolve {@code IsoRole} by name through its own class loader
 * and cache it as a non-bean type (an upstream {@code TypeFactory} issue), so the join would be rejected as "not an
 * entity type" before the intermediate class is looked up.</p>
 */
@TestInstance(Lifecycle.PER_CLASS)
public class JoinInfoClassLoaderTest extends TestBase {

    private static final String PACKAGE = "isolated.m2m";

    private static final Map<String, String> SOURCES = Map.of("IsoUser", """
            package isolated.m2m;

            import com.landawn.abacus.annotation.JoinedBy;

            public class IsoUser {
                private long userId;

                @JoinedBy("userId = IsoUserRole.userId, IsoUserRole.roleId = roleId")
                private IsoRole primaryRole;

                @JoinedBy("userId = IsoMissingLink.userId, IsoMissingLink.roleId = roleId")
                private IsoRole missingLinkRole;

                @JoinedBy("userId = IsoFailingInitLink.userId, IsoFailingInitLink.roleId = roleId")
                private IsoRole failingInitRole;

                public long getUserId() { return userId; }
                public void setUserId(long userId) { this.userId = userId; }
                public IsoRole getPrimaryRole() { return primaryRole; }
                public void setPrimaryRole(IsoRole primaryRole) { this.primaryRole = primaryRole; }
                public IsoRole getMissingLinkRole() { return missingLinkRole; }
                public void setMissingLinkRole(IsoRole missingLinkRole) { this.missingLinkRole = missingLinkRole; }
                public IsoRole getFailingInitRole() { return failingInitRole; }
                public void setFailingInitRole(IsoRole failingInitRole) { this.failingInitRole = failingInitRole; }
            }
            """, "IsoRole", """
            package isolated.m2m;

            public class IsoRole {
                private long roleId;
                private String name;

                public long getRoleId() { return roleId; }
                public void setRoleId(long roleId) { this.roleId = roleId; }
                public String getName() { return name; }
                public void setName(String name) { this.name = name; }
            }
            """, "IsoUserRole", """
            package isolated.m2m;

            public class IsoUserRole {
                private long userId;
                private long roleId;

                public long getUserId() { return userId; }
                public void setUserId(long userId) { this.userId = userId; }
                public long getRoleId() { return roleId; }
                public void setRoleId(long roleId) { this.roleId = roleId; }
            }
            """, "IsoFailingInitLink", """
            package isolated.m2m;

            public class IsoFailingInitLink {
                static {
                    if (Boolean.TRUE) {
                        throw new IllegalStateException("the intermediate class must not be initialized to read its metadata");
                    }
                }

                private long userId;
                private long roleId;

                public long getUserId() { return userId; }
                public void setUserId(long userId) { this.userId = userId; }
                public long getRoleId() { return roleId; }
                public void setRoleId(long roleId) { this.roleId = roleId; }
            }
            """);

    @TempDir
    static Path directory;

    private URLClassLoader isolatedLoader;
    private Class<?> userClass;

    @BeforeAll
    public void compileIsolatedEntities() throws IOException, ClassNotFoundException {
        final Path sourceDir = Files.createDirectories(directory.resolve("src").resolve(PACKAGE.replace('.', '/')));
        final Path classesDir = Files.createDirectories(directory.resolve("classes"));
        final List<String> args = new ArrayList<>(
                List.of("-encoding", "UTF-8", "-proc:none", "-classpath", System.getProperty("java.class.path"), "-d", classesDir.toString()));

        for (final Map.Entry<String, String> source : SOURCES.entrySet()) {
            final Path file = sourceDir.resolve(source.getKey() + ".java");
            Files.writeString(file, source.getValue());
            args.add(file.toString());
        }

        final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        final int exitCode = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, args.toArray(new String[0]));
        assertEquals(0, exitCode, () -> diagnostics.toString(StandardCharsets.UTF_8));

        isolatedLoader = new URLClassLoader(new URL[] { classesDir.toUri().toURL() }, JoinInfoClassLoaderTest.class.getClassLoader());
        userClass = Class.forName(PACKAGE + ".IsoUser", true, isolatedLoader);
    }

    @AfterAll
    public void closeLoader() throws IOException {
        if (isolatedLoader != null) {
            isolatedLoader.close();
        }
    }

    @Test
    public void intermediateClassIsResolvedThroughTheEntityClassLoader() {
        // The loader that defined abacus-common (and this test) cannot see the intermediate class.
        assertThrows(ClassNotFoundException.class, () -> Class.forName(PACKAGE + ".IsoUserRole"));

        final JoinInfo joinInfo = new JoinInfo(userClass, "iso_user", "primaryRole", false);

        assertTrue(joinInfo.isManyToManyJoin());
        assertEquals(List.of("userId"), joinInfo.sourcePropNames());
    }

    @Test
    public void missingIntermediateClassIsReportedAsNotFound() {
        final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new JoinInfo(userClass, "iso_user", "missingLinkRole", false));

        assertTrue(failure.getMessage().contains("intermediate entity class is required but not found: " + PACKAGE + ".IsoMissingLink"), failure.getMessage());
        assertInstanceOf(ClassNotFoundException.class, failure.getCause());
    }

    // Only the intermediate class's bean metadata is read, so its static initializer does not run (it used to run, and
    // its failure was reported as "not found").
    @Test
    public void intermediateClassIsNotInitialized() {
        final JoinInfo joinInfo = new JoinInfo(userClass, "iso_user", "failingInitRole", false);

        assertTrue(joinInfo.isManyToManyJoin());
    }
}
