package com.model.water.ezviz;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 萤石云摄像头接入接口。 */
@RestController
@RequestMapping("/api/ezviz")
public class EzvizController {

    private final EzvizService service;
    private final EzvizProperties props;
    private final CameraInferenceScheduler scheduler;

    public EzvizController(EzvizService service, EzvizProperties props, CameraInferenceScheduler scheduler) {
        this.service = service;
        this.props = props;
        this.scheduler = scheduler;
    }

    @GetMapping("/measurements/latest")
    public Map<String, Object> latest(@RequestParam String serial) {
        return scheduler.latest(serial);
    }

    /** 已接入的摄像头列表。 */
    @GetMapping("/devices")
    public Map<String, Object> devices() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("count", props.getDevices().size());
        resp.put("devices", props.getDevices());
        return resp;
    }

    /**
     * 直播流地址。
     * protocol: ezopen | hls | rtmp | flv（默认 hls，前端 video.js + hls.js 播放用；
     * 注意 hls/rtmp/flv 仅支持 H.264，H.265 设备必须 hd=false 走标清）。
     * hd 不传时按设备注册表默认值。
     */
    @GetMapping("/play-url")
    public Map<String, Object> playUrl(@RequestParam String serial,
                                       @RequestParam(defaultValue = "1") int channel,
                                       @RequestParam(defaultValue = "hls") String protocol,
                                       @RequestParam(required = false) Boolean hd) {
        int proto = parseProtocol(protocol);
        boolean useHd = hd != null ? hd : service.defaultHd(serial);
        EzvizService.PlayAddress addr = service.getLiveAddress(serial, channel, proto, useHd);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("serial", serial);
        resp.put("channel", channel);
        resp.put("protocol", protocol.toLowerCase());
        resp.put("quality", addr.quality() == EzvizOpenApi.QUALITY_HD ? "hd" : "sd");
        resp.put("expireSeconds", addr.expireSeconds());
        resp.put("url", addr.url());
        if (proto == EzvizOpenApi.PROTOCOL_EZOPEN) {
            resp.put("h5Iframe", service.buildIframeUrl(addr.url()));
        }
        return resp;
    }

    /** H5 免 SDK 播放地址（iframe src），前端直接嵌入即可。 */
    @GetMapping("/h5-iframe")
    public ResponseEntity<Map<String, Object>> h5Iframe(@RequestParam String serial,
                                                        @RequestParam(defaultValue = "1") int channel,
                                                        @RequestParam(required = false) Boolean hd) {
        boolean useHd = hd != null ? hd : service.defaultHd(serial);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("serial", serial);
        resp.put("channel", channel);
        resp.put("quality", service.effectiveQuality(serial, useHd) == EzvizOpenApi.QUALITY_HD ? "hd" : "sd");
        resp.put("iframe", service.h5IframeUrl(serial, channel, useHd));
        return ResponseEntity.ok(resp);
    }

    /** 抓拍一帧（JPEG）。推理调度抽帧和告警截图共用；官方建议每摄像头调用间隔 4 秒以上。 */
    @GetMapping(value = "/capture", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> capture(@RequestParam String serial,
                                          @RequestParam(defaultValue = "1") int channel) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(service.grabFrame(serial, channel), "jpeg", out);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(out.toByteArray());
    }

    private int parseProtocol(String protocol) {
        return switch (protocol.toLowerCase()) {
            case "ezopen" -> EzvizOpenApi.PROTOCOL_EZOPEN;
            case "hls" -> EzvizOpenApi.PROTOCOL_HLS;
            case "rtmp" -> EzvizOpenApi.PROTOCOL_RTMP;
            case "flv" -> EzvizOpenApi.PROTOCOL_FLV;
            default -> throw new IllegalArgumentException("不支持的 protocol: " + protocol
                    + "（可选 ezopen/hls/rtmp/flv）");
        };
    }

    @ExceptionHandler(EzvizApiException.class)
    public ResponseEntity<Map<String, Object>> handleEzvizError(EzvizApiException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "400");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
