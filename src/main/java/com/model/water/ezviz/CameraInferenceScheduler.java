package com.model.water.ezviz;

import com.model.water.ai.WaterflowMedianSmoother;
import com.model.water.ai.WaterflowOnnxService;
import com.model.water.measurement.MeasurementRepository;
import com.model.water.realtime.RealtimeWebSocketHandler;
import com.model.water.ai.DebrisOnnxService;
import com.model.water.alarm.AlarmRepository;
import com.model.water.alarm.SnapshotStorage;
import com.model.water.ai.HydraulicAlarmUtil;
import com.model.water.device.DeviceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataAccessException;
import jakarta.annotation.PreDestroy;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 摄像头到模型的最小闭环：按设备周期获取 HLS，使用 ffmpeg 抽取两帧，推理并做设备级中值平滑。
 * 生产环境需安装 ffmpeg，并通过 waterflow.ffmpeg-path 指定其绝对路径。
 */
@Component
public class CameraInferenceScheduler {
    private static final Logger log = LoggerFactory.getLogger(CameraInferenceScheduler.class);
    private final EzvizProperties props;
    private final DeviceRepository devices;
    private final EzvizService ezviz;
    private final WaterflowOnnxService model;
    private final MeasurementRepository measurements;
    private final RealtimeWebSocketHandler realtime;
    private final DebrisOnnxService debris;
    private final AlarmRepository alarms;
    private final SnapshotStorage snapshots;
    private final String ffmpegPath;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public CameraInferenceScheduler(EzvizProperties props, DeviceRepository devices, EzvizService ezviz,
                                    WaterflowOnnxService model,
                                    MeasurementRepository measurements,
                                    RealtimeWebSocketHandler realtime,
                                    DebrisOnnxService debris,
                                    AlarmRepository alarms,
                                    SnapshotStorage snapshots,
                                    @Value("${waterflow.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this.props = props;
        this.devices = devices;
        this.ezviz = ezviz;
        this.model = model;
        this.measurements = measurements;
        this.realtime = realtime;
        this.debris = debris;
        this.alarms = alarms;
        this.snapshots = snapshots;
        this.ffmpegPath = ffmpegPath;
    }

    @Scheduled(fixedDelayString = "${waterflow.scheduler-tick-ms:1000}")
    public void tick() {
        long now = System.currentTimeMillis();
        List<EzvizProperties.Device> active;
        try {
            active = devices.listActiveForInference().stream().map(this::fromRow).toList();
        } catch (DataAccessException e) {
            log.error("无法从 device 表读取在线设备，本次调度跳过", e);
            return;
        }
        Set<String> activeSerials = new HashSet<>();
        for (EzvizProperties.Device d : active) {
            String serial = d.serial().toUpperCase();
            activeSerials.add(serial);
            State state = states.compute(serial, (k, old) -> old == null || !Objects.equals(old.params, d.deviceOtherParams())
                    ? new State(model.newMedianSmoother(), d.deviceOtherParams()) : old);
            if (now - state.lastStarted < d.inferIntervalSeconds() * 1000L || !state.running.compareAndSet(false, true)) continue;
            state.lastStarted = now;
            executor.submit(() -> runOne(d, state));
        }
        states.keySet().removeIf(k -> !activeSerials.contains(k));
    }

    private EzvizProperties.Device fromRow(Map<String, Object> row) {
        String serial = String.valueOf(row.get("serial"));
        int channel = number(row.get("channel_no"), 1);
        boolean hd = number(row.get("hd"), 0) != 0;
        String name = row.get("name") == null ? serial : String.valueOf(row.get("name"));
        String params = row.get("device_other_params") == null ? null : String.valueOf(row.get("device_other_params"));
        int interval = Math.max(1, number(row.get("infer_interval_sec"), 60));
        EzvizProperties.Device d = new EzvizProperties.Device(serial, channel, name, hd, params, interval);
        d.setStatus(number(row.get("status"), 1));
        return d;
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try { return value == null ? fallback : Integer.parseInt(value.toString()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private void runOne(EzvizProperties.Device d, State state) {
        try {
            if (d.deviceOtherParams() == null || d.deviceOtherParams().isBlank())
                throw new IllegalArgumentException("设备未配置 21 维 deviceOtherParams");
            List<BufferedImage> frames = captureFrames(d);
            state.latest = model.infer(frames, d.deviceOtherParams(), state.smoother);
            state.lastSuccess = Instant.now();
            state.lastError = null;
            Map<String, Float> alarmParams = HydraulicAlarmUtil.parseKv(d.deviceOtherParams());
            float debrisMinConfidence = alarmParams.getOrDefault("debris_min_conf", 0.5f);
            DebrisOnnxService.DebrisResult debrisResult = debris.detect(frames.get(0), debrisMinConfidence);
            if (state.debrisAlarm.update(debrisResult.count())) {
                String snapshotPath = snapshots.save(d.serial(), frames.get(0));
                if (alarms.createDebris(d.serial(), debrisResult.count(), debrisResult.maxConfidence(), snapshotPath)) {
                    realtime.publishAlarm(d.serial(), "DEBRIS", "检测到漂浮物：" + debrisResult.count() + "个，最高置信度" + debrisResult.maxConfidence(), snapshotPath, Instant.now().toString());
                }
            }

            // 水满：使用水面框中心点按设备标定参数换算水位。
            for (WaterflowOnnxService.Detection det : state.latest.detections()) {
                if (!"water_surface".equals(det.label())) continue;
                float[] box = det.bbox();
                float level = HydraulicAlarmUtil.waterLevelMeters((box[1] + box[3]) / 2f,
                        alarmParams);
                if (state.fullAlarm.update(level)) {
                    String detail = "渠道水位超过阈值：" + level + " m";
                    String path = snapshots.save(d.serial(), frames.get(0));
                    if (alarms.createIfNoOpen(d.serial(), "CHANNEL_FULL", detail, path))
                        realtime.publishAlarm(d.serial(), "CHANNEL_FULL", detail, path, Instant.now().toString());
                }
                break;
            }

            // 遮挡：对原始帧做亮度、对比度和拉普拉斯方差检查。
            HydraulicAlarmUtil.FrameStats stats = frameStats(frames.get(0));
            if (state.occlusionAlarm.update(stats)) {
                String detail = "摄像头画面疑似遮挡：mean=" + stats.mean + ",std=" + stats.std + ",lapVar=" + stats.lapVar;
                String path = snapshots.save(d.serial(), frames.get(0));
                if (alarms.createIfNoOpen(d.serial(), "OCCLUSION", detail, path))
                    realtime.publishAlarm(d.serial(), "OCCLUSION", detail, path, Instant.now().toString());
            }
            try {
                measurements.save(d.serial(), state.latest.waterLevel(), state.latest.velocity(),
                        state.latest.discharge(), state.lastSuccess);
            } catch (DataAccessException | IllegalStateException dbError) {
                // Keep the in-memory latest value available while exposing persistence failure in status.
                state.lastError = "measurement persistence failed: " + dbError.getMessage();
            }
            // Push after inference regardless of database availability; consumers still receive the latest value.
            realtime.publish(d.serial(), state.latest, state.lastSuccess.toString());
        } catch (Exception e) {
            state.lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("设备 {} 推理失败，本周期跳过，不影响其他设备", d.serial(), e);
        } finally {
            state.running.set(false);
        }
    }

    private static HydraulicAlarmUtil.FrameStats frameStats(BufferedImage image) {
        int w = Math.min(image.getWidth(), 320), h = Math.min(image.getHeight(), 180);
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var graphics = scaled.createGraphics();
        graphics.drawImage(image, 0, 0, w, h, null);
        graphics.dispose();
        int[] pixels = scaled.getRGB(0, 0, w, h, null, 0, w);
        return HydraulicAlarmUtil.computeStats(pixels, w, h);
    }

    private List<BufferedImage> captureFrames(EzvizProperties.Device d) throws Exception {
        Map<String, Float> kv = com.model.water.ai.HydraulicAlarmUtil.parseKv(d.deviceOtherParams());
        float fps = requirePositive(kv, "fps");
        float step = requirePositive(kv, "frame_step");
        double sampleFps = fps / step;
        Path dir = Files.createTempDirectory("waterflow-frames-");
        try {
            Path pattern = dir.resolve("frame-%d.png");
            Process p = new ProcessBuilder(ffmpegPath, "-y", "-hide_banner", "-loglevel", "error",
                    "-i", ezviz.getInferenceHlsUrl(d.serial(), d.channelNo(), d.hd()),
                    "-vf", "fps=" + sampleFps, "-frames:v", "2", pattern.toString())
                    .redirectErrorStream(true).start();
            // Drain ffmpeg diagnostics so a verbose failure cannot deadlock the process pipe.
            Thread.ofVirtual().start(() -> {
                try { p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream()); }
                catch (IOException ignored) { }
            });
            if (!p.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IOException("ffmpeg 抽帧超时"); }
            if (p.exitValue() != 0) throw new IOException("ffmpeg 抽帧失败，退出码=" + p.exitValue());
            List<BufferedImage> frames = new ArrayList<>();
            for (int i = 1; i <= 2; i++) {
                BufferedImage image = ImageIO.read(dir.resolve("frame-" + i + ".png").toFile());
                if (image == null) throw new IOException("ffmpeg 未生成第 " + i + " 帧");
                frames.add(image);
            }
            return frames;
        } finally {
            try (var stream = Files.list(dir)) { stream.forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} }); }
            Files.deleteIfExists(dir);
        }
    }

