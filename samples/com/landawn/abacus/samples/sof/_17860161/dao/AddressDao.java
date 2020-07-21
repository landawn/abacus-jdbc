package com.landawn.abacus.samples.sof._17860161.dao;

import com.landawn.abacus.samples.sof._17860161.entity.Address;
import com.landawn.abacus.util.JdbcUtil;
import com.landawn.abacus.util.SQLBuilder;

public interface AddressDao extends JdbcUtil.CrudDaoL<Address, SQLBuilder.PSC, AddressDao>, JdbcUtil.JoinEntityHelper<Address, SQLBuilder.PSC, AddressDao> {
}