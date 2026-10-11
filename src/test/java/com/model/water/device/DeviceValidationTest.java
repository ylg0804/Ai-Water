package com.model.water.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.model.water.ai.HydraulicParams;
import com.model.water.api.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class DeviceValidationTest {
    public static final String PARAMS = "gauge_roi_x1=1,gauge_roi_y1=2,gauge_roi_x2=3,gauge_roi_y2=4,flow_roi_x1=5,flow_roi_y1=6,flow_roi_x2=7,flow_roi_y2=8,scale_top_y=0,scale_top_value=0.5,scale_bottom_y=260,scale_bottom_value=1.5,gauge_base_height=0,frame_width=640,frame_height=360,fps=12.5,frame_step=6.25,channel_bottom_width=0.8,channel_side_slope_1=1.5,channel_side_slope_2=1.5,alpha=0.85";
    final DeviceRepository repository = mock(DeviceRepository.class);
    final ObjectMapper mapper = new ObjectMapper();
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new DeviceController(repository)).setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test void invalidParametersAreRejectedBeforeAnyDatabaseWrite() throws Exception {
        for (String params : List.of("", PARAMS.replace(",alpha=0.85", ""), PARAMS + ",alpha=0.9", PARAMS.replace("alpha=0.85", "alpha=abc"),
                PARAMS.replace("alpha=0.85", "alpha=NaN"), PARAMS.replace("fps=12.5", "fps=0"), PARAMS + ",occ_consec_frames=1.5")) {
            mvc.perform(post("/api/device").contentType("application/json").content(mapper.writeValueAsString(Map.of("serial", "CAM", "deviceOtherParams", params))))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.msg").isNotEmpty());
        }
        verifyNoInteractions(repository);
    }
    @Test void preservesTheOriginalParameterStringAndOptionalKeys() throws Exception {
        String params = PARAMS + ", channel_full_level=1.4";
        when(repository.create(anyMap())).thenReturn(17L);
        mvc.perform(post("/api/device").contentType("application/json").content(mapper.writeValueAsString(Map.of("serial", "CAM", "deviceOtherParams", params))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(17));
        verify(repository).create(argThat(body -> params.equals(body.get("deviceOtherParams"))));
    }
    @Test void missingKeyNamesAndInvalidIntervalsHaveSpecificErrors() throws Exception {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> HydraulicParams.parseFloatArray(PARAMS.replace(",alpha=0.85", ""))).getMessage().contains("alpha"));
        mvc.perform(put("/api/device/1").contentType("application/json").content(mapper.writeValueAsString(Map.of("serial", "CAM", "deviceOtherParams", PARAMS, "inferIntervalSec", 0))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.msg").value("inferIntervalSec 必须是 1 到 2147483647 的整数"));
        verifyNoInteractions(repository);
    }
}
