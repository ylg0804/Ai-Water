package com.model.water.ezviz;

/** 萤石云开放平台接口调用失败。code 为平台返回的错误码（如 10002=accessToken 无效/过期）。 */
public class EzvizApiException extends RuntimeException {

    private final String code;

    public EzvizApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public EzvizApiException(String message, Throwable cause) {
        super(message, cause);
        this.code = null;
    }

    public String code() {
        return code;
    }

    /** 10002：accessToken 无效或过期，应重新获取后重试。 */
    public boolean isTokenInvalid() {
        return "10002".equals(code);
    }
}
