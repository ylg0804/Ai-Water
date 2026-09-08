package com.model.water.device;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import com.model.water.api.ApiResponse;

@RestController
@RequestMapping("/api/device")
public class DeviceController {
    private final DeviceRepository repository;
    public DeviceController(DeviceRepository repository) { this.repository = repository; }

    @GetMapping public ApiResponse<Object> list() { return ApiResponse.ok(repository.list()); }

    @PostMapping public ApiResponse<Object> create(@RequestBody Map<String,Object> body) {
        validate(body); Number id = repository.create(body); return ApiResponse.ok(Map.of("id", id));
    }

    @PutMapping("/{id}") public ApiResponse<Object> update(@PathVariable long id, @RequestBody Map<String,Object> body) {
        validate(body); repository.update(id, body); return ApiResponse.ok(repository.find(id));
    }

    @DeleteMapping("/{id}") public ApiResponse<Object> delete(@PathVariable long id) {
        if (repository.delete(id) == 0) throw new IllegalArgumentException("设备不存在: " + id);
        return ApiResponse.ok(null);
    }

    private static void validate(Map<String,Object> b) {
        if (b == null || b.get("serial") == null || String.valueOf(b.get("serial")).isBlank())
            throw new IllegalArgumentException("serial 不能为空");
    }
}
