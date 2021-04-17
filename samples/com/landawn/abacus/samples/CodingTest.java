package com.landawn.abacus.samples;

import static com.landawn.abacus.samples.Jdbc.dataSource;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.util.EntityCodeConfig;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.Tuple;

public class CodingTest {

    @Test
    public void test_01() {
        EntityCodeConfig ecc = EntityCodeConfig.builder().packageName("codes.entity").srcDir("./samples").build();

        String str = JdbcUtil.writeEntityClass(dataSource, "account", ecc);
        System.out.println(str);

        ecc = EntityCodeConfig.builder()
                .className("Account2")
                .packageName("codes.entity")
                .srcDir("./samples")
                .useBoxedType(true)
                .columnAnnotationClass(javax.persistence.Column.class)
                .tableAnnotationClass(javax.persistence.Table.class)
                .customizedFields(N.asList(Tuple.of("createTime", "create_time", java.util.Date.class)))
                .build();

        str = JdbcUtil.writeEntityClass(dataSource, "account", ecc);
        System.out.println(str);
    }

}
