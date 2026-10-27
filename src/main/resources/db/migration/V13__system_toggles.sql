-- Runtime system toggles, ANDed with the env switches (GOOGLE_CALENDAR_ENABLED / MAIL_ENABLED):
-- the env switch provisions the capability, the admin toggle pauses it without a redeploy.
INSERT INTO app_settings (key, value) VALUES
    ('calendar.sync-enabled', 'true'),
    ('mail.enabled', 'true');
