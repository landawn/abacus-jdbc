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
public class Device {
    @Id
    private int id;
    private long userId;
    private String manufacture;
    private String model;
}