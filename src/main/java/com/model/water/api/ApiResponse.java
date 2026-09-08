package com.model.water.api;

public record ApiResponse<T>(String code, String msg, T data) {
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>("200", "ok", data); }
    public static ApiResponse<Void> error(String code, String msg) { return new ApiResponse<>(code, msg, null); }
}
