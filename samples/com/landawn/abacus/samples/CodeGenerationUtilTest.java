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

import static com.landawn.abacus.query.Dsl.SCSB;
import static com.landawn.abacus.samples.JdbcTest.dataSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.jdbc.JdbcCodeGenerationUtil;
import com.landawn.abacus.jdbc.JdbcCodeGenerationUtil.EntityCodeConfig;
import com.landawn.abacus.jdbc.JdbcCodeGenerationUtil.EntityCodeConfig.FieldMapping;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.Beans;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.CodeGenerationUtil;
import com.landawn.abacus.util.CodeGenerationUtil.PropNameTableCodeConfig;
import com.landawn.abacus.util.EnumType;
import com.landawn.abacus.util.IOUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NamingPolicy;
import com.landawn.abacus.util.Tuple;

import codes.entity.Account;

class CodeGenerationUtilTest {

    @Test
    public void test_generatePropNameTableClasses() {
        N.println(CodeGenerationUtil.generatePropNameTableClass(Account.class, CodeGenerationUtil.X, "./samples"));

        final Collection<Class<?>> classes = N.concat(ClassUtil.findClassesInPackage(User.class.getPackageName(), false, false));

        final PropNameTableCodeConfig codeConfig = PropNameTableCodeConfig.builder()
                .entityClasses(classes)
                .className(CodeGenerationUtil.S)
                .packageName("com.landawn.abacus.samples.entity")
                .srcDir("./samples")
                .propNameConverter((cls, propName) -> propName.equals("create_time") ? "createTime" : propName)
                .generateClassPropNameList(true)
                .generateSnakeCase(true)
                .generateScreamingSnakeCase(true)
                .generateFunctionPropName(true)
                .functionClassName("f")
                .propFunctions(N.asMap("min", CodeGenerationUtil.MIN_FUNC, "max", CodeGenerationUtil.MAX_FUNC))
                .build();

        N.println(CodeGenerationUtil.generatePropNameTableClasses(codeConfig));
    }

    @Test
    public void test_generateEntityClass() {

        EntityCodeConfig ecc = EntityCodeConfig.builder()
                .packageName("codes.entity")
                .srcDir("./samples")
                .idFields(List.of("id"))
                .readOnlyFields(N.asSet("createTime"))
                .nonUpdatableFields(N.asSet("id"))
                .generateBuilder(true)
                .build();

        String str = JdbcCodeGenerationUtil.generateEntityClass(dataSource, "user1", ecc);
        System.out.println(str);

        ecc = EntityCodeConfig.builder()
                .className("User")
                .packageName("codes.entity")
                .srcDir("./samples")
                .useBoxedTypes(true)
                .readOnlyFields(N.asSet("id"))
                .idFields(List.of("id"))
                .nonUpdatableFields(N.asSet("create_time"))
                .idAnnotationClass(javax.persistence.Id.class)
                .columnAnnotationClass(jakarta.persistence.Column.class)
                .tableAnnotationClass(com.landawn.abacus.annotation.Table.class)
                .customFieldMappings(N.asList(new FieldMapping("create_time", "create_time", java.util.Date.class)))
                .fieldTypeAnnotationArguments(N.asList(Tuple.of("create_time", "name = \"List<String>\"")))
                .generateChainAccessors(true)
                .generateBuilder(true)
                .generateCopyMethod(true)
                .jsonXmlConfig(EntityCodeConfig.JsonXmlConfig.builder()
                        .namingPolicy(NamingPolicy.SCREAMING_SNAKE_CASE)
                        .ignoredFields("id,   create_time")
                        .dateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                        .numberFormat("#.###")
                        .timeZone("PDT")
                        .enumerated(EnumType.ORDINAL)
                        .build())
                .build();

        str = JdbcCodeGenerationUtil.generateEntityClass(dataSource, "user1", ecc);
        System.out.println(str);

        final String additionalLines = """
                    // test
                    private List<User> users;

                    private Set<User> userSet; // test
                """;

        ecc = EntityCodeConfig.builder()
                .packageName("codes.entity")
                .srcDir("./samples")
                .idFields(List.of("id"))
                .readOnlyFields(N.asSet("createTime"))
                .nonUpdatableFields(N.asSet("id"))
                .generateBuilder(true)
                .className("UserQueryAllResult")
                .additionalClassBodySource(additionalLines)
                .idAnnotationClass(javax.persistence.Id.class)
                .columnAnnotationClass(jakarta.persistence.Column.class)
                .tableAnnotationClass(com.landawn.abacus.annotation.Table.class)
                .generatePropNameTable(true)
                .build();
        // ecc.setClassNamesToImport(N.asList("codes.entity.User", "jakarta.persistence.Column"));
        str = JdbcCodeGenerationUtil.generateEntityClassByQuery(dataSource, "UserQueryAllResult", "select * from user1", ecc);
        System.out.println(str);

        IOUtil.deleteIfExists(new File("./samples/codes/entity/User1.java"));
    }

