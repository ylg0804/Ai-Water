package com.model.water.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class WaterflowOnnxServiceTest {
    @org.springframework.boot.test.mock.mockito.MockBean
    com.model.water.ezviz.FrameSource frameSource;
    @org.springframework.boot.test.mock.mockito.MockBean
    com.model.water.device.DeviceRepository devices;

    /** F98228237 摄像头的 deviceOtherParams（与交付包 params_F98228237.json 一致）。 */
    private static final String F98228237_PARAMS =
            "gauge_roi_x1=206.666672,gauge_roi_y1=306.666656,gauge_roi_x2=263.333344,gauge_roi_y2=473.333344,"
                    + "flow_roi_x1=283.333344,flow_roi_y1=290.0,flow_roi_x2=383.333344,flow_roi_y2=373.333344,"
                    + "scale_top_y=0.0,scale_top_value=0.5,scale_bottom_y=500.0,scale_bottom_value=1.5,"
                    + "gauge_base_height=0.0,frame_width=1920.0,frame_height=1080.0,fps=12.5,frame_step=6.25,"
                    + "channel_bottom_width=0.8,channel_side_slope_1=1.5,channel_side_slope_2=1.5,alpha=0.85";

    /** G04690603 摄像头的 deviceOtherParams（与交付包 params_G04690603.json 一致）。 */
    private static final String G04690603_PARAMS =
            "gauge_roi_x1=90.0,gauge_roi_y1=340.0,gauge_roi_x2=220.0,gauge_roi_y2=490.0,"
                    + "flow_roi_x1=260.0,flow_roi_y1=300.0,flow_roi_x2=420.0,flow_roi_y2=380.0,"
                    + "scale_top_y=0.0,scale_top_value=0.5,scale_bottom_y=260.0,scale_bottom_value=1.5,"
                    + "gauge_base_height=0.0,frame_width=640.0,frame_height=360.0,fps=12.5,frame_step=6.25,"
                    + "channel_bottom_width=0.8,channel_side_slope_1=1.5,channel_side_slope_2=1.5,alpha=0.85";

    @Autowired
    private WaterflowOnnxService service;

    @Test
    void inferOnF98228237Frames() throws Exception {
        // 两帧取自实拍录像 camera_F98228237_20260720_151327.mp4（t=10.0s 与 t=10.5s，间隔 = frame_step 0.5s）
        List<BufferedImage> frames = List.of(loadFrame("frames/F98228237_t10.0.png"),
                loadFrame("frames/F98228237_t10.5.png"));

        WaterflowOnnxService.WaterflowResult r = service.infer(frames, F98228237_PARAMS);

        System.out.printf("F98228237: 水位=%.4f m, 流速=%.4f m/s, 流量=%.4f m³/s%n",
                r.waterLevel(), r.velocity(), r.discharge());
        r.detections().forEach(d -> System.out.printf("  frame %d: %s conf=%s bbox=%s%n",
                d.frame(), d.label(), Float.toString(d.confidence()), Arrays.toString(d.bbox())));

        // 解码结构断言：每帧 1 个水面框 + 第 0 帧流速/流量编码框
        assertEquals(4, r.detections().size());
        assertEquals("water_surface", r.detections().get(0).label());
        assertEquals("water_surface", r.detections().get(1).label());
        assertEquals("velocity_ms", r.detections().get(2).label());
        assertEquals("discharge_m3s", r.detections().get(3).label());
        r.detections().forEach(d -> assertEquals(0.99f, d.confidence(), 0.000001f));

        // 数值编码框保留模型中的 float 坐标，不取整、不裁剪。
        float velocityX2 = r.detections().get(2).bbox()[2];
        float dischargeX2 = r.detections().get(3).bbox()[2];
        assertTrue(velocityX2 != Math.rint(velocityX2), "流速编码坐标不应被取整: " + velocityX2);
        assertTrue(dischargeX2 != Math.rint(dischargeX2), "流量编码坐标不应被取整: " + dischargeX2);
        assertEquals(velocityX2 / 1000f, r.velocity(), 0.0000001);
        assertEquals(dischargeX2 / 1000f, r.discharge(), 0.0000001);
        // 当前标定为占位值，绝对数值仅验证量级合理（水位 0~10m、流速 0~10m/s、流量 0~100m³/s）
        assertTrue(r.waterLevel() > 0.5 && r.waterLevel() < 2, "水位量级异常: " + r.waterLevel());
        assertTrue(r.velocity() >= 0 && r.velocity() < 10, "流速量级异常: " + r.velocity());
        assertTrue(r.discharge() >= 0 && r.discharge() < 100, "流量量级异常: " + r.discharge());
    }

    @Test
    void inferWithG04690603Params() throws Exception {
        // 同一模型 + 换摄像头参数：验证参数切换生效（画面分辨率不同，仅验证流程跑通、无异常）
        List<BufferedImage> frames = List.of(loadFrame("frames/F98228237_t10.0.png"),
                loadFrame("frames/F98228237_t10.5.png"));

        WaterflowOnnxService.WaterflowResult r = service.infer(frames, G04690603_PARAMS);

        System.out.printf("G04690603: 水位=%.4f m, 流速=%.4f m/s, 流量=%.4f m³/s%n",
                r.waterLevel(), r.velocity(), r.discharge());
        assertTrue(r.waterLevel() > 0.5 && r.waterLevel() < 2, "水位量级异常: " + r.waterLevel());
        assertTrue(r.velocity() >= 0 && r.velocity() < 10, "流速量级异常: " + r.velocity());
        assertTrue(r.discharge() >= 0 && r.discharge() < 100, "流量量级异常: " + r.discharge());
    }

    @Test
    void letterboxHandlesSinglePixelFrames() {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, new Color(10, 20, 30).getRGB());
        float[] tensor = service.letterboxBgr(image);
        int plane = WaterflowOnnxService.INPUT_SIZE * WaterflowOnnxService.INPUT_SIZE;
        assertEquals(30f / 255f, tensor[0], 0.000001f);
        assertEquals(20f / 255f, tensor[plane], 0.000001f);
        assertEquals(10f / 255f, tensor[2 * plane], 0.000001f);
    }

    @Test
    void inferThreeFramesPreservesExpectedDetectionContract() throws Exception {
        BufferedImage first = loadFrame("frames/F98228237_t10.0.png");
        BufferedImage second = loadFrame("frames/F98228237_t10.5.png");

        // 第三帧复用已有实拍帧，只验证动态批次 N=3 的输入输出契约。
        WaterflowOnnxService.WaterflowResult r = service.infer(
                List.of(first, second, second), F98228237_PARAMS);

        assertEquals(5, r.detections().size(), "三帧应返回 3 个水面框和 2 个数值编码框");
        for (int frame = 0; frame < 3; frame++) {
            WaterflowOnnxService.Detection water = r.detections().get(frame);
            assertEquals("water_surface", water.label());
            assertEquals(frame, water.frame());
            assertEquals(0.99f, water.confidence(), 0.000001f);
        }

        WaterflowOnnxService.Detection velocity = r.detections().get(3);
        WaterflowOnnxService.Detection discharge = r.detections().get(4);
        assertEquals("velocity_ms", velocity.label());
        assertEquals("discharge_m3s", discharge.label());
        assertEquals(0, velocity.frame());
        assertEquals(0, discharge.frame());
        assertEquals(0.99f, velocity.confidence(), 0.000001f);
        assertEquals(0.99f, discharge.confidence(), 0.000001f);
        assertEquals(velocity.bbox()[2] / 1000f, r.velocity(), 0.0000001);
        assertEquals(discharge.bbox()[2] / 1000f, r.discharge(), 0.0000001);
    }

    @Test
    void decodeKeepsFloatCoordinatesWithoutClipping() {
        float[][][] output = new float[2][84][3];
        // Anchor 0: water_surface -> [90, 400, 220, 406].
        output[0][0][0] = 155.0f;
        output[0][1][0] = 403.0f;
        output[0][2][0] = 130.0f;
        output[0][3][0] = 6.0f;
        output[1][0][0] = -5.25f;
        output[1][1][0] = 400.125f;
        output[1][2][0] = 130.5f;
        output[1][3][0] = 6.25f;
        output[0][4][0] = 0.99f;
        output[1][4][0] = 0.99f;

        // Anchor 1: velocity -> [0, 0, 3.2, 2].
        output[0][0][1] = 1.6f;
        output[0][1][1] = 1.0f;
        output[0][2][1] = 3.2f;
        output[0][3][1] = 2.0f;
        output[0][5][1] = 0.99f;

        // Anchor 2: discharge -> [0, 28, 3.6, 32].
        output[0][0][2] = 1.8f;
        output[0][1][2] = 30.0f;
        output[0][2][2] = 3.6f;
        output[0][3][2] = 4.0f;
        output[0][6][2] = 0.99f;

        Map<String, Float> params = HydraulicAlarmUtil.parseKv(F98228237_PARAMS);
        WaterflowOnnxService.WaterflowResult result = service.decode(output, params);
        assertArrayEquals(new float[]{90f, 400f, 220f, 406f}, result.detections().get(0).bbox());
        assertArrayEquals(new float[]{-70.5f, 397f, 60f, 403.25f}, result.detections().get(1).bbox());
        assertArrayEquals(new float[]{0f, 0f, 3.2f, 2f}, result.detections().get(2).bbox());
        assertArrayEquals(new float[]{0f, 28f, 3.6f, 32f}, result.detections().get(3).bbox());
        assertEquals(0.99f, result.detections().get(0).confidence(), 0.000001f);
        assertEquals(0.99f, result.detections().get(2).confidence(), 0.000001f);
        assertEquals(0.0032, result.velocity(), 0.0000001);
        assertEquals(0.0036, result.discharge(), 0.0000001);
    }

    private static BufferedImage loadFrame(String path) throws Exception {
        try (InputStream in = WaterflowOnnxServiceTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("测试帧不存在: " + path);
            }
            return ImageIO.read(in);
        }
    }
}
