package com.model.water.ezviz;
import java.awt.image.BufferedImage;
import java.util.List;
public interface FrameSource {
    List<BufferedImage> capture(EzvizProperties.Device device) throws Exception;
    default void release(String serial) { }
}
