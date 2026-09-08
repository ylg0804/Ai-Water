package com.model.water.measurement;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.List;

/** MySQL 8 measurement persistence for the inference loop. */
@Repository
public class MeasurementRepository {
    private final JdbcTemplate jdbc;

    public MeasurementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String serial, double waterLevel, double velocity, double discharge, Instant measuredAt) {
        Long deviceId = jdbc.queryForObject("SELECT id FROM device WHERE serial = ?", Long.class, serial);
        if (deviceId == null) throw new IllegalStateException("数据库中不存在设备: " + serial);
        jdbc.update("INSERT INTO measurement(device_id, water_level, velocity, discharge, measured_at) VALUES (?, ?, ?, ?, ?)",
                deviceId, waterLevel, velocity, discharge, measuredAt);
    }

    public Map<String, Object> latest(String serial) {
        return jdbc.queryForMap("""
                SELECT m.id, d.serial, m.water_level, m.velocity, m.discharge, m.measured_at
                FROM measurement m JOIN device d ON d.id = m.device_id
                WHERE d.serial = ? ORDER BY m.measured_at DESC, m.id DESC LIMIT 1
                """, serial);
    }

    public Map<String, Object> latest(long deviceId) {
        return jdbc.queryForMap("""
                SELECT m.id, d.serial, m.water_level, m.velocity, m.discharge, m.measured_at
                FROM measurement m JOIN device d ON d.id = m.device_id
                WHERE d.id = ? ORDER BY m.measured_at DESC, m.id DESC LIMIT 1
                """, deviceId);
    }

    public List<Map<String, Object>> history(String serial, LocalDateTime start, LocalDateTime end) {
        return jdbc.queryForList("""
                SELECT m.id, d.serial, m.water_level, m.velocity, m.discharge, m.measured_at
                FROM measurement m JOIN device d ON d.id = m.device_id
                WHERE d.serial = ? AND m.measured_at >= ? AND m.measured_at < ?
                ORDER BY m.measured_at ASC, m.id ASC
                """, serial, start, end);
    }

    public List<Map<String, Object>> history(long deviceId, LocalDateTime start, LocalDateTime end) {
        return jdbc.queryForList("""
                SELECT m.id, d.serial, m.water_level, m.velocity, m.discharge, m.measured_at
                FROM measurement m JOIN device d ON d.id = m.device_id
                WHERE d.id = ? AND m.measured_at >= ? AND m.measured_at < ?
                ORDER BY m.measured_at ASC, m.id ASC
                """, deviceId, start, end);
    }
}
