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
package com.landawn.abacus.jdbc;

/**
 *
 * Refer to the operations in {@code AbstractQuery}
 *
 */
public enum OP {
    exists,
    findOnlyOne,
    findFirst,
    list,

    /**
     * @deprecated generally it's unnecessary to specify the {@code "op = OP.query"} in {@code Select/NamedSelect}.
     */
    query,

    /**
     *
     * @deprecated generally it's unnecessary to specify the {@code "op = OP.stream"} in {@code Select/NamedSelect}.
     */
    stream,

    queryForSingle,

    queryForUnique,

    /**
     * Mostly it's for {@code @Call} to retrieve all the {@code ResultSets} returned from the executed procedure by {@code listAll/listAllAndGetOutParameters}.
     */
    listAll,

    /**
     * Mostly it's for {@code @Call} to retrieve all the {@code ResultSets} returned from the executed procedure by {@code queryAll/queryAllAndGetOutParameters}.
     */
    queryAll,

    /**
     * Mostly it's for {@code @Call} to retrieve all the {@code ResultSets} returned from the executed procedure by {@code streamAll}.
     */
    streamAll,

    /**
     * Mostly it's for {@code @Call} to execute the target procedure and get out parameters by {@code executeAndGetOutParameters}.
     */
    executeAndGetOutParameters,

    update,

    largeUpdate,

    /* batchUpdate,*/

    DEFAULT

}
