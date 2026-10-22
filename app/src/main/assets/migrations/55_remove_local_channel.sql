-- Remove the "local channel" (RemoteProtocol.LOCAL): local directory sync connections created by old versions are cleaned up together with their mount records.
-- Delete mounts first, then connections, without relying on foreign-key cascade behavior.
DELETE FROM remote_mounts WHERE connectionId IN (SELECT id FROM remote_connections WHERE protocol = 'LOCAL');
DELETE FROM remote_connections WHERE protocol = 'LOCAL';
