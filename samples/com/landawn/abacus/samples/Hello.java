package com.landawn.abacus.samples;

import java.sql.Connection;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.condition.ConditionFactory.CF;
import com.landawn.abacus.jdbc.Jdbc.BiParametersSetter;
import com.landawn.abacus.jdbc.JdbcUtils;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.DataSet;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.SQLBuilder.PSC;
import com.landawn.abacus.util.u;

public class Hello {

    @Test
    public void test_expr() {

        String sql = PSC.update(User.class)
                .set(N.asProps("firstName", CF.expr("first_name + 'abc'")))
                .where(CF.eq("firstName", CF.expr("first_name + 'abc'")))
                .sql();

        N.println(sql);

        N.println(u.Optional.class.getEnclosingClass());
        N.println(String.class.getEnclosingClass());

        DataSet ds = null;
        Connection conn = null;
        JdbcUtils.importData(ds, conn, "", BiParametersSetter.createForArray(N.asList(""), User.class));
    }
}
