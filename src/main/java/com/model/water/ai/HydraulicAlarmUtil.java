package com.model.water.ai;

import java.util.HashMap;
import java.util.Map;

/**
 * 水利视频测量平台侧告警工具：渠道水满 + 摄像头遮挡。
 *
 * 纯 Java 实现，不依赖 OpenCV / ONNX Runtime，不影响既有推理流程：
 *  - 水满：从既有模型输出的 water_surface 框解出水位（复用 deviceOtherParams
 *    中已有的 21 个标定参数），与新增可选 key channel_full_level 比较；
 *  - 遮挡：对平台解码后的原始帧做图像质量统计（均值/方差/拉普拉斯方差），
 *    连续多帧异常才告警。
 *
 * deviceOtherParams 只需在原有 21 个 key 后追加可选 key（原 parseParams
 * 只读取已知的 21 个 key，追加内容不影响 params 张量组装）：
 *   channel_full_level=1.4        水满阈值（水位 m，超过告警），缺省不告警
 *   occ_min_mean=15               画面灰度均值下限（低于视为黑屏/遮挡）
 *   occ_max_mean=240              画面灰度均值上限（高于视为全白/过曝遮挡）
 *   occ_min_std=10                灰度标准差下限（低于视为无纹理/被糊住）
 *   occ_min_lapvar=20             拉普拉斯方差下限（低于视为严重模糊/污损）
 *   occ_consec_frames=5           连续异常帧数达到才告警
 *   full_consec_count=3           水满连续命中次数达到才告警
 */
public final class HydraulicAlarmUtil {

    private HydraulicAlarmUtil() {}

    // ------------------------------------------------------------------
    // deviceOtherParams 解析（与既有 parseParams 兼容：多出的 key 被忽略）
    // ------------------------------------------------------------------

    public static Map<String, Float> parseKv(String deviceOtherParams) {
        Map<String, Float> kv = new HashMap<>();
        if (deviceOtherParams == null || deviceOtherParams.isBlank()) return kv;
        for (String pair : deviceOtherParams.split(",")) {
            if (pair.isBlank()) continue;
            String[] a = pair.split("=", 2);
            if (a.length != 2 || a[0].trim().isEmpty() || a[1].trim().isEmpty()) {
                throw new IllegalArgumentException("参数项必须是 key=value: " + pair);
            }
            String key = a[0].trim();
            if (kv.containsKey(key)) {
                throw new IllegalArgumentException("参数重复: " + key);
            }
            final float value;
            try {
                value = Float.parseFloat(a[1].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("参数值不是有效数字: " + key, e);
            }
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("参数值必须是有限数字: " + key);
            }
            kv.put(key, value);
        }
        return kv;
    }

    private static float get(Map<String, Float> kv, String key, float def) {
        Float v = kv.get(key);
        return v == null ? def : v;
    }

    // ------------------------------------------------------------------
    // 渠道水满
    // ------------------------------------------------------------------

    /**
     * 由水面线框的 cy（640x640 letterbox 画布像素）解出水位（m）。
     * 与模型图内公式一致（见 README "21 个参数 key 的含义"）：
     *   level = ((cy - gauge_roi_y1)/scale_c - scale_top_y) * dv/dy
     *           + scale_top_value + gauge_base_height
     *   scale_c = min(640/frame_width, 640/frame_height)
     *   dv = scale_bottom_value - scale_top_value, dy = scale_bottom_y - scale_top_y
     *
     * @param cyCanvas water_surface 框中心 y：bbox 为 [x1,y1,x2,y2] 时 cy=(y1+y2)/2
     */
    public static float waterLevelMeters(float cyCanvas, Map<String, Float> kv) {
        if (kv == null) throw new IllegalArgumentException("参数映射不能为空");
        if (!Float.isFinite(cyCanvas)) throw new IllegalArgumentException("水面中心 y 必须是有限数字");
        float fw = required(kv, "frame_width"), fh = required(kv, "frame_height");
        float gaugeY = required(kv, "gauge_roi_y1");
        float topY = required(kv, "scale_top_y"), bottomY = required(kv, "scale_bottom_y");
        float topValue = required(kv, "scale_top_value"), bottomValue = required(kv, "scale_bottom_value");
        float baseHeight = required(kv, "gauge_base_height");
        if (!(fw > 0) || !(fh > 0)) throw new IllegalArgumentException("frame_width/frame_height 必须大于 0");
        if (bottomY == topY) throw new IllegalArgumentException("scale_top_y 与 scale_bottom_y 不能相同");
        float scaleC = Math.min(640f / fw, 640f / fh);
        float dv = bottomValue - topValue;
        float dy = bottomY - topY;
        float level = ((cyCanvas - gaugeY) / scaleC - topY) * (dv / dy)
                + topValue + baseHeight;
        return Math.max(level, 0f);
    }

