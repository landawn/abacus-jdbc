package com.landawn.abacus.samples.entity;

import java.util.List;

/**
 * Auto-generated class for property(field) name table for classes: {@code [Address, Device, Employee, EmployeeProject, ImmutableUser, Project, User]}
 */
public interface s { // NOSONAR

    /** Property(field) name {@code "address"} for classes: {@code [ImmutableUser, User]} */
    String address = "address";

    /** Property(field) name {@code "address2"} for classes: {@code [ImmutableUser, User]} */
    String address2 = "address2";

    /** Property(field) name {@code "city"} for classes: {@code [Address]} */
    String city = "city";

    /** Property(field) name {@code "createTime"} for classes: {@code [ImmutableUser, User]} */
    String createTime = "createTime";

    /** Property(field) name {@code "devices"} for classes: {@code [ImmutableUser, User]} */
    String devices = "devices";

    /** Property(field) name {@code "devices2"} for classes: {@code [ImmutableUser, User]} */
    String devices2 = "devices2";

    /** Property(field) name {@code "email"} for classes: {@code [ImmutableUser, User]} */
    String email = "email";

    /** Property(field) name {@code "employeeId"} for classes: {@code [Employee, EmployeeProject]} */
    String employeeId = "employeeId";

    /** Property(field) name {@code "employees"} for classes: {@code [Project]} */
    String employees = "employees";

    /** Property(field) name {@code "firstName"} for classes: {@code [Employee, ImmutableUser, User]} */
    String firstName = "firstName";

    /** Property(field) name {@code "id"} for classes: {@code [Address, Device, ImmutableUser, User]} */
    String id = "id";

    /** Property(field) name {@code "lastName"} for classes: {@code [Employee, ImmutableUser, User]} */
    String lastName = "lastName";

    /** Property(field) name {@code "manufacture"} for classes: {@code [Device]} */
    String manufacture = "manufacture";

    /** Property(field) name {@code "model"} for classes: {@code [Device]} */
    String model = "model";

    /** Property(field) name {@code "nickName"} for classes: {@code [ImmutableUser, User]} */
    String nickName = "nickName";

    /** Property(field) name {@code "projectId"} for classes: {@code [EmployeeProject, Project]} */
    String projectId = "projectId";

    /** Property(field) name {@code "projects"} for classes: {@code [Employee]} */
    String projects = "projects";

    /** Property(field) name {@code "startDate"} for classes: {@code [Project]} */
    String startDate = "startDate";

    /** Property(field) name {@code "street"} for classes: {@code [Address]} */
    String street = "street";

    /** Property(field) name {@code "title"} for classes: {@code [Project]} */
    String title = "title";

    /** Property(field) name {@code "userId"} for classes: {@code [Address, Device]} */
    String userId = "userId";

    /** Unmodifiable property(field) name list for class: {@code "Project"}. */
    List<String> projectPropNameList = List.of(employees, projectId, startDate, title);

    /** Unmodifiable property(field) name list for class: {@code "Employee"}. */
    List<String> employeePropNameList = List.of(employeeId, firstName, lastName, projects);

    /** Unmodifiable property(field) name list for class: {@code "User"}. */
    List<String> userPropNameList = List.of(address, address2, createTime, devices, devices2, email, firstName, id, lastName, nickName);

    /** Unmodifiable property(field) name list for class: {@code "Address"}. */
    List<String> addressPropNameList = List.of(city, id, street, userId);

    /** Unmodifiable property(field) name list for class: {@code "Device"}. */
    List<String> devicePropNameList = List.of(id, manufacture, model, userId);

    /** Unmodifiable property(field) name list for class: {@code "ImmutableUser"}. */
    List<String> immutableUserPropNameList = List.of(address, address2, createTime, devices, devices2, email, firstName, id, lastName, nickName);

    /** Unmodifiable property(field) name list for class: {@code "EmployeeProject"}. */
    List<String> employeeProjectPropNameList = List.of(employeeId, projectId);

    /**
     * Auto-generated class for lower case property(field) name table for classes: {@code [Address, Device, Employee, EmployeeProject, ImmutableUser, Project, User]}
     */
    public interface sl { // NOSONAR

