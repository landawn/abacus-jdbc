package com.landawn.abacus.samples.util;

/*
 * Auto-generated class for property name table by abacus-jdbc for classes: [class com.landawn.abacus.samples.entity.Address$AddressBuilder, class com.landawn.abacus.samples.entity.Address, class com.landawn.abacus.samples.entity.Device$DeviceBuilder, class com.landawn.abacus.samples.entity.Device, class com.landawn.abacus.samples.entity.Employee$EmployeeBuilder, class com.landawn.abacus.samples.entity.Employee, class com.landawn.abacus.samples.entity.EmployeeProject$EmployeeProjectBuilder, class com.landawn.abacus.samples.entity.EmployeeProject, class com.landawn.abacus.samples.entity.ImmutableUser$ImmutableUserBuilder, class com.landawn.abacus.samples.entity.ImmutableUser, class com.landawn.abacus.samples.entity.Project$ProjectBuilder, class com.landawn.abacus.samples.entity.Project, class com.landawn.abacus.samples.entity.User$UserBuilder, class com.landawn.abacus.samples.entity.User, class codes.entity.Account$AccountBuilder, class codes.entity.Account, class codes.entity.User$UserBuilder, class codes.entity.User, class codes.entity.UserQueryAllResult$UserQueryAllResultBuilder, class codes.entity.UserQueryAllResult]
 */
public interface s { // NOSONAR

    /** Property(field) name {@code "address"} for classes: {@code [ImmutableUser, User]} */
    String address = "address";

    /** Property(field) name {@code "address2"} for classes: {@code [ImmutableUser, User]} */
    String address2 = "address2";

    /** Property(field) name {@code "city"} for classes: {@code [Address]} */
    String city = "city";

    /** Property(field) name {@code "createTime"} for classes: {@code [Account, ImmutableUser, User, User.create_time, UserQueryAllResult.create_time]} */
    String createTime = "createTime";

    /** Property(field) name {@code "devices"} for classes: {@code [ImmutableUser, User]} */
    String devices = "devices";

    /** Property(field) name {@code "devices2"} for classes: {@code [ImmutableUser, User]} */
    String devices2 = "devices2";

    /** Property(field) name {@code "email"} for classes: {@code [ImmutableUser, User, User, UserQueryAllResult]} */
    String email = "email";

    /** Property(field) name {@code "emailAddress"} for classes: {@code [Account]} */
    String emailAddress = "emailAddress";

    /** Property(field) name {@code "employeeId"} for classes: {@code [Employee, EmployeeProject]} */
    String employeeId = "employeeId";

    /** Property(field) name {@code "employees"} for classes: {@code [Project]} */
    String employees = "employees";

    /** Property(field) name {@code "firstName"} for classes: {@code [Account, Employee, ImmutableUser, User, User, UserQueryAllResult]} */
    String firstName = "firstName";

    /** Property(field) name {@code "id"} for classes: {@code [Account, Address, Device, ImmutableUser, User, User, UserQueryAllResult]} */
    String id = "id";

    /** Property(field) name {@code "lastName"} for classes: {@code [Account, Employee, ImmutableUser, User, User, UserQueryAllResult]} */
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

    /** Property(field) name {@code "prop1"} for classes: {@code [User, UserQueryAllResult]} */
    String prop1 = "prop1";

    /** Property(field) name {@code "street"} for classes: {@code [Address]} */
    String street = "street";

    /** Property(field) name {@code "title"} for classes: {@code [Project]} */
    String title = "title";

    /** Property(field) name {@code "userId"} for classes: {@code [Address, Device]} */
    String userId = "userId";

    /** Property(field) name {@code "userSet"} for classes: {@code [UserQueryAllResult]} */
    String userSet = "userSet";

    /** Property(field) name {@code "users"} for classes: {@code [UserQueryAllResult]} */
    String users = "users";

    /*
     * Auto-generated class for property name table by abacus-jdbc for classes:     [class com.landawn.abacus.samples.entity.Address$AddressBuilder, class com.landawn.abacus.samples.entity.Address, class com.landawn.abacus.samples.entity.Device$DeviceBuilder, class com.landawn.abacus.samples.entity.Device, class com.landawn.abacus.samples.entity.Employee$EmployeeBuilder, class com.landawn.abacus.samples.entity.Employee, class com.landawn.abacus.samples.entity.EmployeeProject$EmployeeProjectBuilder, class com.landawn.abacus.samples.entity.EmployeeProject, class com.landawn.abacus.samples.entity.ImmutableUser$ImmutableUserBuilder, class com.landawn.abacus.samples.entity.ImmutableUser, class com.landawn.abacus.samples.entity.Project$ProjectBuilder, class com.landawn.abacus.samples.entity.Project, class com.landawn.abacus.samples.entity.User$UserBuilder, class com.landawn.abacus.samples.entity.User, class codes.entity.Account$AccountBuilder, class codes.entity.Account, class codes.entity.User$UserBuilder, class codes.entity.User, class codes.entity.UserQueryAllResult$UserQueryAllResultBuilder, class codes.entity.UserQueryAllResult]
     */
    public interface sf     {     // NOSONAR