    private static float required(Map<String, Float> kv, String key) {
        Float value = kv.get(key);
        if (value == null || !Float.isFinite(value)) {
            throw new IllegalArgumentException("缺少或无效参数: " + key);
        }
        return value;
    }

    /** 水满判定（带连续命中计数，防抖）。 */
    public static final class ChannelFullAlarm {
        private final float fullLevel;   // 阈值 m
        private final int consecNeed;    // 需要连续命中的次数
        private int consec = 0;

        public ChannelFullAlarm(Map<String, Float> kv) {
            this.fullLevel = get(kv, "channel_full_level", Float.NaN);
            this.consecNeed = (int) get(kv, "full_consec_count", 3f);
        }

        /** @return true 表示触发水满告警；未配置 channel_full_level 时恒 false */
        public boolean update(float levelMeters) {
            if (Float.isNaN(fullLevel)) return false;
            consec = (levelMeters >= fullLevel) ? consec + 1 : 0;
            return consec >= consecNeed;
        }
    }

    // ------------------------------------------------------------------
    // 摄像头遮挡（黑屏 / 全白 / 被糊住 / 严重污损模糊）
    // ------------------------------------------------------------------

    /** 单帧图像质量统计。 */
    public static final class FrameStats {
        public final float mean;     // 灰度均值 [0,255]
        public final float std;      // 灰度标准差
        public final float lapVar;   // 拉普拉斯方差（清晰度）

        FrameStats(float mean, float std, float lapVar) {
            this.mean = mean; this.std = std; this.lapVar = lapVar;
        }
    }

    /**
     * 计算图像质量统计。
     *
     * @param rgb 打包 RGB 像素（0xRRGGBB），长度 = w*h；为控制耗时，
     *            建议先缩到 320x180 左右再传入
     */
    public static FrameStats computeStats(int[] rgb, int w, int h) {
        if (rgb == null || w <= 0 || h <= 0 || rgb.length < (long) w * h) {
            throw new IllegalArgumentException("rgb 像素数组与图像尺寸不匹配");
        }
        int n = w * h;
        float[] gray = new float[n];
        double sum = 0, sum2 = 0;
        for (int i = 0; i < n; i++) {
            int p = rgb[i];
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            float y = 0.299f * r + 0.587f * g + 0.114f * b;
            gray[i] = y;
            sum += y; sum2 += (double) y * y;
        }
        double mean = sum / n;
        double std = Math.sqrt(Math.max(sum2 / n - mean * mean, 0));

        // 4 邻域拉普拉斯：4*c - left - right - up - down
        double lsum = 0, lsum2 = 0;
        int cnt = 0;
        for (int y = 1; y < h - 1; y++) {
            int row = y * w;
            for (int x = 1; x < w - 1; x++) {
                int i = row + x;
                double lap = 4 * gray[i] - gray[i - 1] - gray[i + 1]
                        - gray[i - w] - gray[i + w];
                lsum += lap; lsum2 += lap * lap; cnt++;
            }
        }
        double lmean = cnt > 0 ? lsum / cnt : 0;
        double lapVar = cnt > 0 ? Math.max(lsum2 / cnt - lmean * lmean, 0) : 0;
        return new FrameStats((float) mean, (float) std, (float) lapVar);
    }

