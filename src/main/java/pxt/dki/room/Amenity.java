package pxt.dki.room;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Catalog entry for room amenities; rooms themselves keep storing plain strings. */
@Entity
@Table(name = "amenities")
@Getter
@Setter
public class Amenity {

    @Id
    private String name;
}
