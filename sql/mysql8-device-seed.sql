USE water_moniter;

-- device.serial 在建表脚本中为 UNIQUE；重复执行本脚本会更新设备配置，不会产生重复行。
INSERT INTO device
    (serial, name, location, channel_no, hd, device_other_params, infer_interval_sec, status)
VALUES
    (
        'F98228237',
        'F98228237',
        '请填写 F98228237 的现场位置',
        1,
        1,
        'gauge_roi_x1=206.666672,gauge_roi_y1=306.666656,gauge_roi_x2=263.333344,gauge_roi_y2=473.333344,flow_roi_x1=283.333344,flow_roi_y1=290.0,flow_roi_x2=383.333344,flow_roi_y2=373.333344,scale_top_y=0.0,scale_top_value=0.5,scale_bottom_y=500.0,scale_bottom_value=1.5,gauge_base_height=0.0,frame_width=1920.0,frame_height=1080.0,fps=12.5,frame_step=6.25,channel_bottom_width=0.8,channel_side_slope_1=1.5,channel_side_slope_2=1.5,alpha=0.85',
        60,
        1
    ),
    (
        'G04690603',
        'G04690603',
        '请填写 G04690603 的现场位置',
        1,
        0,
        'gauge_roi_x1=90.0,gauge_roi_y1=340.0,gauge_roi_x2=220.0,gauge_roi_y2=490.0,flow_roi_x1=260.0,flow_roi_y1=300.0,flow_roi_x2=420.0,flow_roi_y2=380.0,scale_top_y=0.0,scale_top_value=0.5,scale_bottom_y=260.0,scale_bottom_value=1.5,gauge_base_height=0.0,frame_width=640.0,frame_height=360.0,fps=12.5,frame_step=6.25,channel_bottom_width=0.8,channel_side_slope_1=1.5,channel_side_slope_2=1.5,alpha=0.85',
        60,
        1
    )
ON DUPLICATE KEY UPDATE
    name = VALUES(name),
    location = VALUES(location),
    channel_no = VALUES(channel_no),
    hd = VALUES(hd),
    device_other_params = VALUES(device_other_params),
    infer_interval_sec = VALUES(infer_interval_sec),
    status = VALUES(status);

-- 验证设备和关键规则：G04690603 必须为标清 hd=0。
SELECT id, serial, name, location, channel_no, hd,
       infer_interval_sec, status, CHAR_LENGTH(device_other_params) AS params_length
FROM device
WHERE serial IN ('F98228237', 'G04690603')
ORDER BY serial;
