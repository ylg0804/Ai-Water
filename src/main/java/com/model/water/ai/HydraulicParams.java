package com.model.water.ai;

import java.util.Map;

/**
 * deviceOtherParams 字符串（key=value 逗号分隔）解析。
 * 按固定顺序组装成模型第二输入 params 所需的 21 维 float 数组，
 * 追加的可选告警 key 不影响组装。
 */
public final class HydraulicParams {

    /** 21 个物理标定参数的固定顺序，与 params_*.json 的 fields 一致。 */
    public static final String[] PARAM_KEYS = {
            "gauge_roi_x1", "gauge_roi_y1", "gauge_roi_x2", "gauge_roi_y2",
            "flow_roi_x1", "flow_roi_y1", "flow_roi_x2", "flow_roi_y2",
            "scale_top_y", "scale_top_value", "scale_bottom_y", "scale_bottom_value",
            "gauge_base_height", "frame_width", "frame_height", "fps", "frame_step",
            "channel_bottom_width", "channel_side_slope_1", "channel_side_slope_2", "alpha"};

    private HydraulicParams() {}

    /** Validate the physical contract without normalizing the original parameter string. */
    public static void validate(String params) {
        parseFloatArray(params);
        Map<String, Float> kv = HydraulicAlarmUtil.parseKv(params);
        for (String key : new String[]{"frame_width", "frame_height", "fps", "frame_step", "channel_bottom_width"})
            if (kv.get(key) <= 0) throw new IllegalArgumentException(key + " 必须大于 0");
        for (String key : new String[]{"frame_width", "frame_height"})
            if (kv.get(key) != Math.rint(kv.get(key))) throw new IllegalArgumentException(key + " 必须为整数");
        if (kv.get("alpha") < 0 || kv.get("alpha") > 1) throw new IllegalArgumentException("alpha 必须在 [0,1] 范围内");
        if (kv.get("channel_side_slope_1") < 0 || kv.get("channel_side_slope_2") < 0)
            throw new IllegalArgumentException("渠道边坡系数不能为负数");
        if (kv.get("scale_top_y").equals(kv.get("scale_bottom_y")))
            throw new IllegalArgumentException("scale_top_y 与 scale_bottom_y 不能相同");
        for (String prefix : new String[]{"gauge_roi_", "flow_roi_"}) {
            float x1 = kv.get(prefix + "x1"), y1 = kv.get(prefix + "y1");
            float x2 = kv.get(prefix + "x2"), y2 = kv.get(prefix + "y2");
            if (x1 < 0 || y1 < 0 || x2 > 640 || y2 > 640 || x2 <= x1 || y2 <= y1)
                throw new IllegalArgumentException(prefix + "区域必须位于 640×640 画布内且左上角小于右下角");
        }
        new HydraulicAlarmUtil.ChannelFullAlarm(kv);
        new HydraulicAlarmUtil.OcclusionAlarm(kv);
        new HydraulicAlarmUtil.DebrisAlarm(kv);
    }

    /** 解析 deviceOtherParams 并组装 21 维 float 参数数组（原始物理值，不做换算）。 */
    public static float[] parseFloatArray(String deviceOtherParams) {
        Map<String, Float> kv = HydraulicAlarmUtil.parseKv(deviceOtherParams);
        float[] p = new float[PARAM_KEYS.length];
        for (int i = 0; i < PARAM_KEYS.length; i++) {
            Float v = kv.get(PARAM_KEYS[i]);
            if (v == null) {
                throw new IllegalArgumentException("deviceOtherParams 缺少必需参数: " + PARAM_KEYS[i]);
            }
            if (!Float.isFinite(v)) {
                throw new IllegalArgumentException("deviceOtherParams 参数必须是有限数字: " + PARAM_KEYS[i]);
            }
            p[i] = v;
        }
        return p;
    }
}
