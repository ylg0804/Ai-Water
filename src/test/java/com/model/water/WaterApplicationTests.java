package com.model.water;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.model.water.device.DeviceRepository;

@SpringBootTest
class WaterApplicationTests {
    @org.springframework.boot.test.mock.mockito.MockBean
    com.model.water.ezviz.FrameSource frameSource;
    @MockBean DeviceRepository devices;

	@Test
	void contextLoads() {
	}

}
