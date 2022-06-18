package com.landawn.abacus.samples;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import com.landawn.abacus.samples.entity.User;
import com.landawn.abacus.util.N;

class MyBatisTest {

    static final SqlSessionFactory sqlSessionFactory;

    static {
        TransactionFactory transactionFactory = new JdbcTransactionFactory();
        Environment environment = new Environment("development", transactionFactory, JdbcTest.dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(UserMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void test_mapper() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            User user = User.builder().id(100).firstName("Forrest").lastName("Gump").email("123@email.com").build();
            mapper.insertUser(user);

            User dbUser = mapper.selectUser(100);
            N.println(dbUser);
        }
    }

    public interface UserMapper {
        @Insert("INSERT INTO user (id, first_name, last_name, email) VALUES (#{id}, #{firstName}, #{lastName}, #{email})")
        long insertUser(User user);

        @Select("SELECT * FROM user WHERE id = #{id}")
        User selectUser(int id);
    }

}
