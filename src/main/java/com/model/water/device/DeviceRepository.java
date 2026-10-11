package com.model.water.device;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import com.model.water.api.ResourceNotFoundException;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import java.sql.Statement;

@Repository
public class DeviceRepository {
    private final JdbcTemplate jdbc;
    public DeviceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Map<String,Object>> list() { return list(false); }
    public List<Map<String,Object>> list(boolean includeDeleted) {
        return jdbc.queryForList("SELECT * FROM device " + (includeDeleted ? "" : "WHERE deleted_at IS NULL ") + "ORDER BY id");
    }
    public List<Map<String,Object>> listActiveForInference() {
        return jdbc.queryForList("SELECT * FROM device WHERE status=1 AND deleted_at IS NULL ORDER BY id");
    }
    public Map<String,Object> find(long id) {
        var rows = jdbc.queryForList("SELECT * FROM device WHERE id=? AND deleted_at IS NULL", id);
        if (rows.isEmpty()) throw new ResourceNotFoundException("设备不存在: " + id);
        return rows.get(0);
    }
    public Number create(Map<String,Object> d) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO device(serial,name,location,channel_no,hd,device_other_params,infer_interval_sec,status) VALUES (?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            Object[] values = {d.get("serial"), d.get("name"), d.get("location"), d.getOrDefault("channelNo",1), d.getOrDefault("hd",0),
                    d.get("deviceOtherParams"), d.getOrDefault("inferIntervalSec",60), d.getOrDefault("status",1)};
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, keys);
        return keys.getKey();
    }
    public void update(long id, Map<String,Object> d) {
        find(id);
        jdbc.update("UPDATE device SET serial=?,name=?,location=?,channel_no=?,hd=?,device_other_params=?,infer_interval_sec=?,status=? WHERE id=? AND deleted_at IS NULL",
                d.get("serial"), d.get("name"), d.get("location"), d.getOrDefault("channelNo",1), d.getOrDefault("hd",0),
                d.get("deviceOtherParams"), d.getOrDefault("inferIntervalSec",60), d.getOrDefault("status",1), id);
    }
    public int delete(long id) { return jdbc.update("UPDATE device SET deleted_at=NOW(),status=0 WHERE id=? AND deleted_at IS NULL", id); }
}
