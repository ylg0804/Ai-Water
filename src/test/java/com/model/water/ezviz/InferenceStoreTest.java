package com.model.water.ezviz;
import com.model.water.ai.WaterflowOnnxService;
import com.model.water.alarm.AlarmRepository;
import com.model.water.alarm.SnapshotStorage;
import com.model.water.measurement.MeasurementRepository;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InferenceStoreTest {
    private InferenceStore transactional(MeasurementRepository measurements, AlarmRepository alarms, SnapshotStorage snapshots) throws Exception {
        var dataSource = mock(DataSource.class); var connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        ProxyFactory factory = new ProxyFactory(new InferenceStore(measurements, alarms, snapshots));
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        return (InferenceStore) factory.getProxy();
    }
    @Test void persistentOpenAlarmDoesNotCreateMoreSnapshots(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var measurements=mock(MeasurementRepository.class); var alarms=mock(AlarmRepository.class);
        var store=transactional(measurements, alarms, new SnapshotStorage(root.toString()));
        when(alarms.createIfNoOpen(anyString(), anyString(), anyString(), anyString())).thenReturn(true);
        var image=new java.awt.image.BufferedImage(16,16,1);
        var candidates=List.of(new InferenceStore.AlarmCandidate("DEBRIS","test",null,image));
        var created=store.persist("CAM",null,Instant.now(),candidates);
        assertEquals(1,created.size()); assertTrue(java.nio.file.Files.exists(root.resolve(created.get(0).snapshotPath())));
        when(alarms.hasOpen("CAM","DEBRIS")).thenReturn(true);
        for(int i=0;i<3;i++) assertTrue(store.persist("CAM",null,Instant.now(),candidates).isEmpty());
        try(var files=java.nio.file.Files.list(root)){assertEquals(1,files.count());}
    }
    @Test void rollbackAndRejectedInsertRemoveOnlyNewSnapshots(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var measurements=mock(MeasurementRepository.class); var alarms=mock(AlarmRepository.class);
        var snapshots=new SnapshotStorage(root.toString());
        var old=snapshots.save("HISTORICAL",new java.awt.image.BufferedImage(16,16,1));
        var store=transactional(measurements,alarms,snapshots);
        var candidates=List.of(new InferenceStore.AlarmCandidate("DEBRIS","test",null,new java.awt.image.BufferedImage(16,16,1)));
        when(alarms.createIfNoOpen(anyString(),anyString(),anyString(),anyString())).thenThrow(new IllegalStateException("write failed"));
        assertThrows(IllegalStateException.class,()->store.persist("CAM",null,Instant.now(),candidates));
        assertTrue(java.nio.file.Files.exists(root.resolve(old)));
        try(var files=java.nio.file.Files.list(root)){assertEquals(1,files.count());}
        doReturn(false).when(alarms).createIfNoOpen(anyString(),anyString(),anyString(),anyString());
        assertTrue(store.persist("CAM",null,Instant.now(),candidates).isEmpty());
        try(var files=java.nio.file.Files.list(root)){assertEquals(1,files.count());}
    }
    @Test void alarmFailureRollsBackTheMeasurementTransaction() throws Exception {
        DataSource dataSource = mock(DataSource.class); Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        var measurements = mock(MeasurementRepository.class); var alarms = mock(AlarmRepository.class);
        when(alarms.createIfNoOpen(anyString(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("write failed"));
        ProxyFactory factory = new ProxyFactory(new InferenceStore(measurements, alarms, mock(SnapshotStorage.class)));
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource), new AnnotationTransactionAttributeSource()));
        InferenceStore store = (InferenceStore) factory.getProxy();
        var result = new WaterflowOnnxService.WaterflowResult(1, 2, 3, List.of());
        assertThrows(IllegalStateException.class, () -> store.persist("CAM", result, Instant.now(), List.of(new InferenceStore.AlarmCandidate("CHANNEL_FULL", "test", "test.jpg"))));
        verify(connection).rollback(); verify(connection, never()).commit();
    }
    @Test void uncertainCommitKeepsImagesThatMayAlreadyBeReferenced(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var dataSource=mock(DataSource.class); var connection=mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        doThrow(new java.sql.SQLException("connection lost during commit")).when(connection).commit();
        var alarms=mock(AlarmRepository.class);
        when(alarms.createIfNoOpen(anyString(),anyString(),anyString(),anyString())).thenReturn(true);
        ProxyFactory factory=new ProxyFactory(new InferenceStore(mock(MeasurementRepository.class),alarms,new SnapshotStorage(root.toString())));
        factory.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(dataSource),new AnnotationTransactionAttributeSource()));
        var store=(InferenceStore)factory.getProxy();
        var candidates=List.of(new InferenceStore.AlarmCandidate("DEBRIS","test",null,new java.awt.image.BufferedImage(16,16,1)));
        assertThrows(org.springframework.transaction.TransactionSystemException.class,()->store.persist("CAM",null,Instant.now(),candidates));
        try(var files=java.nio.file.Files.list(root)){assertEquals(1,files.count());}
    }
}
