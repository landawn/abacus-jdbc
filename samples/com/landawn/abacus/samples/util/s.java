package com.landawn.abacus.samples.util;

/*
 * Auto-generated class for property name table by abacus-jdbc for classes: [class com.landawn.abacus.samples.entity.Address$AddressBuilder, class com.landawn.abacus.samples.entity.Address, class com.landawn.abacus.samples.entity.Device$DeviceBuilder, class com.landawn.abacus.samples.entity.Device, class com.landawn.abacus.samples.entity.Employee$EmployeeBuilder, class com.landawn.abacus.samples.entity.Employee, class com.landawn.abacus.samples.entity.EmployeeProject$EmployeeProjectBuilder, class com.landawn.abacus.samples.entity.EmployeeProject, class com.landawn.abacus.samples.entity.ImmutableUser$ImmutableUserBuilder, class com.landawn.abacus.samples.entity.ImmutableUser, class com.landawn.abacus.samples.entity.Project$ProjectBuilder, class com.landawn.abacus.samples.entity.Project, class com.landawn.abacus.samples.entity.User$UserBuilder, class com.landawn.abacus.samples.entity.User, class codes.entity.Account$AccountBuilder, class codes.entity.Account, class codes.entity.User$UserBuilder, class codes.entity.User, class codes.entity.UserQueryAllResult$UserQueryAllResultBuilder, class codes.entity.UserQueryAllResult]
 */
public interface s { // NOSONAR

    /** Property name {@code "address"} for classes: {@code [ImmutableUser, User]} */
    String address = "address";

    /** Property name {@code "address2"} for classes: {@code [ImmutableUser, User]} */
    String address2 = "address2";

    /** Property name {@code "city"} for classes: {@code [Address]} */
    String city = "city";

    /** Property name {@code "createTime"} for classes: {@code [Account, ImmutableUser, User, User.create_time, UserQueryAllResult.create_time]} */
    String createTime = "createTime";

    /** Property name {@code "devices"} for classes: {@code [ImmutableUser, User]} */
    String devices = "devices";

    /** Property name {@code "devices2"} for classes: {@code [ImmutableUser, User]} */
    String devices2 = "devices2";

    /** Property name {@code "email"} for classes: {@code [ImmutableUser, User, User, UserQueryAllResult]} */
    String email = "email";

    /** Property name {@code "emailAddress"} for classes: {@code [Account]} */
    String emailAddress = "emailAddress";

    /** Property name {@code "employeeId"} for classes: {@code [Employee, EmployeeProject]} */
    String employeeId = "employeeId";

    /** Property name {@code "employees"} for classes: {@code [Project]} */
    String employees = "employees";

    /** Property name {@code "firstName"} for classes: {@code [Account, Employee, ImmutableUser, User, User, UserQueryAllResult]} */
    String firstName = "firstName";

    /** Property name {@code "id"} for classes: {@code [Account, Address, Device, ImmutableUser, User, User, UserQueryAllResult]} */
    String id = "id";

    /** Property name {@code "lastName"} for classes: {@code [Account, Employee, ImmutableUser, User, User, UserQueryAllResult]} */
    String lastName = "lastName";

    /** Property name {@code "manufacture"} for classes: {@code [Device]} */
    String manufacture = "manufacture";

    /** Property name {@code "model"} for classes: {@code [Device]} */
    String model = "model";

    /** Property name {@code "nickName"} for classes: {@code [ImmutableUser, User]} */
    String nickName = "nickName";

    /** Property name {@code "projectId"} for classes: {@code [EmployeeProject, Project]} */
    String projectId = "projectId";

    /** Property name {@code "projects"} for classes: {@code [Employee]} */
    String projects = "projects";

    /** Property name {@code "prop1"} for classes: {@code [User, UserQueryAllResult]} */
    String prop1 = "prop1";

    /** Property name {@code "street"} for classes: {@code [Address]} */
    String street = "street";

    /** Property name {@code "title"} for classes: {@code [Project]} */
    String title = "title";

    /** Property name {@code "userId"} for classes: {@code [Address, Device]} */
    String userId = "userId";

    /** Property name {@code "userSet"} for classes: {@code [UserQueryAllResult]} */
    String userSet = "userSet";

    /** Property name {@code "users"} for classes: {@code [UserQueryAllResult]} */
    String users = "users";

