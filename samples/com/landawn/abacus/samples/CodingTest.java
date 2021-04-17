package com.landawn.abacus.samples;

import static com.landawn.abacus.samples.Jdbc.dataSource;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.CodingUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Tuple;

public class CodingTest {

    @Test
    public void test_01() {

        String str = CodingUtil.writeEntityClass(dataSource, "account", null, "codes.entity", "./samples");
        System.out.println(str);

        str = CodingUtil.writeEntityClass(dataSource, "account", "Account2", "codes.entity", "./samples",
                N.asList(Tuple.of("createTime", "create_time", java.util.Date.class)));
        System.out.println(str);
    }

}
