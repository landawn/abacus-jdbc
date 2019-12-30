/*
 * Copyright (c) 2019, Haiyang Li.
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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The configurations in annotations {@code Dao.PerfLog/Select/NamedSelect/...} have higher priority than configurations defined in this class.
 * 
 * @author Haiyang Li
 *
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DaoSettings {
    @Builder.Default
    private long queryTimeout = -1;
    @Builder.Default
    private int fetchSize = -1;
    @Builder.Default
    private int batchSize = -1;

    @Builder.Default
    private long minExecutionTimeForSqlPerfLog = -1;
    @Builder.Default
    private long minExecutionTimeForOperationPerfLog = -1;
}
