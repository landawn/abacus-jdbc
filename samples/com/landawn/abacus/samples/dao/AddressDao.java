package com.landawn.abacus.samples.dao;

import com.landawn.abacus.samples.entity.Address;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.SQLBuilder;

public interface AddressDao extends JdbcUtil.CrudDaoL<Address, SQLBuilder.PSC, AddressDao> {
}