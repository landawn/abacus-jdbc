package com.landawn.abacus.dao.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.landawn.abacus.dao.OP;
import com.landawn.abacus.util.JdbcUtil;

/**
 * The Interface NamedUpdate.
 *
 * @see <a href="https://stackoverflow.com/questions/1820908/how-to-turn-off-the-eclipse-code-formatter-for-certain-sections-of-java-code">How to turn off the Eclipse code formatter for certain sections of Java code?</a>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NamedUpdate {

    /**
     *
     * @return
     * @deprecated using sql="SELECT ... FROM ..." for explicit call.
     */
    @Deprecated
    String value() default "";

    /**
     *
     * @return
     */
    String id() default ""; // id defined SqlMapper

    /**
     *
     * @return
     */
    String sql() default "";

    /**
     *
     * @return
     */
    boolean isBatch() default false;

    /**
     *
     * @return
     */
    int batchSize() default JdbcUtil.DEFAULT_BATCH_SIZE;

    /**
     * Unit is seconds.
     *
     * @return
     */
    int queryTimeout() default -1;

    OP op() default OP.DEFAULT;
}