package pxt.dki.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "app_settings")
@Getter
@Setter
public class AppSetting {

    @Id
    @Column(name = "key")
    private String key;

    @Column(nullable = false)
    private String value;
}
