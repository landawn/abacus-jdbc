package com.landawn.abacus.samples;

import static com.landawn.abacus.samples.Jdbc.dataSource;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.CodingUtil;
import com.landawn.abacus.util.EntityCodeConfig;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Tuple;

public class CodingTest {

    @Test
    public void test_01() {

        String str = CodingUtil.writeEntityClass(dataSource, "account", EntityCodeConfig.builder().packageName("codes.entity").srcDir("./samples").build());
        System.out.println(str);

        str = CodingUtil.writeEntityClass(dataSource, "account",
                EntityCodeConfig.builder()
                        .className("Account2")
                        .packageName("codes.entity")
                        .srcDir("./samples")
                        .useBoxedType(true)
                        .columnAnnotationClass(javax.persistence.Column.class)
                        .tableAnnotationClass(javax.persistence.Table.class)
                        .customizedFields(N.asList(Tuple.of("createTime", "create_time", java.util.Date.class)))
                        .build());
        System.out.println(str);
    }

}
