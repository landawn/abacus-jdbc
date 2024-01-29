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
package com.landawn.abacus.jdbc.dao;

import java.util.Map;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.util.SQLBuilder;

@Beta
public interface UncheckedNoUpdateCrudDaoL<T, SB extends SQLBuilder, TD extends UncheckedNoUpdateCrudDaoL<T, SB, TD>>
        extends UncheckedNoUpdateCrudDao<T, Long, SB, TD>, UncheckedCrudDaoL<T, SB, TD> {

    /**
     * 
     *
     * @param propName 
     * @param propValue 
     * @param id 
     * @return 
     * @throws UncheckedSQLException 
     * @throws UnsupportedOperationException 
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final String propName, final Object propValue, final long id) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     *
     * @param updateProps 
     * @param id 
     * @return 
     * @throws UncheckedSQLException 
     * @throws UnsupportedOperationException 
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int update(final Map<String, Object> updateProps, final long id) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }

    /**
     * 
     *
     * @param id 
     * @return 
     * @throws UncheckedSQLException 
     * @throws UnsupportedOperationException 
     * @deprecated unsupported Operation
     */
    @Deprecated
    @Override
    default int deleteById(final long id) throws UncheckedSQLException, UnsupportedOperationException {
        throw new UnsupportedOperationException();
    }
}
