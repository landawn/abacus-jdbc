package com.landawn.abacus.samples.sof._17860161.dao;

import com.landawn.abacus.samples.sof._17860161.entity.Person;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.SQLBuilder;

public interface PersonDao extends JdbcUtil.CrudDao<Person, Long, SQLBuilder.PSC, PersonDao>, JdbcUtil.JoinEntityHelper<Person, SQLBuilder.PSC, PersonDao> {
}