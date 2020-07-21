package com.landawn.abacus.samples.sof._17860161.entity;

import java.sql.Timestamp;

import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.annotation.ReadOnly;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Person {

    @Id
    private long id;
    private String firstName;
    private String lastName;
    private String email;
    @ReadOnly
    private Timestamp createTime;

    @JoinedBy("id=personId")
    private Address address;
}
