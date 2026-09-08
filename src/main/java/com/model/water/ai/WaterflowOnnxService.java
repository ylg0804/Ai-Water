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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 三合一水利测量模型（yolo_waterflow_universal.onnx）推理服务。
 *
 * 输入：N≥2 帧连续 BGR 图像 + 21 维物理标定参数；
 * 输出：水位(m)、表面流速(m/s)、流量(m³/s) + 各帧水面框。
 */
@Service
public class WaterflowOnnxService {

    /** 模型输入画布尺寸（YOLO 标准居中 letterbox）。 */
    public static final int INPUT_SIZE = 640;

    /** 后续设备调度层的默认推理间隔；单台 CPU 推理约 1 秒，60 秒一次足够。 */
    public static final int DEFAULT_INFER_INTERVAL_SECONDS = 60;

    /** letterbox 灰边填充值 114（YOLO 标准）。 */
    private static final float FILL = 114f / 255f;

    /** 类别索引：0=水面框 1=流速编码框 2=流量编码框（类别数从输出维度动态读取，模型升级改配置即可）。 */
    private static final int CLASS_WATER_SURFACE = 0;
    private static final int CLASS_VELOCITY = 1;
    private static final int CLASS_DISCHARGE = 2;

    /** 数值编码系数：流速/流量值编在 bbox 的 x2 通道，实际值 = bbox[2] / 1000。 */
    private static final float VALUE_SCALE = 1000f;

    /** YOLO 类别数可通过 waterflow.class-count 调整，以兼容模型类别升级。 */
    @Value("${waterflow.class-count:80}")
    private int classCount = 80;

    @Value("${waterflow.smoothing-window:5}")
    private int smoothingWindowSize = WaterflowMedianSmoother.DEFAULT_WINDOW_SIZE;

    @Value("${waterflow.infer-interval-seconds:60}")
    private int inferIntervalSeconds = DEFAULT_INFER_INTERVAL_SECONDS;

    private final OrtEnvironment env;
    private final OrtSession session;

    public WaterflowOnnxService(@Value("${waterflow.model-path:models/yolo_waterflow_universal.onnx}") String modelPath)
            throws OrtException, IOException {
        this.env = OrtEnvironment.getEnvironment();
        byte[] modelBytes = loadModel(modelPath);
        this.session = env.createSession(modelBytes, new OrtSession.SessionOptions());
    }

    private byte[] loadModel(String modelPath) throws IOException {
        Path path = Path.of(modelPath);
        if (Files.isRegularFile(path)) {
            return Files.readAllBytes(path);
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(modelPath)) {
            if (in == null) {
                throw new IOException("找不到 ONNX 模型文件（文件系统或 classpath）: " + modelPath);
            }
            return in.readAllBytes();
        }
    }

    @PreDestroy
    public void close() throws OrtException {
        session.close();
    }

    /** 创建一台设备专用的结果平滑器；调度层应为每台设备分别持有。 */
    public WaterflowMedianSmoother newMedianSmoother() {
        return new WaterflowMedianSmoother(smoothingWindowSize);
    }

    public int inferIntervalSeconds() {
        if (inferIntervalSeconds < 1) {
            throw new IllegalStateException("waterflow.infer-interval-seconds 必须大于 0");
        }
        return inferIntervalSeconds;
    }

    /**
     * 对 N 帧连续图像做一次推理。
     *
     * @param framesBgr N≥2 帧图像（任意 BufferedImage 类型，内部统一转 BGR），
     *                  帧间隔应等于 deviceOtherParams 中 frame_step 对应的抽帧间隔
     * @param deviceOtherParams 该摄像头的 "key=value,key=value" 参数字符串（21 个必需 key）
     */
    public WaterflowResult infer(List<BufferedImage> framesBgr, String deviceOtherParams) throws OrtException {
        if (framesBgr == null || framesBgr.size() < 2) {
            throw new IllegalArgumentException("至少需要 2 帧连续图像，实际: "
                    + (framesBgr == null ? 0 : framesBgr.size()));
        }
        int n = framesBgr.size();
        if (framesBgr.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("framesBgr 不能包含 null 帧");
        }
        float[] params = HydraulicParams.parseFloatArray(deviceOtherParams);
        Map<String, Float> kv = HydraulicAlarmUtil.parseKv(deviceOtherParams);

        // 1. 预处理：每帧 letterbox → (1,3,640,640) BGR [0,1]，堆叠成 (N,3,640,640)
        float[] flat = new float[n * 3 * INPUT_SIZE * INPUT_SIZE];
        int offset = 0;
        for (BufferedImage frame : framesBgr) {
            float[] tensor = letterboxBgr(frame);
            System.arraycopy(tensor, 0, flat, offset, tensor.length);
            offset += tensor.length;
        }

        try (OnnxTensor imageTensor = OnnxTensor.createTensor(
                     env, FloatBuffer.wrap(flat), new long[]{n, 3, INPUT_SIZE, INPUT_SIZE});
             OnnxTensor paramsTensor = OnnxTensor.createTensor(
                     env, FloatBuffer.wrap(params), new long[]{21});
             OrtSession.Result result = session.run(
                     Map.of("images", imageTensor, "params", paramsTensor))) {

            Object output = result.get(0).getValue();
            if (!(output instanceof float[][][] out)) {
                throw new IllegalStateException("模型输出必须是 float[N][channels][anchors]，实际: "
                        + (output == null ? "null" : output.getClass().getTypeName()));
            }
            return decode(out, kv);
        }
    }