        /** Property(field) name in lower case concatenated with underscore: {@code "address"} for classes: {@code [ImmutableUser, User]} */
        String address = "address";

        /** Property(field) name in lower case concatenated with underscore: {@code "address2"} for classes: {@code [ImmutableUser, User]} */
        String address2 = "address2";

        /** Property(field) name in lower case concatenated with underscore: {@code "city"} for classes: {@code [Address]} */
        String city = "city";

        /** Property(field) name in lower case concatenated with underscore: {@code "create_time"} for classes: {@code [ImmutableUser, User]} */
        String createTime = "create_time";

        /** Property(field) name in lower case concatenated with underscore: {@code "devices"} for classes: {@code [ImmutableUser, User]} */
        String devices = "devices";

        /** Property(field) name in lower case concatenated with underscore: {@code "devices2"} for classes: {@code [ImmutableUser, User]} */
        String devices2 = "devices2";

        /** Property(field) name in lower case concatenated with underscore: {@code "email"} for classes: {@code [ImmutableUser, User]} */
        String email = "email";

        /** Property(field) name in lower case concatenated with underscore: {@code "employee_id"} for classes: {@code [Employee, EmployeeProject]} */
        String employeeId = "employee_id";

        /** Property(field) name in lower case concatenated with underscore: {@code "employees"} for classes: {@code [Project]} */
        String employees = "employees";

        /** Property(field) name in lower case concatenated with underscore: {@code "first_name"} for classes: {@code [Employee, ImmutableUser, User]} */
        String firstName = "first_name";

        /** Property(field) name in lower case concatenated with underscore: {@code "id"} for classes: {@code [Address, Device, ImmutableUser, User]} */
        String id = "id";

        /** Property(field) name in lower case concatenated with underscore: {@code "last_name"} for classes: {@code [Employee, ImmutableUser, User]} */
        String lastName = "last_name";

        /** Property(field) name in lower case concatenated with underscore: {@code "manufacture"} for classes: {@code [Device]} */
        String manufacture = "manufacture";

        /** Property(field) name in lower case concatenated with underscore: {@code "model"} for classes: {@code [Device]} */
        String model = "model";

        /** Property(field) name in lower case concatenated with underscore: {@code "nick_name"} for classes: {@code [ImmutableUser, User]} */
        String nickName = "nick_name";

        /** Property(field) name in lower case concatenated with underscore: {@code "project_id"} for classes: {@code [EmployeeProject, Project]} */
        String projectId = "project_id";

        /** Property(field) name in lower case concatenated with underscore: {@code "projects"} for classes: {@code [Employee]} */
        String projects = "projects";

        /** Property(field) name in lower case concatenated with underscore: {@code "start_date"} for classes: {@code [Project]} */
        String startDate = "start_date";

        /** Property(field) name in lower case concatenated with underscore: {@code "street"} for classes: {@code [Address]} */
        String street = "street";

        /** Property(field) name in lower case concatenated with underscore: {@code "title"} for classes: {@code [Project]} */
        String title = "title";

        /** Property(field) name in lower case concatenated with underscore: {@code "user_id"} for classes: {@code [Address, Device]} */
        String userId = "user_id";

        /** Unmodifiable property(field) name list for class: {@code "Project"}. */
        List<String> projectPropNameList = List.of(employees, projectId, startDate, title);

        /** Unmodifiable property(field) name list for class: {@code "Employee"}. */
        List<String> employeePropNameList = List.of(employeeId, firstName, lastName, projects);

        /** Unmodifiable property(field) name list for class: {@code "User"}. */
        List<String> userPropNameList = List.of(address, address2, createTime, devices, devices2, email, firstName, id, lastName, nickName);

        /** Unmodifiable property(field) name list for class: {@code "Address"}. */
        List<String> addressPropNameList = List.of(city, id, street, userId);

        /** Unmodifiable property(field) name list for class: {@code "Device"}. */
        List<String> devicePropNameList = List.of(id, manufacture, model, userId);

        /** Unmodifiable property(field) name list for class: {@code "ImmutableUser"}. */
        List<String> immutableUserPropNameList = List.of(address, address2, createTime, devices, devices2, email, firstName, id, lastName, nickName);

