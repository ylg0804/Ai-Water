package com.model.water.device;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class DeviceRepository {
    private final JdbcTemplate jdbc;
    public DeviceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Map<String,Object>> list() { return jdbc.queryForList("SELECT * FROM device ORDER BY id"); }
    public List<Map<String,Object>> listActiveForInference() {
        return jdbc.queryForList("SELECT * FROM device WHERE status=1 ORDER BY id");
    }
    public Map<String,Object> find(long id) { return jdbc.queryForMap("SELECT * FROM device WHERE id=?", id); }
    public Number create(Map<String,Object> d) {
        jdbc.update("INSERT INTO device(serial,name,location,channel_no,hd,device_other_params,infer_interval_sec,status) VALUES (?,?,?,?,?,?,?,?)",
                d.get("serial"), d.get("name"), d.get("location"), d.getOrDefault("channelNo",1), d.getOrDefault("hd",0),
                d.get("deviceOtherParams"), d.getOrDefault("inferIntervalSec",60), d.getOrDefault("status",1));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Number.class);
    }
    public void update(long id, Map<String,Object> d) {
        jdbc.update("UPDATE device SET serial=?,name=?,location=?,channel_no=?,hd=?,device_other_params=?,infer_interval_sec=?,status=? WHERE id=?",
                d.get("serial"), d.get("name"), d.get("location"), d.getOrDefault("channelNo",1), d.getOrDefault("hd",0),
                d.get("deviceOtherParams"), d.getOrDefault("inferIntervalSec",60), d.getOrDefault("status",1), id);
    }
    public int delete(long id) { return jdbc.update("DELETE FROM device WHERE id=?", id); }
}
