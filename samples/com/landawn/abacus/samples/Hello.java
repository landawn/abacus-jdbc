package com.landawn.abacus.samples;

import static org.junit.Assert.assertEquals;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.condition.ConditionFactory.CF;
import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.N;
import com.landawn.abacus.util.SQLBuilder.PSC;

public class Hello {

    @Test
    public void test_expr() {

        String sql = PSC.update(User.class)
                .set(N.asProps("firstName", CF.expr("first_name + 'abc'"), "lastName", CF.expr("lastName + '123'")))
                .where(CF.eq("firstName", CF.expr("first_name + 'abc'")))
                .sql();

        N.println(sql);
        assertEquals("UPDATE user SET FIRST_NAME = first_name + 'abc', last_name = last_name + '123' WHERE FIRST_NAME = first_name + 'abc'", sql);
    }
}
