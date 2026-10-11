package com.model.water.device;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import com.model.water.api.ApiResponse;
import com.model.water.ai.HydraulicParams;
import com.model.water.api.ResourceNotFoundException;

@RestController
@RequestMapping("/api/device")
public class DeviceController {
    private final DeviceRepository repository;
    public DeviceController(DeviceRepository repository) { this.repository = repository; }

    @GetMapping public ApiResponse<Object> list(@RequestParam(defaultValue = "false") boolean includeDeleted) {
        return ApiResponse.ok(repository.list(includeDeleted));
    }

    @PostMapping public ApiResponse<Object> create(@RequestBody Map<String,Object> body) {
        validate(body); Number id = repository.create(body); return ApiResponse.ok(Map.of("id", id));
    }

    @PutMapping("/{id}") public ApiResponse<Object> update(@PathVariable long id, @RequestBody Map<String,Object> body) {
        validate(body); repository.update(id, body); return ApiResponse.ok(repository.find(id));
    }

    @DeleteMapping("/{id}") public ApiResponse<Object> delete(@PathVariable long id) {
        if (repository.delete(id) == 0) throw new ResourceNotFoundException("设备不存在: " + id);
        return ApiResponse.ok(null);
    }

    private static void validate(Map<String,Object> b) {
        if (b == null || b.get("serial") == null || String.valueOf(b.get("serial")).isBlank())
            throw new IllegalArgumentException("serial 不能为空");
        text(b, "serial", 64); text(b, "name", 128); text(b, "location", 256);
        integer(b, "channelNo", 1, Integer.MAX_VALUE);
        integer(b, "inferIntervalSec", 1, Integer.MAX_VALUE);
        integer(b, "hd", 0, 1); integer(b, "status", 0, 1);
        if (!(b.get("deviceOtherParams") instanceof String params))
            throw new IllegalArgumentException("deviceOtherParams 必须为参数字符串");
        HydraulicParams.validate(params);
    }
    private static void text(Map<String,Object> b, String key, int max) {
        Object value = b.get(key);
        if (value != null && (!(value instanceof String s) || s.length() > max))
            throw new IllegalArgumentException(key + " 必须为长度不超过 " + max + " 的字符串");
    }
    private static void integer(Map<String,Object> b, String key, int min, int max) {
        Object value = b.get(key);
        if (value == null) { if (b.containsKey(key)) throw new IllegalArgumentException(key + " 不能为空"); return; }
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != Math.rint(n.doubleValue())
                || n.doubleValue() < min || n.doubleValue() > max)
            throw new IllegalArgumentException(key + " 必须是 " + min + " 到 " + max + " 的整数");
    }
}