        /** Unmodifiable property(field) name list for class: {@code "EmployeeProject"}. */
        List<String> employeeProjectPropNameList = List.of(employeeId, projectId);

    }

    /**
     * Auto-generated class for upper case property(field) name table for classes: {@code [Address, Device, Employee, EmployeeProject, ImmutableUser, Project, User]}
     */
    public interface su { // NOSONAR

        /** Property(field) name in upper case concatenated with underscore: {@code "ADDRESS"} for classes: {@code [ImmutableUser, User]} */
        String address = "ADDRESS";

        /** Property(field) name in upper case concatenated with underscore: {@code "ADDRESS2"} for classes: {@code [ImmutableUser, User]} */
        String address2 = "ADDRESS2";

        /** Property(field) name in upper case concatenated with underscore: {@code "CITY"} for classes: {@code [Address]} */
        String city = "CITY";

        /** Property(field) name in upper case concatenated with underscore: {@code "CREATE_TIME"} for classes: {@code [ImmutableUser, User]} */
        String createTime = "CREATE_TIME";

        /** Property(field) name in upper case concatenated with underscore: {@code "DEVICES"} for classes: {@code [ImmutableUser, User]} */
        String devices = "DEVICES";

        /** Property(field) name in upper case concatenated with underscore: {@code "DEVICES2"} for classes: {@code [ImmutableUser, User]} */
        String devices2 = "DEVICES2";

        /** Property(field) name in upper case concatenated with underscore: {@code "EMAIL"} for classes: {@code [ImmutableUser, User]} */
        String email = "EMAIL";

        /** Property(field) name in upper case concatenated with underscore: {@code "EMPLOYEE_ID"} for classes: {@code [Employee, EmployeeProject]} */
        String employeeId = "EMPLOYEE_ID";

        /** Property(field) name in upper case concatenated with underscore: {@code "EMPLOYEES"} for classes: {@code [Project]} */
        String employees = "EMPLOYEES";

        /** Property(field) name in upper case concatenated with underscore: {@code "FIRST_NAME"} for classes: {@code [Employee, ImmutableUser, User]} */
        String firstName = "FIRST_NAME";

        /** Property(field) name in upper case concatenated with underscore: {@code "ID"} for classes: {@code [Address, Device, ImmutableUser, User]} */
        String id = "ID";

        /** Property(field) name in upper case concatenated with underscore: {@code "LAST_NAME"} for classes: {@code [Employee, ImmutableUser, User]} */
        String lastName = "LAST_NAME";

        /** Property(field) name in upper case concatenated with underscore: {@code "MANUFACTURE"} for classes: {@code [Device]} */
        String manufacture = "MANUFACTURE";

        /** Property(field) name in upper case concatenated with underscore: {@code "MODEL"} for classes: {@code [Device]} */
        String model = "MODEL";

        /** Property(field) name in upper case concatenated with underscore: {@code "NICK_NAME"} for classes: {@code [ImmutableUser, User]} */
        String nickName = "NICK_NAME";

        /** Property(field) name in upper case concatenated with underscore: {@code "PROJECT_ID"} for classes: {@code [EmployeeProject, Project]} */
        String projectId = "PROJECT_ID";

        /** Property(field) name in upper case concatenated with underscore: {@code "PROJECTS"} for classes: {@code [Employee]} */
        String projects = "PROJECTS";

        /** Property(field) name in upper case concatenated with underscore: {@code "START_DATE"} for classes: {@code [Project]} */
        String startDate = "START_DATE";

        /** Property(field) name in upper case concatenated with underscore: {@code "STREET"} for classes: {@code [Address]} */
        String street = "STREET";

        /** Property(field) name in upper case concatenated with underscore: {@code "TITLE"} for classes: {@code [Project]} */
        String title = "TITLE";

        /** Property(field) name in upper case concatenated with underscore: {@code "USER_ID"} for classes: {@code [Address, Device]} */
        String userId = "USER_ID";

        /** Unmodifiable property(field) name list for class: {@code "Project"}. */
        List<String> projectPropNameList = List.of(employees, projectId, startDate, title);

        /** Unmodifiable property(field) name list for class: {@code "Employee"}. */
        List<String> employeePropNameList = List.of(employeeId, firstName, lastName, projects);

