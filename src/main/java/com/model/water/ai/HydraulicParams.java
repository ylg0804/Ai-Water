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