    /** 遮挡判定（带连续异常帧计数，防抖）。 */
    public static final class OcclusionAlarm {
        private final float minMean, maxMean, minStd, minLapVar;
        private final int consecNeed;
        private int consec = 0;

        public OcclusionAlarm(Map<String, Float> kv) {
            this.minMean = get(kv, "occ_min_mean", 15f);
            this.maxMean = get(kv, "occ_max_mean", 240f);
            this.minStd = get(kv, "occ_min_std", 10f);
            this.minLapVar = get(kv, "occ_min_lapvar", 20f);
            this.consecNeed = (int) get(kv, "occ_consec_frames", 5f);
        }

        /** 单帧是否异常。 */
        public boolean isAbnormal(FrameStats s) {
            return s.mean < minMean || s.mean > maxMean
                    || s.std < minStd || s.lapVar < minLapVar;
        }

        /** @return true 表示触发遮挡告警 */
        public boolean update(FrameStats s) {
            consec = isAbnormal(s) ? consec + 1 : 0;
            return consec >= consecNeed;
        }
    }

    // ------------------------------------------------------------------
    // 河道漂浮物（配合独立模型 yolo_debris.onnx，标准单输入 YOLO 流程）
    // ------------------------------------------------------------------

    /**
     * 漂浮物判定（带连续命中计数，防抖）。
     *
     * 平台对 yolo_debris.onnx 的解码结果先做置信度过滤（建议 >= debris_min_conf，
     * 默认 0.5），再统计 label=floating_debris 的框个数传给 update()。
     * 漂浮物随水流持续存在，连续多次推理都有检出才告警，可滤掉水花/反光的瞬时误检。
     *
     * deviceOtherParams 可选 key：
     *   debris_min_count=1       单次推理至少检出几个漂浮物（默认 1）
     *   debris_consec_frames=3   连续命中次数（默认 3）
     *   debris_min_conf=0.5      置信度过滤阈值（默认 0.5，供平台解码用）
     */
    public static final class DebrisAlarm {
        private final int minCount;
        private final int consecNeed;
        private int consec = 0;

        public DebrisAlarm(Map<String, Float> kv) {
            this.minCount = (int) get(kv, "debris_min_count", 1f);
            this.consecNeed = (int) get(kv, "debris_consec_frames", 3f);
        }

        /**
         * @param debrisCount 本次推理置信度过滤后的 floating_debris 框个数
         * @return true 表示触发漂浮物告警
         */
        public boolean update(int debrisCount) {
            consec = (debrisCount >= minCount) ? consec + 1 : 0;
            return consec >= consecNeed;
        }
    }

    // ------------------------------------------------------------------
    // 平台调用示例
    // ------------------------------------------------------------------

    /*
    Map<String, Float> kv = HydraulicAlarmUtil.parseKv(deviceOtherParams);
    HydraulicAlarmUtil.ChannelFullAlarm fullAlarm = new HydraulicAlarmUtil.ChannelFullAlarm(kv);
    HydraulicAlarmUtil.OcclusionAlarm occAlarm = new HydraulicAlarmUtil.OcclusionAlarm(kv);

    // 每次推理后：
    // 1) 水满：取第 0 帧 label=water_surface 的框，bbox=[x1,y1,x2,y2]（float 原样读，勿取整）
    float cy = (bbox[1] + bbox[3]) / 2f;
    float level = HydraulicAlarmUtil.waterLevelMeters(cy, kv);
    if (fullAlarm.update(level)) { // 上报道平台：渠道水满，level=...
    }

    // 2) 遮挡：取送入推理前的原始帧（缩小到 ~320x180），转成打包 RGB 数组
    HydraulicAlarmUtil.FrameStats s = HydraulicAlarmUtil.computeStats(rgbPixels, w, h);
    if (occAlarm.update(s)) { // 上报道平台：摄像头遮挡/污损
    }
    */
}
