-- Password change timestamp: JWTs issued before it are rejected (see JwtAuthenticationFilter).
ALTER TABLE users ADD COLUMN password_changed_at timestamptz;

-- Emails are unique regardless of case; users_email_key (exact match) stays as-is.
CREATE UNIQUE INDEX IF NOT EXISTS users_email_lower_idx ON users (lower(email));
