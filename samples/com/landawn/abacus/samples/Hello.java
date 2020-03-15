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
                .set(N.asProps("firstName", CF.expr("first_name + 'abc'")))
                .where(CF.eq("firstName", CF.expr("first_name + 'abc'")))
                .sql();

        N.println(sql);
        
        assertEquals("UPDATE user SET first_name = first_name + 'abc' WHERE first_name = first_name + 'abc'", sql);
    }
}
