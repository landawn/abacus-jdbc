package com.landawn.abacus.samples.sof._17860161;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

import com.landawn.abacus.exception.UncheckedSQLException;
import com.landawn.abacus.samples.sof._17860161.dao.AddressDao;
import com.landawn.abacus.samples.sof._17860161.dao.CityDao;
import com.landawn.abacus.samples.sof._17860161.dao.PersonDao;
import com.landawn.abacus.samples.sof._17860161.entity.Address;
import com.landawn.abacus.samples.sof._17860161.entity.City;
import com.landawn.abacus.samples.sof._17860161.entity.Person;
import com.landawn.abacus.util.JdbcUtil;

public class _17860161 {

    static final DataSource dataSource = JdbcUtil.createHikariDataSource("jdbc:h2:~/test", "sa", "");
    static final PersonDao personDao = JdbcUtil.createDao(PersonDao.class, dataSource);
    static final AddressDao addressDao = JdbcUtil.createDao(AddressDao.class, dataSource);
    static final CityDao cityDao = JdbcUtil.createDao(CityDao.class, dataSource);

    // initialize DB schema.
    static {
        try {
            final String sql_user_drop_table = "DROP TABLE IF EXISTS person";
            final String sql_user_creat_table = "CREATE TABLE IF NOT EXISTS person (" //
                    + "id bigint(20) NOT NULL AUTO_INCREMENT PRIMARY KEY, " //
                    + "first_name varchar(32) NOT NULL, " //
                    + "last_name varchar(32) NOT NULL, " // 
                    + "email varchar(32), " //
                    + "create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP)";

            JdbcUtil.executeUpdate(dataSource, sql_user_drop_table);
            JdbcUtil.executeUpdate(dataSource, sql_user_creat_table);

            final String sql_address_drop_table = "DROP TABLE IF EXISTS address";
            final String sql_address_creat_table = "CREATE TABLE IF NOT EXISTS address (" //
                    + "id bigint(20) NOT NULL AUTO_INCREMENT PRIMARY KEY, " //
                    + "street varchar(128) NOT NULL, " //
                    + "city_id int NOT NULL, " //
                    + "state varchar(32) NOT NULL, " //
                    + "zip_code varchar(16) NOT NULL, " //
                    + "person_id bigint(20), " //
                    + "FOREIGN KEY (person_id) REFERENCES person(id) ON DELETE CASCADE)";

            JdbcUtil.executeUpdate(dataSource, sql_address_drop_table);
            JdbcUtil.executeUpdate(dataSource, sql_address_creat_table);

            final String sql_city_drop_table = "DROP TABLE IF EXISTS city";
            final String sql_city_creat_table = "CREATE TABLE IF NOT EXISTS city (" //
                    + "id int(11) NOT NULL AUTO_INCREMENT PRIMARY KEY, " //
                    + "name varchar(32) DEFAULT NULL)";

            JdbcUtil.executeUpdate(dataSource, sql_city_drop_table);
            JdbcUtil.executeUpdate(dataSource, sql_city_creat_table);

        } catch (SQLException e) {
            throw new UncheckedSQLException(e);
        }
    }

    @Test
    public void crud() throws SQLException {
        Person person = Person.builder().firstName("jdbc").lastName("whoiam").build();
        long personId = personDao.insert(person);

        City city = new City(123, "Sunnyvalue");
        int cityId = cityDao.insert(city);

        Address address = Address.builder().street("1130 Ky").cityId(cityId).state("CA").zipCode("95677").personId(personId).build();
        addressDao.insert(address);

        Person personFromDB = personDao.gett(personId);
        System.out.println(personFromDB);

        personDao.loadJoinEntitiesIfNull(personFromDB, Address.class);

        System.out.println(personFromDB);
    }
}
