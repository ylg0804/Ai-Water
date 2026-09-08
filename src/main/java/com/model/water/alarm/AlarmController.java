package com.model.water.alarm;

import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.Map;
import com.model.water.api.ApiResponse;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/alarm")
public class AlarmController {
    private final AlarmRepository repository;
    private final Path snapshotRoot;
    public AlarmController(AlarmRepository repository,
                           @Value("${waterflow.snapshot-dir:snapshots}") String snapshotDir) {
        this.repository = repository;
        this.snapshotRoot = Path.of(snapshotDir).toAbsolutePath().normalize();
    }
    @GetMapping public ApiResponse<Object> list(@RequestParam(required=false) Long deviceId,
                                                @RequestParam(required=false) String type,
                                                @RequestParam(required=false) String status) {
        return ApiResponse.ok(repository.list(deviceId, type, status));
    }
    @PostMapping("/{id}/handle")
    public ApiResponse<Object> handle(@PathVariable long id, @RequestBody HandleRequest request) {
        if (!"CONFIRMED".equals(request.action()) && !"IGNORED".equals(request.action()))
            throw new IllegalArgumentException("action 必须为 CONFIRMED 或 IGNORED");
        if (repository.handle(id, request.action()) == 0) throw new IllegalArgumentException("告警不存在: " + id);
        return ApiResponse.ok(Map.of("id", id, "status", request.action()));
    }

    @GetMapping(value = "/{id}/snapshot", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<Resource> snapshot(@PathVariable long id) {
        String relative = repository.snapshotPath(id).orElseThrow(() -> new IllegalArgumentException("告警截图不存在: " + id));
        Path file = snapshotRoot.resolve(relative).normalize();
        if (!file.startsWith(snapshotRoot) || !java.nio.file.Files.isRegularFile(file))
            throw new IllegalArgumentException("告警截图文件不存在: " + id);
        return ResponseEntity.ok(new FileSystemResource(file));
    }
    public record HandleRequest(String action) {}
}
