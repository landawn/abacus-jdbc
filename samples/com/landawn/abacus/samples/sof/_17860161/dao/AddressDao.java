package com.landawn.abacus.samples.sof._17860161.dao;

import com.landawn.abacus.dao.CrudDaoL;
import com.landawn.abacus.dao.JoinEntityHelper;
import com.landawn.abacus.samples.sof._17860161.entity.Address;
import com.landawn.abacus.util.SQLBuilder;

public interface AddressDao extends CrudDaoL<Address, SQLBuilder.PSC, AddressDao>, JoinEntityHelper<Address, SQLBuilder.PSC, AddressDao> {
}