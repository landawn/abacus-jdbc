package com.landawn.abacus.samples.entity;

import java.sql.Timestamp;
import java.util.List;

import com.landawn.abacus.annotation.Column;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.annotation.ReadOnly;

import lombok.Builder;
import lombok.Value;

// Entity Object mapped to record in DB table.
@Builder
@Value
public class ImmutableUser {
    @Id
    private long id;

    @Column("FIRST_NAME")
    private String firstName;
    private String lastName;

    @Column("prop1")
    private String nickName;
    private String email;
    @ReadOnly
    private Timestamp createTime;

    @JoinedBy("id=userId")
    private List<Device> devices;

    // Supposed to be empty.
    @JoinedBy("id=userId, firstName=model")
    private Device devices2;

    @JoinedBy("id=userId")
    private Address address;

    @JoinedBy({ "id=userId", "lastName=street" })
    private List<Address> address2;
}