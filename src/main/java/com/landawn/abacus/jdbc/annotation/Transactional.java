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
package com.landawn.abacus.jdbc.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.landawn.abacus.jdbc.IsolationLevel;
import com.landawn.abacus.jdbc.Propagation;

/**
 * It's for transaction started in {@code Dao} methods.
 * For service classes in Spring, {@code org.springframework.transaction.annotation.Transactional} should be used.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD) // Should be used on method only, not for ElementType.TYPE/CLASS
public @interface Transactional {
    
    /**
     * 
     *
     * @return 
     */
    Propagation propagation() default Propagation.REQUIRED;

    /**
     * 
     *
     * @return 
     */
    IsolationLevel isolation() default IsolationLevel.DEFAULT;
}
