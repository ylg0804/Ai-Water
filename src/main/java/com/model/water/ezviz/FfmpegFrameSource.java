package com.model.water.ezviz;
import com.model.water.ai.HydraulicAlarmUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class FfmpegFrameSource implements FrameSource {
    private final EzvizService ezviz;
    private final TestSourceProperties props;
    private final String ffmpeg;
    private final Map<String, Double> offsets = new ConcurrentHashMap<>();
    public FfmpegFrameSource(EzvizService ezviz, TestSourceProperties props,
                            @Value("${waterflow.ffmpeg-path:ffmpeg}") String ffmpeg) {
        this.ezviz = ezviz; this.props = props; this.ffmpeg = ffmpeg;
    }
    @jakarta.annotation.PostConstruct
    void validateExecutable() throws Exception {
        Process process;
        try { process = new ProcessBuilder(ffmpeg, "-version").redirectErrorStream(true).start(); }
        catch (IOException e) { throw new IllegalArgumentException("未找到 FFmpeg，请设置 waterflow.ffmpeg-path 为实际 ffmpeg.exe 路径", e); }
        Thread drain = Thread.ofVirtual().start(() -> {
            try { process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream()); } catch (IOException ignored) { }
        });
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IllegalArgumentException("FFmpeg 无法运行，请检查 waterflow.ffmpeg-path");
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            drain.join(1000);
        }
    }
    Path localFile(String file) throws IOException {
        Path root = Path.of(props.getRoot()).toRealPath(), path = root.resolve(file).toRealPath();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) throw new IllegalArgumentException("测试视频必须位于配置的测试素材目录内");
        return path;
    }
    @Override public List<BufferedImage> capture(EzvizProperties.Device device) throws Exception {
        String serial = device.serial().toUpperCase(Locale.ROOT);
        var params = HydraulicAlarmUtil.parseKv(device.deviceOtherParams());
        double interval = params.get("frame_step") / (double) params.get("fps");
        String file = props.isEnabled() ? lookup(props.getFiles(), serial) : null;
        String stream = props.isEnabled() ? lookup(props.getUrls(), serial) : null;
        if (file != null && stream != null) throw new IllegalArgumentException("设备测试源不能同时配置文件和流地址");
        String input = file != null ? localFile(file).toString() : stream != null ? stream
                : ezviz.getInferenceHlsUrl(serial, device.channelNo(), device.hd());
        if (stream != null && !stream.matches("(?i)^(https?|rtsp|rtmp)://.+"))
            throw new IllegalArgumentException("测试流地址仅支持 HTTP/HTTPS/RTSP/RTMP");
        Path dir = Files.createTempDirectory("waterflow-frames-");
        try {
            var args = new ArrayList<>(List.of(ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-nostdin"));
            double offset = offsets.getOrDefault(serial, 0d);
            if (file != null && offset > 0) args.addAll(List.of("-ss", Double.toString(offset)));
            args.addAll(List.of("-i", input, "-vf", "fps=" + (1d / interval), "-frames:v", "2", dir.resolve("frame-%d.png").toString()));
            extract(args);
            List<BufferedImage> frames = read(dir);
            if (frames.size() < 2 && file != null && offset > 0) {
                for (int i = 1; i <= 2; i++) Files.deleteIfExists(dir.resolve("frame-" + i + ".png"));
                int seek = args.indexOf("-ss"); args.remove(seek); args.remove(seek);
                extract(args); frames = read(dir); offset = 0;
            }
            if (frames.size() < 2) throw new IOException("视频源未生成两帧画面");
            if (file != null) offsets.put(serial, offset + 2 * interval);
            return frames;
        } finally {
            try (var paths = Files.list(dir)) { for (Path path : paths.toList()) Files.deleteIfExists(path); }
            Files.deleteIfExists(dir);
        }
    }
    private static String lookup(Map<String,String> map, String serial) {
        return map.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(serial)).map(Map.Entry::getValue).findFirst().orElse(null);
    }
    private static List<BufferedImage> read(Path dir) throws IOException {
        List<BufferedImage> frames = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            Path path = dir.resolve("frame-" + i + ".png");
            if (Files.exists(path)) { BufferedImage frame = ImageIO.read(path.toFile()); if (frame != null) frames.add(frame); }
        }
        return frames;
    }
    private static void extract(List<String> args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        Thread drain = Thread.ofVirtual().start(() -> {
            try { process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream()); } catch (IOException ignored) { }
        });
        try {
            if (!process.waitFor(45, TimeUnit.SECONDS)) throw new IOException("ffmpeg 抽帧超时");
            if (process.exitValue() != 0) throw new IOException("ffmpeg 抽帧失败，退出码=" + process.exitValue());
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
            drain.join(1000);
        }
    }
    @Override public void release(String serial) { offsets.remove(serial.toUpperCase(Locale.ROOT)); }
}
