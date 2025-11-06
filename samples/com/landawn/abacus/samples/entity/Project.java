package com.landawn.abacus.samples.entity;

import java.util.Date;
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
public class Project {
    @Id
    private int projectId;
    private String title;
    private Date startDate;

    @JoinedBy({ "projectId=EmployeeProject.projectId", "EmployeeProject.employeeId = employeeId" })
    private Set<Employee> employees;
}
