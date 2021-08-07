package com.landawn.abacus.samples.dao;

import com.landawn.abacus.dao.CrudDaoL;
import com.landawn.abacus.samples.entity.Address;
import com.landawn.abacus.util.SQLBuilder;

public interface AddressDao extends CrudDaoL<Address, SQLBuilder.PSC, AddressDao> {
}