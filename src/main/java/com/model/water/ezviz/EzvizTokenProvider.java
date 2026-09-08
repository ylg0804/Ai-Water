package com.model.water.ezviz;

import org.springframework.stereotype.Component;

/**
 * accessToken 提供器：缓存 token 直至过期前 1 小时才重新获取（有效期 7 天），
 * 接口返回 10002 时由调用方 invalidate() 后重取。
 */
@Component
public class EzvizTokenProvider {

    /** 提前刷新余量：离过期不足 1 小时就换新 token。 */
    static final long REFRESH_AHEAD_MILLIS = 60 * 60 * 1000L;

    private final EzvizOpenApi api;
    private final EzvizProperties props;

    private String token;
    private long expireAtMillis;

    public EzvizTokenProvider(EzvizOpenApi api, EzvizProperties props) {
        this.api = api;
        this.props = props;
    }

    public String getAccessToken() {
        if (token != null && System.currentTimeMillis() < expireAtMillis - REFRESH_AHEAD_MILLIS) {
            return token;
        }
        synchronized (this) {
            if (token == null || System.currentTimeMillis() >= expireAtMillis - REFRESH_AHEAD_MILLIS) {
                EzvizOpenApi.TokenData data = api.getToken(props.getAppKey(), props.getAppSecret());
                token = data.accessToken();
                expireAtMillis = data.expireTimeMillis();
            }
            return token;
        }
    }

    /** token 失效（10002）后调用，下次获取会重新请求。 */
    public void invalidate() {
        synchronized (this) {
            token = null;
            expireAtMillis = 0;
        }
    }
}
