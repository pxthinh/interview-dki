package pxt.dki.integrations;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One row per user who connected their personal Google account. Token is encrypted at rest. */
@Entity
@Table(name = "user_google_accounts")
@Getter
@Setter
public class UserGoogleAccount {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "google_email", nullable = false)
    private String googleEmail;

    @Column(name = "refresh_token_enc", nullable = false)
    private String refreshTokenEnc;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GoogleAccountStatus status = GoogleAccountStatus.CONNECTED;

    @Column(name = "connected_at", nullable = false)
    private Instant connectedAt;
}
