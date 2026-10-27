package com.landawn.abacus.samples.entity;

import java.sql.Timestamp;
import java.util.List;

import com.landawn.abacus.annotation.Column;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.JoinedBy;
import com.landawn.abacus.annotation.ReadOnly;
import com.landawn.abacus.annotation.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Maps user1 columns and declares related entities that are loaded explicitly by DAO helpers. */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("user1")
public class User {
    @Id
    private long id;

    @Column("FIRST_NAME")
    private String firstName;
    private String lastName;

    @Column("prop1")
    private String nickName;
    private String email;
    @ReadOnly
    // Database default supplies this value; it is excluded from generated INSERT/UPDATE SQL.
    private Timestamp createTime;

    @JoinedBy("id=userId")
    private List<Device> devices;

    // Composite join: both the user's ID and first name must match the device.
    @JoinedBy("id=userId, firstName=model")
    private Device devices2;

    @JoinedBy("id=userId")
    private Address address;

    @JoinedBy({ "id=userId", "lastName=street" })
    private List<Address> address2;
}
