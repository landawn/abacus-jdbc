package codes.entity;

import javax.persistence.Column;
import com.landawn.abacus.annotation.NonUpdatable;
import com.landawn.abacus.annotation.ReadOnly;
import javax.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user")
public class User {

    @ReadOnly
    @Column(name = "ID")
    private Long id;

    @Column(name = "FIRST_NAME")
    private String firstName;

    @Column(name = "LAST_NAME")
    private String lastName;

    @Column(name = "PROP1")
    private String prop1;

    @Column(name = "EMAIL")
    private String email;

    @NonUpdatable
    @Column(name = "CREATE_TIME")
    private java.util.Date create_time;

}
