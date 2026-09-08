package com.model.water.ezviz;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EzvizTokenProviderTest {

    private static final long WEEK_MILLIS = 7 * 24 * 3600 * 1000L;

    private EzvizOpenApi api = mock(EzvizOpenApi.class);
    private EzvizProperties props = new EzvizProperties();

    private EzvizTokenProvider newProvider() {
        props.setAppKey("key");
        props.setAppSecret("secret");
        return new EzvizTokenProvider(api, props);
    }

    @Test
    void cachesTokenUntilNearExpiry() {
        when(api.getToken("key", "secret"))
                .thenReturn(new EzvizOpenApi.TokenData("at.1", System.currentTimeMillis() + WEEK_MILLIS));
        EzvizTokenProvider provider = newProvider();

        assertThat(provider.getAccessToken()).isEqualTo("at.1");
        assertThat(provider.getAccessToken()).isEqualTo("at.1");
        verify(api, times(1)).getToken("key", "secret");
    }

    @Test
    void refetchesWhenWithinRefreshAheadWindow() {
        long soon = System.currentTimeMillis() + 30 * 60 * 1000L; // 距过期 30 分钟 < 提前刷新窗口 1 小时
        when(api.getToken("key", "secret"))
                .thenReturn(new EzvizOpenApi.TokenData("at.1", soon))
                .thenReturn(new EzvizOpenApi.TokenData("at.2", soon + WEEK_MILLIS));
        EzvizTokenProvider provider = newProvider();

        provider.getAccessToken();
        assertThat(provider.getAccessToken()).isEqualTo("at.2");
        verify(api, times(2)).getToken("key", "secret");
    }

    @Test
    void invalidateForcesRefetch() {
        when(api.getToken("key", "secret"))
                .thenReturn(new EzvizOpenApi.TokenData("at.1", System.currentTimeMillis() + WEEK_MILLIS))
                .thenReturn(new EzvizOpenApi.TokenData("at.2", System.currentTimeMillis() + WEEK_MILLIS));
        EzvizTokenProvider provider = newProvider();

        assertThat(provider.getAccessToken()).isEqualTo("at.1");
        provider.invalidate();
        assertThat(provider.getAccessToken()).isEqualTo("at.2");
        verify(api, times(2)).getToken("key", "secret");
    }
}
