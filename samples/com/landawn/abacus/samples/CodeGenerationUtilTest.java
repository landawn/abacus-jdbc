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

import static com.landawn.abacus.samples.JdbcTest.dataSource;

import java.io.File;
import java.util.Collection;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.annotation.Type.EnumBy;
import com.landawn.abacus.jdbc.JdbcCodeGenerationUtil;
import com.landawn.abacus.jdbc.JdbcCodeGenerationUtil.EntityCodeConfig;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.ClassUtil;
import com.landawn.abacus.util.CodeGenerationUtil;
import com.landawn.abacus.util.CodeGenerationUtil.PropNameTableCodeConfig;
import com.landawn.abacus.util.IOUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.NamingPolicy;
import com.landawn.abacus.query.SQLBuilder.SCSB;
import com.landawn.abacus.util.Tuple;

import codes.entity.Account;

class CodeGenerationUtilTest {

    @Test
    public void test_generatePropNameTableClasses() {
        N.println(CodeGenerationUtil.generatePropNameTableClass(Account.class, CodeGenerationUtil.X, "./samples"));

        final Collection<Class<?>> classes = N.concat(ClassUtil.getClassesByPackage(User.class.getPackageName(), false, false));

        final PropNameTableCodeConfig codeConfig = PropNameTableCodeConfig.builder()
                .entityClasses(classes)
                .className(CodeGenerationUtil.S)
                .packageName("com.landawn.abacus.samples.entity")
                .srcDir("./samples")
                .propNameConverter((cls, propName) -> propName.equals("create_time") ? "createTime" : propName)
                .generateClassPropNameList(true)
                .generateLowerCaseWithUnderscore(true)
                .generateUpperCaseWithUnderscore(true)
                .generateFunctionPropName(true)
                .functionClassName("f")
                .propFunctions(N.asLinkedHashMap("min", CodeGenerationUtil.MIN_FUNC, "max", CodeGenerationUtil.MAX_FUNC))
                .build();

        N.println(CodeGenerationUtil.generatePropNameTableClasses(codeConfig));
    }

    @Test
    public void test_generateEntityClass() {

        EntityCodeConfig ecc = EntityCodeConfig.builder()
                .packageName("codes.entity")
                .srcDir("./samples")
                .idField("id")
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
                .useBoxedType(true)
                .readOnlyFields(N.asSet("id"))
                .idField("id")
                .nonUpdatableFields(N.asSet("create_time"))
                .idAnnotationClass(javax.persistence.Id.class)
                .columnAnnotationClass(jakarta.persistence.Column.class)
                .tableAnnotationClass(com.landawn.abacus.annotation.Table.class)
                .customizedFields(N.asList(Tuple.of("create_time", "create_time", java.util.Date.class)))
                .customizedFieldDbTypes(N.asList(Tuple.of("create_time", "List<String>")))
                .chainAccessor(true)
                .generateBuilder(true)
                .generateCopyMethod(true)
                .jsonXmlConfig(EntityCodeConfig.JsonXmlConfig.builder()
                        .namingPolicy(NamingPolicy.UPPER_CASE_WITH_UNDERSCORE)
                        .ignoredFields("id,   create_time")
                        .dateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
                        .numberFormat("#.###")
                        .timeZone("PDT")
                        .enumerated(EnumBy.ORDINAL)
                        .build())
                .build();

        str = JdbcCodeGenerationUtil.generateEntityClass(dataSource, "user1", ecc);
        System.out.println(str);

        final String additionalLines = """
                    // test
                    private List<User> users;

                    private Set<User> userSet; // test
                """;

        ecc.setClassName("UserQueryAllResult");
        ecc.setAdditionalFieldsOrLines(additionalLines);
        ecc.setGenerateFieldNameTable(true);
        str = JdbcCodeGenerationUtil.generateEntityClass(dataSource, "UserQueryAllResult", "select * from user1", ecc);
        System.out.println(str);

        IOUtil.deleteIfExists(new File("./samples/codes/entity/User1.java"));
    }

    @Test
    public void test_generateSql() {
        String sql = JdbcCodeGenerationUtil.generateSelectSql(dataSource, "user1");
        N.println(sql);

        sql = JdbcCodeGenerationUtil.generateInsertSql(dataSource, "user1");
        N.println(sql);

        sql = JdbcCodeGenerationUtil.generateNamedInsertSql(dataSource, "user1");
        N.println(sql);

        sql = JdbcCodeGenerationUtil.generateUpdateSql(dataSource, "user1");
        N.println(sql);

        sql = JdbcCodeGenerationUtil.generateNamedUpdateSql(dataSource, "user1");
        N.println(sql);
    }

    @Test
    public void test_convertInsertSqlToUpdateSql() {
        User user = N.fill(User.class);
        user.setEmail(null);

        String sql = SCSB.insert(user).into(User.class).sql();
        N.println(sql);

        String updateSql = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(sql);
        N.println(updateSql);

        N.println("==================================");

        sql = SCSB.insert(user).into(User.class).sql();
        N.println(sql);

        updateSql = JdbcCodeGenerationUtil.convertInsertSqlToUpdateSql(sql, "id > 2");
        N.println(updateSql);

    }

}
