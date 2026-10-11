package com.model.water.ezviz;
import com.model.water.ai.WaterflowOnnxService;
import com.model.water.alarm.AlarmRepository;
import com.model.water.measurement.MeasurementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.awt.image.BufferedImage;
import com.model.water.alarm.SnapshotStorage;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class InferenceStore {
    public record AlarmCandidate(String type, String detail, String snapshotPath, BufferedImage image) {
        public AlarmCandidate(String type, String detail, String snapshotPath) { this(type, detail, snapshotPath, null); }
    }
    private final MeasurementRepository measurements;
    private final AlarmRepository alarms;
    private final SnapshotStorage snapshots;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InferenceStore.class);
    public InferenceStore(MeasurementRepository measurements, AlarmRepository alarms, SnapshotStorage snapshots) {
        this.measurements = measurements; this.alarms = alarms; this.snapshots = snapshots;
    }
    /** Commit before publishing; failures roll back both measurement and alarms. */
    @Transactional
    public List<AlarmCandidate> persist(String serial, WaterflowOnnxService.WaterflowResult result,
                                       Instant measuredAt, List<AlarmCandidate> candidates) {
        measurements.lockActive(serial);
        List<String> saved = new ArrayList<>(), retained = new ArrayList<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_UNKNOWN) {
                    log.warn("告警事务提交结果不确定，保留本次截图以免删除可能已被引用的文件");
                    return;
                }
                for (String path : saved) if (status != STATUS_COMMITTED || !retained.contains(path)) {
                    try { snapshots.deleteCreated(path); }
                    catch (Exception e) { log.warn("未引用告警截图清理失败: {}", path, e); }
                }
            }
        });
        if (result != null) measurements.save(serial, result.waterLevel(), result.velocity(), result.discharge(), measuredAt);
        List<AlarmCandidate> created = new ArrayList<>();
        for (AlarmCandidate candidate : candidates) {
            if (alarms.hasOpen(serial, candidate.type())) continue;
            String path = candidate.snapshotPath();
            if (candidate.image() != null) {
                try { path = snapshots.save(serial, candidate.image()); }
                catch (java.io.IOException e) { throw new IllegalStateException("保存告警截图失败", e); }
                saved.add(path);
            }
            if (alarms.createIfNoOpen(serial, candidate.type(), candidate.detail(), path)) {
                created.add(new AlarmCandidate(candidate.type(), candidate.detail(), path)); retained.add(path);
            }
        }
        return created;
    }
}
