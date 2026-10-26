package pxt.dki.room;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository extends JpaRepository<Room, UUID> {

    List<Room> findByActiveTrueOrderByCapacityAscNameAsc();

    List<Room> findAllByOrderByNameAsc();

    boolean existsByNameIgnoreCase(String name);

    Optional<Room> findByNameIgnoreCase(String name);

    /**
     * Row lock on the room. Booking writes take this lock first, so all booking attempts for
     * the same room are serialized while different rooms stay fully concurrent.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.id = :id")
    Optional<Room> findByIdForUpdate(@Param("id") UUID id);
}
