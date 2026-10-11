-- Existing installations: run once before starting the updated backend.
-- Additive migration; measurements, alarms, snapshots and device IDs are preserved.
USE water_moniter;
ALTER TABLE device ADD COLUMN deleted_at DATETIME NULL;
