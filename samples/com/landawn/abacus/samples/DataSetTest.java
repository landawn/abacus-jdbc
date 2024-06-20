package com.landawn.abacus.samples;

import java.util.Random;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.samples.entity.Address;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.DataSet;
import com.landawn.abacus.util.N;

public class DataSetTest {
    static final Random RAND = new Random();

    /**
     * 
     */
    @Test
    public void test_join() {
        User user = User.builder().id(1001).firstName("Tom").build();
        Address address = Address.builder().id(2001).userId(1001).street("1 Rd").build();

        DataSet ds1 = N.newDataSet(N.asList("id", "firstName"), N.asList(user));
        ds1.println();

        DataSet ds2 = N.newDataSet(N.asList("id", "userId", "street"), N.asList(address));
        ds2.renameColumn("id", "addressId");
        ds2.println();

        DataSet ds3 = ds1.innerJoin(ds2, N.asMap("id", "userId"));
        ds3.println();
    }

}
