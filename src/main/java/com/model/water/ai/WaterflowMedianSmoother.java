package com.model.water.ai;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * 对单台设备的水位、流速和流量做滑动中值平滑。
 * 每台设备应创建并持有一个实例，避免不同设备的数据混合。
 */
public final class WaterflowMedianSmoother {

    public static final int DEFAULT_WINDOW_SIZE = 5;

    private final int windowSize;
    private final Deque<Double> waterLevels = new ArrayDeque<>();
    private final Deque<Double> velocities = new ArrayDeque<>();
    private final Deque<Double> discharges = new ArrayDeque<>();

    public WaterflowMedianSmoother() {
        this(DEFAULT_WINDOW_SIZE);
    }

    public WaterflowMedianSmoother(int windowSize) {
        if (windowSize < 1 || windowSize % 2 == 0) {
            throw new IllegalArgumentException("中值窗口必须是正奇数: " + windowSize);
        }
        this.windowSize = windowSize;
    }

    /**
     * 添加一次原始推理结果并返回平滑后的数值。
     * detections 原样沿用最近一次推理结果，不对 bbox 做任何修改。
     */
    public synchronized WaterflowOnnxService.WaterflowResult add(
            WaterflowOnnxService.WaterflowResult raw) {
        if (raw == null) throw new IllegalArgumentException("推理结果不能为空");
        append(waterLevels, raw.waterLevel());
        append(velocities, raw.velocity());
        append(discharges, raw.discharge());
        return new WaterflowOnnxService.WaterflowResult(
                median(waterLevels), median(velocities), median(discharges), raw.detections());
    }

    public int size() {
        return waterLevels.size();
    }

    public int windowSize() {
        return windowSize;
    }

    private void append(Deque<Double> values, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("测量值必须是有限数字: " + value);
        }
        values.addLast(value);
        if (values.size() > windowSize) values.removeFirst();
    }

    private static double median(Deque<Double> values) {
        double[] sorted = new double[values.size()];
        int i = 0;
        for (double value : values) sorted[i++] = value;
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
