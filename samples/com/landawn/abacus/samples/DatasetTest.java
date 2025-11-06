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

import java.util.Random;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.samples.entity.Address;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.Dataset;
import com.landawn.abacus.util.N;

public class DatasetTest {
    static final Random RAND = new Random();

    @Test
    public void test_join() {
        final User user = User.builder().id(1001).firstName("Tom").build();
        final Address address = Address.builder().id(2001).userId(1001).street("1 Rd").build();

        final Dataset ds1 = N.newDataset(N.asList("id", "firstName"), N.asList(user));
        ds1.println();

        final Dataset ds2 = N.newDataset(N.asList("id", "userId", "street"), N.asList(address));
        ds2.renameColumn("id", "addressId");
        ds2.println();

        final Dataset ds3 = ds1.innerJoin(ds2, N.asMap("id", "userId"));
        ds3.println();
    }

}
