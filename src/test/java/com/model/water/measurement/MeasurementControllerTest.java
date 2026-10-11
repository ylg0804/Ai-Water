package com.model.water.measurement;

import com.model.water.api.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MeasurementControllerTest {
    final MeasurementRepository repository = mock(MeasurementRepository.class);
    final MockMvc mvc = MockMvcBuilders.standaloneSetup(new MeasurementController(repository)).setControllerAdvice(new GlobalExceptionHandler()).build();
    @Test void malformedAndMissingTimeParametersReturn400() throws Exception {
        for (String endpoint : List.of("history", "export")) {
            mvc.perform(get("/api/measurement/" + endpoint).param("deviceId", "1").param("start", "abc").param("end", "2026-10-08T00:00:00"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("start")));
            mvc.perform(get("/api/measurement/" + endpoint).param("deviceId", "1").param("start", "2026-10-08T00:00:00"))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(repository);
    }
    @Test void csvPreservesSmallValuesWithoutScientificNotation() throws Exception {
        when(repository.history(eq(1L), any(), any())).thenReturn(List.of(Map.of("serial", "CAM", "measured_at", "2026-10-08 00:00:00",
                "water_level", 1.2f, "velocity", 7.09315E-4f, "discharge", 1.2E-9)));
        byte[] bytes = mvc.perform(get("/api/measurement/export").param("deviceId", "1").param("start", "2026-10-08T00:00:00").param("end", "2026-10-09T00:00:00"))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsByteArray();
        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("\uFEFFserial,"));
        assertTrue(csv.contains("\"0.000709315\"")); assertTrue(csv.contains("\"0.0000000012\""));
        assertFalse(csv.contains("E-")); assertEquals(2, csv.lines().count());
    }
    @Test void emptyLatestMeasurementReturnsNullInsteadOf500() throws Exception {
        when(repository.latest(1L)).thenReturn(null);
        mvc.perform(get("/api/measurement/latest").param("deviceId", "1"))
                .andExpect(status().isOk()).andExpect(content().json("{\"code\":\"200\",\"msg\":\"ok\",\"data\":null}"));
    }
}
