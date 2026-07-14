package codes.entity;

import java.util.List;
import java.util.Set;

import jakarta.persistence.Column;
import javax.persistence.Id;

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
@Table(name = "UserQueryAllResult")
public class UserQueryAllResult {

    @Id
    @NonUpdatable
    @Column(name = "ID")
    private long id;

    @Column(name = "FIRST_NAME")
    private String firstName;

    @Column(name = "LAST_NAME")
    private String lastName;

    @Column(name = "PROP1")
    private String prop1;

    @Column(name = "EMAIL")
    private String email;

    @ReadOnly
    @Column(name = "CREATE_TIME")
    private java.sql.Timestamp createTime;

    // test
    private List<User> users;

    private Set<User> userSet; // test

    /*
     * Auto-generated class for property(field) name table by abacus-jdbc.
     */
    public interface x { // NOSONAR

        /** Property(field) name {@code "id"} */
        String id = "id";

        /** Property(field) name {@code "firstName"} */
        String firstName = "firstName";

        /** Property(field) name {@code "lastName"} */
        String lastName = "lastName";

        /** Property(field) name {@code "prop1"} */
        String prop1 = "prop1";

        /** Property(field) name {@code "email"} */
        String email = "email";

        /** Property(field) name {@code "createTime"} */
        String createTime = "createTime";

    }

}
