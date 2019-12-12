package com.landawn.abacus.samples.dao;

import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.SQLBuilder;

public interface NoUpdateUserDao extends JdbcUtil.NoUpdateCrudDao<User, Long, SQLBuilder.PSC, NoUpdateUserDao> {
}