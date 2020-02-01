package com.landawn.abacus.samples.entity;

import java.util.HashSet;
import java.util.Set;

import com.landawn.abacus.annotation.Table;

import lombok.Data;

@Table("Employee")
@Data
public class Employee {
    private int employeeId;
    private String firstName;
    private String lastName;

    private Set<Project> projects = new HashSet<>();
}
