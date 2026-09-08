USE water_moniter;

-- 为已存在的设备补齐任务书要求的告警阈值；新设备请在 device_other_params 中按现场标定调整。
UPDATE device
SET device_other_params = CONCAT(device_other_params,
  ',channel_full_level=1.4,full_consec_count=3,occ_min_mean=15,occ_max_mean=240',
  ',occ_min_std=10,occ_min_lapvar=20,occ_consec_frames=5',
  ',debris_min_conf=0.5,debris_min_count=1,debris_consec_frames=3')
WHERE serial IN ('F98228237','G04690603')
  AND device_other_params NOT LIKE '%channel_full_level=%';
