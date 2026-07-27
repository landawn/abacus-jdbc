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

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;address&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String address = "address";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;address2&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String address2 = "address2";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;city&quot;</code> for classes: {@code [Address]} */
        String city = "city";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;create_time&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String createTime = "create_time";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;devices&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String devices = "devices";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;devices2&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String devices2 = "devices2";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;email&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String email = "email";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;employee_id&quot;</code> for classes: {@code [Employee, EmployeeProject]} */
        String employeeId = "employee_id";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;employees&quot;</code> for classes: {@code [Project]} */
        String employees = "employees";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;first_name&quot;</code> for classes: {@code [Employee, ImmutableUser, User]} */
        String firstName = "first_name";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;id&quot;</code> for classes: {@code [Address, Device, ImmutableUser, User]} */
        String id = "id";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;last_name&quot;</code> for classes: {@code [Employee, ImmutableUser, User]} */
        String lastName = "last_name";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;manufacture&quot;</code> for classes: {@code [Device]} */
        String manufacture = "manufacture";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;model&quot;</code> for classes: {@code [Device]} */
        String model = "model";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;nick_name&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String nickName = "nick_name";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;project_id&quot;</code> for classes: {@code [EmployeeProject, Project]} */
        String projectId = "project_id";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;projects&quot;</code> for classes: {@code [Employee]} */
        String projects = "projects";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;start_date&quot;</code> for classes: {@code [Project]} */
        String startDate = "start_date";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;street&quot;</code> for classes: {@code [Address]} */
        String street = "street";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;title&quot;</code> for classes: {@code [Project]} */
        String title = "title";

        /** Property(field) name in lower case concatenated with underscore: <code>&quot;user_id&quot;</code> for classes: {@code [Address, Device]} */
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

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;ADDRESS&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String address = "ADDRESS";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;ADDRESS2&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String address2 = "ADDRESS2";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;CITY&quot;</code> for classes: {@code [Address]} */
        String city = "CITY";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;CREATE_TIME&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String createTime = "CREATE_TIME";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;DEVICES&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String devices = "DEVICES";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;DEVICES2&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String devices2 = "DEVICES2";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;EMAIL&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String email = "EMAIL";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;EMPLOYEE_ID&quot;</code> for classes: {@code [Employee, EmployeeProject]} */
        String employeeId = "EMPLOYEE_ID";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;EMPLOYEES&quot;</code> for classes: {@code [Project]} */
        String employees = "EMPLOYEES";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;FIRST_NAME&quot;</code> for classes: {@code [Employee, ImmutableUser, User]} */
        String firstName = "FIRST_NAME";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;ID&quot;</code> for classes: {@code [Address, Device, ImmutableUser, User]} */
        String id = "ID";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;LAST_NAME&quot;</code> for classes: {@code [Employee, ImmutableUser, User]} */
        String lastName = "LAST_NAME";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;MANUFACTURE&quot;</code> for classes: {@code [Device]} */
        String manufacture = "MANUFACTURE";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;MODEL&quot;</code> for classes: {@code [Device]} */
        String model = "MODEL";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;NICK_NAME&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String nickName = "NICK_NAME";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;PROJECT_ID&quot;</code> for classes: {@code [EmployeeProject, Project]} */
        String projectId = "PROJECT_ID";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;PROJECTS&quot;</code> for classes: {@code [Employee]} */
        String projects = "PROJECTS";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;START_DATE&quot;</code> for classes: {@code [Project]} */
        String startDate = "START_DATE";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;STREET&quot;</code> for classes: {@code [Address]} */
        String street = "STREET";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;TITLE&quot;</code> for classes: {@code [Project]} */
        String title = "TITLE";

        /** Property(field) name in upper case concatenated with underscore: <code>&quot;USER_ID&quot;</code> for classes: {@code [Address, Device]} */
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

        /** Function property(field) name <code>&quot;min(city)&quot;</code> for classes: {@code [Address]} */
        String min_city = "min(city)";

        /** Function property(field) name <code>&quot;min(createTime)&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String min_createTime = "min(createTime)";

        /** Function property(field) name <code>&quot;min(email)&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String min_email = "min(email)";

        /** Function property(field) name <code>&quot;min(employeeId)&quot;</code> for classes: {@code [Employee, EmployeeProject]} */
        String min_employeeId = "min(employeeId)";

        /** Function property(field) name <code>&quot;min(firstName)&quot;</code> for classes: {@code [Employee, ImmutableUser, User]} */
        String min_firstName = "min(firstName)";

        /** Function property(field) name <code>&quot;min(id)&quot;</code> for classes: {@code [Address, Device, ImmutableUser, User]} */
        String min_id = "min(id)";

        /** Function property(field) name <code>&quot;min(lastName)&quot;</code> for classes: {@code [Employee, ImmutableUser, User]} */
        String min_lastName = "min(lastName)";

        /** Function property(field) name <code>&quot;min(manufacture)&quot;</code> for classes: {@code [Device]} */
        String min_manufacture = "min(manufacture)";

        /** Function property(field) name <code>&quot;min(model)&quot;</code> for classes: {@code [Device]} */
        String min_model = "min(model)";

        /** Function property(field) name <code>&quot;min(nickName)&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String min_nickName = "min(nickName)";

        /** Function property(field) name <code>&quot;min(projectId)&quot;</code> for classes: {@code [EmployeeProject, Project]} */
        String min_projectId = "min(projectId)";

        /** Function property(field) name <code>&quot;min(startDate)&quot;</code> for classes: {@code [Project]} */
        String min_startDate = "min(startDate)";

        /** Function property(field) name <code>&quot;min(street)&quot;</code> for classes: {@code [Address]} */
        String min_street = "min(street)";

        /** Function property(field) name <code>&quot;min(title)&quot;</code> for classes: {@code [Project]} */
        String min_title = "min(title)";

        /** Function property(field) name <code>&quot;min(userId)&quot;</code> for classes: {@code [Address, Device]} */
        String min_userId = "min(userId)";

        /** Function property(field) name <code>&quot;max(city)&quot;</code> for classes: {@code [Address]} */
        String max_city = "max(city)";

        /** Function property(field) name <code>&quot;max(createTime)&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String max_createTime = "max(createTime)";

        /** Function property(field) name <code>&quot;max(email)&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String max_email = "max(email)";

        /** Function property(field) name <code>&quot;max(employeeId)&quot;</code> for classes: {@code [Employee, EmployeeProject]} */
        String max_employeeId = "max(employeeId)";

        /** Function property(field) name <code>&quot;max(firstName)&quot;</code> for classes: {@code [Employee, ImmutableUser, User]} */
        String max_firstName = "max(firstName)";

        /** Function property(field) name <code>&quot;max(id)&quot;</code> for classes: {@code [Address, Device, ImmutableUser, User]} */
        String max_id = "max(id)";

        /** Function property(field) name <code>&quot;max(lastName)&quot;</code> for classes: {@code [Employee, ImmutableUser, User]} */
        String max_lastName = "max(lastName)";

        /** Function property(field) name <code>&quot;max(manufacture)&quot;</code> for classes: {@code [Device]} */
        String max_manufacture = "max(manufacture)";

        /** Function property(field) name <code>&quot;max(model)&quot;</code> for classes: {@code [Device]} */
        String max_model = "max(model)";

        /** Function property(field) name <code>&quot;max(nickName)&quot;</code> for classes: {@code [ImmutableUser, User]} */
        String max_nickName = "max(nickName)";

        /** Function property(field) name <code>&quot;max(projectId)&quot;</code> for classes: {@code [EmployeeProject, Project]} */
        String max_projectId = "max(projectId)";

        /** Function property(field) name <code>&quot;max(startDate)&quot;</code> for classes: {@code [Project]} */
        String max_startDate = "max(startDate)";

        /** Function property(field) name <code>&quot;max(street)&quot;</code> for classes: {@code [Address]} */
        String max_street = "max(street)";

        /** Function property(field) name <code>&quot;max(title)&quot;</code> for classes: {@code [Project]} */
        String max_title = "max(title)";

        /** Function property(field) name <code>&quot;max(userId)&quot;</code> for classes: {@code [Address, Device]} */
        String max_userId = "max(userId)";

    }

}
