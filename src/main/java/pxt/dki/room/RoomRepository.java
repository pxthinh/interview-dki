package pxt.dki.room;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    /** Gives every admin-less room (e.g. Flyway-seeded before any user existed) an owning admin. */
    @Modifying
    @Transactional
    @Query("update Room r set r.adminUserId = :adminId where r.adminUserId is null")
    int assignAdminWhereMissing(@Param("adminId") UUID adminId);
}