    private static float requirePositive(Map<String, Float> kv, String key) {
        Float value = kv.get(key);
        if (value == null || !Float.isFinite(value) || value <= 0) throw new IllegalArgumentException(key + " 必须为正数");
        return value;
    }

    public Map<String, Object> latest(String serial) {
        State s = states.get(serial.toUpperCase());
        if (s == null) throw new IllegalArgumentException("未配置设备: " + serial);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serial", serial);
        data.put("running", s.running.get());
        data.put("lastStarted", s.lastStarted == 0 ? null : Instant.ofEpochMilli(s.lastStarted));
        data.put("lastSuccess", s.lastSuccess);
        data.put("error", s.lastError);
        data.put("measurement", s.latest);
        return Map.of("code", "200", "msg", "ok", "data", data);
    }

    @PreDestroy
    public void close() { executor.shutdownNow(); }

    private static final class State {
        final WaterflowMedianSmoother smoother;
        final AtomicBoolean running = new AtomicBoolean();
        volatile long lastStarted;
        volatile Instant lastSuccess;
        volatile String lastError;
        volatile WaterflowOnnxService.WaterflowResult latest;
        final HydraulicAlarmUtil.DebrisAlarm debrisAlarm;
        final HydraulicAlarmUtil.ChannelFullAlarm fullAlarm;
        final HydraulicAlarmUtil.OcclusionAlarm occlusionAlarm;
        final String params;
        State(WaterflowMedianSmoother smoother, String params) {
            this.params = params;
            this.smoother = smoother;
            Map<String, Float> kv = HydraulicAlarmUtil.parseKv(params);
            this.debrisAlarm = new HydraulicAlarmUtil.DebrisAlarm(kv);
            this.fullAlarm = new HydraulicAlarmUtil.ChannelFullAlarm(kv);
            this.occlusionAlarm = new HydraulicAlarmUtil.OcclusionAlarm(kv);
        }
    }
}
