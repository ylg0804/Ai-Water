package com.model.water.measurement;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/measurement")
public class MeasurementController {
    private final MeasurementRepository repository;

    public MeasurementController(MeasurementRepository repository) { this.repository = repository; }

    @GetMapping("/latest")
    public Map<String, Object> latest(@RequestParam(required = false) Long deviceId,
                                      @RequestParam(required = false) String serial) {
        if (deviceId == null && (serial == null || serial.isBlank())) throw new IllegalArgumentException("必须提供 deviceId 或 serial");
        return ok(deviceId != null ? repository.latest(deviceId) : repository.latest(serial));
    }

    @GetMapping("/history")
    public Map<String, Object> history(@RequestParam(required = false) Long deviceId,
                                       @RequestParam(required = false) String serial,
                                       @RequestParam LocalDateTime start,
                                       @RequestParam LocalDateTime end) {
        if (!end.isAfter(start)) throw new IllegalArgumentException("end 必须晚于 start");
        if (deviceId == null && (serial == null || serial.isBlank())) throw new IllegalArgumentException("必须提供 deviceId 或 serial");
        return ok(deviceId != null ? repository.history(deviceId, start, end) : repository.history(serial, start, end));
    }

    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Long deviceId,
                                         @RequestParam(required = false) String serial,
                                         @RequestParam LocalDateTime start,
                                         @RequestParam LocalDateTime end) {
        if (!end.isAfter(start)) throw new IllegalArgumentException("end 必须晚于 start");
        if (deviceId == null && (serial == null || serial.isBlank())) throw new IllegalArgumentException("必须提供 deviceId 或 serial");
        List<Map<String, Object>> rows = deviceId != null ? repository.history(deviceId, start, end) : repository.history(serial, start, end);
        String fileKey = serial == null || serial.isBlank() ? "device-" + deviceId : serial;
        StringBuilder csv = new StringBuilder("\uFEFFserial,measured_at,water_level,velocity,discharge\r\n");
        for (Map<String, Object> row : rows) {
            csv.append(csvCell(row.get("serial"))).append(',')
                    .append(csvCell(row.get("measured_at"))).append(',')
                    .append(csvCell(row.get("water_level"))).append(',')
                    .append(csvCell(row.get("velocity"))).append(',')
                    .append(csvCell(row.get("discharge"))).append("\r\n");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("text", "csv", StandardCharsets.UTF_8));
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename("measurement-" + fileKey + ".csv", StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(csv.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> ok(Object data) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", "200"); result.put("msg", "ok"); result.put("data", data);
        return result;
    }

    private static String csvCell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
