package com.model.water.alarm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

@Service
public class SnapshotStorage {
    private final Path root;

    public SnapshotStorage(@Value("${waterflow.snapshot-dir:snapshots}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    /** Saves a JPEG and returns the relative path stored in alarm.snapshot_path. */
    public String save(String serial, BufferedImage image) throws IOException {
        if (image == null) throw new IllegalArgumentException("告警截图不能为空");
        Files.createDirectories(root);
        String safeSerial = serial.replaceAll("[^A-Za-z0-9._-]", "_");
        String filename = safeSerial + "-" + DateTimeFormatter.ISO_INSTANT
                .format(Instant.now()).replace(':', '-') + ".jpg";
        Path target = root.resolve(filename).normalize();
        if (!target.startsWith(root)) throw new IOException("截图路径非法");
        if (!ImageIO.write(image, "jpg", target.toFile())) throw new IOException("JPEG 编码失败");
        return root.relativize(target).toString().replace('\\', '/');
    }
}
