/*
 * Copyright (c) 2021, Haiyang Li.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.landawn.abacus.util;

import java.lang.annotation.Annotation;
import java.util.Collection;
import java.util.List;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.annotation.Type.EnumBy;
import com.landawn.abacus.util.Tuple.Tuple2;
import com.landawn.abacus.util.Tuple.Tuple3;
import com.landawn.abacus.util.function.BiFunction;
import com.landawn.abacus.util.function.QuadFunction;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A sample, just a sample, not a general configuration required.
 * <pre> 
 * EntityCodeConfig ecc = EntityCodeConfig.builder()
 *        .className("User")
 *        .packageName("codes.entity")
 *        .srcDir("./samples")
 *        .fieldNameConverter((tableName, columnName) -> StringUtil.toCamelCase(columnName))
 *        .fieldTypeConverter((tableName, columnName, fieldName, columnClassName) -> ClassUtil.getCanonicalClassName(ClassUtil.forClass(columnClassName)) // columnClassName <- resultSetMetaData.getColumnClassName(columnIndex);
 *                .replace("java.lang.", ""))
 *        .useBoxedType(false)
 *        .readOnlyFields(N.asSet("id"))
 *        .nonUpdatableFields(N.asSet("create_time"))
 *        // .idAnnotationClass(javax.persistence.Id.class)
 *        // .columnAnnotationClass(javax.persistence.Column.class)
 *        // .tableAnnotationClass(javax.persistence.Table.class)
 *        .customizedFields(N.asList(Tuple.of("columnName", "fieldName", java.util.Date.class)))
 *        .customizedFieldDbTypes(N.asList(Tuple.of("fieldName", "List<String>")))
 *        .build();
 * </pre>
 *
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public final class EntityCodeConfig {

    private String className;
    private String packageName;
    private String srcDir;
    /**
     * First parameter in the function is table name, 2nd is column name.
     */
    private BiFunction<String, String, String> fieldNameConverter;
    /**
     * First parameter in the function is table name, 2nd is column name, 3rd is field name, 4th is column class name.
     */
    private QuadFunction<String, String, String, String, String> fieldTypeConverter;
    private List<Tuple3<String, String, Class<?>>> customizedFields;
    private List<Tuple2<String, String>> customizedFieldDbTypes;

    private boolean useBoxedType;
    private boolean mapBigIntegerToLong;
    private boolean mapBigDecimalToDouble;

    private Collection<String> readOnlyFields;
    private Collection<String> nonUpdatableFields;
    private Collection<String> idFields;
    private String idField;

    private Class<? extends Annotation> tableAnnotationClass;
    private Class<? extends Annotation> columnAnnotationClass;
    private Class<? extends Annotation> idAnnotationClass;

    private boolean chainAccessor;
    private boolean generateBuilder;
    private boolean generateCopyMethod;

    // private List<Tuple2<String, String>> customizedJsonFields;
    @Beta
    private JsonXmlConfig jsonXmlConfig;

    /**
     *  
     * @see com.landawn.abacus.annotation.JsonXmlConfig
     */
    @Builder
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JsonXmlConfig {
        private NamingPolicy namingPolicy;

        private String ignoredFields;

        private String dateFormat;

        private String timeZone;

        private String numberFormat;

        private EnumBy enumerated;
    }
}
