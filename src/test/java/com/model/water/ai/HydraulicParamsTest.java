package com.model.water.ai;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HydraulicParamsTest {

    private static final String REQUIRED =
            "gauge_roi_x1=1,gauge_roi_y1=2,gauge_roi_x2=3,gauge_roi_y2=4," +
            "flow_roi_x1=5,flow_roi_y1=6,flow_roi_x2=7,flow_roi_y2=8," +
            "scale_top_y=9,scale_top_value=10,scale_bottom_y=11,scale_bottom_value=12," +
            "gauge_base_height=13,frame_width=640,frame_height=360,fps=12.5,frame_step=6.25," +
            "channel_bottom_width=0.8,channel_side_slope_1=1.5,channel_side_slope_2=1.5,alpha=0.85";

    @Test
    void parsesRequiredKeysAndIgnoresOptionalKeys() {
        float[] values = HydraulicParams.parseFloatArray(REQUIRED + ", channel_full_level=1.4");
        assertEquals(21, values.length);
        assertEquals(12.5f, values[15]);
        assertEquals(6.25f, values[16]);
        assertEquals(0.85f, values[20]);
    }

    @Test
    void rejectsMalformedAndDuplicateEntries() {
        assertThrows(IllegalArgumentException.class,
                () -> HydraulicAlarmUtil.parseKv(REQUIRED + ",broken"));
        assertThrows(IllegalArgumentException.class,
                () -> HydraulicAlarmUtil.parseKv(REQUIRED + ",alpha=0.9"));
        assertThrows(IllegalArgumentException.class,
                () -> HydraulicParams.parseFloatArray(REQUIRED.replace("fps=12.5", "fps=NaN")));
    }

    @Test
    void waterLevelUsesCanvasCenterAndCalibration() {
        Map<String, Float> kv = HydraulicAlarmUtil.parseKv(REQUIRED);
        assertEquals(22f, HydraulicAlarmUtil.waterLevelMeters(10f, kv), 0.0001f);
    }
}
