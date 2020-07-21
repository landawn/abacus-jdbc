package com.landawn.abacus.samples.sof._17860161.entity;

import com.landawn.abacus.annotation.Id;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class City {
    @Id
    private int id;
    private String name;
}
