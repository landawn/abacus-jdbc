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
package com.landawn.abacus.dao.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotated methods in {@code Dao} for:
 *
 * <li> No {@code Handler} is applied to {@code non-db Operation} </li>
 * <li> No {@code sql/performance} log is applied to {@code non-db Operation} </li>
 * <li> No {@code Transaction} annotation is applied to {@code non-db Operation} </li>
 *
 * <br />
 * By default, {@code targetEntityClass/dataSource/sqlMapper/executor/asyncExecutor/prepareQuery/prepareNamedQuery/prepareCallableQuery} methods in {@code Dao} are annotated with {@code NonDBOperation}.
 *
 * <br />
 * <br />
 *
 * @author haiyangl
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.METHOD })
public @interface NonDBOperation {

}