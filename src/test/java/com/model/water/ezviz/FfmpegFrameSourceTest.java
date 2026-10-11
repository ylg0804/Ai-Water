package com.model.water.ezviz;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import com.model.water.device.DeviceValidationTest;
import java.util.List;
import java.util.concurrent.TimeUnit;

class FfmpegFrameSourceTest {
    @Test void missingExecutableReportsTheConfigurationKey() {
        var source = new FfmpegFrameSource(mock(EzvizService.class), new TestSourceProperties(), "missing-waterflow-ffmpeg-executable");
        var error = assertThrows(IllegalArgumentException.class, source::validateExecutable);
        assertTrue(error.getMessage().contains("waterflow.ffmpeg-path"));
    }
    @Test
    @EnabledIfSystemProperty(named = "waterflow.test.ffmpeg", matches = ".+")
    void localClipAdvancesAndLoopsAtEofWithoutCallingCloud(@TempDir Path root) throws Exception {
        String ffmpeg = System.getProperty("waterflow.test.ffmpeg");
        Path clip = root.resolve("clip.mp4");
        String video = System.getProperty("waterflow.test.video");
        Process process = (video == null
                ? new ProcessBuilder(ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=640x360:rate=12", "-t", "2", "-c:v", "mpeg4", clip.toString())
                : new ProcessBuilder(ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", video, "-t", "2", "-c:v", "copy", "-an", clip.toString()))
                .inheritIO().start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, process.exitValue());
        var props = new TestSourceProperties(); props.setEnabled(true); props.setRoot(root.toString()); props.getFiles().put("CAM", "clip.mp4");
        var cloud = mock(EzvizService.class); var source = new FfmpegFrameSource(cloud, props, ffmpeg);
        var device = new EzvizProperties.Device("CAM", 1, "test", false, DeviceValidationTest.PARAMS, 60);
        var first = source.capture(device); var second = source.capture(device); var looped = source.capture(device);
        int width = first.get(0).getWidth(), height = first.get(0).getHeight();
        assertEquals(2, first.size()); assertTrue(width >= 640);
        assertFalse(java.util.Arrays.equals(first.get(0).getRGB(0,0,width,height,null,0,width), second.get(0).getRGB(0,0,width,height,null,0,width)));
        assertArrayEquals(first.get(0).getRGB(0,0,width,height,null,0,width), looped.get(0).getRGB(0,0,width,height,null,0,width));
        verifyNoInteractions(cloud);
    }

    @Test void acceptsOnlyFilesInsideTheConfiguredRoot(@TempDir Path temp) throws Exception {
        Path root = Files.createDirectory(temp.resolve("videos"));
        Path inside = Files.createFile(root.resolve("clip.mp4"));
        Path outside = Files.createFile(temp.resolve("outside.mp4"));
        var props = new TestSourceProperties(); props.setRoot(root.toString());
        var source = new FfmpegFrameSource(mock(EzvizService.class), props, "ffmpeg");
        assertEquals(inside.toRealPath(), source.localFile("clip.mp4"));
        assertThrows(IllegalArgumentException.class, () -> source.localFile(outside.toString()));
        assertThrows(IllegalArgumentException.class, () -> source.localFile("../outside.mp4"));
    }
}
