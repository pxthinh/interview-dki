package pxt.dki.room;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AmenityRepository extends JpaRepository<Amenity, String> {

    /** Rename propagation: swap the value inside every room's amenities array. */
    @Modifying
    @Query(value = """
            UPDATE rooms SET amenities = array_replace(amenities, :oldName, :newName), updated_at = now()
            WHERE :oldName = ANY(amenities)""", nativeQuery = true)
    int renameInRooms(@Param("oldName") String oldName, @Param("newName") String newName);
}
