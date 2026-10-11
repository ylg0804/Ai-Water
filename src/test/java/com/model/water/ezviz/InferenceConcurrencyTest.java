package com.model.water.ezviz;

import com.model.water.ai.WaterflowOnnxService;
import com.model.water.alarm.*;
import com.model.water.measurement.MeasurementRepository;
import com.model.water.device.DeviceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;
import java.time.Instant;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Uses a disposable database, never the configured production database. */
class InferenceConcurrencyTest {
    @Test
    @EnabledIfSystemProperty(named="waterflow.test.mysql-config",matches=".+")
    void deletionWaitsForTheInferenceTransactionAndFutureWritesAreRejected(@TempDir Path root) throws Exception {
        var settings=new Properties();
        try(var reader=Files.newBufferedReader(Path.of(System.getProperty("waterflow.test.mysql-config")))){settings.load(reader);}
        var name="water_regression_"+UUID.randomUUID().toString().replace("-","");
        var admin=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/?serverTimezone=Asia/Shanghai",settings.getProperty("spring.datasource.username"),settings.getProperty("spring.datasource.password"));
        var adminJdbc=new JdbcTemplate(admin);
        adminJdbc.execute("CREATE DATABASE `"+name+"`");
        var pool=Executors.newFixedThreadPool(2);
        var release=new CountDownLatch(1);
        try {
            var ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:3306/"+name+"?serverTimezone=Asia/Shanghai",settings.getProperty("spring.datasource.username"),settings.getProperty("spring.datasource.password"));
            var jdbc=new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE device(id BIGINT PRIMARY KEY,serial VARCHAR(64) UNIQUE,status INT,deleted_at DATETIME NULL) ENGINE=InnoDB");
            jdbc.execute("CREATE TABLE measurement(id BIGINT AUTO_INCREMENT PRIMARY KEY,device_id BIGINT,water_level FLOAT,velocity FLOAT,discharge FLOAT,measured_at DATETIME,FOREIGN KEY(device_id) REFERENCES device(id)) ENGINE=InnoDB");
            jdbc.execute("CREATE TABLE alarm(id BIGINT AUTO_INCREMENT PRIMARY KEY,device_id BIGINT,type VARCHAR(32),detail VARCHAR(512),snapshot_path VARCHAR(256),status VARCHAR(16),FOREIGN KEY(device_id) REFERENCES device(id)) ENGINE=InnoDB");
            jdbc.update("INSERT INTO device(id,serial,status) VALUES (1,'CAM',1)");
            var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
            var measurements=new MeasurementRepository(jdbc);
            var store=new InferenceStore(measurements,new AlarmRepository(jdbc),new SnapshotStorage(root.toString()));
            var locked=new CountDownLatch(1); var deleteStarted=new CountDownLatch(1); var deleteFinished=new CountDownLatch(1);
            var inference=pool.submit(()->tx.execute(status->{
                measurements.lockActive("CAM"); locked.countDown();
                try { if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out"); }
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                return store.persist("CAM",new WaterflowOnnxService.WaterflowResult(1,2,3,List.of()),Instant.now(),List.of());
            }));
            assertTrue(locked.await(10,TimeUnit.SECONDS));
            var deletion=pool.submit(()->{deleteStarted.countDown();var changed=new DeviceRepository(jdbc).delete(1);deleteFinished.countDown();return changed;});
            assertTrue(deleteStarted.await(10,TimeUnit.SECONDS));
            assertFalse(deleteFinished.await(250,TimeUnit.MILLISECONDS),"soft delete must wait for the device lock");
            release.countDown(); inference.get(10,TimeUnit.SECONDS); assertEquals(1,deletion.get(10,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM measurement",Integer.class));
            assertThrows(org.springframework.dao.EmptyResultDataAccessException.class,()->tx.execute(status->store.persist("CAM",new WaterflowOnnxService.WaterflowResult(4,5,6,List.of()),Instant.now(),List.of())));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM measurement",Integer.class));
            assertThrows(org.springframework.dao.EmptyResultDataAccessException.class,()->tx.execute(status->store.persist("CAM",null,Instant.now(),List.of(new InferenceStore.AlarmCandidate("OCCLUSION","test",null,new java.awt.image.BufferedImage(16,16,1))))));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM alarm",Integer.class));
            try(var files=Files.list(root)){assertEquals(0,files.count());}
        } finally {
            release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(15,TimeUnit.SECONDS));
            if(!name.matches("water_regression_[a-f0-9]{32}")) throw new IllegalStateException("Unsafe test database name");
            adminJdbc.execute("DROP DATABASE `"+name+"`");
        }
    }
}
