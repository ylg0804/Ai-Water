package com.model.water.ezviz;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 萤石云开放平台配置（open.ys7.com 控制台"我的应用"里的 appKey/appSecret）。
 */
@ConfigurationProperties(prefix = "ezviz")
public class EzvizProperties {

    /** 开放平台 API 根地址。 */
    private String baseUrl = "https://open.ys7.com";

    private String appKey;

    private String appSecret;

    /** 平台接入的摄像头注册表（后续设备管理 CRUD 落库后可迁移到 device 表）。 */
    private List<Device> devices = List.of();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getAppSecret() {
        return appSecret;
    }

    public void setAppSecret(String appSecret) {
        this.appSecret = appSecret;
    }

    public List<Device> getDevices() {
        return devices;
    }

    public void setDevices(List<Device> devices) {
        this.devices = devices;
    }

    /**
     * 一台萤石云摄像头的接入信息。
     *
     * @param hd true=高清主码流（quality=1），false=标清子码流（quality=2）。
     *           G04690603 的高清流是 H.265，会被服务端拦截，必须标清 640×360（任务书已知坑）
     */
    public static class Device {
        private String serial;
        private int channelNo = 1;
        private String name;
        private boolean hd;
        private String deviceOtherParams;
        private int inferIntervalSeconds = 60;
        private int status = 1;

        public Device() {}

        public Device(String serial, int channelNo, String name, boolean hd) {
            this(serial, channelNo, name, hd, null, 60);
        }

        public Device(String serial, int channelNo, String name, boolean hd,
                      String deviceOtherParams, int inferIntervalSeconds) {
            this.serial = serial; this.channelNo = channelNo; this.name = name; this.hd = hd;
            this.deviceOtherParams = deviceOtherParams; this.inferIntervalSeconds = inferIntervalSeconds;
        }

        public String serial() { return serial; }
        public int channelNo() { return channelNo; }
        public String name() { return name; }
        public boolean hd() { return hd; }
        public String deviceOtherParams() { return deviceOtherParams; }
        public int inferIntervalSeconds() { return inferIntervalSeconds; }
        public int status() { return status; }
        public void setSerial(String serial) { this.serial = serial; }
        public void setChannelNo(int channelNo) { this.channelNo = channelNo; }
        public void setName(String name) { this.name = name; }
        public void setHd(boolean hd) { this.hd = hd; }
        public void setDeviceOtherParams(String value) { this.deviceOtherParams = value; }
        public void setInferIntervalSeconds(int value) { this.inferIntervalSeconds = value; }
        public void setStatus(int value) { this.status = value; }
    }
}
