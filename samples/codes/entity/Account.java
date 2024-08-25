package codes.entity;

import com.landawn.abacus.annotation.Column;
import com.landawn.abacus.annotation.Id;
import com.landawn.abacus.annotation.NonUpdatable;
import com.landawn.abacus.annotation.ReadOnly;
import com.landawn.abacus.annotation.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("account")
public class Account {

    @Id
    @NonUpdatable
    @Column("ID")
    private long id;

    @Column("FIRST_NAME")
    private String firstName;

    @Column("LAST_NAME")
    private String lastName;

    @Column("EMAIL_ADDRESS")
    private String emailAddress;

    @ReadOnly
    @Column("CREATE_TIME")
    private java.sql.Timestamp createTime;


    /*
     * Auto-generated class for property name table by abacus-jdbc.
     */
    public interface x { // NOSONAR

        String id = "id";
        String firstName = "firstName";
        String lastName = "lastName";
        String emailAddress = "emailAddress";
        String createTime = "createTime";

    }

}
