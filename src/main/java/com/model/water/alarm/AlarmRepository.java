package com.model.water.alarm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AlarmRepository {
    private final JdbcTemplate jdbc;
    public AlarmRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Map<String,Object>> list(Long deviceId, String type, String status) {
        return jdbc.queryForList("""
                SELECT * FROM alarm WHERE (? IS NULL OR device_id=?)
                  AND (? IS NULL OR type=?) AND (? IS NULL OR status=?)
                ORDER BY created_at DESC, id DESC
                """, deviceId, deviceId, type, type, status, status);
    }
    public int handle(long id, String action) {
        return jdbc.update("UPDATE alarm SET status=?, handled_at=NOW() WHERE id=?", action, id);
    }
    public Optional<String> snapshotPath(long id) {
        List<String> paths = jdbc.query("SELECT snapshot_path FROM alarm WHERE id=?", (rs, row) -> rs.getString(1), id);
        return paths.isEmpty() ? Optional.empty() : Optional.ofNullable(paths.get(0));
    }
    public boolean createDebris(String serial, int count, float confidence, String snapshotPath) {
        return createIfNoOpen(serial, "DEBRIS", "检测到漂浮物：" + count + "个，最高置信度" + confidence, snapshotPath);
    }
    public synchronized boolean createIfNoOpen(String serial, String type, String detail, String snapshotPath) {
        Integer open = jdbc.queryForObject("""
                SELECT COUNT(*) FROM alarm a JOIN device d ON d.id=a.device_id
                WHERE d.serial=? AND a.type=? AND a.status='OPEN'
                """, Integer.class, serial, type);
        if (open != null && open > 0) return false;
        int changed = jdbc.update("""
                INSERT INTO alarm(device_id,type,detail,snapshot_path,status)
                SELECT id,?,?,?,'OPEN' FROM device WHERE serial=?
                """, type, detail, snapshotPath, serial);
        return changed > 0;
    }
}
