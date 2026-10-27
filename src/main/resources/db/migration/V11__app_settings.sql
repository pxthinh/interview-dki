-- Admin-tunable booking limits. Values are text, parsed by SettingsService; seeds mirror the
-- previously hardcoded rules (90 days ahead, 26 recurring weeks).
CREATE TABLE app_settings (
    key   varchar(100) PRIMARY KEY,
    value varchar(100) NOT NULL
);

INSERT INTO app_settings (key, value) VALUES
    ('booking.max-days-ahead', '90'),
    ('booking.max-recurring-weeks', '26');
