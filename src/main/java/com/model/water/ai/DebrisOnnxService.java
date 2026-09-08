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

/** Single-frame YOLO floating-debris detector from the platform handover package. */
@Service
public class DebrisOnnxService {
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final int classCount;
    private final float minConfidence;

    public DebrisOnnxService(
            @Value("${waterflow.debris-model-path:models/yolo_debris.onnx}") String modelPath,
            @Value("${waterflow.debris-class-count:1}") int classCount,
            @Value("${waterflow.debris-min-confidence:0.5}") float minConfidence) throws Exception {
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
            if (!(value instanceof float[][][] out) || out.length < 1 || out[0].length < 5)
                throw new IllegalStateException("漂浮物模型输出应为 (1,5+类别数,8400)");
            int channels = out[0].length, anchors = out[0][0].length;
            int count = 0; float max = 0;
            for (int a = 0; a < anchors; a++) {
                float confidence = out[0][4][a];
                if (channels > 5) { for (int c=1;c<classCount && 4+c<channels;c++) confidence *= out[0][4+c][a]; }
                if (confidence >= confidenceThreshold) { count++; max = Math.max(max, confidence); }
            }
            return new DebrisResult(count, max);
        }
    }

    @PreDestroy public void close() throws OrtException { session.close(); }
    public record DebrisResult(int count, float maxConfidence) {}
}
