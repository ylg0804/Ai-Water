package com.model.water.device;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.model.water.api.ResourceNotFoundException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeviceRepositoryTest {
    @Test void deletingDeviceArchivesItsRowInsteadOfDeletingHistory() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.update("UPDATE device SET deleted_at=NOW(),status=0 WHERE id=? AND deleted_at IS NULL", 17L)).thenReturn(1);
        var repository = new DeviceRepository(jdbc);
        assertEquals(1, repository.delete(17L));
        verify(jdbc).update("UPDATE device SET deleted_at=NOW(),status=0 WHERE id=? AND deleted_at IS NULL", 17L);
        verifyNoMoreInteractions(jdbc);
    }
    @Test void nonexistentDeviceIs404AndArchivedCatalogIsQueryable() {
        var jdbc = mock(JdbcTemplate.class); var repository = new DeviceRepository(jdbc);
        when(jdbc.queryForList("SELECT * FROM device WHERE id=? AND deleted_at IS NULL", 17L)).thenReturn(List.of());
        assertThrows(ResourceNotFoundException.class, () -> repository.find(17L));
        repository.list(true);
        verify(jdbc).queryForList("SELECT * FROM device ORDER BY id");
    }
}
