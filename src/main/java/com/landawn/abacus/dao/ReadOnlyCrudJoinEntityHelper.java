package com.landawn.abacus.dao;

import com.landawn.abacus.util.SQLBuilder;

public interface ReadOnlyCrudJoinEntityHelper<T, ID, SB extends SQLBuilder, TD extends CrudDao<T, ID, SB, TD>>
        extends ReadOnlyJoinEntityHelper<T, SB, TD>, CrudJoinEntityHelper<T, ID, SB, TD> {

}