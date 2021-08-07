package com.landawn.abacus.dao;

/**
 *
 * @see The operations in {@code AbstractPreparedQuery}
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

    /**
     *
     */
    queryForSingle,

    /**
     *
     */
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

    /**
     *
     */
    update,

    /**
     *
     */
    largeUpdate,

    /* batchUpdate,*/

    /**
     *
     */
    DEFAULT;
}