package com.model.water.ezviz;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Function;

/** 萤石云业务服务：直播地址、H5 免 SDK 播放地址、抽帧抓拍。 */
@Service
public class EzvizService {

    /** 流地址默认有效期：2 小时（秒）。 */
    static final int DEFAULT_EXPIRE_SECONDS = 2 * 60 * 60;

    private final EzvizOpenApi api;
    private final EzvizTokenProvider tokenProvider;
    private final EzvizProperties props;
    private final RestClient downloadClient;

    public EzvizService(EzvizOpenApi api, EzvizTokenProvider tokenProvider, EzvizProperties props) {
        this.api = api;
        this.tokenProvider = tokenProvider;
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.downloadClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 获取设备直播流地址。
     *
     * @param hd true=高清主码流，false=标清子码流。
     *           G04690603 高清流是 H.265 会被服务端拦截，必须用标清（任务书已知坑）
     */
    public PlayAddress getLiveAddress(String deviceSerial, int channelNo, int protocol, boolean hd) {
        // G04690603 的高清主码流是 H.265；平台 HLS/RTMP/FLV 服务会拦截，
        // 按交接规则强制使用 640x360 标清，不能被请求参数覆盖。
        boolean effectiveHd = isH265RestrictedDevice(deviceSerial) ? false : hd;
        int quality = effectiveHd ? EzvizOpenApi.QUALITY_HD : EzvizOpenApi.QUALITY_SD;
        String url = withTokenRetry(token ->
                api.getLiveAddressUrl(token, deviceSerial, channelNo, protocol, quality, DEFAULT_EXPIRE_SECONDS));
        return new PlayAddress(url, protocol, quality, DEFAULT_EXPIRE_SECONDS);
    }

    static boolean isH265RestrictedDevice(String deviceSerial) {
        return "G04690603".equalsIgnoreCase(deviceSerial);
    }

    public String getInferenceHlsUrl(String deviceSerial, int channelNo, boolean hd) {
        return getLiveAddress(deviceSerial, channelNo, EzvizOpenApi.PROTOCOL_HLS, hd).url();
    }

    /**
     * H5 免 SDK 播放地址：iframe 内嵌 ezopen 播放器，返回给前端直接放入 iframe src。
     * ezopen URL 上追加 accessToken 完成鉴权（token 有效期 7 天）。
     */
    public String h5IframeUrl(String deviceSerial, int channelNo, boolean hd) {
        PlayAddress addr = getLiveAddress(deviceSerial, channelNo, EzvizOpenApi.PROTOCOL_EZOPEN, hd);
        return buildIframeUrl(addr.url());
    }

    public int effectiveQuality(String deviceSerial, boolean hd) {
        return (isH265RestrictedDevice(deviceSerial) ? false : hd)
                ? EzvizOpenApi.QUALITY_HD : EzvizOpenApi.QUALITY_SD;
    }

    /** 由已有的 ezopen URL 拼出 H5 iframe 地址（play-url 接口复用，避免二次取流）。 */
    public String buildIframeUrl(String ezopenUrl) {
        if (ezopenUrl == null || ezopenUrl.isEmpty()) {
            throw new EzvizApiException("h5-iframe", "直播地址接口未返回 ezopen URL");
        }
        String withToken = ezopenUrl + (ezopenUrl.contains("?") ? "&" : "?")
                + "accessToken=" + tokenProvider.getAccessToken();
        return "https://open.ys7.com/ezopen/h5/iframe?url="
                + URLEncoder.encode(withToken, StandardCharsets.UTF_8);
    }

    /**
     * 抓拍一帧 JPEG，供推理调度抽帧和告警截图使用。
     * 注意：测量模型需要间隔 0.5 秒（frame_step/fps）的两帧，而官方建议抓拍间隔 4 秒以上，
     * 调度层每周期连抓两帧的策略需实测是否触发 20008/10028 限频，必要时改走 FLV 拉流。
     */
    public BufferedImage grabFrame(String deviceSerial, int channelNo) {
        String picUrl = withTokenRetry(token -> api.capture(token, deviceSerial, channelNo));
        try {
            byte[] jpeg = downloadClient.get()
                    .uri(URI.create(picUrl))
                    .retrieve()
                    .body(byte[].class);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
            if (img == null) {
                throw new EzvizApiException("grabFrame", "抓拍图片无法解码（可能已过期，picUrl 仅 2 小时有效）");
            }
            return img;
        } catch (EzvizApiException e) {
            throw e;
        } catch (Exception e) {
            throw new EzvizApiException("下载抓拍图片失败: " + picUrl, e);
        }
    }

    /** 10002（token 失效）时自动失效重取并重试一次。 */
    private <T> T withTokenRetry(Function<String, T> call) {
        try {
            return call.apply(tokenProvider.getAccessToken());
        } catch (EzvizApiException e) {
            if (!e.isTokenInvalid()) {
                throw e;
            }
            tokenProvider.invalidate();
            return call.apply(tokenProvider.getAccessToken());
        }
    }

    /** 未显式指定 hd 时按设备注册表默认；未注册的设备默认标清（H.265 拦截坑，标清最稳）。 */
    public boolean defaultHd(String deviceSerial) {
        Optional<EzvizProperties.Device> device = props.getDevices().stream()
                .filter(d -> d.serial().equalsIgnoreCase(deviceSerial))
                .findFirst();
        return device.map(EzvizProperties.Device::hd).orElse(false);
    }

    /** 直播地址结果。 */
    public record PlayAddress(String url, int protocol, int quality, int expireSeconds) {
    }
}
