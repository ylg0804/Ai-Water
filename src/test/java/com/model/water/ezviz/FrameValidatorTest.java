package com.model.water.ezviz;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FrameValidatorTest {
    final Map<String, Float> params = Map.of("frame_width", 640f, "frame_height", 360f);
    static BufferedImage plain(Color color) {
        var image = new BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(color); graphics.fillRect(0, 0, 640, 360); graphics.dispose(); return image;
    }
    @Test void rejectsCenteredErrorCardButPreservesTrueWhiteAndBlackOcclusion() throws Exception {
        var validator = new FrameValidator("");
        var card = plain(Color.WHITE);
        var g = card.createGraphics(); g.setColor(Color.BLACK); g.drawString("Maximum concurrent viewers reached (9048)", 180, 180); g.dispose();
        assertThrows(InvalidFrameException.class, () -> validator.validate(List.of(card, card), params));
        for (Color color : List.of(Color.WHITE, Color.BLACK)) {
            var image = plain(color); assertDoesNotThrow(() -> validator.validate(List.of(image, image), params));
        }
    }
    @Test void validatesBothFrameDimensions() throws Exception {
        var validator = new FrameValidator("");
        assertThrows(InvalidFrameException.class, () -> validator.validate(List.of(plain(Color.GRAY), new BufferedImage(320, 180, 1)), params));
    }
    @Test void waterEncodingBoxesCannotBeInTheLetterboxPadding() throws Exception {
        var validator = new FrameValidator(""); var image = plain(Color.GRAY);
        assertThrows(InvalidFrameException.class, () -> validator.validateWaterSurface(image, new float[]{0,47,100,53}, params));
        assertDoesNotThrow(() -> validator.validateWaterSurface(image, new float[]{0,150,100,156}, params));
    }
    @Test void configuredTemplatesRejectErrorScreensWithoutRejectingPlainWhite(@TempDir Path dir) throws Exception {
        var image = plain(Color.YELLOW); var g = image.createGraphics(); g.setColor(Color.BLUE); g.fillRect(50, 30, 100, 80); g.dispose();
        ImageIO.write(image, "png", dir.resolve("error.png").toFile());
        var validator = new FrameValidator(dir.toString());
        assertThrows(InvalidFrameException.class, () -> validator.validate(List.of(image, image), params));
        assertDoesNotThrow(() -> validator.validate(List.of(plain(Color.WHITE), plain(Color.WHITE)), params));
    }
}
