package pxt.dki.calendar;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Google event id of one booking's personal copy on one user's primary calendar. */
@Entity
@Table(name = "booking_user_events")
@IdClass(BookingUserEventId.class)
@Getter
@Setter
public class BookingUserEvent {

    @Id
    @Column(name = "booking_id")
    private UUID bookingId;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "google_event_id", nullable = false)
    private String googleEventId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }
}
