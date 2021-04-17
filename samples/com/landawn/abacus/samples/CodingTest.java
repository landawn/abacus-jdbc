package com.landawn.abacus.samples;

import static com.landawn.abacus.samples.Jdbc.dataSource;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.CodingUtil;

public class CodingTest {

    @Test
    public void test_01() {
        String str = CodingUtil.writeEntityClass(dataSource, "account", "", "codes.entity", "./samples");
        System.out.println(str);
    }

}
