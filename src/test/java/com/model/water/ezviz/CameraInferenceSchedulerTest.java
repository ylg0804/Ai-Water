package com.model.water.ezviz;

import com.model.water.ai.*;
import com.model.water.alarm.SnapshotStorage;
import com.model.water.device.DeviceRepository;
import com.model.water.device.DeviceValidationTest;
import com.model.water.realtime.RealtimeWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CameraInferenceSchedulerTest {
    final DeviceRepository devices = mock(DeviceRepository.class);
    final WaterflowOnnxService model = mock(WaterflowOnnxService.class);
    final DebrisOnnxService debris = mock(DebrisOnnxService.class);
    final SnapshotStorage snapshots = mock(SnapshotStorage.class);
    final RealtimeWebSocketHandler realtime = mock(RealtimeWebSocketHandler.class);
    final FrameSource source = mock(FrameSource.class);
    final InferenceStore store = mock(InferenceStore.class);
    final CameraInferenceScheduler.State state = new CameraInferenceScheduler.State();
    final EzvizProperties.Device device = new EzvizProperties.Device("CAM", 1, "test", false, DeviceValidationTest.PARAMS, 60);
    CameraInferenceScheduler scheduler;

    @BeforeEach void setup() throws Exception {
        scheduler = new CameraInferenceScheduler(devices, model, debris, realtime, source, new FrameValidator(""), store, 2);
        state.playback("test-viewer", 1, true, System.currentTimeMillis());
        when(model.newMedianSmoother()).thenAnswer(invocation -> new WaterflowMedianSmoother());
        when(model.infer(anyList(), anyString())).thenReturn(new WaterflowOnnxService.WaterflowResult(1, .1, .2,
                List.of(new WaterflowOnnxService.Detection("water_surface", new float[]{1,150,3,156}, .99f, 0))));
        when(debris.detect(any(), anyFloat())).thenReturn(new DebrisOnnxService.DebrisResult(0, 0, List.of()));
        when(store.persist(anyString(), any(), any(), anyList())).thenReturn(List.of());
    }
    @AfterEach void close() { scheduler.close(); }
    static BufferedImage textured() {
        BufferedImage image = new BufferedImage(640, 360, 1);
        Random random = new Random(7);
        for (int y = 0; y < 360; y++) for (int x = 0; x < 640; x++) image.setRGB(x, y, random.nextInt(0x1000000));
        return image;
    }
    @Test void sourceErrorDoesNotReachInferencePersistenceOrSuccessPublication() throws Exception {
        var card = FrameValidatorTest.plain(Color.WHITE); var g = card.createGraphics(); g.setColor(Color.BLACK);
        g.drawString("Maximum concurrent viewers reached (9048)", 180, 180); g.dispose();
        when(source.capture(device)).thenReturn(List.of(card, card));
        scheduler.runOne(device, state);
        assertEquals(1, state.failureCount); assertNull(state.lastSuccess); assertNull(state.latest);
        verifyNoInteractions(store, debris, snapshots);
        verify(model, never()).infer(anyList(), anyString());
        verify(realtime, never()).publish(anyString(), any(), anyString());
    }
    @Test void databaseFailureRetainsPreviousSuccessAndNextGoodCycleRecovers() throws Exception {
        var image = textured(); when(source.capture(device)).thenReturn(List.of(image, image));
        scheduler.runOne(device, state);
        var lastSuccess = state.lastSuccess; var latest = state.latest;
        when(store.persist(anyString(), any(), any(), anyList())).thenThrow(new IllegalStateException("database offline"));
        clearInvocations(realtime);
        scheduler.runOne(device, state);
        assertSame(lastSuccess, state.lastSuccess); assertSame(latest, state.latest);
        assertEquals(1, state.failureCount); assertEquals(1, state.consecutiveFailures);
        verify(realtime, never()).publish(anyString(), any(), anyString());
        doReturn(List.of()).when(store).persist(anyString(), any(), any(), anyList());
        scheduler.runOne(device, state);
        assertEquals(1, state.failureCount); assertEquals(0, state.consecutiveFailures); assertNull(state.lastError);
        verify(realtime).publish(eq("CAM"), any(), anyString());
    }
    @Test void realBlackFramesStillTriggerOcclusionButNeverProduceHydraulicMeasurements() throws Exception {
        var image = FrameValidatorTest.plain(Color.BLACK);
        when(source.capture(device)).thenReturn(List.of(image, image));
        when(snapshots.save(anyString(), any())).thenReturn("CAM/test.jpg");
        for (int i = 0; i < 3; i++) scheduler.runOne(device, state);
        assertEquals(3, state.failureCount); assertNull(state.lastSuccess);
        verify(store, atLeastOnce()).persist(eq("CAM"), isNull(), any(), argThat(candidates -> candidates.stream().anyMatch(a -> "OCCLUSION".equals(a.type()))));
        verify(model, never()).infer(anyList(), anyString());
    }
    @Test void noWaterSurfaceIsARecordedFailure() throws Exception {
        var image = textured(); when(source.capture(device)).thenReturn(List.of(image, image));
        when(model.infer(anyList(), anyString())).thenReturn(new WaterflowOnnxService.WaterflowResult(0, 0, 0, List.of()));
        scheduler.runOne(device, state);
        assertTrue(state.lastError.contains("水面框")); verifyNoInteractions(store);
    }
    @Test void noPlaybackDoesNotPullStreamInferOrPersist() {
        scheduler.runOne(device, new CameraInferenceScheduler.State());
        verifyNoInteractions(source, model, debris, store);
    }
    @Test void stoppingPlaybackWhileCapturingPreventsInference() throws Exception {
        var image = textured();
        when(source.capture(device)).thenAnswer(invocation -> {
            state.playback("test-viewer", 2, false, System.currentTimeMillis());
            return List.of(image, image);
        });
        scheduler.runOne(device, state);
        verify(model, never()).infer(anyList(), anyString());
        verifyNoInteractions(debris, store);
        assertEquals(0, state.failureCount);
    }
    @Test void viewerLeasesExpireRejectLateUpdatesAndDoNotStopOtherViewers() {
        var runtime = new CameraInferenceScheduler.State();
        runtime.playback("A", 1, true, 100_000);
        assertTrue(runtime.isPlaybackReady(144_999));
        assertFalse(runtime.isPlaybackReady(145_000));
        runtime.playback("A", 3, false, 145_000);
        runtime.playback("A", 2, true, 145_001);
        assertFalse(runtime.isPlaybackReady(145_001));
        runtime.playback("B", 1, true, 145_002);
        runtime.playback("A", 4, false, 145_003);
        assertTrue(runtime.isPlaybackReady(145_004));
    }
    @Test void schedulerWaitsForPlaybackAndStartsWithoutWaitingForOldInterval() throws Exception {
        var row = Map.<String,Object>of("serial", "CAM", "infer_interval_sec", 60, "device_other_params", DeviceValidationTest.PARAMS);
        when(devices.listActiveForInference()).thenReturn(List.of(row));
        scheduler.tick();
        verifyNoInteractions(source);
        when(source.capture(any())).thenReturn(List.of(textured(), textured()));
        scheduler.playback("cam", "viewer", 1, true);
        scheduler.tick();
        verify(realtime, timeout(2000)).publish(eq("CAM"), any(), anyString());
        scheduler.playback("CAM", "viewer", 2, false);
        clearInvocations(source);
        scheduler.tick();
        verifyNoInteractions(source);
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="waterflow.test.video",matches=".+")
    void actualVideoReplayRunsExistingModelAfterPlaybackWithoutCloud() throws Exception {
        var video=java.nio.file.Path.of(System.getProperty("waterflow.test.video"));
        var properties=new TestSourceProperties(); properties.setEnabled(true); properties.setRoot(video.getParent().toString());
        properties.getFiles().put("CAM",video.getFileName().toString());
        var cloud=mock(EzvizService.class);
        var replay=new FfmpegFrameSource(cloud,properties,System.getProperty("waterflow.test.ffmpeg"));
        replay.validateExecutable();
        var actualModel=new WaterflowOnnxService("models/yolo_waterflow_universal.onnx");
        when(model.infer(anyList(),anyString())).thenAnswer(invocation -> actualModel.infer(invocation.getArgument(0),invocation.getArgument(1)));
        var runner=new CameraInferenceScheduler(devices,model,debris,realtime,replay,new FrameValidator(""),store,2);
        String params="gauge_roi_x1=206.666672,gauge_roi_y1=306.666656,gauge_roi_x2=263.333344,gauge_roi_y2=473.333344,flow_roi_x1=283.333344,flow_roi_y1=290,flow_roi_x2=383.333344,flow_roi_y2=373.333344,scale_top_y=0,scale_top_value=0.5,scale_bottom_y=500,scale_bottom_value=1.5,gauge_base_height=0,frame_width=1920,frame_height=1080,fps=12.5,frame_step=6.25,channel_bottom_width=0.8,channel_side_slope_1=1.5,channel_side_slope_2=1.5,alpha=0.85";
        var runtime=new CameraInferenceScheduler.State();
        runtime.playback("replay-viewer",1,true,System.currentTimeMillis());
        try {
            runner.runOne(new EzvizProperties.Device("CAM",1,"replay",true,params,60),runtime);
            assertNull(runtime.lastError,runtime.lastError);
            assertNotNull(runtime.lastSuccess); assertNotNull(runtime.latest);
            assertTrue(Double.isFinite(runtime.latest.waterLevel()));
            verify(model).infer(anyList(),anyString());
            verify(store).persist(eq("CAM"),notNull(),any(),anyList());
            verify(realtime).publish(eq("CAM"),notNull(),anyString());
            verifyNoInteractions(cloud);
        } finally {runner.close();actualModel.close();}
    }
    @Test void invalidDeviceDoesNotPreventOtherDevicesBeingScheduled() throws Exception {
        Map<String,Object> bad = Map.of("serial", "BAD", "infer_interval_sec", 0);
        Map<String,Object> good = Map.of("serial", "GOOD", "infer_interval_sec", 60, "device_other_params", DeviceValidationTest.PARAMS);
        when(devices.listActiveForInference()).thenReturn(List.of(bad, good));
        scheduler.playback("BAD", "viewer", 1, true);
        scheduler.playback("GOOD", "viewer", 1, true);
        when(source.capture(any())).thenThrow(new java.io.IOException("test source offline"));
        scheduler.tick();
        verify(source, timeout(2000)).capture(argThat(d -> "GOOD".equals(d.serial())));
        assertEquals(1L, ((Map<?,?>) scheduler.latest("BAD").get("data")).get("failureCount"));
    }
}