    /*
     * Auto-generated class for property name table by abacus-jdbc for classes:     [class com.landawn.abacus.samples.entity.Address$AddressBuilder, class com.landawn.abacus.samples.entity.Address, class com.landawn.abacus.samples.entity.Device$DeviceBuilder, class com.landawn.abacus.samples.entity.Device, class com.landawn.abacus.samples.entity.Employee$EmployeeBuilder, class com.landawn.abacus.samples.entity.Employee, class com.landawn.abacus.samples.entity.EmployeeProject$EmployeeProjectBuilder, class com.landawn.abacus.samples.entity.EmployeeProject, class com.landawn.abacus.samples.entity.ImmutableUser$ImmutableUserBuilder, class com.landawn.abacus.samples.entity.ImmutableUser, class com.landawn.abacus.samples.entity.Project$ProjectBuilder, class com.landawn.abacus.samples.entity.Project, class com.landawn.abacus.samples.entity.User$UserBuilder, class com.landawn.abacus.samples.entity.User, class codes.entity.Account$AccountBuilder, class codes.entity.Account, class codes.entity.User$UserBuilder, class codes.entity.User, class codes.entity.UserQueryAllResult$UserQueryAllResultBuilder, class codes.entity.UserQueryAllResult]
     */
    public interface sf     {     // NOSONAR

        /** Function property name {@code "min(city)"} for classes: {@code [Address]} */
        String min_city = "min(city)";

        /** Function property name {@code "min(createTime)"} for classes: {@code [Account, ImmutableUser, User, User, UserQueryAllResult]} */
        String min_createTime = "min(createTime)";

        /** Function property name {@code "min(email)"} for classes: {@code [ImmutableUser, User, User, UserQueryAllResult]} */
        String min_email = "min(email)";

        /** Function property name {@code "min(emailAddress)"} for classes: {@code [Account]} */
        String min_emailAddress = "min(emailAddress)";

        /** Function property name {@code "min(firstName)"} for classes: {@code [Account, Employee, ImmutableUser, User, User, UserQueryAllResult]} */
        String min_firstName = "min(firstName)";

        /** Function property name {@code "min(id)"} for classes: {@code [User, UserQueryAllResult]} */
        String min_id = "min(id)";

        /** Function property name {@code "min(lastName)"} for classes: {@code [Account, Employee, ImmutableUser, User, User, UserQueryAllResult]} */
        String min_lastName = "min(lastName)";

        /** Function property name {@code "min(manufacture)"} for classes: {@code [Device]} */
        String min_manufacture = "min(manufacture)";

        /** Function property name {@code "min(model)"} for classes: {@code [Device]} */
        String min_model = "min(model)";

        /** Function property name {@code "min(nickName)"} for classes: {@code [ImmutableUser, User]} */
        String min_nickName = "min(nickName)";

        /** Function property name {@code "min(prop1)"} for classes: {@code [User, UserQueryAllResult]} */
        String min_prop1 = "min(prop1)";

        /** Function property name {@code "min(street)"} for classes: {@code [Address]} */
        String min_street = "min(street)";

        /** Function property name {@code "min(title)"} for classes: {@code [Project]} */
        String min_title = "min(title)";

        /** Function property name {@code "max(city)"} for classes: {@code [Address]} */
        String max_city = "max(city)";

        /** Function property name {@code "max(createTime)"} for classes: {@code [Account, ImmutableUser, User, User, UserQueryAllResult]} */
        String max_createTime = "max(createTime)";

        /** Function property name {@code "max(email)"} for classes: {@code [ImmutableUser, User, User, UserQueryAllResult]} */
        String max_email = "max(email)";

        /** Function property name {@code "max(emailAddress)"} for classes: {@code [Account]} */
        String max_emailAddress = "max(emailAddress)";

        /** Function property name {@code "max(firstName)"} for classes: {@code [Account, Employee, ImmutableUser, User, User, UserQueryAllResult]} */
        String max_firstName = "max(firstName)";

        /** Function property name {@code "max(id)"} for classes: {@code [User, UserQueryAllResult]} */
        String max_id = "max(id)";

        /** Function property name {@code "max(lastName)"} for classes: {@code [Account, Employee, ImmutableUser, User, User, UserQueryAllResult]} */
        String max_lastName = "max(lastName)";

        /** Function property name {@code "max(manufacture)"} for classes: {@code [Device]} */
        String max_manufacture = "max(manufacture)";

        /** Function property name {@code "max(model)"} for classes: {@code [Device]} */
        String max_model = "max(model)";

        /** Function property name {@code "max(nickName)"} for classes: {@code [ImmutableUser, User]} */
        String max_nickName = "max(nickName)";

        /** Function property name {@code "max(prop1)"} for classes: {@code [User, UserQueryAllResult]} */
        String max_prop1 = "max(prop1)";

        /** Function property name {@code "max(street)"} for classes: {@code [Address]} */
        String max_street = "max(street)";

        /** Function property name {@code "max(title)"} for classes: {@code [Project]} */
        String max_title = "max(title)";

    }

}

