package codes.entity;

import com.landawn.abacus.annotation.Column;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Account {

    @Column("ID")
    private long id;

    @Column("FIRST_NAME")
    private String firstName;

    @Column("LAST_NAME")
    private String lastName;

    @Column("EMAIL_ADDRESS")
    private String emailAddress;

    @Column("CREATE_TIME")
    private java.sql.Timestamp createTime;

}