    @Test
    public void test_generateSql() {
        String sql = JdbcCodeGenerationUtil.generateSelectSql(dataSource, "user1");
        N.println(sql);
        assertEquals("SELECT ID, FIRST_NAME, LAST_NAME, PROP1, EMAIL, CREATE_TIME FROM user1", sql);

        sql = JdbcCodeGenerationUtil.generateInsertSql(dataSource, "user1");
        N.println(sql);
        assertEquals("INSERT INTO user1(ID, FIRST_NAME, LAST_NAME, PROP1, EMAIL, CREATE_TIME) VALUES (?, ?, ?, ?, ?, ?)", sql);

        sql = JdbcCodeGenerationUtil.generateNamedInsertSql(dataSource, "user1");
        N.println(sql);
        assertEquals("INSERT INTO user1(ID, FIRST_NAME, LAST_NAME, PROP1, EMAIL, CREATE_TIME) VALUES (:id, :firstName, :lastName, :prop1, :email, :createTime)",
                sql);

        sql = JdbcCodeGenerationUtil.generateUpdateSql(dataSource, "user1");
        N.println(sql);
        assertEquals("UPDATE user1 SET ID = ?, FIRST_NAME = ?, LAST_NAME = ?, PROP1 = ?, EMAIL = ?, CREATE_TIME = ?", sql);

        sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(dataSource, "user1");
        N.println(sql);
        assertEquals("UPDATE user1 SET ID = :id, FIRST_NAME = :firstName, LAST_NAME = :lastName, PROP1 = :prop1, EMAIL = :email, CREATE_TIME = :createTime",
                sql);

        sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(dataSource, "user1", "id");
        N.println(sql);
        assertEquals(
                "UPDATE user1 SET FIRST_NAME = :firstName, LAST_NAME = :lastName, PROP1 = :prop1, EMAIL = :email, CREATE_TIME = :createTime WHERE ID = :id",
                sql);

        sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(dataSource, "user1", null, N.asList("id", "email"), null);
        N.println(sql);
        assertEquals(
                "UPDATE user1 SET FIRST_NAME = :firstName, LAST_NAME = :lastName, PROP1 = :prop1, CREATE_TIME = :createTime WHERE ID = :id AND EMAIL = :email",
                sql);

        sql = JdbcCodeGenerationUtil.generateUpdateSql(dataSource, "user1", "id");
        N.println(sql);
        assertEquals("UPDATE user1 SET FIRST_NAME = ?, LAST_NAME = ?, PROP1 = ?, EMAIL = ?, CREATE_TIME = ? WHERE ID = ?", sql);

        sql = JdbcCodeGenerationUtil.generateUpdateSql(dataSource, "user1", null, N.asList("id", "email"), null);
        N.println(sql);
        assertEquals("UPDATE user1 SET FIRST_NAME = ?, LAST_NAME = ?, PROP1 = ?, CREATE_TIME = ? WHERE ID = ? AND EMAIL = ?", sql);
    }

    @Test
    public void test_convertInsertSqlToUpdateSql() {
        User user = Beans.newRandomBean(User.class);
        user.setEmail(null);

        String sql = SCSB.insert(user).into(User.class).build().query();
        N.println(sql);

        String updateSql = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, sql);
        N.println(updateSql);

        N.println("==================================");

        sql = SCSB.insert(user).into(User.class).build().query();
        N.println(sql);

        updateSql = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(dataSource, sql, "id > 2");
        N.println(updateSql);

    }

}
