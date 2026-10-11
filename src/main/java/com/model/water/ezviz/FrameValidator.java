package com.model.water.ezviz;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Source error cards are rejected; actual black/overexposed frames remain eligible for occlusion alarms. */
@Component
public class FrameValidator {
    private final List<int[]> templates = new ArrayList<>();
    public FrameValidator(@Value("${waterflow.invalid-frame-template-dir:}") String directory) throws Exception {
        if (!directory.isBlank()) {
            Path root = Path.of(directory);
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("错误画面模板目录不存在");
            try (var paths = Files.list(root)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    BufferedImage image = ImageIO.read(path.toFile());
                    if (image != null) templates.add(thumbnail(image));
                }
            }
        }
    }
    public void validate(List<BufferedImage> frames, Map<String, Float> params) {
        if (frames == null || frames.size() < 2) throw new InvalidFrameException("未获取到两帧有效画面");
        for (BufferedImage image : frames) {
            if (image == null) throw new InvalidFrameException("视频画面无法解码");
            int width = params.get("frame_width").intValue(), height = params.get("frame_height").intValue();
            if (image.getWidth() != width || image.getHeight() != height)
                throw new InvalidFrameException("画面尺寸与标定参数不符，期望 " + width + "×" + height
                        + "，实际 " + image.getWidth() + "×" + image.getHeight());
            if (isErrorCard(image)) throw new InvalidFrameException("视频源返回错误提示画面，可能是萤石云并发观看数受限（9048）");
            int[] pixels = thumbnail(image);
            for (int[] template : templates)
                if (similar(pixels, template)) throw new InvalidFrameException("视频源画面命中错误提示图模板");
        }
    }

    /** Reject model boxes that lie in the letterbox padding instead of the camera image. */
    public void validateWaterSurface(BufferedImage image, float[] bbox, Map<String, Float> params) {
        if (bbox == null || bbox.length < 4 || !Float.isFinite(bbox[0]) || !Float.isFinite(bbox[1])
                || !Float.isFinite(bbox[2]) || !Float.isFinite(bbox[3]) || bbox[2] <= bbox[0] || bbox[3] <= bbox[1])
            throw new InvalidFrameException("水面框坐标无效");
        int input = 640;
        float scale = Math.min((float) input / image.getWidth(), (float) input / image.getHeight());
        int contentWidth = Math.max(1, Math.round(image.getWidth() * scale));
        int contentHeight = Math.max(1, Math.round(image.getHeight() * scale));
        float dx = (input - contentWidth) / 2, dy = (input - contentHeight) / 2;
        if (bbox[0] < dx || bbox[1] < dy || bbox[2] > dx + contentWidth || bbox[3] > dy + contentHeight)
            throw new InvalidFrameException("水面框位于视频画布的填充区域，不能生成水情数值");
    }
    // White background with a narrow central text band and blank margins, not pure white/black.
    static boolean isErrorCard(BufferedImage image) {
        int w = image.getWidth(), h = image.getHeight();
        if (w < 160 || h < 90) return false;
        int total = 0, white = 0, dark = 0, outside = 0, minX = w, maxX = 0, minY = h, maxY = 0;
        int stride = Math.max(1, w / 640);
        for (int y = 0; y < h; y += stride) for (int x = 0; x < w; x += stride) {
            int rgb = image.getRGB(x, y), r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
            total++;
            if (r >= 245 && g >= 245 && b >= 245) white++;
            if (r < 180 && g < 180 && b < 180) {
                dark++; minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                if (x < w * .08 || x > w * .92 || y < h * .3 || y > h * .7) outside++;
            }
        }
        return white > total * .96 && dark >= 20 && dark < total * .035 && outside == 0
                && maxX - minX > w * .25 && maxY - minY < h * .2;
    }
    private static int[] thumbnail(BufferedImage image) {
        BufferedImage scaled = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
        var graphics = scaled.createGraphics(); graphics.drawImage(image, 0, 0, 64, 36, null); graphics.dispose();
        return scaled.getRGB(0, 0, 64, 36, null, 0, 64);
    }
    private static boolean similar(int[] image, int[] template) {
        long error = 0, contrast = 0, dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < image.length; i++) {
            int a = gray(image[i]), b = gray(template[i]);
            error += Math.abs(a - b); contrast += Math.abs(b - 255);
            int da = 255 - a, db = 255 - b;
            dot += (long) da * db; normA += (long) da * da; normB += (long) db * db;
        }
        return contrast > 1000 && error < image.length * 5L && normA > 0 && normB > 0
                && dot / Math.sqrt((double) normA * normB) > .98;
    }
    private static int gray(int rgb) { return (((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255)) / 3; }
}
