package com.landawn.abacus.jdbc;

import static org.mockito.Mockito.when;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.landawn.abacus.query.ParsedSql;
import com.landawn.abacus.TestBase;

abstract class JdbcTestSupport extends TestBase {

    @Mock
    protected ResultSet mockResultSet;

    @Mock
    protected PreparedStatement mockPreparedStatement;

    @Mock
    protected CallableStatement mockCallableStatement;

    @Mock
    protected ResultSetMetaData mockResultSetMetaData;

    @Mock
    protected AbstractQuery mockAbstractQuery;

    @Mock
    protected ParsedSql mockParsedSql;

    protected AutoCloseable mockitoCloseable;

    @BeforeEach
    public void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        when(mockResultSet.getMetaData()).thenReturn(mockResultSetMetaData);
        when(mockResultSetMetaData.getColumnCount()).thenReturn(3);
        when(mockResultSetMetaData.getColumnLabel(1)).thenReturn("id");
        when(mockResultSetMetaData.getColumnLabel(2)).thenReturn("name");
        when(mockResultSetMetaData.getColumnLabel(3)).thenReturn("age");
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (mockitoCloseable != null) {
            mockitoCloseable.close();
        }
    }

    // Test entity class for testing
    public static class TestEntity {
        private Long id;
        private String name;
        private Integer age;

        public TestEntity() {
        }

        public TestEntity(Long id, String name, Integer age) {
            this.id = id;
            this.name = name;
            this.age = age;
        }

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }
    }

    // Entity with camelCase field to exercise column2FieldNameMap lookup path
    public static class CamelCaseEntity {
        private String firstName;
        private Integer totalCount;

        public String getFirstName() {
            return firstName;
        }

        public void setFirstName(final String firstName) {
            this.firstName = firstName;
        }

        public Integer getTotalCount() {
            return totalCount;
        }

        public void setTotalCount(final Integer totalCount) {
            this.totalCount = totalCount;
        }
    }
}
