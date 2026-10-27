package pxt.dki.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;
import pxt.dki.room.Room;
import pxt.dki.user.User;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bookings")
@Getter
@Setter
public class Booking {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id")
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private String title;

    private String description;

    @Column(name = "attendee_count")
    private Integer attendeeCount;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status = BookingStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BookingSource source = BookingSource.APP;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** Set when the booker or a participant confirmed presence; null on a NO_SHOW release. */
    @Column(name = "checked_in_at")
    private Instant checkedInAt;

    @Column(name = "google_event_id")
    private String googleEventId;

    /** Weekly series this occurrence belongs to; null for one-off bookings. */
    @Column(name = "series_id")
    private UUID seriesId;

    /** Snapshot of the room's turnover break when this booking was made (0 or 15 minutes). */
    @Column(name = "buffer_minutes", nullable = false)
    private int bufferMinutes = 15;

    /** Invited participants (besides the booker); notified by email on invite/change/cancel. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "booking_participants",
            joinColumns = @JoinColumn(name = "booking_id"),
            inverseJoinColumns = @JoinColumn(name = "user_id"))
    private java.util.Set<User> participants = new java.util.LinkedHashSet<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Optimistic lock: concurrent update/cancel of the same booking surfaces as 409. */
    @Version
    @Column(nullable = false)
    private long version;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
