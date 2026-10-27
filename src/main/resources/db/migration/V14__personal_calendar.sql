-- Per-user Google OAuth connections. Refresh token encrypted at rest (AES-256-GCM, base64).
CREATE TABLE user_google_accounts (
    user_id           uuid PRIMARY KEY REFERENCES users (id),
    google_email      varchar(255) NOT NULL,
    refresh_token_enc text         NOT NULL,
    status            varchar(20)  NOT NULL DEFAULT 'CONNECTED' CHECK (status IN ('CONNECTED', 'EXPIRED')),
    connected_at      timestamptz  NOT NULL
);

-- Personal event copies: one Google event per (booking, connected user), in that user's primary
-- calendar. Deliberately separate from bookings.google_event_id: the import job links on that.
CREATE TABLE booking_user_events (
    booking_id      uuid         NOT NULL REFERENCES bookings (id),
    user_id         uuid         NOT NULL REFERENCES users (id),
    google_event_id varchar(255) NOT NULL,
    created_at      timestamptz  NOT NULL,
    PRIMARY KEY (booking_id, user_id)
);

-- NULL = room-calendar row (legacy behavior); set = push to this user's primary calendar.
-- action/status CHECKs unchanged: personal rows reuse CREATE/UPDATE/CANCEL + PENDING/DONE/FAILED.
ALTER TABLE calendar_outbox ADD COLUMN target_user_id uuid REFERENCES users (id);
CREATE INDEX idx_outbox_target_user ON calendar_outbox (target_user_id) WHERE target_user_id IS NOT NULL;
