package com.model.water.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WaterflowMedianSmootherTest {

    @Test
    void usesMedianOfLatestFiveSamples() {
        WaterflowMedianSmoother smoother = new WaterflowMedianSmoother(5);
        WaterflowOnnxService.WaterflowResult result = null;
        double[] levels = {5, 1, 3, 100, 4, 6};
        for (double level : levels) {
            result = smoother.add(new WaterflowOnnxService.WaterflowResult(
                    level, level * 10, level * 100, List.of()));
        }

        assertEquals(4, result.waterLevel()); // latest window: 1, 3, 4, 6, 100
        assertEquals(40, result.velocity());
        assertEquals(400, result.discharge());
        assertEquals(5, smoother.size());
    }

    @Test
    void keepsDetectionsFromRawResult() {
        WaterflowMedianSmoother smoother = new WaterflowMedianSmoother();
        List<WaterflowOnnxService.Detection> detections = List.of(
                new WaterflowOnnxService.Detection("water_surface", new float[]{1, 2, 3, 4}, 0.99f, 0));
        WaterflowOnnxService.WaterflowResult raw =
                new WaterflowOnnxService.WaterflowResult(1, 2, 3, detections);

        assertSame(detections, smoother.add(raw).detections());
    }

    @Test
    void requiresOddPositiveWindowAndFiniteValues() {
        assertThrows(IllegalArgumentException.class, () -> new WaterflowMedianSmoother(0));
        assertThrows(IllegalArgumentException.class, () -> new WaterflowMedianSmoother(4));
        assertThrows(IllegalArgumentException.class, () -> new WaterflowMedianSmoother().add(
                new WaterflowOnnxService.WaterflowResult(Double.NaN, 1, 1, List.of())));
    }
}
