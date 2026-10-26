-- Incremental reverse sync (Google Calendar -> DB): each room calendar keeps the
-- nextSyncToken Google handed us after the last import run.
ALTER TABLE rooms ADD COLUMN google_sync_token text;