    /** 推理并通过指定设备专用的滑动中值窗口返回平滑结果。 */
    public WaterflowResult infer(List<BufferedImage> framesBgr, String deviceOtherParams,
                                 WaterflowMedianSmoother smoother) throws OrtException {
        if (smoother == null) throw new IllegalArgumentException("smoother 不能为空");
        return smoother.add(infer(framesBgr, deviceOtherParams));
    }

    /**
     * YOLO 标准居中 letterbox（按交接包 end2end_onnx.py 的 preprocess 流程翻译）：
     * 等比缩放（cv2 INTER_LINEAR 采样公式）→ 640×640 画布居中放置 → 灰边填充 114 → BGR → [0,1]。
     * 与 end2end_onnx.py 的差异仅为通用模型约定：640×640（非 384×512）、居中灰边 114（非右下角零填充）、
     * 保持 BGR（模型图内自行翻转 RGB）。
     */
    public static float[] letterboxBgr(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        float scale = Math.min((float) INPUT_SIZE / w, (float) INPUT_SIZE / h);
        int newW = Math.max(1, Math.round(w * scale));
        int newH = Math.max(1, Math.round(h * scale));
        int dx = (INPUT_SIZE - newW) / 2;
        int dy = (INPUT_SIZE - newH) / 2;

        // getRGB 按颜色模型返回真实 sRGB 颜色（0xAARRGGBB），再手动打包成 BGR 字节。
        // 不直接读栅格：ImageIO 解码结果不可依赖栅格布局（如 PNG 会返回
        // TYPE_3BYTE_BGR 但栅格实为 RGB 顺序）；也不经 Graphics2D（同色彩空间会走 blit 捷径跳过转换）
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        byte[] srcBgr = new byte[w * h * 3];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            srcBgr[i * 3] = (byte) (p & 0xFF);
            srcBgr[i * 3 + 1] = (byte) ((p >> 8) & 0xFF);
            srcBgr[i * 3 + 2] = (byte) ((p >> 16) & 0xFF);
        }

        byte[] resized = resizeLinear(srcBgr, w, h, newW, newH);

