package com.landawn.abacus.samples.entity;

import com.landawn.abacus.annotation.Id;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Id({ "employeeId", "projectId" })
public class EmployeeProject {
    private int employeeId;
    private int projectId;
}