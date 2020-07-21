package com.landawn.abacus.samples.sof._17860161.entity;

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
public class Address {
    @Id
    private long id;
    private long personId;
    private String street;
    private int cityId;
    @JoinedBy("cityId=id")
    private City city;
    private String state;
    private String zipCode;
}