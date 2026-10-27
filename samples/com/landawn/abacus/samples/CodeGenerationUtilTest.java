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
package com.landawn.abacus.samples;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.landawn.abacus.jdbc.JdbcCodeGenerationUtil;
import com.landawn.abacus.jdbc.JdbcCodeGenerationUtil.EntityCodeConfig;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.CodeGenerationUtil;
import com.landawn.abacus.util.CodeGenerationUtil.PropNameTableCodeConfig;

/** Generate into a temporary directory so running the guide never rewrites its sources. */
public class CodeGenerationUtilTest extends SampleTestSupport {
    @TempDir
    Path outputDirectory;

    @Test
    public void generateAnEntityFromTableMetadata() throws Exception {
        final EntityCodeConfig config = EntityCodeConfig.builder()
                .className("GeneratedUser")
                .packageName("guide.generated")
                .srcDir(outputDirectory.toString())
                .idFields(List.of("id"))
                .readOnlyFields(Set.of("createTime"))
                .generateBuilder(true)
                .build();

        final String source = JdbcCodeGenerationUtil.generateEntityClass(dataSource, "user1", config);
        assertTrue(source.contains("class GeneratedUser"));
        assertTrue(Files.exists(outputDirectory.resolve("guide/generated/GeneratedUser.java")));
        // In your project, choose a generated-sources directory and review the mapping annotations.
    }

    @Test
    public void generateAProjectionFromAQuery() throws Exception {
        final EntityCodeConfig config = EntityCodeConfig.builder()
                .packageName("guide.generated")
                .srcDir(outputDirectory.toString())
                .generateBuilder(true)
                .build();

        final String source = JdbcCodeGenerationUtil.generateEntityClassByQuery(dataSource, "UserSummary", "SELECT id, first_name, email FROM user1", config);
        assertTrue(source.contains("class UserSummary"));
        assertTrue(Files.exists(outputDirectory.resolve("guide/generated/UserSummary.java")));
    }

    @Test
    public void generatePropertyNameConstants() {
        final PropNameTableCodeConfig config = PropNameTableCodeConfig.builder()
                .entityClasses(List.of(User.class))
                .className("UserProperties")
                .packageName("guide.generated")
                .srcDir(outputDirectory.toString())
                .generateClassPropNameList(true)
                .generateSnakeCase(true)
                .build();

        CodeGenerationUtil.generatePropNameTableClasses(config);
        assertTrue(Files.exists(outputDirectory.resolve("guide/generated/UserProperties.java")));
    }

    @Test
    public void generateAnUpdateWithAnExplicitKeyPredicate() {
        // Supply the key column; a generated UPDATE without a WHERE clause updates every row.
        final String sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(dataSource, "user1", "id");
        assertEquals("UPDATE user1 SET FIRST_NAME = :firstName, LAST_NAME = :lastName, PROP1 = :prop1, EMAIL = :email, "
                + "CREATE_TIME = :createTime WHERE ID = :id", sql);
    }
}
