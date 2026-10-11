package com.model.water.ai;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DebrisDecodeTest {
    @Test void nmsSuppressesDuplicateAnchorsAndRetainsSeparateObjects() {
        float[][] out = {{100,101,300}, {100,101,300}, {20,20,20}, {20,20,20}, {.9f,.8f,.7f}};
        var result = DebrisOnnxService.decode(out, List.of("floating_debris"), .5f);
        assertEquals(2, result.count()); assertEquals(.9f, result.maxConfidence());
        assertEquals(List.of("floating_debris"), result.labels());
    }
    @Test void multiClassUsesArgmaxInsteadOfMultiplyingUnrelatedScores() {
        float[][] out = {{100,100}, {100,100}, {20,20}, {20,20}, {.9f,.01f}, {.01f,.85f}};
        var result = DebrisOnnxService.decode(out, List.of("bottle", "bag"), .5f);
        assertEquals(2, result.count()); assertEquals(List.of("bottle", "bag"), result.labels());
        assertThrows(IllegalArgumentException.class, () -> DebrisOnnxService.decode(out, List.of("floating_debris"), .5f));
    }
}