        /** Unmodifiable property(field) name list for class: {@code "User"}. */
        List<String> userPropNameList = List.of(address, address2, createTime, devices, devices2, email, firstName, id, lastName, nickName);

        /** Unmodifiable property(field) name list for class: {@code "Address"}. */
        List<String> addressPropNameList = List.of(city, id, street, userId);

        /** Unmodifiable property(field) name list for class: {@code "Device"}. */
        List<String> devicePropNameList = List.of(id, manufacture, model, userId);

        /** Unmodifiable property(field) name list for class: {@code "ImmutableUser"}. */
        List<String> immutableUserPropNameList = List.of(address, address2, createTime, devices, devices2, email, firstName, id, lastName, nickName);

        /** Unmodifiable property(field) name list for class: {@code "EmployeeProject"}. */
        List<String> employeeProjectPropNameList = List.of(employeeId, projectId);

    }

    /**
     * Auto-generated class for function property(field) name table for classes: {@code [Address, Device, Employee, EmployeeProject, ImmutableUser, Project, User]}
     */
    public interface f { // NOSONAR

        /** Function property(field) name {@code "min(city)"} for classes: {@code [Address]} */
        String min_city = "min(city)";

        /** Function property(field) name {@code "min(createTime)"} for classes: {@code [ImmutableUser, User]} */
        String min_createTime = "min(createTime)";

        /** Function property(field) name {@code "min(email)"} for classes: {@code [ImmutableUser, User]} */
        String min_email = "min(email)";

        /** Function property(field) name {@code "min(firstName)"} for classes: {@code [Employee, ImmutableUser, User]} */
        String min_firstName = "min(firstName)";

        /** Function property(field) name {@code "min(lastName)"} for classes: {@code [Employee, ImmutableUser, User]} */
        String min_lastName = "min(lastName)";

        /** Function property(field) name {@code "min(manufacture)"} for classes: {@code [Device]} */
        String min_manufacture = "min(manufacture)";

        /** Function property(field) name {@code "min(model)"} for classes: {@code [Device]} */
        String min_model = "min(model)";

        /** Function property(field) name {@code "min(nickName)"} for classes: {@code [ImmutableUser, User]} */
        String min_nickName = "min(nickName)";

        /** Function property(field) name {@code "min(startDate)"} for classes: {@code [Project]} */
        String min_startDate = "min(startDate)";

        /** Function property(field) name {@code "min(street)"} for classes: {@code [Address]} */
        String min_street = "min(street)";

        /** Function property(field) name {@code "min(title)"} for classes: {@code [Project]} */
        String min_title = "min(title)";

        /** Function property(field) name {@code "max(city)"} for classes: {@code [Address]} */
        String max_city = "max(city)";

        /** Function property(field) name {@code "max(createTime)"} for classes: {@code [ImmutableUser, User]} */
        String max_createTime = "max(createTime)";

        /** Function property(field) name {@code "max(email)"} for classes: {@code [ImmutableUser, User]} */
        String max_email = "max(email)";

        /** Function property(field) name {@code "max(firstName)"} for classes: {@code [Employee, ImmutableUser, User]} */
        String max_firstName = "max(firstName)";

        /** Function property(field) name {@code "max(lastName)"} for classes: {@code [Employee, ImmutableUser, User]} */
        String max_lastName = "max(lastName)";

        /** Function property(field) name {@code "max(manufacture)"} for classes: {@code [Device]} */
        String max_manufacture = "max(manufacture)";

        /** Function property(field) name {@code "max(model)"} for classes: {@code [Device]} */
        String max_model = "max(model)";

        /** Function property(field) name {@code "max(nickName)"} for classes: {@code [ImmutableUser, User]} */
        String max_nickName = "max(nickName)";

        /** Function property(field) name {@code "max(startDate)"} for classes: {@code [Project]} */
        String max_startDate = "max(startDate)";

        /** Function property(field) name {@code "max(street)"} for classes: {@code [Address]} */
        String max_street = "max(street)";

        /** Function property(field) name {@code "max(title)"} for classes: {@code [Project]} */
        String max_title = "max(title)";

    }

}
