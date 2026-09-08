CREATE DATABASE IF NOT EXISTS water_moniter
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE water_moniter;

CREATE TABLE IF NOT EXISTS device (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  serial VARCHAR(64) NOT NULL UNIQUE,
  name VARCHAR(128), location VARCHAR(256), channel_no INT DEFAULT 1,
  hd TINYINT DEFAULT 0, device_other_params TEXT,
  infer_interval_sec INT DEFAULT 60, status TINYINT DEFAULT 1,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS measurement (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, device_id BIGINT NOT NULL,
  water_level FLOAT, velocity FLOAT, discharge FLOAT,
  measured_at DATETIME NOT NULL,
  INDEX idx_dev_time (device_id, measured_at),
  CONSTRAINT fk_measurement_device FOREIGN KEY (device_id) REFERENCES device(id)
);
CREATE TABLE IF NOT EXISTS alarm (
  id BIGINT PRIMARY KEY AUTO_INCREMENT, device_id BIGINT NOT NULL,
  type VARCHAR(32) NOT NULL, detail VARCHAR(512), snapshot_path VARCHAR(256),
  status VARCHAR(16) DEFAULT 'OPEN', created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  handled_at DATETIME NULL, INDEX idx_dev_time (device_id, created_at),
  CONSTRAINT fk_alarm_device FOREIGN KEY (device_id) REFERENCES device(id)
);
