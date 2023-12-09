package codes.entity;

import javax.persistence.Id;
import javax.persistence.Table;

import com.landawn.abacus.annotation.Column;
import com.landawn.abacus.annotation.JsonXmlConfig;
import com.landawn.abacus.annotation.NonUpdatable;
import com.landawn.abacus.annotation.ReadOnly;
import com.landawn.abacus.annotation.Type;
import com.landawn.abacus.annotation.Type.EnumBy;
import com.landawn.abacus.util.NamingPolicy;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@JsonXmlConfig(namingPolicy = NamingPolicy.UPPER_CASE_WITH_UNDERSCORE, ignoredFields = { "id", "create_time" }, dateFormat = "yyyy-mm-dd\"T\"", timeZone = "PDT", numberFormat = "#.###", enumerated = EnumBy.ORDINAL)
@Table(name = "user1")
public class User {

    @Id
    @ReadOnly
    @Column("ID")
    private Long id;

    @Column("FIRST_NAME")
    private String firstName;

    @Column("LAST_NAME")
    private String lastName;

    @Column("PROP1")
    private String prop1;

    @Column("EMAIL")
    private String email;

    @NonUpdatable
    @Column("CREATE_TIME")
    @Type(name = "List<String>")
    private java.util.Date create_time;

    public User copy() {
        final User copy = new User();
        copy.id = this.id;
        copy.firstName = this.firstName;
        copy.lastName = this.lastName;
        copy.prop1 = this.prop1;
        copy.email = this.email;
        copy.create_time = this.create_time;
        return copy;
    }

}
