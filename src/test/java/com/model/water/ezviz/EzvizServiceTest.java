package com.model.water.ezviz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EzvizServiceTest {

    private static final int EXPIRE = EzvizService.DEFAULT_EXPIRE_SECONDS;

    private EzvizOpenApi api;
    private EzvizTokenProvider tokenProvider;
    private EzvizService service;

    @BeforeEach
    void setUp() {
        api = mock(EzvizOpenApi.class);
        tokenProvider = mock(EzvizTokenProvider.class);
        EzvizProperties props = new EzvizProperties();
        props.setDevices(List.of(
                new EzvizProperties.Device("F98228237", 1, "F98228237", true),
                new EzvizProperties.Device("G04690603", 1, "G04690603", false)));
        service = new EzvizService(api, tokenProvider, props);
    }

    @Test
    void hdDeviceUsesHighQuality() {
        when(tokenProvider.getAccessToken()).thenReturn("at.test");
        when(api.getLiveAddressUrl("at.test", "F98228237", 1, EzvizOpenApi.PROTOCOL_HLS,
                EzvizOpenApi.QUALITY_HD, EXPIRE)).thenReturn("https://example/hd.m3u8");

        EzvizService.PlayAddress addr = service.getLiveAddress("F98228237", 1, EzvizOpenApi.PROTOCOL_HLS, true);

        assertThat(addr.url()).isEqualTo("https://example/hd.m3u8");
        assertThat(addr.quality()).isEqualTo(EzvizOpenApi.QUALITY_HD);
    }

    @Test
    void sdDeviceUsesLowQuality() {
        when(tokenProvider.getAccessToken()).thenReturn("at.test");
        when(api.getLiveAddressUrl("at.test", "G04690603", 1, EzvizOpenApi.PROTOCOL_HLS,
                EzvizOpenApi.QUALITY_SD, EXPIRE)).thenReturn("https://example/sd.m3u8");

        EzvizService.PlayAddress addr = service.getLiveAddress("G04690603", 1, EzvizOpenApi.PROTOCOL_HLS, false);

        assertThat(addr.url()).isEqualTo("https://example/sd.m3u8");
        assertThat(addr.quality()).isEqualTo(EzvizOpenApi.QUALITY_SD);
    }

    @Test
    void h265RestrictedDeviceAlwaysUsesStandardQuality() {
        when(tokenProvider.getAccessToken()).thenReturn("at.test");
        when(api.getLiveAddressUrl("at.test", "G04690603", 1, EzvizOpenApi.PROTOCOL_HLS,
                EzvizOpenApi.QUALITY_SD, EXPIRE)).thenReturn("https://example/sd.m3u8");

        EzvizService.PlayAddress addr = service.getLiveAddress("G04690603", 1,
                EzvizOpenApi.PROTOCOL_HLS, true);

        assertThat(addr.quality()).isEqualTo(EzvizOpenApi.QUALITY_SD);
        verify(api).getLiveAddressUrl("at.test", "G04690603", 1, EzvizOpenApi.PROTOCOL_HLS,
                EzvizOpenApi.QUALITY_SD, EXPIRE);
    }

    @Test
    void invalidTokenRetriesOnce() {
        when(tokenProvider.getAccessToken()).thenReturn("at.test");
        when(api.getLiveAddressUrl(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenThrow(new EzvizApiException("10002", "token 失效"))
                .thenReturn("https://example/retry.m3u8");

        EzvizService.PlayAddress addr = service.getLiveAddress("F98228237", 1, EzvizOpenApi.PROTOCOL_FLV, true);

        assertThat(addr.url()).isEqualTo("https://example/retry.m3u8");
        verify(tokenProvider, times(1)).invalidate();
        verify(api, times(2)).getLiveAddressUrl(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void otherApiErrorNotRetried() {
        when(tokenProvider.getAccessToken()).thenReturn("at.test");
        when(api.getLiveAddressUrl(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenThrow(new EzvizApiException("20007", "设备不在线"));

        assertThatThrownBy(() -> service.getLiveAddress("F98228237", 1, EzvizOpenApi.PROTOCOL_HLS, true))
                .isInstanceOfSatisfying(EzvizApiException.class,
                        e -> assertThat(e.code()).isEqualTo("20007"));
        verify(tokenProvider, never()).invalidate();
        verify(api, times(1)).getLiveAddressUrl(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void h5IframeAppendsTokenAndUrlEncodes() {
        when(tokenProvider.getAccessToken()).thenReturn("at.test");
        when(api.getLiveAddressUrl("at.test", "F98228237", 1, EzvizOpenApi.PROTOCOL_EZOPEN,
                EzvizOpenApi.QUALITY_HD, EXPIRE)).thenReturn("ezopen://open.ys7.com/F98228237/1.live");

        String iframe = service.h5IframeUrl("F98228237", 1, true);

        assertThat(iframe).startsWith("https://open.ys7.com/ezopen/h5/iframe?url=");
        String decoded = URLDecoder.decode(
                iframe.substring(iframe.indexOf('=') + 1), StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo("ezopen://open.ys7.com/F98228237/1.live?accessToken=at.test");
    }

    @Test
    void defaultHdComesFromRegistryAndFallsBackToSd() {
        assertThat(service.defaultHd("F98228237")).isTrue();
        assertThat(service.defaultHd("G04690603")).isFalse();
        assertThat(service.defaultHd("UNKNOWN")).isFalse();
    }
}
