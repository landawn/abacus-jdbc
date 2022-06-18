package com.landawn.abacus.samples.dao;

import com.landawn.abacus.jdbc.annotation.SqlLogEnabled;
import com.landawn.abacus.jdbc.dao.CrudDao;
import com.landawn.abacus.jdbc.dao.JoinEntityHelper;
import com.landawn.abacus.samples.entity.Project;
import com.landawn.abacus.util.SQLBuilder;

@SqlLogEnabled(true)
public interface ProjectDao extends CrudDao<Project, Integer, SQLBuilder.PSC, ProjectDao>, JoinEntityHelper<Project, SQLBuilder.PSC, ProjectDao> {
}