package com.model.water.ezviz;

import com.model.water.ai.*;
import com.model.water.device.DeviceRepository;
import com.model.water.realtime.RealtimeWebSocketHandler;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class CameraInferenceScheduler {
    private static final Logger log = LoggerFactory.getLogger(CameraInferenceScheduler.class);
    private final DeviceRepository devices;
    private final WaterflowOnnxService model;
    private final DebrisOnnxService debris;
    private final RealtimeWebSocketHandler realtime;
    private final FrameSource source;
    private final FrameValidator validator;
    private final InferenceStore store;
    private final ExecutorService executor;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Instant runtimeEpoch = Instant.now();

    public CameraInferenceScheduler(DeviceRepository devices, WaterflowOnnxService model, DebrisOnnxService debris,
                                    RealtimeWebSocketHandler realtime, FrameSource source,
                                    FrameValidator validator, InferenceStore store,
                                    @Value("${waterflow.inference-workers:2}") int workers) {
        if (workers < 1) throw new IllegalArgumentException("推理工作线程数必须大于 0");
        this.devices = devices; this.model = model; this.debris = debris;
        this.realtime = realtime; this.source = source; this.validator = validator; this.store = store;
        executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(workers * 2), new ThreadPoolExecutor.AbortPolicy());
    }

    @Scheduled(fixedDelayString = "${waterflow.scheduler-tick-ms:1000}")
    public void tick() {
        List<Map<String, Object>> rows;
        try { rows = devices.listActiveForInference(); }
        catch (Exception e) { log.error("读取设备失败，本周期跳过", e); return; }
        Set<String> active = new HashSet<>();
        long now = System.currentTimeMillis();
        for (Map<String, Object> row : rows) {
            String serial = String.valueOf(row.get("serial")).toUpperCase(Locale.ROOT);
            active.add(serial);
            State state = states.computeIfAbsent(serial, key -> new State());
            if (!state.isPlaybackReady(now)) continue;
            if (now < state.nextAttempt || !state.running.compareAndSet(false, true)) continue;
            state.lastStarted = now;
            try {
                EzvizProperties.Device device = fromRow(row);
                state.nextAttempt = now + device.inferIntervalSeconds() * 1000L;
                executor.submit(() -> runOne(device, state));
            } catch (RejectedExecutionException e) {
                state.nextAttempt = now + 1000; state.running.set(false);
            } catch (Exception e) {
                state.nextAttempt = now + 60000; state.failure(e); state.running.set(false);
                state.runtimeVersion++;
                realtime.publishRuntime(serial, status(serial, state));
                log.warn("设备 {} 配置无效，其余设备继续调度", serial);
            }
        }
        states.forEach((serial, state) -> { if (!active.contains(serial) && !state.running.get()) source.release(serial); });
    }

    private EzvizProperties.Device fromRow(Map<String, Object> row) {
        int interval = number(row.get("infer_interval_sec"), 60), channel = number(row.get("channel_no"), 1);
        if (interval < 1 || channel < 1) throw new IllegalArgumentException("设备周期和通道号必须大于 0");
        return new EzvizProperties.Device(String.valueOf(row.get("serial")), channel, String.valueOf(row.get("name")),
                number(row.get("hd"), 0) != 0, (String) row.get("device_other_params"), interval);
    }
    private static int number(Object value, int fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Number n) || n.doubleValue() != n.intValue()) throw new IllegalArgumentException("设备配置必须为有效整数");
        return n.intValue();
    }

    void runOne(EzvizProperties.Device device, State state) {
        try {
            if (!state.isPlaybackReady(System.currentTimeMillis())) return;
            HydraulicParams.validate(device.deviceOtherParams());
            if (!Objects.equals(state.params, device.deviceOtherParams())) {
                state.configure(model.newMedianSmoother(), device.deviceOtherParams()); source.release(device.serial());
            }
            Map<String, Float> kv = HydraulicAlarmUtil.parseKv(device.deviceOtherParams());
            List<BufferedImage> frames = source.capture(device);
            if (!state.isPlaybackReady(System.currentTimeMillis())) return;
            validator.validate(frames, kv);
            List<InferenceStore.AlarmCandidate> candidates = new ArrayList<>();
            boolean obscured = false;
            for (BufferedImage frame : frames) {
                var stats = frameStats(frame);
                obscured |= state.occlusionAlarm.isAbnormal(stats);
                if (state.occlusionAlarm.update(stats)) addAlarm(candidates, device, frame, "OCCLUSION",
                        "摄像头画面疑似遮挡：mean=" + stats.mean + ",std=" + stats.std + ",lapVar=" + stats.lapVar);
            }
            if (obscured) {
                publishAlarms(device.serial(), store.persist(device.serial(), null, Instant.now(), candidates));
                throw new InvalidFrameException("真实画面疑似遮挡、过曝或模糊，本周期不发布测量值");
            }
            var debrisResult = debris.detect(frames.get(0), kv.getOrDefault("debris_min_conf", .5f));
            if (state.debrisAlarm.update(debrisResult.count())) addAlarm(candidates, device, frames.get(0), "DEBRIS",
                    "检测到漂浮物：" + debrisResult.count() + "个，类别=" + String.join("、", debrisResult.labels())
                            + "，最高置信度=" + debrisResult.maxConfidence());
            var raw = model.infer(frames, device.deviceOtherParams());
            if (raw.detections().stream().noneMatch(d -> d.frame() == 0 && "water_surface".equals(d.label()) && d.confidence() > 0))
                throw new InvalidFrameException("本周期未识别到有效水面框");
            var water = raw.detections().stream().filter(d -> d.frame() == 0 && "water_surface".equals(d.label())).findFirst().orElseThrow();
            validator.validateWaterSurface(frames.get(0), water.bbox(), kv);
            if (state.fullAlarm.update((float) raw.waterLevel())) addAlarm(candidates, device, frames.get(0), "CHANNEL_FULL",
                    "渠道水位达到或超过阈值：" + raw.waterLevel() + " m");
            var result = state.smoother.add(raw);
            if (!state.isPlaybackReady(System.currentTimeMillis())) { state.resetMeasurementAlarms(); state.smoother = model.newMedianSmoother(); return; }
            Instant measuredAt = Instant.now();
            var created = store.persist(device.serial(), result, measuredAt, candidates);
            state.latest = result; state.lastSuccess = measuredAt; state.lastError = null; state.consecutiveFailures = 0;
            realtime.publish(device.serial(), result, measuredAt.toString());
            publishAlarms(device.serial(), created);
        } catch (Exception e) {
            state.failure(e);
            state.resetMeasurementAlarms();
            if (state.smoother != null) state.smoother = model.newMedianSmoother();
            if (!(e instanceof InvalidFrameException) || !e.getMessage().startsWith("真实画面")) state.resetOcclusion();
            log.warn("设备 {} 推理失败，本周期跳过：{}", device.serial(), state.lastError);
        } finally {
            state.runtimeVersion++;
            state.running.set(false);
            realtime.publishRuntime(device.serial(), status(device.serial(), state));
        }
    }

    private void addAlarm(List<InferenceStore.AlarmCandidate> candidates, EzvizProperties.Device device,
                          BufferedImage image, String type, String detail) throws Exception {
        if (candidates.stream().noneMatch(a -> a.type().equals(type)))
            candidates.add(new InferenceStore.AlarmCandidate(type, detail, null, image));
    }
    private void publishAlarms(String serial, List<InferenceStore.AlarmCandidate> alarms) {
        for (var alarm : alarms) realtime.publishAlarm(serial, alarm.type(), alarm.detail(), alarm.snapshotPath(), Instant.now().toString());
    }
    private static HydraulicAlarmUtil.FrameStats frameStats(BufferedImage image) {
        int w = Math.min(image.getWidth(), 320), h = Math.min(image.getHeight(), 180);
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = scaled.createGraphics(); g.drawImage(image, 0, 0, w, h, null); g.dispose();
        return HydraulicAlarmUtil.computeStats(scaled.getRGB(0, 0, w, h, null, 0, w), w, h);
    }
    public Map<String, Object> latest(String serial) {
        State state = states.get(serial.toUpperCase(Locale.ROOT));
        if (state == null) {
            boolean exists = devices.list().stream().anyMatch(d -> serial.equalsIgnoreCase(String.valueOf(d.get("serial"))));
            if (!exists) throw new com.model.water.api.ResourceNotFoundException("未配置设备: " + serial);
            state = new State();
        }
        return Map.of("code", "200", "msg", "ok", "data", status(serial, state));
    }
    public void playback(String serial, String viewerId, long version, boolean ready) {
        if (serial == null || serial.isBlank() || viewerId == null || !viewerId.matches("[a-zA-Z0-9_-]{1,80}") || version < 1)
            throw new IllegalArgumentException("播放状态参数无效");
        boolean enabled = devices.listActiveForInference().stream().anyMatch(d -> serial.equalsIgnoreCase(String.valueOf(d.get("serial"))));
        if (!enabled) throw new com.model.water.api.ResourceNotFoundException("设备不存在或已停用: " + serial);
        State state = states.computeIfAbsent(serial.toUpperCase(Locale.ROOT), key -> new State());
        state.playback(viewerId, version, ready, System.currentTimeMillis());
    }
    private Map<String, Object> status(String serial, State state) {
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("serial", serial); data.put("running", state.running.get());
        data.put("playbackReady", state.isPlaybackReady(System.currentTimeMillis()));
        data.put("lastStarted", state.lastStarted == 0 ? null : Instant.ofEpochMilli(state.lastStarted));
        data.put("lastSuccess", state.lastSuccess); data.put("error", state.lastError); data.put("measurement", state.latest);
        data.put("failureCount", state.failureCount); data.put("consecutiveFailures", state.consecutiveFailures);
        data.put("runtimeEpoch", runtimeEpoch); data.put("runtimeVersion", state.runtimeVersion);
        data.put("lastFailureAt", state.lastFailureAt); data.put("lastFailureReason", state.lastFailureReason);
        return data;
    }
    @PreDestroy public void close() { executor.shutdownNow(); }

    static final class State {
        private record Viewer(long version, boolean ready, long updatedAt) {}
        private final Map<String, Viewer> viewers = new HashMap<>();
        synchronized void playback(String id, long version, boolean ready, long now) {
            viewers.entrySet().removeIf(entry -> now - entry.getValue().updatedAt() > 90_000);
            Viewer previous = viewers.get(id);
            if (previous != null && version <= previous.version()) return;
            boolean wasReady = isPlaybackReady(now);
            viewers.put(id, new Viewer(version, ready, now));
            if (ready && !wasReady) nextAttempt = 0;
        }
        synchronized boolean isPlaybackReady(long now) {
            return viewers.values().stream().anyMatch(viewer -> viewer.ready() && now - viewer.updatedAt() < 45_000);
        }
        final AtomicBoolean running = new AtomicBoolean();
        volatile long lastStarted, nextAttempt, failureCount, consecutiveFailures, runtimeVersion;
        volatile Instant lastSuccess, lastFailureAt;
        volatile String lastError, lastFailureReason;
        volatile WaterflowOnnxService.WaterflowResult latest;
        String params;
        WaterflowMedianSmoother smoother;
        HydraulicAlarmUtil.DebrisAlarm debrisAlarm;
        HydraulicAlarmUtil.ChannelFullAlarm fullAlarm;
        HydraulicAlarmUtil.OcclusionAlarm occlusionAlarm;
        void configure(WaterflowMedianSmoother smoother, String params) {
            this.smoother = smoother; this.params = params; resetMeasurementAlarms(); resetOcclusion();
        }
        void resetMeasurementAlarms() {
            if (params == null) return;
            var kv = HydraulicAlarmUtil.parseKv(params);
            debrisAlarm = new HydraulicAlarmUtil.DebrisAlarm(kv); fullAlarm = new HydraulicAlarmUtil.ChannelFullAlarm(kv);
        }
        void resetOcclusion() { if (params != null) occlusionAlarm = new HydraulicAlarmUtil.OcclusionAlarm(HydraulicAlarmUtil.parseKv(params)); }
        void failure(Exception error) {
            failureCount++; consecutiveFailures++; lastFailureAt = Instant.now();
            lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
            lastError = lastError.replaceAll("https?://[^\\s]+", "[视频地址]"); lastFailureReason = lastError;
        }
    }
}
