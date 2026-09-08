package com.model.water.ezviz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 萤石云开放平台 API 客户端（https://open.ys7.com/api/lapp/...）。
 * 所有接口为 application/x-www-form-urlencoded POST，成功返回 code="200"。
 */
@Component
public class EzvizOpenApi {

    /** 播放协议：1-ezopen（支持 H.264/H.265）、2-hls、3-rtmp、4-flv（后三者仅 H.264）。 */
    public static final int PROTOCOL_EZOPEN = 1;
    public static final int PROTOCOL_HLS = 2;
    public static final int PROTOCOL_RTMP = 3;
    public static final int PROTOCOL_FLV = 4;

    /** 清晰度：1-高清主码流、2-流畅子码流。 */
    public static final int QUALITY_HD = 1;
    public static final int QUALITY_SD = 2;

    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public EzvizOpenApi(EzvizProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.client = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /** accessToken（有效期 7 天，可重复获取，新 token 不影响旧 token 的有效性）。 */
    public TokenData getToken(String appKey, String appSecret) {
        JsonNode data = postForm("/api/lapp/token/get",
                Map.of("appKey", appKey, "appSecret", appSecret));
        return new TokenData(data.path("accessToken").asText(), data.path("expireTime").asLong());
    }

    /**
     * 获取直播流地址。
     *
     * @param expireSeconds 地址有效期（秒，30 秒 ~ 7 天），仅 hls/rtmp/flv 协议生效
     */
    public String getLiveAddressUrl(String accessToken, String deviceSerial, int channelNo,
                                    int protocol, int quality, int expireSeconds) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("accessToken", accessToken);
        form.put("deviceSerial", deviceSerial);
        form.put("channelNo", String.valueOf(channelNo));
        form.put("protocol", String.valueOf(protocol));
        form.put("quality", String.valueOf(quality));
        if (protocol != PROTOCOL_EZOPEN) {
            form.put("expireTime", String.valueOf(expireSeconds));
        }
        JsonNode data = postForm("/api/lapp/v2/live/address/get", form);
        return data.path("url").asText();
    }

    /**
     * 设备抓拍一帧，返回图片地址（2 小时内有效）。
     * 官方建议每个摄像头调用间隔 4 秒以上，频繁调用会返回 20008/10028。
     */
    public String capture(String accessToken, String deviceSerial, int channelNo) {
        JsonNode data = postForm("/api/lapp/device/capture", Map.of(
                "accessToken", accessToken,
                "deviceSerial", deviceSerial,
                "channelNo", String.valueOf(channelNo)));
        String picUrl = data.path("picUrl").asText();
        if (picUrl == null || picUrl.isEmpty()) {
            throw new EzvizApiException("capture", "抓拍响应缺少 picUrl 字段");
        }
        return picUrl;
    }

    private JsonNode postForm(String path, Map<String, String> form) {
        String body;
        try {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            form.forEach(params::add);
            body = client.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(params)
                    .retrieve()
                    .body(String.class);
        } catch (EzvizApiException e) {
            throw e;
        } catch (Exception e) {
            throw new EzvizApiException("调用萤石云接口失败: " + path, e);
        }
        try {
            JsonNode root = mapper.readTree(body);
            String code = root.path("code").asText();
            if (!"200".equals(code)) {
                throw new EzvizApiException(code, "萤石云接口 " + path + " 返回错误: code=" + code
                        + ", msg=" + root.path("msg").asText());
            }
            return root.path("data");
        } catch (EzvizApiException e) {
            throw e;
        } catch (Exception e) {
            throw new EzvizApiException("解析萤石云响应失败: " + path, e);
        }
    }

    /** token/get 的 data 字段。expireTimeMillis 为过期时间（毫秒时间戳）。 */
    public record TokenData(String accessToken, long expireTimeMillis) {
    }
}
