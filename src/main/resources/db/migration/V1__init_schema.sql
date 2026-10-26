-- btree_gist lets the GiST exclusion constraint combine the scalar room_id with a time range.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE users (
    id            uuid PRIMARY KEY,
    email         varchar(255) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,
    full_name     varchar(255) NOT NULL,
    role          varchar(20)  NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    active        boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL
);

CREATE TABLE rooms (
    id                 uuid PRIMARY KEY,
    name               varchar(100) NOT NULL UNIQUE,
    location           varchar(255),
    capacity           int          NOT NULL CHECK (capacity > 0),
    amenities          text[]       NOT NULL DEFAULT '{}',
    active             boolean      NOT NULL DEFAULT true,
    google_calendar_id varchar(255),
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL
);

CREATE TABLE bookings (
    id              uuid PRIMARY KEY,
    room_id         uuid         NOT NULL REFERENCES rooms (id),
    user_id         uuid         NOT NULL REFERENCES users (id),
    title           varchar(200) NOT NULL,
    description     text,
    attendee_count  int,
    start_time      timestamptz  NOT NULL,
    end_time        timestamptz  NOT NULL,
    status          varchar(20)  NOT NULL CHECK (status IN ('ACTIVE', 'CANCELLED')),
    cancelled_at    timestamptz,
    google_event_id varchar(255),
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    CONSTRAINT bookings_end_after_start CHECK (end_time > start_time),
    -- Last line of defence against double booking: no two ACTIVE bookings of the same room
    -- may overlap. Half-open range [start, end) so back-to-back bookings touch without clashing.
    CONSTRAINT bookings_no_overlap EXCLUDE USING gist
        (room_id WITH =, tstzrange(start_time, end_time, '[)') WITH &&)
        WHERE (status = 'ACTIVE')
);

CREATE INDEX idx_bookings_room_start ON bookings (room_id, start_time);
CREATE INDEX idx_bookings_user_start ON bookings (user_id, start_time);

CREATE TABLE calendar_outbox (
    id              bigserial PRIMARY KEY,
    booking_id      uuid        NOT NULL REFERENCES bookings (id),
    action          varchar(20) NOT NULL CHECK (action IN ('CREATE', 'UPDATE', 'CANCEL')),
    status          varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    attempts        int         NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    last_error      text,
    created_at      timestamptz NOT NULL,
    processed_at    timestamptz
);

CREATE INDEX idx_outbox_pending ON calendar_outbox (status, next_attempt_at);
