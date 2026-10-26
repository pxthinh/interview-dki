package pxt.dki.room;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "rooms")
@Getter
@Setter
public class Room {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    private String location;

    @Column(nullable = false)
    private int capacity;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]", nullable = false)
    private List<String> amenities = new ArrayList<>();

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "google_calendar_id")
    private String googleCalendarId;

    /** Google incremental-sync cursor for the reverse (Calendar -> DB) import. */
    @Column(name = "google_sync_token")
    private String googleSyncToken;

    /** Owning admin; imported bookings with an unknown creator are attributed to this user. */
    @Column(name = "admin_user_id")
    private UUID adminUserId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

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
