package com.landawn.abacus.dao;

import com.landawn.abacus.annotation.Beta;
import com.landawn.abacus.util.SQLBuilder;

@Beta
public interface UncheckedReadOnlyCrudDaoL<T, SB extends SQLBuilder, TD extends UncheckedReadOnlyCrudDaoL<T, SB, TD>>
        extends UncheckedReadOnlyCrudDao<T, Long, SB, TD>, UncheckedNoUpdateCrudDaoL<T, SB, TD> {
}