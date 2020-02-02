package com.landawn.abacus.samples.entity;

import java.util.Set;

import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.JoinedBy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Employee {
    @Id
    private int employeeId;
    private String firstName;
    private String lastName;

    @JoinedBy({ "employeeId=EmployeeProject.employeeId", "EmployeeProject.projectId = projectId" })
    private Set<Project> projects;
}
