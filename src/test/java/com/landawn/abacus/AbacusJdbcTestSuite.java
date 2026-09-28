package com.landawn.abacus;

import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.IncludeTags;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

//--add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED --add-opens java.base/java.io=ALL-UNNAMED --add-opens java.base/sun.nio.ch=ALL-UNNAMED --add-opens java.base/java.nio=ALL-UNNAMED --add-exports java.base/jdk.internal.ref=ALL-UNNAMED --add-exports java.base/sun.nio.ch=ALL-UNNAMED --add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED

/**
 * Runs every test class in {@code com.landawn.abacus.jdbc}, {@code com.landawn.abacus.jdbc.annotation} and
 * {@code com.landawn.abacus.jdbc.dao}.
 *
 * <p>A test class is included through the {@code base-test} tag inherited from {@link TestBase} or through a
 * class-level {@code @Tag("2025")}; a new test class in these packages needs one of the two. Package selection is
 * recursive, and a class reached through more than one selected package runs once.</p>
 */
@Suite
@SelectPackages({ "com.landawn.abacus.jdbc", "com.landawn.abacus.jdbc.annotation", "com.landawn.abacus.jdbc.dao" })
@IncludeTags({ "base-test", "2025" }) // Include tests with these tags
@ExcludeTags("slow-test") // But exclude any that also have "slow-test" tag
public class AbacusJdbcTestSuite {
}