        /** Function property(field) name {@code "min(city)"} for classes: {@code [Address]} */
        String min_city = "min(city)";

        /** Function property(field) name {@code "min(createTime)"} for classes: {@code [Account, ImmutableUser, User, User, UserQueryAllResult]} */
        String min_createTime = "min(createTime)";

        /** Function property(field) name {@code "min(email)"} for classes: {@code [ImmutableUser, User, User, UserQueryAllResult]} */
        String min_email = "min(email)";

        /** Function property(field) name {@code "min(emailAddress)"} for classes: {@code [Account]} */
        String min_emailAddress = "min(emailAddress)";

        /** Function property(field) name {@code "min(firstName)"} for classes: {@code [Account, Employee, ImmutableUser, User, User, UserQueryAllResult]} */
        String min_firstName = "min(firstName)";

        /** Function property(field) name {@code "min(id)"} for classes: {@code [User, UserQueryAllResult]} */
        String min_id = "min(id)";

        /** Function property(field) name {@code "min(lastName)"} for classes: {@code [Account, Employee, ImmutableUser, User, User, UserQueryAllResult]} */
        String min_lastName = "min(lastName)";

        /** Function property(field) name {@code "min(manufacture)"} for classes: {@code [Device]} */
        String min_manufacture = "min(manufacture)";

        /** Function property(field) name {@code "min(model)"} for classes: {@code [Device]} */
        String min_model = "min(model)";

        /** Function property(field) name {@code "min(nickName)"} for classes: {@code [ImmutableUser, User]} */
        String min_nickName = "min(nickName)";

        /** Function property(field) name {@code "min(prop1)"} for classes: {@code [User, UserQueryAllResult]} */
        String min_prop1 = "min(prop1)";

        /** Function property(field) name {@code "min(street)"} for classes: {@code [Address]} */
        String min_street = "min(street)";

        /** Function property(field) name {@code "min(title)"} for classes: {@code [Project]} */
        String min_title = "min(title)";

        /** Function property(field) name {@code "max(city)"} for classes: {@code [Address]} */
        String max_city = "max(city)";

        /** Function property(field) name {@code "max(createTime)"} for classes: {@code [Account, ImmutableUser, User, User, UserQueryAllResult]} */
        String max_createTime = "max(createTime)";

        /** Function property(field) name {@code "max(email)"} for classes: {@code [ImmutableUser, User, User, UserQueryAllResult]} */
        String max_email = "max(email)";

        /** Function property(field) name {@code "max(emailAddress)"} for classes: {@code [Account]} */
        String max_emailAddress = "max(emailAddress)";

        /** Function property(field) name {@code "max(firstName)"} for classes: {@code [Account, Employee, ImmutableUser, User, User, UserQueryAllResult]} */
        String max_firstName = "max(firstName)";

        /** Function property(field) name {@code "max(id)"} for classes: {@code [User, UserQueryAllResult]} */
        String max_id = "max(id)";

        /** Function property(field) name {@code "max(lastName)"} for classes: {@code [Account, Employee, ImmutableUser, User, User, UserQueryAllResult]} */
        String max_lastName = "max(lastName)";

        /** Function property(field) name {@code "max(manufacture)"} for classes: {@code [Device]} */
        String max_manufacture = "max(manufacture)";

        /** Function property(field) name {@code "max(model)"} for classes: {@code [Device]} */
        String max_model = "max(model)";

        /** Function property(field) name {@code "max(nickName)"} for classes: {@code [ImmutableUser, User]} */
        String max_nickName = "max(nickName)";

        /** Function property(field) name {@code "max(prop1)"} for classes: {@code [User, UserQueryAllResult]} */
        String max_prop1 = "max(prop1)";

        /** Function property(field) name {@code "max(street)"} for classes: {@code [Address]} */
        String max_street = "max(street)";

        /** Function property(field) name {@code "max(title)"} for classes: {@code [Project]} */
        String max_title = "max(title)";

    }

}

