package com.landawn.abacus.dao.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Replace the parts defined with format <code>{part}</code> in the sql annotated to the method.
 * For example:
 * <p>
 * <code>
 *
 *  @Select("SELECT first_name, last_name FROM {tableName} WHERE id = :id")
 *  <br />
 *  User selectByUserId(@Define("tableName") String realTableName, @Bind("id") int id) throws SQLException;
 *
 * <br />
 * <br />
 * <br />
 * OR with customized '{whatever}':
 * <br />
 *
 *  @Select("SELECT first_name, last_name FROM {tableName} WHERE id = :id ORDER BY {whatever -> orderBy{{P}}")
 *  <br/>
 *  User selectByUserId(@Define("tableName") String realTableName, @Bind("id") int id, @Define("{whatever -> orderBy{{P}}") String orderBy) throws SQLException;
 *
 * </code>
 * </p>
 *
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(value = { ElementType.PARAMETER })
public @interface Define {
    String value() default "";
}