package com.model.water.ai;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;

/** Single-frame YOLO floating-debris detector from the platform handover package. */
@Service
public class DebrisOnnxService {
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final int classCount;
    private final float minConfidence;
    private final List<String> labels;

    public DebrisOnnxService(
            @Value("${waterflow.debris-model-path:models/yolo_debris.onnx}") String modelPath,
            @Value("${waterflow.debris-class-count:1}") int classCount,
            @Value("${waterflow.debris-min-confidence:0.5}") float minConfidence,
            @Value("${waterflow.debris-labels:}") String labelNames) throws Exception {
        if (classCount < 1 || !Float.isFinite(minConfidence) || minConfidence < 0 || minConfidence > 1)
            throw new IllegalArgumentException("漂浮物类别数或置信度配置无效");
        if (labelNames.isBlank()) {
            labels = new ArrayList<>();
            for (int i = 0; i < classCount; i++) labels.add(classCount == 1 ? "floating_debris" : "class_" + i);
        } else {
            labels = java.util.Arrays.stream(labelNames.split(",")).map(String::trim).toList();
            if (labels.size() != classCount || labels.stream().anyMatch(String::isBlank))
                throw new IllegalArgumentException("漂浮物类别名称数量必须等于类别数");
        }
        this.session = env.createSession(loadModel(modelPath), new OrtSession.SessionOptions());
        this.classCount = classCount;
        this.minConfidence = minConfidence;
    }

    private byte[] loadModel(String modelPath) throws IOException {
        Path path = Path.of(modelPath);
        if (Files.isRegularFile(path)) return Files.readAllBytes(path);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(modelPath)) {
            if (in == null) throw new IOException("找不到漂浮物模型: " + modelPath);
            return in.readAllBytes();
        }
    }

    public DebrisResult detect(BufferedImage frame) throws OrtException {
        return detect(frame, minConfidence);
    }

    /** Detect with a device-specific confidence threshold from device_other_params. */
    public DebrisResult detect(BufferedImage frame, float confidenceThreshold) throws OrtException {
        if (!Float.isFinite(confidenceThreshold) || confidenceThreshold < 0f || confidenceThreshold > 1f) {
            throw new IllegalArgumentException("debris_min_conf 必须在 [0,1] 范围内");
        }
        float[] tensor = WaterflowOnnxService.letterboxBgr(frame);
        try (OnnxTensor image = OnnxTensor.createTensor(env, FloatBuffer.wrap(tensor), new long[]{1,3,640,640});
             OrtSession.Result result = session.run(Map.of("images", image))) {
            Object value = result.get(0).getValue();
            if (!(value instanceof float[][][] out) || out.length != 1)
                throw new IllegalStateException("漂浮物模型输出应为 (1,4+类别数,anchors)");
            return decode(out[0], labels, confidenceThreshold);
        }
    }

    @PreDestroy public void close() throws OrtException { session.close(); }
    static DebrisResult decode(float[][] output, List<String> labels, float threshold) {
        if (output == null || output.length != labels.size() + 4 || output[0] == null)
            throw new IllegalArgumentException("漂浮物模型类别数与配置不一致");
        int anchors = output[0].length;
        for (float[] channel : output) if (channel == null || channel.length != anchors)
            throw new IllegalArgumentException("漂浮物模型输出维度不一致");
        List<Box> boxes = new ArrayList<>();
        for (int a = 0; a < anchors; a++) {
            int best = 0;
            for (int c = 1; c < labels.size(); c++) if (output[4 + c][a] > output[4 + best][a]) best = c;
            float confidence = output[4 + best][a], w = output[2][a], h = output[3][a];
            float x = output[0][a], y = output[1][a];
            if (!Float.isFinite(confidence) || confidence < threshold || !Float.isFinite(x) || !Float.isFinite(y)
                    || !Float.isFinite(w) || !Float.isFinite(h) || w <= 0 || h <= 0) continue;
            boxes.add(new Box(x - w / 2, y - h / 2, x + w / 2, y + h / 2, confidence, best));
        }
        boxes.sort(Comparator.comparingDouble(Box::confidence).reversed());
        List<Box> kept = new ArrayList<>();
        for (Box box : boxes.subList(0, Math.min(1000, boxes.size()))) {
            if (kept.stream().noneMatch(other -> other.category == box.category && iou(box, other) > .45)) kept.add(box);
            if (kept.size() >= 200) break;
        }
        return new DebrisResult(kept.size(), kept.isEmpty() ? 0 : kept.get(0).confidence,
                kept.stream().map(b -> labels.get(b.category)).distinct().toList());
    }
    private static double iou(Box a, Box b) {
        double intersection = Math.max(0, Math.min(a.x2, b.x2) - Math.max(a.x1, b.x1))
                * Math.max(0, Math.min(a.y2, b.y2) - Math.max(a.y1, b.y1));
        return intersection / ((a.x2 - a.x1) * (a.y2 - a.y1) + (b.x2 - b.x1) * (b.y2 - b.y1) - intersection);
    }
    private record Box(float x1, float y1, float x2, float y2, float confidence, int category) { }
    public record DebrisResult(int count, float maxConfidence, List<String> labels) { }
}
