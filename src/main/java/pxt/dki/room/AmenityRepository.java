package pxt.dki.room;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AmenityRepository extends JpaRepository<Amenity, String> {

    /**
     * Rename propagation: swap the value inside every room's amenities array, keeping order and
     * dropping the duplicate when a room already carries the new name (rooms may keep values
     * that left the catalog).
     */
    @Modifying
    @Query(value = """
            UPDATE rooms SET updated_at = now(), amenities = ARRAY(
                SELECT a FROM unnest(array_replace(amenities, :oldName, :newName)) WITH ORDINALITY AS t(a, i)
                GROUP BY a ORDER BY min(i))
            WHERE :oldName = ANY(amenities)""", nativeQuery = true)
    int renameInRooms(@Param("oldName") String oldName, @Param("newName") String newName);
}
