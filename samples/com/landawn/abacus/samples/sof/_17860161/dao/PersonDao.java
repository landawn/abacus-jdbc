package com.landawn.abacus.samples.sof._17860161.dao;

import com.landawn.abacus.dao.CrudDao;
import com.landawn.abacus.dao.JoinEntityHelper;
import com.landawn.abacus.samples.sof._17860161.entity.Person;
import com.landawn.abacus.util.SQLBuilder;

public interface PersonDao extends CrudDao<Person, Long, SQLBuilder.PSC, PersonDao>, JoinEntityHelper<Person, SQLBuilder.PSC, PersonDao> {
}