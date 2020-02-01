package com.landawn.abacus.samples.entity;

import java.util.HashSet;
import java.util.Set;

import com.landawn.abacus.annotation.Table;

import lombok.Data;

@Table("Project")
@Data
public class Project {
    private int projectId;
    private String title;

    private Set<Employee> employees = new HashSet<>();
}