        // ONNX 张量为 CHW 平面布局：先 B 平面整幅，再 G 平面、R 平面
        float[] tensor = new float[INPUT_SIZE * INPUT_SIZE * 3];
        int plane = INPUT_SIZE * INPUT_SIZE;
        for (int y = 0; y < INPUT_SIZE; y++) {
            int sy = y - dy;
            boolean inY = sy >= 0 && sy < newH;
            for (int x = 0; x < INPUT_SIZE; x++) {
                int sx = x - dx;
                int srcOff = (inY && sx >= 0 && sx < newW) ? (sy * newW + sx) * 3 : -1;
                int idx = y * INPUT_SIZE + x;
                for (int c = 0; c < 3; c++) {
                    tensor[c * plane + idx] = (srcOff >= 0) ? (resized[srcOff + c] & 0xFF) / 255f : FILL;
                }
            }
        }
        return tensor;
    }

    /**
     * 与 cv2.resize(src, dsize, interpolation=INTER_LINEAR) 等价的等比缩放：
     * 采样公式 srcPos = (dstPos + 0.5) * (srcSize / dstSize) - 0.5，
     * 越界坐标钳到边缘（对应 OpenCV 的 sx&lt;0 / sx&gt;=width-1 处理），结果四舍五入到 uint8。
     */
    private static byte[] resizeLinear(byte[] src, int w, int h, int dstW, int dstH) {
        float[] hPass = new float[dstW * h * 3];
        double fx = (double) w / dstW;
        for (int y = 0; y < h; y++) {
            int srcRow = y * w * 3;
            for (int x = 0; x < dstW; x++) {
                double sx = (x + 0.5) * fx - 0.5;
                int x0 = (int) Math.floor(sx);
                double wx = sx - x0;
                if (x0 < 0) {
                    x0 = 0;
                    wx = 0;
                }
                if (x0 >= w - 1) {
                    x0 = w - 1;
                    wx = 0;
                }
                int dstOff = (y * dstW + x) * 3;
                int x1 = Math.min(x0 + 1, w - 1);
                for (int c = 0; c < 3; c++) {
                    float v0 = src[srcRow + x0 * 3 + c] & 0xFF;
                    float v1 = src[srcRow + x1 * 3 + c] & 0xFF;
                    hPass[dstOff + c] = (float) (v0 + wx * (v1 - v0));
                }
            }
        }

        byte[] dst = new byte[dstW * dstH * 3];
        double fy = (double) h / dstH;
        for (int y = 0; y < dstH; y++) {
            double sy = (y + 0.5) * fy - 0.5;
            int y0 = (int) Math.floor(sy);
            double wy = sy - y0;
            if (y0 < 0) {
                y0 = 0;
                wy = 0;
            }
            if (y0 >= h - 1) {
                y0 = h - 1;
                wy = 0;
            }
            for (int x = 0; x < dstW; x++) {
                int c0 = (y0 * dstW + x) * 3;
                int y1 = Math.min(y0 + 1, h - 1);
                int c1 = (y1 * dstW + x) * 3;
                int dstOff = (y * dstW + x) * 3;
                for (int c = 0; c < 3; c++) {
                    float v = (float) (hPass[c0 + c] + wy * (hPass[c1 + c] - hPass[c0 + c]));
                    dst[dstOff + c] = (byte) Math.round(v);
                }
            }
        }
        return dst;
    }

    /**
     * 解码 output0 (N, 4+类别数, 8400)。模型输出的前四个通道是 YOLO 原始
     * [cx,cy,w,h]，这里转换成平台契约要求的 [x1,y1,x2,y2]，保持 float 精度：
     *  - 每帧：水面线框（类别 0），水位由第 0 帧水面框 cy 换算；
     *  - 第 0 帧：流速 = 类别 1 框 bbox[2]/1000，流量 = 类别 2 框 bbox[2]/1000。
     */
    WaterflowResult decode(float[][][] out, Map<String, Float> kv) {
        if (out == null || out.length < 2 || out[0] == null || out[0].length < 7
                || out[0][0] == null || out[0][0].length < 3) {
            throw new IllegalArgumentException("模型输出形状异常，期望 N>=2、channels>=7、anchors>=3");
        }
        int n = out.length;
        int channels = out[0].length;
        if (channels != classCount + 4) {
            throw new IllegalArgumentException("模型类别数与配置不一致: channels=" + channels
                    + ", classCount=" + classCount);
        }
        int anchors = out[0][0].length;
        for (int f = 0; f < n; f++) {
            if (out[f] == null || out[f].length != channels) {
                throw new IllegalArgumentException("模型输出各帧 channel 维度不一致");
            }
            for (int c = 0; c < channels; c++) {
                if (out[f][c] == null || out[f][c].length != anchors) {
                    throw new IllegalArgumentException("模型输出各帧 anchor 维度不一致");
                }
            }
        }

        List<Detection> detections = new ArrayList<>();

        // 各帧水面框
        for (int f = 0; f < n; f++) {
            // 交付模型的编码契约固定使用 anchor 0，不能用最高分框替代。
            int a = 0;
            float[] bbox = toXyxy(out[f], a);
            float conf = out[f][4 + CLASS_WATER_SURFACE][a];
            detections.add(new Detection("water_surface", bbox, conf, f));
        }

        // 水位：取第 0 帧水面框 cy=(y1+y2)/2，代入水尺标定换算
        float[] wsBbox = detections.get(0).bbox();
        float cy = (wsBbox[1] + wsBbox[3]) / 2f;
        float level = HydraulicAlarmUtil.waterLevelMeters(cy, kv);

        // 流速 / 流量：第 0 帧类别 1 / 2 框的 x2 通道承载数值
        int va = 1;
        float[] velocityBox = toXyxy(out[0], va);
        float velocity = velocityBox[2] / VALUE_SCALE;
        detections.add(new Detection("velocity_ms", velocityBox,
                out[0][4 + CLASS_VELOCITY][va], 0));

        int da = 2;
        float[] dischargeBox = toXyxy(out[0], da);
        float discharge = dischargeBox[2] / VALUE_SCALE;
        detections.add(new Detection("discharge_m3s", dischargeBox,
                out[0][4 + CLASS_DISCHARGE][da], 0));

        return new WaterflowResult(level, velocity, discharge, detections);
    }

    private static float[] toXyxy(float[][] frame, int anchor) {
        float cx = frame[0][anchor];
        float cy = frame[1][anchor];
        float width = frame[2][anchor];
        float height = frame[3][anchor];
        return new float[]{cx - width / 2f, cy - height / 2f,
                cx + width / 2f, cy + height / 2f};
    }

    private static int argmax(float[] scores, int length) {
        int best = 0;
        float bestScore = scores[0];
        for (int i = 1; i < length; i++) {
            if (scores[i] > bestScore) {
                bestScore = scores[i];
                best = i;
            }
        }
        return best;
    }

    /** 单次推理结果。waterLevel/velocity/discharge 单位分别为 m、m/s、m³/s。 */
    public record WaterflowResult(double waterLevel, double velocity, double discharge,
                                  List<Detection> detections) {
    }

    /** 解码出的框。bbox 为 [x1,y1,x2,y2] 浮点值（勿取整、勿裁剪）。 */
    public record Detection(String label, float[] bbox, float confidence, int frame) {
    }
}
