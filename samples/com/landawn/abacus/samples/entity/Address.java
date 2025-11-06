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
public class Address {
    @Id
    private long id;
    private long userId;
    private String street;
    private String city;
}